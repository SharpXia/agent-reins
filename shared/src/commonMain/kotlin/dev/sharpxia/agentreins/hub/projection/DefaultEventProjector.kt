package dev.sharpxia.agentreins.hub.projection

import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile

// ── 事件载荷 DTO（hub-wss-frames-v1.schema.json $defs/Data* 镜像；宽松解析，未知字段忽略） ──

@Serializable
private data class DataMessageDelta(val messageId: String, val deltaText: String, val replace: Boolean? = null)

@Serializable
private data class DataMessageCreated(
    val messageId: String,
    val role: String,
    val content: String,
    val openclawId: String,
    val sessionId: String,
    val sourceMessageSeq: Long,
    val commandId: String? = null,
    val idempotencyKey: String? = null,
)

@Serializable
private data class DataMessageReplaced(val messageId: String, val content: String, val openclawId: String)

@Serializable
private data class DataMessageFailed(val messageId: String, val reason: String)

@Serializable
private data class DataSessionCreated(
    val sessionKey: String,
    val agentId: String,
    val label: String,
    val hasActiveRun: Boolean,
)

@Serializable
private data class DataSessionUpdated(
    val sessionKey: String,
    val agentId: String,
    val label: String,
    val sessionId: String,
    val lifecycleRevision: Long,
    val hasActiveRun: Boolean,
    val activeRunIds: JsonElement? = null,
)

@Serializable
private data class DataSessionRemoved(val sessionKey: String)

@Serializable
private data class DataApprovalRequested(
    val runId: String,
    val approvalId: String,
    val toolName: String,
    val payloadSummary: String,
)

@Serializable
private data class DataApprovalResolved(
    val runId: String,
    val approvalId: String,
    val decision: String,
    val decidedBy: String,
    val decisionRaw: JsonElement? = null,
)

@Serializable
private data class DataCommandAcked(
    val commandId: String,
    val idempotencyKey: String,
    val commandType: String,
    val status: String,
    val previousStatus: String,
)

@Serializable
private data class DataCommandFailed(
    val commandId: String,
    val idempotencyKey: String,
    val commandType: String,
    val status: String,
    val previousStatus: String,
    val errorCode: String,
    val retryable: Boolean? = null,
)

@Serializable
private data class DataReadUpdated(val conversationId: String, val reader: String, val readUpTo: Long)

/** 快照落定 / 事件应用时投影仪对外可见的统计（验收取证用）。 */
class ProjectionStats {
    @Volatile var eventsApplied: Int = 0
    @Volatile var eventsSkipped: Int = 0 // 载荷解析失败/目标行缺失而忽略内容（游标照常推进）
    @Volatile var snapshotsApplied: Int = 0
}

/**
 * [EventProjector] 的 SQLite 落库实现（05 §3.11 合并规则）。
 *
 * 关键契约事实：服务端 conversation_id 是投影时新生成的内部 ULID，事件载荷不携带——
 * 会话/消息/已读的自然键 = (gatewayId, sessionKey)，conversationId 仅由快照对齐填充；
 * 事件流单独建的会话行 reconciling=1，句柄补齐前 UI 不可进详情页。
 * 幂等性边界（INV-1）：本类所有写路径除 delta append 外均为 upsert/幂等 UPDATE；
 * delta 的重复防护由调用方（事件 id 去重 + 水位裁决）承担。
 */
