package dev.sharpxia.agentreins.hub.command

import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.HubJson
import dev.sharpxia.agentreins.hub.RunOriginRegistry
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.AttachmentRef
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.MessageAttachment
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.encodeAttachments
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * [CommandClient] 实现（ADR-07 幂等队列 + 乐观 UI；v1.1 命令目标 = conversationId）：
 * 入队（PENDING_LOCAL + 乐观行 sending）→ REST 提交（202 receipt → PERSISTED）→
 * 后续状态只经事件流 command.acked/command.failed 翻转（projector 落库）。
 * 幂等键生成后永不更换；HUB-BIZ-001 → FAILED（禁换键）；网络失败保持 PENDING_LOCAL 待重试。
 * payloadJson 存原始载荷（message.send 存文本、read.mark 存 readUpTo 数字串、conversation.create
 * 存路由信封 [ConversationCreateEnvelope]），重试按同键同载荷。
 * compact/rename 不走队列（幂等键由 Hub 生成）；create 自 v1.4.0（多主会话轮 2026-11-16）入队
 * （客户端幂等键 + conversationId=null + 路由信封 payloadJson，见 [CommandClient] 注）。
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

    /**
     * v1.2.0 additive：payloadJson 升级为 SendMessageRequest 同形 JSON（idempotencyKey + message
     * + attachments）——设计稿 §6「透传 attachments 进 payloadJson（同键同载荷重试语义不变，
     * 重试重发同一 mediaId 集合，幂等键不换）」。存量行 payloadJson 为裸文本，retryUnfinished
     * 解码失败即回退裸文本口径（见下）；decodeFromJsonElement 失败面窄（仅用户正文恰为该形
     * JSON 的病态输入），后果仍是同键重发，可接受。
     */
    override suspend fun sendMessage(conversationId: String, text: String, attachments: List<AttachmentRef>): CommandReceipt? =
        enqueueAndSubmit(
            "message.send",
            conversationId,
            payload = { key ->
                HubJson.encodeToString(
                    SendMessageRequest.serializer(),
                    SendMessageRequest(idempotencyKey = key, message = text, attachments = attachments),
                )
            },
            optimisticContent = text,
            optimisticAttachments = attachments,
        ) { key ->
            api.sendMessage(conversationId, SendMessageRequest(idempotencyKey = key, message = text, attachments = attachments))
        }

    override suspend fun markRead(conversationId: String, readUpTo: Long) {
        enqueueAndSubmit("read.mark", conversationId, payload = { readUpTo.toString() }) { key ->
            api.markRead(conversationId, ReadCursorRequest(idempotencyKey = key, readUpTo = readUpTo))
        }
    }

    override suspend fun cancelRun(runId: String) {
        enqueueAndSubmit("run.cancel", null, payload = { runId }) { key ->
            api.cancelRun(runId, CancelRunRequest(idempotencyKey = key))
        }
    }

    /** 审批决定（wp-9）：同 sendMessage 语义——PERSISTED 回执返回 receipt，确定性拒绝/网络不可达返回 null。 */
    override suspend fun decideApproval(approvalId: String, decision: String): CommandReceipt? =
        enqueueAndSubmit("approval.decide", null, payload = { "$approvalId:$decision" }) { key ->
            api.decideApproval(approvalId, ApprovalDecisionRequest(idempotencyKey = key, decision = decision))
        }

    /**
     * #16 新开会话（v1.4.0 起入队，此前 v1.1 直调不入队）：客户端生成幂等键 → PENDING_LOCAL
     * 队列 → 提交。payloadJson 存路由信封（[ConversationCreateEnvelope]——conversationId=null
     * 条目的重放分支无路由参数可依，信封整体落 payloadJson 零加列）；REST 体 =
     * CreateConversationRequest 同形（[createConversationRequest] 同一构造器：信封 request 与
     * REST 体由同一 key 构造 → 逐字节同 → 服务端确定性派生指纹不漂移，H4/H5）。
     * mainSession 一并参数化（评审 H2，不再硬编码 true）：newMainSession=false（缺省）→
     * mainSession=true 默认主线（旧入口语义，sessionKey=agent:<agentId>:main，同名 key adopt
     * 不 fork）；newMainSession=true → mainSession=false（契约明文「含显式 false」= 缺省语义）
     * + newMainSession=true（服务端以请求幂等键派生 agent:<agentId>:main-<idempotencyKey>）——
     * 两者恒不双真（互斥 400 HUB-BIZ-004）。encodeDefaults=false 下显式 true/false 正常编码、
     * null（label）不编码。无乐观行（新卡由快照/校准灌入，发送队列仅登记不产行）。
     */
    override suspend fun createConversation(
        gatewayId: String,
        agentId: String,
        label: String?,
        newMainSession: Boolean,
    ): CommandReceipt? =
        enqueueAndSubmit(
            "conversation.create",
            conversationId = null,
            payload = { key ->
                HubJson.encodeToString(
                    ConversationCreateEnvelope.serializer(),
                    ConversationCreateEnvelope(
                        gatewayId = gatewayId,
                        agentId = agentId,
                        request = createConversationRequest(key, label, newMainSession),
                    ),
                )
            },
        ) { key ->
            api.createConversation(gatewayId, agentId, createConversationRequest(key, label, newMainSession))
        }

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
                    // v1.2.0 additive：payloadJson 为 SendMessageRequest 同形 JSON（含 attachments
                    // mediaId 集合），同键同载荷幂等重发；存量裸文本行解码失败回退旧口径（空附件）。
                    val req = runCatching {
                        HubJson.decodeFromString(SendMessageRequest.serializer(), cmd.payloadJson)
                    }.getOrElse { SendMessageRequest(idempotencyKey = cmd.idempotencyKey, message = cmd.payloadJson) }
                    submit(cmd.idempotencyKey, onPersisted = {
                        // 断线恢复重试成功同样登记 SEND 准入（消息此时才真正发出、run 才由它发起）
                        runOriginRegistry?.markSubmission(convId, RunOriginRegistry.Kind.SEND)
                    }) {
                        api.sendMessage(convId, req.copy(idempotencyKey = cmd.idempotencyKey))
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
                "conversation.create" -> {
                    // v1.4.0：payloadJson 为路由信封（gatewayId/agentId + CreateConversationRequest
                    // 同形，conversationId=null 条目的路由参数唯一来源）——解码后按同键同载荷重放
                    // （幂等键以队列条目 cmd.idempotencyKey 为准；mainSession/newMainSession 原样
                    // 透传，服务端确定性派生使重试载荷逐字节同 → 同指纹 → 202+原 commandId）。
                    // 解码失败（存量/异形行）按既有纪律跳过不重放。
                    val env = runCatching {
                        HubJson.decodeFromString(ConversationCreateEnvelope.serializer(), cmd.payloadJson)
                    }.getOrNull()
                    if (env != null) {
                        submit(cmd.idempotencyKey) {
                            api.createConversation(
                                env.gatewayId,
                                env.agentId,
                                CreateConversationRequest(
                                    idempotencyKey = cmd.idempotencyKey,
                                    label = env.request.label,
                                    mainSession = env.request.mainSession,
                                    newMainSession = env.request.newMainSession,
                                ),
                            )
                        }
                    }
                }
                else -> Unit // run.cancel/approval.decide 的重放需服务端资源在途，UI 显式重发路径处理
            }
        }
    }

    // ── 内部 ──

    /**
     * #16 请求体构造器：入队信封 request 与初次提交 REST 体共用同一构造（同一 key 传入），
     * 保证两条路径编码逐字节同——服务端确定性派生（v1.4.0 S1b③）以请求幂等键派生
     * agent:<agentId>:main-<idempotencyKey>，重试同键同载荷才同指纹（H4/H5 闭环在本层）。
     */
    private fun createConversationRequest(key: String, label: String?, newMainSession: Boolean) =
        CreateConversationRequest(
            idempotencyKey = key,
            label = label,
            mainSession = !newMainSession,
            newMainSession = newMainSession,
        )

    /**
     * [payload] 为幂等键 → 载荷 JSON 编码函数（键在函数内生成，message.send 的载荷含键）；
     * [optimisticContent]/[optimisticAttachments] 仅 message.send 乐观行落库用（v9：附件随行
     * 落列供本地即时渲染，回执提升 promoteLocalEcho 不触附件列，保留至服务端权威行）。
     */
    private suspend fun enqueueAndSubmit(
        type: String,
        conversationId: String?,
        payload: (key: String) -> String,
        optimisticContent: String? = null,
        optimisticAttachments: List<AttachmentRef> = emptyList(),
        call: suspend (key: String) -> CommandReceipt,
    ): CommandReceipt? {
        val key = newUuid()
        val now = nowText()
        db.hubQueries.insertCommand(
            idempotencyKey = key, commandId = null, conversationId = conversationId,
            runId = null, approvalId = null, type = type,
            payloadJson = payload(key), state = "PENDING_LOCAL", errorCode = null,
            createdAt = now, updatedAt = now,
        )
        // message.send 同步插乐观行（messageId=幂等键临时占位，message.created 回执时提升为服务端行；
        // seq=Long.MAX_VALUE 恒排末尾，回执后取服务端 seq）。uiState 按发送时刻会话 run 态分档
        // （#3 2026-11-08：排队态下沉为消息级落库状态——发送时 run 活跃 = 该行排队，run 终态
        // releaseQueuedRows 统一释放；旧版 Swift 侧会话级水位判定会把全会话用户行误染排队，
        // 且发消息自己触发的 run 会把自己染上排队，本落库口径同时修 #3/#4）。wp-4：SEND 准入
        // 登记**不在提交前**——挪到 submit 取得 PERSISTED 回执之后（回归修复 2026-11-06），
        // 提交未达（FAILED/网络失败）的消息不登记，避免「没发出的消息」给会话开准入
        if (type == "message.send" && conversationId != null) {
            val runActive = db.hubQueries.selectConversation(conversationId).executeAsOneOrNull()
                ?.hasActiveRun == 1L
            db.hubQueries.upsertMessage(
                messageId = key, conversationId = conversationId, seq = Long.MAX_VALUE,
                role = "user", content = optimisticContent ?: payload(key), state = "final", reason = null,
                uiState = if (runActive) "queued" else "sending",
                commandId = null, idempotencyKey = key, ts = now,
                // v9：附件随乐观行落列（MessageAttachment[] JSON）供本地即时渲染；空清单落 NULL
                attachments = encodeAttachments(
                    optimisticAttachments.map { ref ->
                        MessageAttachment(
                            source = "hub",
                            mediaId = ref.mediaId,
                            mimeType = ref.mimeType,
                            fileName = ref.fileName,
                            sizeBytes = ref.sizeBytes,
                            width = ref.width,
                            height = ref.height,
                        )
                    },
                ),
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

/**
 * conversation.create 路由信封（v1.4.0 多主会话轮 2026-11-16；hubCommands.payloadJson 专用形态，
 * 非 REST 线格式——REST 体 = 信封 [CreateConversationRequest] 字段同形逐字节）：conversationId=null
 * 的队列条目在重放分支无路由参数可依（hubCommands 无 gatewayId/agentId 列、#16 REST 体无路由
 * 字段），信封把路由三元组 (gatewayId, agentId, request) 整体落 payloadJson，零加列闭合重放。
 * 同键同载荷：request.idempotencyKey 与队列条目 idempotencyKey 同值（客户端生成 UUID），
 * 重放重建的请求与初次提交逐字节同 → 服务端确定性派生 mainKey 指纹不漂移（评审 H4/H5）。
 * 解码失败（存量/异形行）按既有纪律跳过不重放。internal：desktopTest 信封用例直用。
 */
@Serializable
internal data class ConversationCreateEnvelope(
    val gatewayId: String,
    val agentId: String,
    val request: CreateConversationRequest,
)
