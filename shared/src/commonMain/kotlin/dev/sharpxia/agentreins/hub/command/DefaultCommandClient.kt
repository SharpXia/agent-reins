package dev.sharpxia.agentreins.hub.command

import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * [CommandClient] 实现（ADR-07 幂等队列 + 乐观 UI）：
 * 入队（PENDING_LOCAL + 乐观行 sending）→ REST 提交（202 receipt → PERSISTED）→
 * 后续状态只经事件流 command.acked/command.failed 翻转（projector 落库）。
 * 幂等键生成后永不更换；HUB-BIZ-001 → FAILED（禁换键）；网络失败保持 PENDING_LOCAL 待重试。
 * payloadJson 存原始载荷（message.send 存文本、read.mark 存 readUpTo 数字串），重试按同键同载荷。
 */
class DefaultCommandClient(
    private val api: HubApi,
    private val auth: TokenManager,
    private val db: HubDatabase,
) : CommandClient {

    override suspend fun sendMessage(
        conversationId: String,
        gatewayId: String,
        sessionKey: String,
        text: String,
    ): CommandReceipt? = enqueueAndSubmit("message.send", conversationId, sessionKey, text, gatewayId) { key ->
        api.sendMessage(conversationId, SendMessageRequest(idempotencyKey = key, message = text))
    }

    override suspend fun markRead(
        conversationId: String,
        gatewayId: String,
        sessionKey: String,
        readUpTo: Long,
    ) {
        enqueueAndSubmit("read.mark", conversationId, sessionKey, readUpTo.toString()) { key ->
            api.markRead(conversationId, ReadCursorRequest(idempotencyKey = key, readUpTo = readUpTo))
        }
    }

    override suspend fun cancelRun(runId: String, sessionKey: String?) {
        enqueueAndSubmit("run.cancel", null, sessionKey, runId) { key ->
            api.cancelRun(runId, CancelRunRequest(idempotencyKey = key))
        }
    }

    override suspend fun decideApproval(approvalId: String, decision: String, sessionKey: String?) {
        enqueueAndSubmit("approval.decide", null, sessionKey, "$approvalId:$decision") { key ->
            api.decideApproval(approvalId, ApprovalDecisionRequest(idempotencyKey = key, decision = decision))
        }
    }

    /** 队列重试：对未终态条目按同键同载荷重发（05 §3.9 义务 6）；重复提交被 Hub 幂等吸收。 */
    override suspend fun retryUnfinished() {
        db.hubQueries.selectNonTerminalCommands().executeAsList().forEach { cmd ->
            when (cmd.type) {
                "message.send" -> if (cmd.conversationId != null && cmd.payloadJson.isNotEmpty()) {
                    submit(cmd.idempotencyKey) {
                        api.sendMessage(cmd.conversationId, SendMessageRequest(cmd.idempotencyKey, cmd.payloadJson))
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
        sessionKey: String?,
        payload: String,
        gatewayId: String = "",
        call: suspend (key: String) -> CommandReceipt,
    ): CommandReceipt? {
        val key = newUuid()
        val now = nowText()
        db.hubQueries.insertCommand(
            idempotencyKey = key, commandId = null, conversationId = conversationId,
            sessionKey = sessionKey, runId = null, approvalId = null, type = type,
            payloadJson = payload, state = "PENDING_LOCAL", errorCode = null,
            createdAt = now, updatedAt = now,
        )
        // message.send 同步插乐观行（messageId=幂等键临时占位，message.created 回执时提升为服务端行）
        if (type == "message.send") {
            db.hubQueries.upsertMessage(
                messageId = key, gatewayId = gatewayId, sessionKey = sessionKey ?: "", seq = Long.MAX_VALUE,
                role = "user", content = payload, state = "final", uiState = "sending",
                openclawId = null, commandId = null, idempotencyKey = key, ts = now,
            )
        }
        return submit(key, call)
    }

    private suspend fun submit(
        key: String,
        call: suspend (String) -> dev.sharpxia.agentreins.hub.projection.CommandReceipt,
    ): dev.sharpxia.agentreins.hub.projection.CommandReceipt? = try {
        val receipt = auth.withBearer { call(key) }
        db.hubQueries.updateCommandId(receipt.commandId, receipt.acceptedAt, key)
        db.hubQueries.updateCommandState("PERSISTED", null, receipt.acceptedAt, key)
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
