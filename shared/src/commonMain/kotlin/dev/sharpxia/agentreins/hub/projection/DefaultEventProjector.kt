package dev.sharpxia.agentreins.hub.projection

import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile

// ── 事件载荷 DTO（hub-wss-frames-v1.schema.json $defs/Data* 镜像，v1.1；宽松解析，未知字段忽略） ──

@Serializable
private data class DataMessageDelta(val messageId: String, val deltaText: String, val replace: Boolean? = null)

/** v1.1：openclawId/sessionId 已撤（App 面禁出 openclaw 锚字段，02 R7）。 */
@Serializable
private data class DataMessageCreated(
    val messageId: String,
    val role: String,
    val content: String,
    val sourceMessageSeq: Long,
    val commandId: String? = null,
    val idempotencyKey: String? = null,
)

@Serializable
private data class DataMessageReplaced(val messageId: String, val content: String)

@Serializable
private data class DataMessageFailed(val messageId: String, val reason: String)

@Serializable
private data class DataSessionCreated(
    val sessionKey: String,
    val agentId: String,
    val label: String,
    val hasActiveRun: Boolean,
)

/** v1.1：sessionId 已撤；lifecycleRevision 保留 = 化身更替信号（客户端据此失效本地缓存，05 §3.10）。 */
@Serializable
private data class DataSessionUpdated(
    val sessionKey: String,
    val agentId: String,
    val label: String,
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
 * [EventProjector] 的 SQLite 落库实现（05 §3.11 合并规则，契约 v1.1 路由）。
 *
 * 路由模型（Hub.sq v3 文件头）：卡片主键 = conversationId（快照/列表是卡片唯一来源）；
 * 事件→卡片经 hubConvKeys 映射 (gatewayId, sessionKey) → conversationId。映射只经两条
 * 强通道学习（弱键如 label 不做，宁可慢不可错绑）：① 自发送回执链（message.created 的
 * idempotencyKey → 命令队列/乐观行携带的 conversationId）；② 自命令 ack（信封 sessionKey +
 * 队列条目）。未映射事件不建卡：skip 并靠 R4 周期校准兜底。
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
                    conversationId = c.conversationId,
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
                        conversationId = c.conversationId,
                        seq = m.seq,
                        role = m.role,
                        content = m.content,
                        state = m.state,
                        uiState = m.uiState,
                        commandId = m.commandId,
                        idempotencyKey = m.idempotencyKey,
                        ts = m.timestamp,
                    )
                }
                snapshot.readCursors[c.conversationId]?.let { up ->
                    db.hubQueries.upsertReadCursor(c.conversationId, up)
                }
            }
            // 映射表跨重建保留（conversationId 化身更替后不变），失效项（卡已不存在）清掉
            db.hubQueries.clearConvKeysOrphan()
        }
        stats.snapshotsApplied++
    }

    /** 会话列表校准（08 §4.2）：upsert 合并，不清表、不碰 hubConvKeys（映射跨校准保留）。 */
    override fun applyConversations(rows: List<ConversationRow>) {
        db.transaction {
            rows.forEach { c ->
                db.hubQueries.upsertConversation(
                    conversationId = c.conversationId,
                    label = c.label,
                    agentId = c.agentId,
                    lastMessagePreview = c.lastMessagePreview,
                    lastSeq = c.lastSeq,
                    lastActiveAt = c.lastActiveAt,
                    unreadCount = c.unreadCount.toLong(),
                    hasActiveRun = if (c.hasActiveRun) 1L else 0L,
                    reconciling = if (c.reconciling) 1L else 0L,
                )
            }
            db.hubQueries.clearConvKeysOrphan()
        }
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
        val convId = resolveConversation(envelope, d.idempotencyKey) ?: return skip()
        val isUser = d.role == "user"
        // 用户行回执：同 idempotencyKey 的本地乐观行（PENDING_LOCAL 期 messageId=幂等键）整体提升，
        // 避免乐观行与服务端行双行并存（05 §3.9 义务 4 的 UI 翻转语义）
        if (isUser && d.idempotencyKey != null &&
            db.hubQueries.selectMessageByIdem(d.idempotencyKey).executeAsOneOrNull() != null
        ) {
            db.hubQueries.promoteLocalEcho(
                messageId = d.messageId,
                content = d.content,
                seq = d.sourceMessageSeq,
                commandId = d.commandId,
                ts = envelope.ts,
                idempotencyKey = d.idempotencyKey,
            )
            db.hubQueries.touchConversationPreview(
                preview = d.content.take(PREVIEW_MAX),
                seq = d.sourceMessageSeq,
                lastActiveAt = envelope.ts,
                conversationId = convId,
            )
            return
        }
        db.hubQueries.upsertMessage(
            messageId = d.messageId,
            conversationId = convId,
            seq = d.sourceMessageSeq,
            role = d.role,
            content = d.content,
            state = STATE_FINAL,
            uiState = if (isUser) UI_DELIVERED else null,
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
            conversationId = convId,
        )
        // 本地未读增量（05 §3.7 义务 3，与 Hub 同口径：当前化身 seq>readUpTo 的 assistant final 行；
        // role="" 合成行不计）
        if (!isUser && d.role != ROLE_SYNTHETIC) {
            val readUpTo = db.hubQueries.selectReadCursor(convId).executeAsOneOrNull() ?: 0L
            if (d.sourceMessageSeq > readUpTo) {
                bumpUnread(convId)
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
        val convId = resolveConversation(envelope, null) ?: return
        db.hubQueries.setConversationActiveRun(
            hasActiveRun = if (active) 1L else 0L,
            conversationId = convId,
        )
    }

    private fun onSessionCreated(envelope: EventEnvelope) {
        // v1.1：事件不带 conversationId，不建卡（卡片唯一来源 = 快照/列表校准），且新会话的
        // conversationId 未知、映射无从学习——静默忽略（非解析失败，不计 skip），卡片由 R4 校准拉入。
    }

    private fun onSessionUpdated(envelope: EventEnvelope) {
        val d = decode(DataSessionUpdated.serializer(), envelope.data) ?: return skip()
        val convId = resolveConversation(envelope, null) ?: return skip()
        val existing = db.hubQueries.selectConversation(convId).executeAsOneOrNull() ?: return skip()
        // 化身更替（05 §3.10）：revision 持久基准（列，-1=未知/快照行）——基准未知时只记录不重置；
        // 已知且变化 = 更替：旧代次消息封存移出、seq 从 1 重计、未读清零、readCursor 重置
        val prior = existing.lifecycleRevision
        if (prior >= 0 && prior != d.lifecycleRevision) {
            db.hubQueries.deleteMessagesByConversation(convId)
            db.hubQueries.deleteReadCursor(convId)
            db.hubQueries.resetConversationIncarnation(convId)
        }
        db.hubQueries.updateConversationIdentity(
            label = d.label,
            hasActiveRun = if (d.hasActiveRun) 1L else 0L,
            revision = d.lifecycleRevision,
            conversationId = convId,
        )
    }

    private fun onSessionRemoved(envelope: EventEnvelope) {
        val d = decode(DataSessionRemoved.serializer(), envelope.data) ?: return skip()
        val g = envelope.gatewayId
        val sk = d.sessionKey.ifEmpty { envelope.sessionKey } ?: return skip()
        val key = db.hubQueries.selectConversationByKey(g, sk).executeAsOneOrNull() ?: return skip()
        val convId = key.conversationId
        db.hubQueries.deleteConversation(convId)
        db.hubQueries.deleteMessagesByConversation(convId)
        db.hubQueries.deleteReadCursor(convId)
        db.hubQueries.deleteConvKey(g, sk)
    }

    // ── approval / command / read 域 ─────────────────────────────────────────

    private fun onApprovalRequested(envelope: EventEnvelope) {
        val d = decode(DataApprovalRequested.serializer(), envelope.data) ?: return skip()
        val convId = resolveConversation(envelope, null)
        db.hubQueries.upsertApproval(
            approvalId = d.approvalId,
            runId = d.runId,
            conversationId = convId,
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
        learnMappingFromCommand(envelope, d.idempotencyKey)
    }

    private fun onCommandFailed(envelope: EventEnvelope) {
        val d = decode(DataCommandFailed.serializer(), envelope.data) ?: return skip()
        db.hubQueries.updateCommandState(d.status, d.errorCode, envelope.ts, d.idempotencyKey)
        if (d.status == "FAILED") {
            db.hubQueries.setMessageUiStateByIdem(UI_FAILED, d.idempotencyKey)
        }
        learnMappingFromCommand(envelope, d.idempotencyKey)
    }

    private fun onReadUpdated(envelope: EventEnvelope) {
        val d = decode(DataReadUpdated.serializer(), envelope.data) ?: return skip()
        // conversationId 直接可路由（read 域载荷自携带），无需映射
        db.hubQueries.selectConversation(d.conversationId).executeAsOneOrNull() ?: return skip()
        db.hubQueries.upsertReadCursor(d.conversationId, d.readUpTo) // INV-7 取 max
        val unread = db.hubQueries.countUnread(d.conversationId, d.readUpTo).executeAsOne()
        db.hubQueries.setConversationUnread(unread, d.conversationId)
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /**
     * 事件 → 卡片路由 + 映射学习：
     * ① hubConvKeys 直查；② 自发送回执链——message.created 的 idempotencyKey 在本地命令队列
     * （或乐观行）中携带 conversationId，学习后写映射；③ 仍无 → null（skip，校准兜底）。
     */
    private fun resolveConversation(envelope: EventEnvelope, idempotencyKey: String?): String? {
        val sk = envelope.sessionKey ?: return null
        val g = envelope.gatewayId
        db.hubQueries.selectConversationByKey(g, sk).executeAsOneOrNull()?.let { return it.conversationId }
        if (idempotencyKey != null) {
            db.hubQueries.selectCommandByKey(idempotencyKey).executeAsOneOrNull()?.conversationId
                ?.let { convId ->
                    db.hubQueries.upsertConvKey(g, sk, convId)
                    return convId
                }
            db.hubQueries.selectMessageByIdem(idempotencyKey).executeAsOneOrNull()?.conversationId
                ?.let { convId ->
                    db.hubQueries.upsertConvKey(g, sk, convId)
                    return convId
                }
        }
        return null
    }

    /** 自命令 ack 的映射学习（通道②）：信封 (gatewayId, sessionKey) + 队列条目 conversationId。 */
    private fun learnMappingFromCommand(envelope: EventEnvelope, idempotencyKey: String) {
        val sk = envelope.sessionKey ?: return
        val convId = db.hubQueries.selectCommandByKey(idempotencyKey).executeAsOneOrNull()?.conversationId ?: return
        db.hubQueries.upsertConvKey(envelope.gatewayId, sk, convId)
    }

    private fun bumpUnread(convId: String) {
        val conv = db.hubQueries.selectConversation(convId).executeAsOneOrNull() ?: return
        db.hubQueries.setConversationUnread(conv.unreadCount + 1, convId)
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
        internal const val ROLE_SYNTHETIC = ""
        internal const val PREVIEW_MAX = 120

        private val ListSerializerAgentRef =
            kotlinx.serialization.builtins.ListSerializer(AgentRef.serializer())
    }
}
