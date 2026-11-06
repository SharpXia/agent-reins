package dev.sharpxia.agentreins.hub.projection

import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.concurrent.Volatile

// ── 事件载荷 DTO（hub-wss-frames-v1.schema.json $defs/Data* 镜像，v1.1；宽松解析，未知字段忽略） ──

@Serializable
private data class DataMessageDelta(val messageId: String, val deltaText: String, val replace: Boolean? = null)

/** v1.1：openclawId/sessionId 已撤（App 面禁出 openclaw 锚字段，02 R7）。
 *  2026-11-06 真机修复：sourceMessageSeq 契约必填（hub-wss-frames-v1 DataMessageCreated required），
 *  但 eventlog 存量行（bridge 修复前入库）不带该字段——必填解码整帧失败 → message.created
 *  族静默 skip，除冷启动快照外任何消息进不了详情页（真机 0.5.4 根因）。改为缺省 0 + 落库时
 *  兜底（见 onMessageCreated），宽松解析纪律与全文件一致。 */
@Serializable
private data class DataMessageCreated(
    val messageId: String,
    val role: String,
    val content: String,
    val sourceMessageSeq: Long = 0,
    val commandId: String? = null,
    val idempotencyKey: String? = null,
)

@Serializable
private data class DataMessageReplaced(val messageId: String, val content: String)

/** reason 可选（v1.1 载荷仅在携带 errorKind 时透传，bridge/src/normalize/index.ts chat.error）：
 *  缺省不整帧丢弃——失败终态照落，reason 列置 NULL。 */
@Serializable
private data class DataMessageFailed(val messageId: String, val reason: String? = null)

@Serializable
private data class DataSessionCreated(
    /** 容缺（2026-11-06，同 DataSessionUpdated）：桥现网载荷身份字段在信封、其余常缺省。 */
    val sessionKey: String = "",
    val agentId: String,
    val label: String = "",
    val hasActiveRun: Boolean = false,
)

