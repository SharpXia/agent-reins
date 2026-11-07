package dev.sharpxia.agentreins.hub.command

import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.RunOriginRegistry
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * [CommandClient] 实现（ADR-07 幂等队列 + 乐观 UI；v1.1 命令目标 = conversationId）：
 * 入队（PENDING_LOCAL + 乐观行 sending）→ REST 提交（202 receipt → PERSISTED）→
 * 后续状态只经事件流 command.acked/command.failed 翻转（projector 落库）。
 * 幂等键生成后永不更换；HUB-BIZ-001 → FAILED（禁换键）；网络失败保持 PENDING_LOCAL 待重试。
 * payloadJson 存原始载荷（message.send 存文本、read.mark 存 readUpTo 数字串），重试按同键同载荷。
 * v1.1 的 create/compact/rename 不走队列（幂等键由 Hub 生成，见 [CommandClient] 注）。
 * wp-4（发起方）：命令提交面兼 [RunOriginRegistry] 登记面——message.send 记 SEND（本端发起 run
 * 的主通道，准入该会话后续 run 终态提醒）；conversation.compact 记 COMPACT（静默通道，wp-4 scope
 * 「compact 触发静默」）。其余命令与 run 发起无关不登记。SEND 登记时机 = submit 取得 PERSISTED
 * 回执之后（回归修复 2026-11-06：提交前登记会让 FAILED/网络失败——没发出去的消息也开准入，
 * 而准入跨 run 存活，误开即通知面泄漏）；compact 登记维持提交前（静默通道宁默勿响）。
 * 重试路径（retryUnfinished）成功同样补登记。
 */