class DefaultEventProjector(
    private val db: HubDatabase,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    },
) : EventProjector {

    val stats = ProjectionStats()

    // ── 快照整体覆盖（05 §3.11 铁律：先覆盖基线，禁止 upsert 到已含事件的模型上） ──

    override fun applySnapshot(snapshot: SnapshotResponse) {
        db.transaction {
            db.hubQueries.clearGateways()
            snapshot.gateways.forEach { g ->
                db.hubQueries.insertGateway(
                    gatewayId = g.gatewayId,
                    name = g.name,
                    online = if (g.online) 1L else 0L,
                    lastSeenAt = g.lastSeenAt,
                    agentsJson = json.encodeToString(ListSerializerAgentRef, g.agents),
                )
            }
            db.hubQueries.clearConversations()
            db.hubQueries.clearMessages()
            db.hubQueries.clearReadCursors()
            snapshot.conversations.forEach { c ->
                db.hubQueries.upsertConversation(
                    gatewayId = c.gatewayId,
                    sessionKey = c.sessionKey,
                    conversationId = c.conversationId,
                    sessionId = c.sessionId,
                    lifecycleRevision = c.lifecycleRevision,
                    label = c.label,
                    agentId = c.agentId,
                    lastMessagePreview = c.lastMessagePreview,
                    lastSeq = c.lastSeq,
                    lastActiveAt = c.lastActiveAt,
                    unreadCount = c.unreadCount.toLong(),
                    hasActiveRun = if (c.hasActiveRun) 1L else 0L,
                    reconciling = if (c.reconciling) 1L else 0L,
                )
                snapshot.messages[c.conversationId]?.forEach { m ->
                    db.hubQueries.upsertMessage(
                        messageId = m.messageId,
                        gatewayId = c.gatewayId,
                        sessionKey = c.sessionKey,
                        seq = m.seq,
                        role = m.role,
                        content = m.content,
                        state = m.state,
                        uiState = m.uiState,
                        openclawId = m.openclaw?.id,
                        commandId = m.commandId,
                        idempotencyKey = m.idempotencyKey,
                        ts = m.timestamp,
                    )
                }
                snapshot.readCursors[c.conversationId]?.let { up ->
                    db.hubQueries.upsertReadCursor(c.gatewayId, c.sessionKey, up)
                }
            }
        }
        stats.snapshotsApplied++
    }

    // ── 事件应用（05 §3.11 表；载荷解析失败/目标行缺失 → 忽略内容，游标由调用方照常推进） ──

    override fun applyEvent(envelope: EventEnvelope) {
        db.transaction {
            when (envelope.type) {
                EventEnvelope.MESSAGE_DELTA -> onMessageDelta(envelope)
                EventEnvelope.MESSAGE_CREATED -> onMessageCreated(envelope)
                EventEnvelope.MESSAGE_REPLACED -> onMessageReplaced(envelope)
                EventEnvelope.MESSAGE_FAILED -> onMessageFailed(envelope)
                EventEnvelope.RUN_STARTED -> setRun(envelope, true)
                EventEnvelope.RUN_COMPLETED,
                EventEnvelope.RUN_FAILED,
                EventEnvelope.RUN_ABORTED,
                -> setRun(envelope, false)
                EventEnvelope.SESSION_CREATED -> onSessionCreated(envelope)
                EventEnvelope.SESSION_UPDATED -> onSessionUpdated(envelope)
                EventEnvelope.SESSION_REMOVED -> onSessionRemoved(envelope)
                EventEnvelope.GATEWAY_ONLINE, EventEnvelope.GATEWAY_OFFLINE -> {
                    val gw = envelope.data.jsonObject["gatewayId"]?.toString()?.trim('"')
                    if (gw != null) {
                        db.hubQueries.setGatewayOnline(
                            online = if (envelope.type == EventEnvelope.GATEWAY_ONLINE) 1L else 0L,
                            lastSeenAt = envelope.ts,
                            gatewayId = gw,
                        )
                    }
                }
                EventEnvelope.APPROVAL_REQUESTED -> onApprovalRequested(envelope)
                EventEnvelope.APPROVAL_RESOLVED -> onApprovalResolved(envelope)
                EventEnvelope.COMMAND_ACKED -> onCommandAcked(envelope)
                EventEnvelope.COMMAND_FAILED -> onCommandFailed(envelope)
                EventEnvelope.READ_UPDATED -> onReadUpdated(envelope)
                else -> Unit // 未注册类型/tool.*：忽略内容，照常推进游标（06 §12.2 义务 2）
            }
        }
        stats.eventsApplied++
    }

    // ── message 域 ───────────────────────────────────────────────────────────

    private fun onMessageDelta(envelope: EventEnvelope) {
        val d = decode(DataMessageDelta.serializer(), envelope.data) ?: return skip()
        val row = db.hubQueries.selectMessage(d.messageId).executeAsOneOrNull() ?: return skip()
        if (d.replace == true) {
            db.hubQueries.replaceMessageContent(d.deltaText, d.messageId)
        } else {
            db.hubQueries.appendMessageContent(d.deltaText, d.messageId)
        }
    }

    private fun onMessageCreated(envelope: EventEnvelope) {
        val d = decode(DataMessageCreated.serializer(), envelope.data) ?: return skip()
        val g = envelope.gatewayId
        val sk = envelope.sessionKey ?: return skip()
        ensureConversationRow(g, sk, envelope)
        val isUser = d.role == "user"
        db.hubQueries.upsertMessage(
            messageId = d.messageId,
            gatewayId = g,
            sessionKey = sk,
            seq = d.sourceMessageSeq,
            role = d.role,
            content = d.content,
            state = STATE_FINAL,
            uiState = if (isUser) UI_DELIVERED else null,
            openclawId = d.openclawId,
            commandId = d.commandId,
            idempotencyKey = d.idempotencyKey,
            ts = envelope.ts,
        )
        // 发送方乐观行翻转（05 §3.9 义务 4）：幂等键命中的本地行 sending → delivered
        if (isUser && d.idempotencyKey != null) {
            db.hubQueries.setMessageUiStateByIdem(UI_DELIVERED, d.idempotencyKey)
        }
        db.hubQueries.touchConversationPreview(
            preview = d.content.take(PREVIEW_MAX),
            seq = d.sourceMessageSeq,
            lastActiveAt = envelope.ts,
            gatewayId = g,
            sessionKey = sk,
        )
        // 本地未读增量（05 §3.7 义务 3，与 Hub 同口径：当前化身 seq>readUpTo 的 assistant final 行）
        if (!isUser) {
            val readUpTo = db.hubQueries.selectReadCursor(g, sk).executeAsOneOrNull() ?: 0L
            if (d.sourceMessageSeq > readUpTo) {
                bumpUnread(g, sk)
            }
        }
    }

    private fun onMessageReplaced(envelope: EventEnvelope) {
        val d = decode(DataMessageReplaced.serializer(), envelope.data) ?: return skip()
        val row = db.hubQueries.selectMessage(d.messageId).executeAsOneOrNull() ?: return skip()
        db.hubQueries.replaceMessageContent(d.content, d.messageId)
        db.hubQueries.setMessageState(STATE_FINAL, d.messageId)
    }

    private fun onMessageFailed(envelope: EventEnvelope) {
        val d = decode(DataMessageFailed.serializer(), envelope.data) ?: return skip()
        val row = db.hubQueries.selectMessage(d.messageId).executeAsOneOrNull() ?: return skip()
        db.hubQueries.setMessageState(STATE_FAILED, d.messageId)
    }

    // ── run / session 域 ─────────────────────────────────────────────────────

    private fun setRun(envelope: EventEnvelope, active: Boolean) {
        val sk = envelope.sessionKey ?: return
        db.hubQueries.setConversationActiveRun(
            hasActiveRun = if (active) 1L else 0L,
            gatewayId = envelope.gatewayId,
            sessionKey = sk,
        )
    }

    private fun onSessionCreated(envelope: EventEnvelope) {
        val d = decode(DataSessionCreated.serializer(), envelope.data) ?: return skip()
        val sk = d.sessionKey.ifEmpty { envelope.sessionKey } ?: return skip()
        upsertConversationFromEvent(envelope.gatewayId, sk, d.label, d.agentId, d.hasActiveRun, envelope.ts)
    }

    private fun onSessionUpdated(envelope: EventEnvelope) {
        val d = decode(DataSessionUpdated.serializer(), envelope.data) ?: return skip()
        val sk = d.sessionKey.ifEmpty { envelope.sessionKey } ?: return skip()
        val g = envelope.gatewayId
        val existing = db.hubQueries.selectConversation(g, sk).executeAsOneOrNull()
        if (existing != null && existing.lifecycleRevision != d.lifecycleRevision) {
            // 化身更替（05 §3.10）：旧代次消息封存移出、seq 从 1 重计、未读清零、readCursor 重置
            db.hubQueries.deleteMessagesByConversation(g, sk)
            db.hubQueries.setReadCursorValue(g, sk, 0L)
            db.hubQueries.setConversationUnread(0L, g, sk)
        }
        upsertConversationFromEvent(g, sk, d.label, d.agentId, d.hasActiveRun, envelope.ts)
        if (existing != null) {
            db.hubQueries.updateConversationIdentity(
                sessionId = d.sessionId,
                revision = d.lifecycleRevision,
                label = d.label,
                hasActiveRun = if (d.hasActiveRun) 1L else 0L,
                gatewayId = g,
                sessionKey = sk,
            )
        }
    }

    private fun onSessionRemoved(envelope: EventEnvelope) {
        val d = decode(DataSessionRemoved.serializer(), envelope.data) ?: return skip()
        val sk = d.sessionKey.ifEmpty { envelope.sessionKey } ?: return skip()
        val g = envelope.gatewayId
        db.hubQueries.deleteConversation(g, sk)
        db.hubQueries.deleteMessagesByConversation(g, sk)
        db.hubQueries.deleteReadCursor(g, sk)
    }

    // ── approval / command / read 域 ─────────────────────────────────────────

    private fun onApprovalRequested(envelope: EventEnvelope) {
        val d = decode(DataApprovalRequested.serializer(), envelope.data) ?: return skip()
        db.hubQueries.upsertApproval(
            approvalId = d.approvalId,
            runId = d.runId,
            sessionKey = envelope.sessionKey,
            toolName = d.toolName,
            payloadSummary = d.payloadSummary,
            status = "pending",
            decidedBy = null,
            updatedAt = envelope.ts,
        )
    }

    private fun onApprovalResolved(envelope: EventEnvelope) {
        val d = decode(DataApprovalResolved.serializer(), envelope.data) ?: return skip()
        db.hubQueries.setApprovalStatus(d.decision, d.decidedBy, envelope.ts, d.approvalId)
    }

    private fun onCommandAcked(envelope: EventEnvelope) {
        val d = decode(DataCommandAcked.serializer(), envelope.data) ?: return skip()
        db.hubQueries.updateCommandState(d.status, null, envelope.ts, d.idempotencyKey)
        db.hubQueries.updateCommandId(d.commandId, envelope.ts, d.idempotencyKey)
        // 发送行 UI 翻转：正向迁移即已投递（COMPLETED 由 message.created 权威内容收敛）
        db.hubQueries.setMessageUiStateByIdem(UI_DELIVERED, d.idempotencyKey)
    }

    private fun onCommandFailed(envelope: EventEnvelope) {
        val d = decode(DataCommandFailed.serializer(), envelope.data) ?: return skip()
        db.hubQueries.updateCommandState(d.status, d.errorCode, envelope.ts, d.idempotencyKey)
        if (d.status == "FAILED") {
            db.hubQueries.setMessageUiStateByIdem(UI_FAILED, d.idempotencyKey)
        }
    }

    private fun onReadUpdated(envelope: EventEnvelope) {
        val d = decode(DataReadUpdated.serializer(), envelope.data) ?: return skip()
        val conv = db.hubQueries.selectConversationByHandle(d.conversationId).executeAsOneOrNull()
            ?: return skip() // 句柄未对齐：跳过内容，快照/校准对齐后重取
        val g = conv.gatewayId
        val sk = conv.sessionKey
        db.hubQueries.upsertReadCursor(g, sk, d.readUpTo) // INV-7 取 max
        val unread = db.hubQueries.countUnread(g, sk, d.readUpTo).executeAsOne()
        db.hubQueries.setConversationUnread(unread, g, sk)
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /** 事件流建会话占位行：conversationId=NULL + reconciling=1，句柄由快照/校准对齐。 */
    private fun ensureConversationRow(g: String, sk: String, envelope: EventEnvelope) {
        val existing = db.hubQueries.selectConversation(g, sk).executeAsOneOrNull()
        if (existing == null) {
            upsertConversationFromEvent(g, sk, label = "", agentId = "", hasActiveRun = false, ts = envelope.ts)
        }
    }

    private fun upsertConversationFromEvent(g: String, sk: String, label: String, agentId: String, hasActiveRun: Boolean, ts: String) {
        db.hubQueries.upsertConversationFromEvent(
            gatewayId = g,
            sessionKey = sk,
            label = label,
            agentId = agentId,
            lastActiveAt = ts,
            hasActiveRun = if (hasActiveRun) 1L else 0L,
        )
    }

    private fun bumpUnread(g: String, sk: String) {
        val conv = db.hubQueries.selectConversation(g, sk).executeAsOneOrNull() ?: return
        db.hubQueries.setConversationUnread(conv.unreadCount + 1, g, sk)
    }

    private fun <T> decode(serializer: KSerializer<T>, data: JsonElement): T? =
        runCatching { json.decodeFromJsonElement(serializer, data.jsonObject) }.getOrNull()

    private fun skip() {
        stats.eventsSkipped++
    }

    companion object {
        internal const val STATE_FINAL = "final"
        internal const val STATE_FAILED = "failed"
        internal const val UI_DELIVERED = "delivered"
        internal const val UI_FAILED = "failed"
        internal const val PREVIEW_MAX = 120

        private val ListSerializerAgentRef =
            kotlinx.serialization.builtins.ListSerializer(AgentRef.serializer())
    }
}