/** v1.1：sessionId 已撤；lifecycleRevision 保留 = 化身更替信号（客户端据此失效本地缓存，05 §3.10）。 */
@Serializable
private data class DataSessionUpdated(
    val sessionKey: String = "",
    val agentId: String,
    /** 可选（2026-11-06 容缺）：桥现网载荷不挂数据内 label/sessionKey/lifecycleRevision
     *  （sessionKey 走信封；label 仅有 chat.title 变更帧携带；revision 从不出场）——必填
     *  解码曾整族失败 → session.updated 全 drop，两跳暂存/归属学习在真机从未生效。缺省
     *  语义：label="" = 不变更（upsert NULLIF 守卫）；revision=null = 不变更（COALESCE）。 */
    val label: String = "",
    val lifecycleRevision: Long? = null,
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

/** wp-15 可选项：run.status 载荷（additive 06 §3.2）。phase 期望为 Gateway 启动阶段枚举，
 *  宽松解析为字符串——异常 phase 不整帧丢弃（未知字段容忍纪律）。 */
@Serializable
private data class DataRunStatus(
    val phase: String? = null,
    val retry: DataRunStatusRetry? = null,
    val errorMessage: String? = null,
    val stopReason: String? = null,
)

@Serializable
private data class DataRunStatusRetry(val attempt: Int? = null, val maxAttempts: Int? = null, val reason: String? = null)

/**
 * wp-15：tool.* 载荷（双形状宽松解码）。契约注册形状（hub-wss-frames-v1.schema.json
 * DataToolStarted：toolName/toolCallKey/inputSummary）与 Bridge 实际透传形状
 * （normalize/index.ts normalizeToolEvent：name/toolCallId/args/result）并存——
 * 投影取并集键宽容解析，缺键降级（toolName 空串、summary null），不整帧丢弃：
 * 事件内容是非权威呈现面（计数与状态优先于名称），丢弃反而制造缺失。
 */
@Serializable
private data class DataToolEvent(
    val toolName: String? = null,
    val name: String? = null,
    val toolCallKey: String? = null,
    val toolCallId: String? = null,
    val inputSummary: String? = null,
) {
    /** 取名称：契约键优先、Bridge 键兜底；两者皆缺 = ''（UI 兜底呈现，不虚构）。 */
    val resolvedName: String get() = toolName ?: name ?: ""
    /** 取调用关联键：契约键优先、Bridge 键兜底；两者皆缺 = null（该帧不可定位调用，跳过）。 */
    val resolvedKey: String? get() = toolCallKey ?: toolCallId
}

/** 快照落定 / 事件应用时投影仪对外可见的统计（验收取证用）。 */
class ProjectionStats {
    @Volatile var eventsApplied: Int = 0
    @Volatile var eventsSkipped: Int = 0 // 载荷解析失败/目标行缺失而忽略内容（游标照常推进）
    @Volatile var snapshotsApplied: Int = 0
}

/**
 * [EventProjector] 的 SQLite 落库实现（05 §3.11 合并规则，契约 v1.1 路由）。
 *
 * 路由模型（Hub.sq v4/v7 文件头）：卡片主键 = conversationId（快照/列表是卡片唯一来源）；
 * 事件→卡片经 hubConvKeys 映射 (gatewayId, sessionKey) → conversationId。映射三条学习通道：
 * ① 自发送回执链（message.created 的 idempotencyKey → 命令队列/乐观行携带的 conversationId）；
 * ② 自命令 ack（信封 sessionKey + 队列条目）；③ 两跳归属（v7 wp-4）——session.created/updated
 * 载荷 (sessionKey, agentId) 未映射时暂存 hubSessionAgents，卡片行 (gatewayId, agentId) 到位后
 * 连结写 hubConvKeys（twoHop=1）。**两跳行分域消费**：只供 run 终态（setRun(false)）与
 * approval.requested 的归属面；message.*（时间线/未读）、session.updated 身份（label/hasActiveRun/
 * 化身）、run.started 活跃翻转、tool.* 与 run.status 捕获只认自学习行——同 agent 的 channel/TG
 * 会话与任务会话由此不混入主线卡（app 主会话隔离，2026-11-06 聚合轮 allowlist 收口的客户端
 * 对位；ADR-14 不解析键形态，以「学习通道」作隔离边界）。
 * 未映射事件不建卡：skip 并靠 R4 周期校准兜底。
 * 幂等性边界（INV-1）：本类所有写路径除 delta append/占位行创建外均为 upsert/幂等 UPDATE；
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
                    gatewayId = c.gatewayId, // v5（wp-11）：required 字段落库，不再投影即丢
                    label = c.label,
                    agentId = c.agentId,
                    lastMessagePreview = c.lastMessagePreview,
                    lastSeq = c.lastSeq,
                    lastActiveAt = c.lastActiveAt,
                    unreadCount = c.unreadCount.toLong(),
                    hasActiveRun = if (c.hasActiveRun) 1L else 0L,
                    // v6（wp-3 活跃 run 权威回填）：快照行带出服务端 runs RUNNING 权威 runId
                    //（老服务端行无此字段即 null，upsert 三级合并保留事件习得值）——冷启动后
                    // Stop 按钮即刻可用，与事件路径 setConversationActiveRun 共用同一列。
                    activeRunId = c.activeRunId,
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
                        reason = m.errorKind, // v4：wp-4 additive errorKind → reason 列（事件路径共用，冷启动失败行由此获得原因）
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
            // 映射表跨重建保留（conversationId 化身更替后不变），失效项（卡已不存在）清掉；
            // 两跳暂存行（跨重建保留）按快照卡行 (gatewayId, agentId) 再连结——快照后映射即全量可用，
            // 不等下一次 R4 校准
            joinStagedSessionAgents(snapshot.conversations)
            db.hubQueries.clearConvKeysOrphan()
        }
        stats.snapshotsApplied++
    }

    /** 会话列表校准（08 §4.2）：upsert 合并，不清表、不碰自学习映射（跨校准保留）；
     *  两跳暂存行按校准卡行 (gatewayId, agentId) 连结（v7 wp-4——新卡首条 run/审批由此即刻有归属）。 */
    override fun applyConversations(rows: List<ConversationRow>) {
        db.transaction {
            rows.forEach { c ->
                db.hubQueries.upsertConversation(
                    conversationId = c.conversationId,
                    gatewayId = c.gatewayId, // v5（wp-11）：校准行同路径透出
                    label = c.label,
                    agentId = c.agentId,
                    lastMessagePreview = c.lastMessagePreview,
                    lastSeq = c.lastSeq,
                    lastActiveAt = c.lastActiveAt,
                    unreadCount = c.unreadCount.toLong(),
                    hasActiveRun = if (c.hasActiveRun) 1L else 0L,
                    // v6（wp-3）：校准路径同口径回填 activeRunId（快照路径同注释）
                    activeRunId = c.activeRunId,
                    reconciling = if (c.reconciling) 1L else 0L,
                )
            }
            joinStagedSessionAgents(rows)
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
                // run.started 只认自学习映射（v7 分域）：两跳归属的他端/channel run 不得翻转卡片
                // 活跃态（hasActiveRun/activeRunId 是本端 Stop 按钮与活跃标记的权威面）
                EventEnvelope.RUN_STARTED -> setRun(envelope, true, allowTwoHop = false)
                // run 终态允许两跳归属（通知面消费；写面由 setRun 终态守卫保护本地在途 run 状态）
                EventEnvelope.RUN_COMPLETED,
                EventEnvelope.RUN_FAILED,
                EventEnvelope.RUN_ABORTED,
                -> setRun(envelope, false, allowTwoHop = true)
                // wp-15 可选项（additive）：run.status = Bridge chat status/aborted 变体的 Run 阶段帧
                //（06 §3.2 additive 注册）；老服务端不发本类型，缺省走 else 忽略分支语义不变。
                EventEnvelope.RUN_STATUS -> onRunStatus(envelope)
                // wp-15：tool.* 本地捕获（v6 hubToolEvents）——Hub 无工具投影表、断线窗口不补发
                //（06 §10.2），会话页「工作过程」披露条只呈现本地已捕获部分，不承诺完整。
                EventEnvelope.TOOL_STARTED -> onToolStarted(envelope)
                EventEnvelope.TOOL_COMPLETED -> onToolFinished(envelope, "completed")
                EventEnvelope.TOOL_FAILED -> onToolFinished(envelope, "failed")
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
                else -> Unit // 未注册类型：忽略内容，照常推进游标（06 §12.2 义务 2）
            }
        }
        stats.eventsApplied++
    }

    // ── message 域 ───────────────────────────────────────────────────────────

    private fun onMessageDelta(envelope: EventEnvelope) {
        val d = decode(DataMessageDelta.serializer(), envelope.data) ?: return skip()
        val row = db.hubQueries.selectMessage(d.messageId).executeAsOneOrNull()
        if (row == null) {
            // 缺行建 streaming 占位行（v4，wp-5①）：wp-1 探针定档「无锚」——本宿主 Gateway 的
            // chat.delta 不带 __openclaw 信封，合并产物 messageId = dstream:<runId>（run 域合成
            // 身份），与 created 行 messageId（displayRowId 26hex）**异锚**——缺行不再是边缘而是
            // 主路径，直接 skip 即流式面上游缺口。门卫：仅会话存在活跃 run 时建占位（run.started
            // 先于 delta 同序到达/快照 hasActiveRun=1）；无活跃 run 的缺行 delta = 迟到重放噪声
            // （合并器 final 后抑制驱逐兜底放行/run 终态清除后再到），skip 防幽灵气泡复活。
            // 只认自学习映射（v7 分域）：两跳行是同 agent 其它会话（channel/任务），不得写入主线卡时间线
            val convId = resolveConversation(envelope, null, allowTwoHop = false) ?: return skip() // 未映射不建卡（卡片唯一来源 = 快照/校准）
            val conv = db.hubQueries.selectConversation(convId).executeAsOneOrNull() ?: return skip()
            if (conv.hasActiveRun == 0L) return skip()
            db.hubQueries.upsertMessage(
                messageId = d.messageId,
                conversationId = convId,
                // 占位行 seq 取当前权威 lastSeq+1（显示序压到末尾；服务端消息 seq 口径不被污染——
                // 不推进 lastSeq。禁止 MAX(messages.seq)+1：乐观用户行 seq=Long.MAX_VALUE 会溢出）。
                // 同会话多占位行可能并列——异锚/终态清除下为瞬态，可接受。
                seq = conv.lastSeq + 1L,
                role = "assistant",
                content = if (d.replace == true) d.deltaText else "",
                state = STATE_STREAMING,
                reason = null,
                uiState = null,
                commandId = null,
                idempotencyKey = null,
                ts = envelope.ts,
            )
            if (d.replace != true) {
                db.hubQueries.appendMessageContent(d.deltaText, d.messageId)
            }
            return
        }
        if (d.replace == true) {
            db.hubQueries.replaceMessageContent(d.deltaText, d.messageId)
        } else {
            db.hubQueries.appendMessageContent(d.deltaText, d.messageId)
        }
    }

    private fun onMessageCreated(envelope: EventEnvelope) {
        val d = decode(DataMessageCreated.serializer(), envelope.data) ?: return skip()
        // 只认自学习映射（v7 分域，注释同 onMessageDelta）：同 agent 的 channel/任务会话消息
        // 不得写入主线卡时间线、不得推未读（app 主会话隔离）
        val convId = resolveConversation(envelope, d.idempotencyKey, allowTwoHop = false) ?: return skip()
        // seq 兜底（2026-11-06 真机修复，DTO 注释）：存量事件行不带 sourceMessageSeq（=0）时，
        // 按当前权威 lastSeq+1 顺延——与 delta 占位行同款兜底口径（服务端 seq 口径不被污染：
        // 不回写 lastSeq 之外的服务端语义）。卡片行缺失（快照/校准未到）且无载荷 seq → 保守 skip。
        val seq = if (d.sourceMessageSeq > 0L) d.sourceMessageSeq else {
            db.hubQueries.selectConversation(convId).executeAsOneOrNull()?.lastSeq?.plus(1L)
                ?: return skip()
        }
        val isUser = d.role == "user"
        // 用户行回执：同 idempotencyKey 的本地乐观行（PENDING_LOCAL 期 messageId=幂等键）整体提升，
        // 避免乐观行与服务端行双行并存（05 §3.9 义务 4 的 UI 翻转语义）
        if (isUser && d.idempotencyKey != null &&
            db.hubQueries.selectMessageByIdem(d.idempotencyKey).executeAsOneOrNull() != null
        ) {
            db.hubQueries.promoteLocalEcho(
                messageId = d.messageId,
                content = d.content,
                seq = seq,
                commandId = d.commandId,
                ts = envelope.ts,
                idempotencyKey = d.idempotencyKey,
            )
            db.hubQueries.touchConversationPreview(
                preview = d.content.take(PREVIEW_MAX),
                seq = seq,
                lastActiveAt = envelope.ts,
                conversationId = convId,
            )
            return
        }
        if (!isUser) {
            // 异锚收敛清除（v4，wp-5①主分支，评审阻塞项 1③）：非用户权威行落定时清除同会话
            // 其余流式占位行（messageId ≠ 本行）——无锚 delta 占位行（dstream:<runId>）与 created
            // 行异锚，不清除即「流式幽灵气泡 + 重复 final 气泡」。同锚分支（未来 Gateway 回锚）
            // 下占位行即本行（被 ≠ 排除），随后 INSERT OR REPLACE 同键覆盖收敛，防御性保留。
            db.hubQueries.deleteStreamingPlaceholdersExcept(convId, d.messageId)
        }
        db.hubQueries.upsertMessage(
            messageId = d.messageId,
            conversationId = convId,
            seq = seq,
            role = d.role,
            content = d.content,
            state = STATE_FINAL,
            reason = null,
            uiState = if (isUser) UI_DELIVERED else null,
            commandId = d.commandId,
            idempotencyKey = d.idempotencyKey,
            ts = envelope.ts,
        )
        if (isUser) {
            // 发送方乐观行翻转（05 §3.9 义务 4）：幂等键命中的本地行 sending → delivered
            if (d.idempotencyKey != null) {
                db.hubQueries.setMessageUiStateByIdem(UI_DELIVERED, d.idempotencyKey)
            } else {
                // 回显不带幂等键（bridge 契约缺口：用户行 required commandId/idempotencyKey，
                // Gateway 载荷不携带、bridge 无法回填）——同会话在途乐观行已被本权威行取代，
                // 不清除即「已送达权威行 + seq=MAX 乐观行」双气泡并存（协议次序 ack→echo，
                // 此处仅清已过 DELIVERED 态的行；仍在途 SENDING 行不动）。
                db.hubQueries.deleteDeliveredOptimisticExcept(convId, d.messageId)
            }
        }
        db.hubQueries.touchConversationPreview(
            preview = d.content.take(PREVIEW_MAX),
            seq = seq,
            lastActiveAt = envelope.ts,
            conversationId = convId,
        )
        // 本地未读增量（05 §3.7 义务 3，与 Hub 同口径：当前化身 seq>readUpTo 的 assistant final 行；
        // role="" 合成行不计）
        if (!isUser && d.role != ROLE_SYNTHETIC) {
            val readUpTo = db.hubQueries.selectReadCursor(convId).executeAsOneOrNull() ?: 0L
            if (seq > readUpTo) {
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
        db.hubQueries.selectMessage(d.messageId).executeAsOneOrNull() ?: return skip()
        // reason 落库（v4，wp-5②）：载荷 reason = 脱敏 errorKind 闭集（06 §3.1.4），此前只翻
        // state 丢 reason——快照重建前失败行徽标无因。缺省载荷（未分类）写 NULL，不虚构。
        db.hubQueries.setMessageStateReason(STATE_FAILED, d.reason, d.messageId)
    }

    // ── run / session 域 ─────────────────────────────────────────────────────

    /**
     * run.* 事件：hasActiveRun 翻转 + 当前活跃 runId 捕获/清空（wp-8 Stop 按钮取值）。
     * v7 分域：run.started 只走自学习映射（[allowTwoHop]=false 调用）；终态允许两跳归属
     * （通知面消费；写面由下方终态守卫保护本端在途 run 状态）。
     */
    private fun setRun(envelope: EventEnvelope, active: Boolean, allowTwoHop: Boolean) {
        val convId = resolveConversation(envelope, null, allowTwoHop) ?: return
        // v4（wp-5③）：捕获当前活跃 runId（wp-8 Stop 按钮 → REST #11 run.cancel 路径参数必带
        // runId，hub-api-v1.yaml:500-511）。信封 run 域必带 runId（06 §2.2）；防御性再读
        // data.runId（与 Hub 引擎 applyRun 同序）。终态一律置 NULL——hasActiveRun=0 的写路径
        // 不残留死 runId。
        val runId = envelope.runId ?: dataRunId(envelope)
        if (!active) {
            // v7 wp-4 终态守卫：两跳归属的终态（同 agent 其它会话的他端/channel run）与乱序旧终态
            // 不得清掉卡片正在跟踪的活跃 run——activeRunId 与本终态 runId 不一致时只清本 run 的
            // 阶段帧，不翻转 hasActiveRun/activeRunId、不清流式行（防误杀本地在途 run 的 Stop 面
            // 与流式气泡）。
            val existing = db.hubQueries.selectConversation(convId).executeAsOneOrNull()
            if (existing != null && existing.activeRunId != null && existing.activeRunId != runId) {
                runId?.let { db.hubQueries.deleteRunStatusByRunId(envelope.gatewayId, it) }
                return
            }
        }
        db.hubQueries.setConversationActiveRun(
            hasActiveRun = if (active) 1L else 0L,
            activeRunId = if (active) runId else null,
            conversationId = convId,
        )
        if (!active) {
            // run 终态清除（v4）：失败/中止 run 无 message 终态事件可收敛其占位行（异锚身份），
            // 活跃 run 结束即清会话全部流式行——正常完成时占位行已被 created 清除，此处兜底
            // 防幽灵流式气泡；单活跃 run 模型下终态时刻不存在合法在途流。
            db.hubQueries.deleteStreamingRows(convId)
            // wp-15（v6）：run 终态连带清该 run 的阶段帧——终态后无在途阶段语义，
            // 「工作过程」披露条不再显示过期 phase。
            (envelope.runId ?: dataRunId(envelope))?.let {
                db.hubQueries.deleteRunStatusByRunId(envelope.gatewayId, it)
            }
        }
    }

    /** run 域载荷 runId 兜底（信封缺省时；与 Hub 引擎 applyRun 的取序一致）。 */
    private fun dataRunId(envelope: EventEnvelope): String? =
        runCatching { envelope.data.jsonObject["runId"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() } }.getOrNull()

    // ── tool / run.status 域（wp-15 v6：本地捕获，已知丢失面 06 §10.2——不补发不承诺完整） ──

    /** tool.started：建 running 行（同键重报不改状态，防重复投递把已完成调用翻回在途）。
     *  只认自学习映射（v7 分域）：channel/任务会话的工具活动不挂进主线卡「工作过程」。 */
    private fun onToolStarted(envelope: EventEnvelope) {
        val d = decode(DataToolEvent.serializer(), envelope.data) ?: return skip()
        val callKey = d.resolvedKey ?: return skip() // 无调用关联键无法串联三态，跳过（丢弃计数）
        val convId = resolveConversation(envelope, null, allowTwoHop = false)
        db.hubQueries.upsertToolStarted(
            gatewayId = envelope.gatewayId,
            runId = envelope.runId ?: return skip(), // tool 域信封必带 runId（06 §2.2）；缺即毒帧跳过
            toolCallKey = callKey,
            conversationId = convId,
            toolName = d.resolvedName,
            summary = toolSummary(envelope.data),
            firstTs = envelope.ts,
            lastTs = envelope.ts,
        )
        pruneToolEvents(convId)
    }

    /** tool.completed/failed：状态翻转；started 缺失（丢失面）时落终态行——计数不缺项，名称缺省 ''。 */
    private fun onToolFinished(envelope: EventEnvelope, status: String) {
        val d = decode(DataToolEvent.serializer(), envelope.data) ?: return skip()
        val callKey = d.resolvedKey ?: return skip()
        val convId = resolveConversation(envelope, null, allowTwoHop = false)
        db.hubQueries.upsertToolFinished(
            gatewayId = envelope.gatewayId,
            runId = envelope.runId ?: return skip(),
            toolCallKey = callKey,
            conversationId = convId,
            toolName = d.resolvedName,
            status = status,
            firstTs = envelope.ts,
            lastTs = envelope.ts,
        )
        pruneToolEvents(convId)
    }

    /** wp-15 可选项：run.status（Bridge chat status/aborted 变体）→ 每 run 最新阶段帧。
     *  只认自学习映射（v7 分域，同 tool.* 注释）。 */
    private fun onRunStatus(envelope: EventEnvelope) {
        val d = decode(DataRunStatus.serializer(), envelope.data) ?: return skip()
        val runId = envelope.runId ?: dataRunId(envelope) ?: return skip()
        val convId = resolveConversation(envelope, null, allowTwoHop = false)
        db.hubQueries.upsertRunStatus(
            gatewayId = envelope.gatewayId,
            runId = runId,
            conversationId = convId,
            phase = d.phase ?: "",
            detail = runStatusDetail(d),
            ts = envelope.ts,
        )
    }

    /** run.status 附加说明（有界）：retry 重试 > aborted/错误说明 > stopReason；皆缺省 NULL。 */
    private fun runStatusDetail(d: DataRunStatus): String? {
        d.retry?.let { r ->
            if (r.attempt != null && r.maxAttempts != null) {
                return ("重试 ${r.attempt}/${r.maxAttempts}" + (r.reason?.let { "（$it）" } ?: "")).take(SUMMARY_MAX)
            }
        }
        d.errorMessage?.let { return it.take(SUMMARY_MAX) }
        d.stopReason?.let { return it.take(SUMMARY_MAX) }
        return null
    }

    /**
     * tool 入参摘要（有界）：契约 inputSummary 字符串优先；Bridge 实际形状 args 对象在
     * 序列化后截断（呈现面非权威，截断不破坏正确性）。皆缺省 NULL（行可无摘要）。
     */
    private fun toolSummary(data: JsonElement): String? {
        val obj = runCatching { data.jsonObject }.getOrNull() ?: return null
        (obj["inputSummary"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return it.content.take(SUMMARY_MAX) }
        val args = obj["args"] as? JsonObject ?: return null
        return args.toString().take(SUMMARY_MAX) // JsonElement.toString() 即 JSON 文本
    }

    /** 每会话累积上限（防无界增长）：只保留最近 TOOL_EVENTS_MAX_PER_CONV 行。 */
    private fun pruneToolEvents(convId: String?) {
        if (convId != null) db.hubQueries.pruneToolEvents(convId, TOOL_EVENTS_MAX_PER_CONV)
    }

    /**
     * session.created（v7 wp-4 两跳学习）：不建卡（卡片唯一来源 = 快照/列表校准铁律不变），且
     * 新会话的 conversationId 未知——未映射时按载荷 (sessionKey, agentId) 暂存 hubSessionAgents
     * 并即时尝试连结（卡片已在则本事件即通，未到则等 applyConversations/applySnapshot 连结），
     * 卡片由 R4 校准拉入。
     */
    private fun onSessionCreated(envelope: EventEnvelope) {
        val d = decode(DataSessionCreated.serializer(), envelope.data) ?: return // 静默忽略（非解析失败，不计 skip）
        val sk = envelope.sessionKey ?: d.sessionKey
        stageAndJoinSessionAgent(envelope.gatewayId, sk, d.agentId)
    }

    private fun onSessionUpdated(envelope: EventEnvelope) {
        val d = decode(DataSessionUpdated.serializer(), envelope.data) ?: return skip()
        // 身份更新只认自学习映射（v7 分域）：channel/任务会话的 label/hasActiveRun/化身不得写入
        // 主线卡。未映射 → 暂存 + 尝试连结（两跳行只供 run 终态/审批归属面，身份更新仍 skip）。
        val convId = resolveConversation(envelope, null, allowTwoHop = false)
        if (convId == null) {
            val sk = envelope.sessionKey ?: d.sessionKey
            stageAndJoinSessionAgent(envelope.gatewayId, sk, d.agentId)
            return skip()
        }
        val existing = db.hubQueries.selectConversation(convId).executeAsOneOrNull() ?: return skip()
        // 化身更替（05 §3.10）：revision 持久基准（列，-1=未知/快照行）——基准未知时只记录不重置；
        // 已知且变化 = 更替：旧代次消息封存移出、seq 从 1 重计、未读清零、readCursor 重置。
        // 载荷缺 revision（桥现网形状，2026-11-06 容缺）= 不变更，不得与既有基准比较。
        val prior = existing.lifecycleRevision
        if (prior >= 0 && d.lifecycleRevision != null && prior != d.lifecycleRevision) {
            db.hubQueries.deleteMessagesByConversation(convId)
            db.hubQueries.deleteReadCursor(convId)
            db.hubQueries.resetConversationIncarnation(convId)
            // wp-15（v6）：化身更替与消息同口径——旧代次工具活动/阶段帧一并封存移出
            db.hubQueries.deleteToolEventsByConversation(convId)
            db.hubQueries.deleteRunStatusByConversation(convId)
        }
        db.hubQueries.updateConversationIdentity(
            label = d.label,
            hasActiveRun = if (d.hasActiveRun) 1L else 0L,
            revision = d.lifecycleRevision,
            conversationId = convId,
        )
    }

    /**
     * session.removed（v7 wp-4 调整）：暂存行无条件清（session 已移除，两跳暂存失效）；
     * 级联删卡**只对自学习行**——两跳连结行属于同 agent 的其它会话（channel/任务），若跟随删卡
     * 会把主线卡连带误删（TG 会话删除 = 主线卡消失）。两跳行此处清映射、卡片保留（卡片唯一来源
     * = 快照/校准，由服务端聚合口径收敛）。
     */
    private fun onSessionRemoved(envelope: EventEnvelope) {
        val d = decode(DataSessionRemoved.serializer(), envelope.data) ?: return skip()
        val g = envelope.gatewayId
        val sk = d.sessionKey.ifEmpty { envelope.sessionKey } ?: return skip()
        db.hubQueries.deleteSessionAgent(g, sk)
        val key = db.hubQueries.selectConversationByKey(g, sk).executeAsOneOrNull()
            ?: return skip()
        if (key.twoHop == 1L) {
            db.hubQueries.deleteConvKey(g, sk)
            return skip()
        }
        val convId = key.conversationId
        db.hubQueries.deleteConversation(convId)
        db.hubQueries.deleteMessagesByConversation(convId)
        db.hubQueries.deleteReadCursor(convId)
        db.hubQueries.deleteToolEventsByConversation(convId)
        db.hubQueries.deleteRunStatusByConversation(convId)
        db.hubQueries.deleteConvKey(g, sk)
    }

    // ── approval / command / read 域 ─────────────────────────────────────────

    /** approval.requested：允许两跳归属（v7 分域）——他端/channel run 的审批具行动性，
     *  归属到主线卡后通知/审批卡可带会话名且能按会话静音。 */
    private fun onApprovalRequested(envelope: EventEnvelope) {
        val d = decode(DataApprovalRequested.serializer(), envelope.data) ?: return skip()
        val convId = resolveConversation(envelope, null, allowTwoHop = true)
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
        // 发送行 UI 翻转（2026-11-06 UX 轮 #6 修正）：此前任何 ack（含 PERSISTED/DELIVERING）
        // 一律翻 delivered——乐观行发出毫秒级即呈 ✓，「发送中」徽标从未可见；网关迟后报失败
        // （实测 chat 启动超时 ~15s，PERSISTED→DELIVERING→FAILED trigger=gateway）即成
        // 「✓ 又发送失败」观感。改为仅在网关确认后翻已投递：PERSISTED/DELIVERING 保持
        // sending（发送中徽标可见窗口），GATEWAY_ACKED/COMPLETED → delivered；COMPLETED
        // 语义仍由 message.created 权威内容收敛（此处只做保底）。
        when (d.status) {
            "GATEWAY_ACKED", "COMPLETED" ->
                db.hubQueries.setMessageUiStateByIdem(UI_DELIVERED, d.idempotencyKey)
            else -> Unit
        }
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
     * ① hubConvKeys 直查（[allowTwoHop]=false 时两跳连结行不参与路由——分域消费，见类注释）；
     * ② 自发送回执链——message.created 的 idempotencyKey 在本地命令队列（或乐观行）中携带
     * conversationId，学习后写映射（自学习即翻回 twoHop=0）；③ 仍无 → null（skip，校准兜底）。
     */
    private fun resolveConversation(envelope: EventEnvelope, idempotencyKey: String?, allowTwoHop: Boolean): String? {
        val sk = envelope.sessionKey ?: return null
        val g = envelope.gatewayId
        db.hubQueries.selectConversationByKey(g, sk).executeAsOneOrNull()?.let { row ->
            if (allowTwoHop || row.twoHop == 0L) return row.conversationId
        }
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

    /**
     * v7 wp-4 两跳学习入口：未映射的 session 域载荷 (sessionKey, agentId) 暂存 hubSessionAgents，
     * 并即时尝试连结（同 (gatewayId, agentId) 卡片已在 → 映射立即可路由；未到则等
     * applyConversations/applySnapshot 连结）。
     */
    private fun stageAndJoinSessionAgent(gatewayId: String, sessionKey: String, agentId: String) {
        if (sessionKey.isEmpty() || agentId.isEmpty()) return
        db.hubQueries.upsertSessionAgent(gatewayId, sessionKey, agentId)
        db.hubQueries.selectConversationIdByAgent(gatewayId, agentId).executeAsOneOrNull()?.let { convId ->
            db.hubQueries.upsertConvKeyTwoHop(gatewayId, sessionKey, convId)
        }
    }

    /** 快照/校准卡行到位后的两跳批量连结（每张卡收编其同 (gatewayId, agentId) 暂存行）。 */
    private fun joinStagedSessionAgents(rows: List<ConversationRow>) {
        rows.forEach { c ->
            db.hubQueries.selectSessionAgentsByCard(c.gatewayId, c.agentId).executeAsList().forEach { staged ->
                db.hubQueries.upsertConvKeyTwoHop(staged.gatewayId, staged.sessionKey, c.conversationId)
            }
        }
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
        internal const val STATE_STREAMING = "streaming"
        internal const val STATE_FAILED = "failed"
        internal const val UI_DELIVERED = "delivered"
        internal const val UI_FAILED = "failed"
        internal const val ROLE_SYNTHETIC = ""
        internal const val PREVIEW_MAX = 120

        /** wp-15：tool 入参摘要/run.status 说明的有界截断（呈现面非权威，与审批摘要同纪律）。 */
        internal const val SUMMARY_MAX = 300

        /** wp-15：每会话工具活动累积上限（「工作过程」披露条呈现的是近期工作，非全量账本）。 */
        internal const val TOOL_EVENTS_MAX_PER_CONV: Long = 200

        private val ListSerializerAgentRef =
            kotlinx.serialization.builtins.ListSerializer(AgentRef.serializer())
    }
}