class DefaultCommandClient(
    private val api: HubApi,
    private val auth: TokenManager,
    private val db: HubDatabase,
    /** wp-4：run 发起登记面（缺省 null = 不登记，单测兼容；生产由 DefaultHubClient.start 注入）。 */
    private val runOriginRegistry: RunOriginRegistry? = null,
) : CommandClient {

    override suspend fun sendMessage(conversationId: String, text: String): CommandReceipt? =
        enqueueAndSubmit("message.send", conversationId, text) { key ->
            api.sendMessage(conversationId, SendMessageRequest(idempotencyKey = key, message = text))
        }

    override suspend fun markRead(conversationId: String, readUpTo: Long) {
        enqueueAndSubmit("read.mark", conversationId, readUpTo.toString()) { key ->
            api.markRead(conversationId, ReadCursorRequest(idempotencyKey = key, readUpTo = readUpTo))
        }
    }

    override suspend fun cancelRun(runId: String) {
        enqueueAndSubmit("run.cancel", null, runId) { key ->
            api.cancelRun(runId, CancelRunRequest(idempotencyKey = key))
        }
    }

    /** 审批决定（wp-9）：同 sendMessage 语义——PERSISTED 回执返回 receipt，确定性拒绝/网络不可达返回 null。 */
    override suspend fun decideApproval(approvalId: String, decision: String): CommandReceipt? =
        enqueueAndSubmit("approval.decide", null, "$approvalId:$decision") { key ->
            api.decideApproval(approvalId, ApprovalDecisionRequest(idempotencyKey = key, decision = decision))
        }

    /**
     * #16 新开会话：固定携带 mainSession=true（v1.1 additive，主线锚定 2026-11-06）——
     * app 新建入口语义即主线锚定，服务端据此组装 sessionKey=agent:<agentId>:main
     * （客户端永不拼键，ADR-14），Gateway sessions.create 同名 key adopt 不 fork。
     * encodeDefaults=false 下显式 true 正常编码；缺省不携带=现状 dashboard 键行为（本客户端不产生）。
     */
    override suspend fun createConversation(gatewayId: String, agentId: String, label: String?): CommandReceipt =
        auth.withBearer { api.createConversation(gatewayId, agentId, CreateConversationRequest(label = label, mainSession = true)) }

    override suspend fun compactConversation(conversationId: String): CommandReceipt {
        // wp-4：compact 是静默通道——登记 COMPACT 覆盖会话准入，其触发的 run 终态不提醒
        runOriginRegistry?.markSubmission(conversationId, RunOriginRegistry.Kind.COMPACT)
        return auth.withBearer { api.compactConversation(conversationId) }
    }

    override suspend fun renameConversation(conversationId: String, label: String): CommandReceipt =
        auth.withBearer { api.renameConversation(conversationId, RenameConversationRequest(label = label)) }

    /** 队列重试：对未终态条目按同键同载荷重发（05 §3.9 义务 6）；重复提交被 Hub 幂等吸收。 */
    override suspend fun retryUnfinished() {
        db.hubQueries.selectNonTerminalCommands().executeAsList().forEach { cmd ->
            when (cmd.type) {
                "message.send" -> if (cmd.conversationId != null && cmd.payloadJson.isNotEmpty()) {
                    val convId = cmd.conversationId
                    submit(cmd.idempotencyKey, onPersisted = {
                        // 断线恢复重试成功同样登记 SEND 准入（消息此时才真正发出、run 才由它发起）
                        runOriginRegistry?.markSubmission(convId, RunOriginRegistry.Kind.SEND)
                    }) {
                        api.sendMessage(convId, SendMessageRequest(cmd.idempotencyKey, cmd.payloadJson))
                    }
                }
                "read.mark" -> {
                    val up = cmd.payloadJson.toLongOrNull()
                    if (cmd.conversationId != null && up != null) {
                        submit(cmd.idempotencyKey) {
                            api.markRead(cmd.conversationId, ReadCursorRequest(cmd.idempotencyKey, up))
                        }
                    }
                }
                else -> Unit // run.cancel/approval.decide 的重放需服务端资源在途，UI 显式重发路径处理
            }
        }
    }

    // ── 内部 ──

    private suspend fun enqueueAndSubmit(
        type: String,
        conversationId: String?,
        payload: String,
        call: suspend (key: String) -> CommandReceipt,
    ): CommandReceipt? {
        val key = newUuid()
        val now = nowText()
        db.hubQueries.insertCommand(
            idempotencyKey = key, commandId = null, conversationId = conversationId,
            runId = null, approvalId = null, type = type,
            payloadJson = payload, state = "PENDING_LOCAL", errorCode = null,
            createdAt = now, updatedAt = now,
        )
        // message.send 同步插乐观行（messageId=幂等键临时占位，message.created 回执时提升为服务端行；
        // seq=Long.MAX_VALUE 恒排末尾，回执后取服务端 seq）。wp-4：SEND 准入登记**不在提交前**——
        // 挪到 submit 取得 PERSISTED 回执之后（回归修复 2026-11-06），提交未达（FAILED/网络失败）
        // 的消息不登记，避免「没发出的消息」给会话开准入
        if (type == "message.send" && conversationId != null) {
            db.hubQueries.upsertMessage(
                messageId = key, conversationId = conversationId, seq = Long.MAX_VALUE,
                role = "user", content = payload, state = "final", reason = null,
                uiState = "sending",
                commandId = null, idempotencyKey = key, ts = now,
            )
        }
        return submit(key, onPersisted = {
            if (type == "message.send" && conversationId != null) {
                runOriginRegistry?.markSubmission(conversationId, RunOriginRegistry.Kind.SEND)
            }
        }, call = call)
    }

    private suspend fun submit(
        key: String,
        onPersisted: (() -> Unit)? = null,
        call: suspend (String) -> CommandReceipt,
    ): CommandReceipt? = try {
        val receipt = auth.withBearer { call(key) }
        db.hubQueries.updateCommandId(receipt.commandId, receipt.acceptedAt, key)
        db.hubQueries.updateCommandState("PERSISTED", null, receipt.acceptedAt, key)
        onPersisted?.invoke() // wp-4：SEND 准入登记钩子（仅 PERSISTED 触发；失败路径不登记）
        receipt
    } catch (e: HubException) {
        // 确定性拒绝：FAILED + 错误码（HUB-BIZ-001 同键不同内容——禁换键，条目作废待人工）
        db.hubQueries.updateCommandState("FAILED", e.payload.code, nowText(), key)
        db.hubQueries.setMessageUiStateByIdem("failed", key)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 网络层失败：保持 PENDING_LOCAL（断线恢复重试，同键幂等）
        null
    }

    private fun nowText(): String = kotlin.time.Clock.System.now().toEpochMilliseconds().toString()

    companion object {
        /** UUIDv4（commonMain 无 java.util.UUID；唯一性优先，随机源可后换 SecureRandom）。 */
        fun newUuid(): String = buildString {
            val hex = "0123456789abcdef"
            repeat(32) { append(hex[Random.nextInt(16)]) }
            insert(8, "-").insert(13, "-").insert(18, "-").insert(23, "-")
            this[14] = '4' // version 4
            this[19] = when (this[19]) { // variant 10xx
                'a', 'b' -> '8'
                'c', 'd' -> '9'
                'e' -> 'a'
                else -> 'b'
            }
        }
    }
}
