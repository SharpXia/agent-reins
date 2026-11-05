package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.platformCredentialStore
import dev.sharpxia.agentreins.hub.command.CommandClient
import dev.sharpxia.agentreins.hub.command.DefaultCommandClient
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.EventProjector
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hub.db.createHubDriver
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hub.sync.DbEventCursorStore
import dev.sharpxia.agentreins.hub.sync.DefaultSyncEngine
import dev.sharpxia.agentreins.hub.sync.DefaultSyncScheduler
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** UI 只读模型：隔离 sqldelight 生成行类型（iOS 桥按此投影）。 */
data class UiConversation(
    /** Hub ULID 句柄；化身更替后不变。卡片唯一来源 = 快照/列表校准（v1.1：UI 只见 conversation）。 */
    val conversationId: String,
    /**
     * 归属网关（v5 wp-11 透出，ConversationRow required）：此前投影落库即丢——#16 新建会话入口
     * 须按 (gatewayId, agentId) 判「该 agent 是否已有主线卡」，网关维度展示同样以此归属。
     */
    val gatewayId: String,
    val label: String,
    val agentId: String,
    val preview: String,
    val lastSeq: Long,
    val lastActiveAt: String,
    val unread: Int,
    val hasActiveRun: Boolean,
    val reconciling: Boolean,
    /** 当前活跃 runId（v4 wp-5③，hubConversations.activeRunId 投影；wp-8 Stop 调 run.cancel 取值）。
     *  映射未学习场景（setRun 在 resolveConversation 失败时直接 return）为 null——消费方不得假设恒有值。 */
    val activeRunId: String? = null,
)

/** UI 消息行：state ∈ final/streaming/failed；uiState 仅用户行（sending/delivered/read/failed）。 */
data class UiMessage(
    val messageId: String,
    val seq: Long,
    val role: String,
    val content: String,
    val state: String,
    val uiState: String?,
    val ts: String?,
    /**
     * 失败原因（v4 wp-5②，hubMessages.reason 投影）：事件路径 message.failed.reason 与快照路径
     * MessageRow.errorKind 双路径共列，六值脱敏闭集（refusal/timeout/rate_limit/context_length/
     * state_contention/unknown）；仅 failed 行可携带，未分类失败为 null（不虚构）。
     */
    val errorKind: String? = null,
)

/**
 * UI 消息翻页结果（wp-12）：REST #8 MessagesPage 的 UI 投影——明细行已按 messageId upsert
 * 合并进投影库（observeMessages Flow 即推送合并后全序列），本类型只承载 UI 决策面。
 */
data class UiMessagesPage(
    /** 服务端权威「还有更早行」：加载更早按钮可见性唯一依据（wp-12 scope，hasMore 驱动）。 */
    val hasMore: Boolean,
    /** 本页最老 seq（服务端口径，空页归一为 null）——下一次翻页游标按此推进（08 §4.3 keyset）。 */
    val oldestSeq: Long?,
    /** 本页最新 seq（服务端口径，空页归一为 null）。 */
    val newestSeq: Long?,
    /** 本页合入行数（UI 诊断；0 页 + hasMore=true 视为无法推进，调用方应终止翻页）。 */
    val count: Int,
)

/** UI 审批卡模型（wp-9）：toolName + payloadSummary + 会话归属；只出 status=pending 行。 */
data class UiApproval(
    /** Hub 侧审批标识（06 §3.6）：不透明字符串，客户端不解析内部结构（ADR-14）。 */
    val approvalId: String,
    val runId: String,
    /**
     * 会话归属（事件路由学习成果；DefaultEventProjector.onApprovalRequested 经 hubConvKeys 查映射，
     * 未命中为 null）——null = 归属未学习，卡片以「未关联会话」降级呈现，不得编造归属。
     */
    val conversationId: String?,
    val toolName: String,
    /** 有界载荷摘要（DataApprovalRequested.payloadSummary，原样透出）。 */
    val payloadSummary: String,
    val updatedAt: String,
)

/** 网关下的 Agent 只读行（GatewaySummary.agents 的 AgentRef 镜像，wp-11 透出 agentsJson）。 */
data class UiAgentRef(
    val agentId: String,
    val label: String,
)

/**
 * UI 网关行（wp-11）：快照 gateways（wp-2 填充，形状 reader_pg.go Gateways）+ gateway.online/offline
 * 事件维护的 online 时点。agentsJson（hubGateways.agents，只存不显）在此解析为结构化 [UiAgentRef]——
 * 「Agent 清单只取 active 行」由服务端 Gateways 口径保证，客户端原样透出不建关系（Hub.sq 注）。
 */
data class UiGateway(
    val gatewayId: String,
    val name: String,
    val online: Boolean,
    val lastSeenAt: String?,
    /** AgentRef 数组解析产物；JSON 解析失败按空清单降级（网关行本体不丢）。 */
    val agents: List<UiAgentRef>,
)

/**
 * Hub 客户端门面（P3 定稿）：认证栈 + 投影库 + 同步引擎 + WSS + 命令队列一站式组装。
 * 时序：create（库 + runtime）→ login/restore 成功（runtime 进入 LoggedIn、authStack 就绪）→
 * [start] 组装 engine/scheduler 并起同步循环 → [stop] 停止。UI 经 observeXxx Flow 订阅投影库，
 * 写路径全走 commands（幂等队列）。db=null（存储不可用）时只保留认证面，同步/命令不可用。
 */
class DefaultHubClient(
    private val scope: CoroutineScope,
    val runtime: HubRuntime,
    private val db: HubDatabase?,
    /**
     * wp-13（additive，可选观察面）：事件旁路回调——投影 applyEvent 之后同步触发（信封为已
     * 应用且已过幂等/水位裁决的事件）。纯观察：不改变投影语义、游标推进与幂等去重；缺省 null
     * 时零开销零行为变化（iOS 通知面用其区分 run 终态 completed/failed——投影把三种终态压成
     * 同一 hasActiveRun=0，Flow 面无法还原事件类型）。回调运行在同步引擎协程线程（本桥传
     * Dispatchers.Main scope，即主线程）。
     */
    private val eventTap: ((EventEnvelope) -> Unit)? = null,
) {
    var projection: EventProjector? = null
        private set
    var engine: DefaultSyncEngine? = null
        private set
    var commands: CommandClient? = null
        private set

    private var scheduler: DefaultSyncScheduler? = null

    /** 登录态就绪后组装同步栈并启动（幂等：重复调用忽略；登出后 stop 再 login 需重新 start）。 */
    fun start() {
        val stack = runtime.authStack ?: return
        if (scheduler != null || db == null) return
        val baseProjection = DefaultEventProjector(db)
        projection = if (eventTap != null) TappedEventProjector(baseProjection, eventTap) else baseProjection
        val cursors = DbEventCursorStore(db)
        engine = DefaultSyncEngine(stack.api, stack.auth, projection!!, cursors)
        commands = DefaultCommandClient(stack.api, stack.auth, db)
        val base = runtime.baseUrl ?: return
        val ws = DefaultHubWsClient(
            config = HubConfig(base, runtime.wsUrlOf(base)),
            accessTokenProvider = { stack.auth.currentAccessToken() },
            scope = scope,
        )
        scheduler = DefaultSyncScheduler(scope, engine!!, ws, cursors)
        scheduler?.start()
        // 断线恢复：未终态命令按同键幂等重发（05 §3.5 恢复流程收尾步）
        scope.launch { runCatching { commands?.retryUnfinished() } }
    }

    fun stop() {
        scheduler?.stop()
        scheduler = null
    }

    /**
     * 清全部本地数据（登出闭环，方案 §6-P4 验收⑤）：七表事务内清空，含 O-01 最小集
     * （命令队列/游标）与审批累积态——设备已在服务端撤销，本地不残留任何账号数据；
     * 重登后从空库 bootstrap。须在 [stop] 之后调用（同步循环已停，无在途投影写入）。
     */
    fun wipeLocalData() {
        val db = db ?: return
        db.hubQueries.transaction {
            db.hubQueries.clearGateways()
            db.hubQueries.clearConversations()
            db.hubQueries.clearConvKeys()
            db.hubQueries.clearMessages()
            db.hubQueries.clearReadCursors()
            db.hubQueries.clearCommands()
            db.hubQueries.clearApprovals()
            db.hubQueries.clearSyncState()
        }
    }

    // ── UI 查询（投影库 Flow；写路径由 projector/commands 承担） ──

    fun observeConversations(): Flow<List<UiConversation>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectConversations().asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiConversation(
                    conversationId = it.conversationId, gatewayId = it.gatewayId,
                    label = it.label, agentId = it.agentId,
                    preview = it.lastMessagePreview, lastSeq = it.lastSeq, lastActiveAt = it.lastActiveAt,
                    unread = it.unreadCount.toInt(), hasActiveRun = it.hasActiveRun == 1L,
                    activeRunId = it.activeRunId, reconciling = it.reconciling == 1L,
                )
            }
        }
    }

    fun observeMessages(conversationId: String): Flow<List<UiMessage>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectMessages(conversationId).asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiMessage(
                    messageId = it.messageId, seq = it.seq, role = it.role, content = it.content,
                    state = it.state, uiState = it.uiState, ts = it.ts, errorKind = it.reason,
                )
            }
        }
    }

    // ── wp-12：历史翻页（REST #8 `?before=<seq>` 向上翻页 + upsert 合并） ──────────

    /** 单页大小（契约 08 §8：默认 50 / 上限 200）。 */
    private val messagesPageSize = 50

    /** 测试注入点（desktopTest 专用）：覆写 messages REST 获取通道；生产路径恒 null，走 runtime.authStack。 */
    internal var messagesApiOverride: HubApi? = null

    /**
     * 向上翻页加载更早历史消息（wp-12）：REST #8 `?before=<seq>&limit=50` → 返回行按 messageId
     * upsert 合并进投影库 hubMessages（主键天然去重；与事件流同库同键，wp-1 合并器的 delta 只
     * 追加既有行、created 同键覆盖收敛，互不冲突——翻页只增行、不推进 cursor、不参与水位过滤，
     * 05 §3.11）。合并完成后 observeMessages Flow 自动推送 seq 升序全序列，UI 无需二次拼装。
     *
     * 返回 null = 通道不可用（未登录/无存储）、REST 未成功或化身更替竞态丢弃（见下）——调用方
     * 呈现可重试降级态，不静默。成功时 [UiMessagesPage.hasMore] 为服务端权威（翻到头即 false，
     * 按钮随之隐藏）。
     *
     * 化身围栏（05 §3.10）：取数前后各读一次 lifecycleRevision——翻页行携带取数时刻化身的 seq
     * 口径，取数途中化身更替（session.updated revision 变化，投影清行 + seq 重计）则整页丢弃，
     * 防旧化身行以旧 seq 复活；调用方重试即以新化身重新取数。
     */
    suspend fun loadEarlierMessages(conversationId: String, before: Long, limit: Int = messagesPageSize): UiMessagesPage? {
        val db = db ?: return null
        if (before < 1) return null // 契约 Before minimum: 1（08 §4.3）
        val convBefore = db.hubQueries.selectConversation(conversationId).executeAsOneOrNull() ?: return null
        val page = try {
            messagesApiOverride?.messages(conversationId, before, limit.coerceIn(1, 200)) ?: run {
                val stack = runtime.authStack ?: return null
                stack.auth.withBearer {
                    stack.api.messages(conversationId, before = before, limit = limit.coerceIn(1, 200))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        }
        val convAfter = db.hubQueries.selectConversation(conversationId).executeAsOneOrNull() ?: return null
        if (convAfter.lifecycleRevision != convBefore.lifecycleRevision) return null
        db.hubQueries.transaction {
            page.messages.forEach { m ->
                db.hubQueries.upsertMessage(
                    messageId = m.messageId,
                    conversationId = conversationId,
                    seq = m.seq,
                    role = m.role,
                    content = m.content,
                    state = m.state,
                    reason = m.errorKind, // 与事件路径共用 reason 列（wp-4 六值闭集，双路径共列）
                    uiState = m.uiState,
                    commandId = m.commandId,
                    idempotencyKey = m.idempotencyKey,
                    ts = m.timestamp,
                )
            }
        }
        return UiMessagesPage(
            hasMore = page.hasMore,
            oldestSeq = page.oldestSeq?.takeIf { it > 0 }, // 服务端空页为 0（service.go messagePageResponse）
            newestSeq = page.newestSeq?.takeIf { it > 0 },
            count = page.messages.size,
        )
    }

    /**
     * 网关列表订阅（wp-11 网关与 agent 维度展示数据面）：hubGateways 快照整体覆盖（wp-2 填充）
     * + gateway.online/offline 事件翻转，Flow 即随库变更推送。agentsJson 在此解析为 [UiAgentRef]
     * 结构化行（只存不显的维度在此透出）；解析失败单行降级为空清单，不丢网关本体。
     */
    fun observeGateways(): Flow<List<UiGateway>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectGateways().asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map { g ->
                UiGateway(
                    gatewayId = g.gatewayId,
                    name = g.name,
                    online = g.online == 1L,
                    lastSeenAt = g.lastSeenAt,
                    agents = runCatching {
                        agentsJson.decodeFromString(AGENT_REF_LIST, g.agentsJson)
                    }.getOrDefault(emptyList()).map { UiAgentRef(it.agentId, it.label) },
                )
            }
        }
    }

    /** 会话完整渲染后上报已读（readUpTo = 已渲染最大 seq）。 */
    suspend fun markRead(conversationId: String, readUpTo: Long) {
        commands?.markRead(conversationId, readUpTo)
    }

    /**
     * 待审批列表订阅（wp-9 审批卡数据源）：hubApprovals 中 status=pending 的行，按 updatedAt 升序。
     * approval.resolved 事件把行翻成 approved/rejected 后自动离场——卡片收敛即由本 Flow 驱动，
     * UI 无需维护第二份状态。conversationId 可能为 null（事件路由未学习），见 [UiApproval]。
     */
    fun observePendingApprovals(): Flow<List<UiApproval>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectPendingApprovals().asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiApproval(
                    approvalId = it.approvalId, runId = it.runId, conversationId = it.conversationId,
                    toolName = it.toolName, payloadSummary = it.payloadSummary, updatedAt = it.updatedAt,
                )
            }
        }
    }

    /**
     * 审批决定（wp-9）：decision ∈ approve/reject，走幂等队列（approval.decide，幂等键客户端生成）。
     * 返回 true = 已取得 PERSISTED 回执（决定生效仍以 approval.resolved 事件收敛，本返回值不承诺生效）；
     * 返回 false = 决策通道不可用——确定性拒绝（Hub FAILED，如 B-4 预检未通过 / HUB-BIZ-001）或
     * 网络不可达未提交（PENDING_LOCAL 不自动重放，见 DefaultCommandClient.retryUnfinished 的跳过注释）。
     * UI 必须对 false 显式呈现降级态，不得静默。重复决策 = 新意图新键（同键同载荷纪律不回退）。
     */
    suspend fun decideApproval(approvalId: String, decision: String): Boolean {
        val commands = commands ?: return false
        return commands.decideApproval(approvalId, decision) != null
    }

    // ── v1.1 新端点门面（#16/#17/#18/#19；不走幂等队列，直调） ──────────────

    /**
     * 新开会话（openclaw 原生指令；幂等键 Hub 生成，HubModels.kt CreateConversationRequest）。
     * #16 聚合语义（2026-11-04 拍板）：无主线卡 agent 调用 → 新 session 兜底成卡；已有主线卡
     * agent 调用 → 新 session 静默（后台任务会话，不进列表）。UI 入口只对无主线卡 agent 开放
     * （入口侧筛选，本门面不拦）。回执 PERSISTED 后主动触发一次会话列表校准：新卡出现从
     * R4 周期节拍（分钟级）提速到秒级，且「90s 内或重进列表出现」的手工验收因此必然命中。
     * 校准失败不吞回执——列表在下一 R4 节拍重试。
     */
    suspend fun createConversation(gatewayId: String, agentId: String, label: String?): CommandReceipt {
        val receipt = commands!!.createConversation(gatewayId, agentId, label)
        scope.launch { runCatching { engine?.calibrateConversations() } }
        return receipt
    }

    suspend fun compactConversation(conversationId: String): CommandReceipt =
        commands!!.compactConversation(conversationId)

    suspend fun renameConversation(conversationId: String, label: String): CommandReceipt =
        commands!!.renameConversation(conversationId, label)

    suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.agentStatus(gatewayId, agentId) }
    }

    companion object {
        /** 组装入口：库 + 运行时；存储不可用时 db=null（UI 呈现不可用态，认证面保留）。 */
        fun create(scope: CoroutineScope, credentialStore: CredentialStore = platformCredentialStore()): DefaultHubClient =
            DefaultHubClient(scope, HubRuntime(scope, credentialStore), openHubDatabase(createHubDriver()))

        /** agentsJson 解析器：宽松容错（投影侧写入与快照解析同 Json 配置族），未知字段忽略。 */
        private val agentsJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        private val AGENT_REF_LIST = ListSerializer(AgentRef.serializer())
    }
}

/**
 * wp-13：投影装饰器（[eventTap] 非 null 时才组装）——快照/校准/事件语义逐方法原样透传，
 * 仅在 applyEvent 委托返回后触发 [tap]（事件此时已应用、事务已提交）。engine 侧的幂等去重
 * （同 id 丢弃）与水位裁决发生在 applyEvent 之前，tap 只见真实应用的事件，不会重复触发。
 */
private class TappedEventProjector(
    private val base: EventProjector,
    private val tap: (EventEnvelope) -> Unit,
) : EventProjector {
    override fun applySnapshot(snapshot: SnapshotResponse) = base.applySnapshot(snapshot)
    override fun applyConversations(rows: List<ConversationRow>) = base.applyConversations(rows)
    override fun applyEvent(envelope: EventEnvelope) {
        base.applyEvent(envelope)
        tap(envelope)
    }
}
