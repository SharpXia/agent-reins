package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
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
import dev.sharpxia.agentreins.hub.sync.SyncPhase
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

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
 * UI 工具调用行（wp-15 工具活动捕获）：tool.started/completed/failed 三态按调用关联键串联的
 * 本地捕获行——Hub 无工具投影表、断线窗口不补发（06 §10.2），本行只代表**已捕获**部分，
 * UI 不承诺完整。toolCallKey/runId 均为不透明字符串（ADR-14）。
 */
data class UiToolActivity(
    val gatewayId: String,
    val runId: String,
    val toolCallKey: String,
    /** 工具名；started/终态载荷皆未携带名称（丢失面 + completed 载荷无名称）时为 ''——UI 兜底呈现，不虚构。 */
    val toolName: String,
    /** 有界入参摘要（started 携带；可能被截断）。 */
    val summary: String?,
    /** running/completed/failed。 */
    val status: String,
    val firstTs: String,
    val lastTs: String,
)

/**
 * UI run 阶段行（wp-15 可选项）：run.status（Bridge chat status/aborted 变体映射，06 §3.2
 * additive）捕获的每 run 最新启动阶段。run 终态即清行（无在途阶段语义）。
 */
data class UiRunPhase(
    val gatewayId: String,
    val runId: String,
    /** Gateway 启动阶段枚举原样（waiting_for_state…starting_model；aborted 变体记 aborted）。 */
    val phase: String,
    /** 有界附加说明（retry 重试 / aborted errorMessage/stopReason）；缺省 null。 */
    val detail: String?,
    val ts: String,
)

/** UI 工作过程快照（wp-15）：会话页「工作过程」折叠披露条的单一数据面（一次订阅两表合流）。 */
data class UiWorkActivity(
    val tools: List<UiToolActivity>,
    val phases: List<UiRunPhase>,
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

    /**
     * wp-4 修法二：run 发起登记表（start 时创建，stop 后重登重建 = 登出态自然清零；
     * wipeLocalData 显式 reset）。两端通知协调器（desktop coordinator 门 / iOS 桥）经此消费
     * run 终态「本端发起」判定，缺省 null（未 start）时调用方按允许放行（事件不流动则门不触发）。
     */
    @kotlin.concurrent.Volatile
    var runOriginRegistry: RunOriginRegistry? = null
        private set

    private var scheduler: DefaultSyncScheduler? = null

    /**
     * wp-10 连接相位观察面（additive，纯观察不参与控制）：稳定 StateFlow 实例——scheduler
     * 每次 start/stop 重建（DefaultSyncScheduler 自持流会换实例），组装时把本流作 phaseSink
     * 注入 [DefaultSyncScheduler]，UI collect 永不换源。语义见 [SyncPhase]：Linked = 已连接
     * 且已追平（两端连接提示条唯一消失相位）；未 start（登录前）为 Catching 占位；stop()
     * 复位 Catching（登出过渡期不留旧相位）。
     */
    private val phaseState = MutableStateFlow(SyncPhase.Catching)
    val syncPhase: StateFlow<SyncPhase> get() = phaseState

    /** 登录态就绪后组装同步栈并启动（幂等：重复调用忽略；登出后 stop 再 login 需重新 start）。 */
    fun start() {
        val stack = runtime.authStack ?: return
        if (scheduler != null || db == null) return
        val registry = RunOriginRegistry()
        runOriginRegistry = registry
        val baseProjection = DefaultEventProjector(db)
        // wp-4：eventTap 组合面——登记喂帧（run.started → RunOriginRegistry，恒开）在用户
        // eventTap（通知协调器）之前；两者都只见已应用且过幂等/水位裁决的事件。
        val userTap = eventTap
        val tap: ((EventEnvelope) -> Unit) = { envelope ->
            feedRunOrigin(registry, envelope)
            userTap?.invoke(envelope)
        }
        projection = TappedEventProjector(baseProjection, tap)
        val cursors = DbEventCursorStore(db)
        engine = DefaultSyncEngine(stack.api, stack.auth, projection!!, cursors)
        commands = DefaultCommandClient(stack.api, stack.auth, db, registry)
        val base = runtime.baseUrl ?: return
        val ws = DefaultHubWsClient(
            config = HubConfig(base, runtime.wsUrlOf(base)),
            accessTokenProvider = { stack.auth.currentAccessToken() },
            scope = scope,
        )
        scheduler = DefaultSyncScheduler(scope, engine!!, ws, cursors, phaseSink = phaseState)
        scheduler?.start()
        // 断线恢复：未终态命令按同键幂等重发（05 §3.5 恢复流程收尾步）
        scope.launch { runCatching { commands?.retryUnfinished() } }
    }

    /**
     * wp-4 登记喂帧：run.started → [RunOriginRegistry.noteRunStarted]。
     *
     * 准入键收紧（wp-4 回归修复 2026-11-06）：conversationId 经 hubConvKeys 解析，**只认
     * twoHop==0 的自学习行**（自发送回执链/自命令 ack——即本设备自己发过消息的键）。两跳连结行
     * （同 agent 的 TG/channel/任务键，joinStagedSessionAgents 连到同一张主线卡）与未映射键的
     * 解析结果**都不喂登记面**——SEND 登记是会话（conversationId）级，若两跳行也解析出主线卡，
     * 主线卡的 SEND 标记会把 channel 会话的 run 误判本端发起，他端/TG 触发的 run 终态在本端
     * 弹通知（回归验收「他端/TG 触发的 run 终态静默」即破）；runId 信封缺省时以 data.runId
     * 兜底（与投影取序一致）。本函数只在 tap 协程线程跑，毫秒级单查询。
     * internal 供 desktopTest 混合形态用例直调（生产唯一调用点 = [start] 的 tap 组合面）。
     */
    internal fun feedRunOrigin(registry: RunOriginRegistry, envelope: EventEnvelope) {
        if (envelope.type != EventEnvelope.RUN_STARTED) return
        val db = db ?: return
        val runId = envelope.runId
            ?: runCatching { envelope.data.jsonObject["runId"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() } }.getOrNull()
            ?: return
        val key = envelope.sessionKey
            ?.let { sk -> db.hubQueries.selectConversationByKey(envelope.gatewayId, sk).executeAsOneOrNull() }
            ?: return // 未映射：解析不出归属，登记面无从判源（终态即抑制）
        if (key.twoHop != 0L) return // 两跳连结行（channel/任务键）不喂登记面——会话级 SEND 标记不得跨键外溢
        registry.noteRunStarted(envelope.gatewayId, runId, key.conversationId)
    }

    fun stop() {
        scheduler?.stop()
        scheduler = null
        phaseState.value = SyncPhase.Catching // wp-10：同步栈停转后复位，不留旧相位
    }

    /**
     * 清全部本地数据（登出闭环，方案 §6-P4 验收⑤）：十一表事务内清空，含 O-01 最小集
     * （命令队列/游标）、审批/工具活动累积态（wp-15 v6 两表）与两跳归属暂存（v7 wp-4）；
     * 同时重置 run 发起登记表（wp-4 修法二，内存态不跨登出复用）。设备已在服务端撤销，
     * 本地不残留任何账号数据；重登后从空库 bootstrap。须在 [stop] 之后调用（同步循环已停，
     * 无在途投影写入）。
     */
    fun wipeLocalData() {
        val db = db ?: return
        db.hubQueries.transaction {
            db.hubQueries.clearGateways()
            db.hubQueries.clearConversations()
            db.hubQueries.clearConvKeys()
            db.hubQueries.clearSessionAgents()
            db.hubQueries.clearMessages()
            db.hubQueries.clearReadCursors()
            db.hubQueries.clearCommands()
            db.hubQueries.clearApprovals()
            db.hubQueries.clearToolEvents()
            db.hubQueries.clearRunStatus()
            db.hubQueries.clearSyncState()
        }
        runOriginRegistry?.reset()
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

    /**
     * 会话工作过程订阅（wp-15 工具活动捕获数据面）：hubToolEvents（tool.* 三态）与
     * hubRunStatus（run.status 阶段帧，可选项）两表合流为单一快照——Swift 侧一次订阅即得
     * 「工作过程」披露条全部数据。两表都是本地累积捕获态：快照重建/断线重连不清，
     * 会话删除/化身更替/run 终态才清——重连后披露条保留已捕获部分（wp-15 验收项）；
     * 断线窗口内的事件天然缺失（06 §10.2 已知丢失面，UI 不承诺完整）。
     */
    fun observeWorkActivity(conversationId: String): Flow<UiWorkActivity> {
        val db = db ?: return flowOf(UiWorkActivity(emptyList(), emptyList()))
        val tools = db.hubQueries.selectToolEventsByConversation(conversationId).asFlow()
            .mapToList(scope.coroutineContext)
            .map { rows ->
                rows.map {
                    UiToolActivity(
                        gatewayId = it.gatewayId, runId = it.runId, toolCallKey = it.toolCallKey,
                        toolName = it.toolName, summary = it.summary, status = it.status,
                        firstTs = it.firstTs, lastTs = it.lastTs,
                    )
                }
            }
        val phases = db.hubQueries.selectRunStatusByConversation(conversationId).asFlow()
            .mapToList(scope.coroutineContext)
            .map { rows ->
                rows.map {
                    UiRunPhase(
                        gatewayId = it.gatewayId, runId = it.runId, phase = it.phase,
                        detail = it.detail, ts = it.ts,
                    )
                }
            }
        return kotlinx.coroutines.flow.combine(tools, phases) { t, p -> UiWorkActivity(t, p) }
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
     * v1.1 additive（#16 主线锚定 2026-11-06）：本门面固定携带 mainSession=true（下游
     * DefaultCommandClient 落地）——服务端组装 sessionKey=agent:<agentId>:main（客户端永不拼键，
     * ADR-14），Gateway sessions.create 同名 key adopt 不 fork；iOS/desktop 新建入口经本门面
     * 自动获得，签名不变。
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

    /**
     * 设备 APNs token 上报（wp-17 推送面，REST #20 POST /v1/devices/apns-token，v1.1 additive）：
     * 204 无响应体、幂等覆盖写——不经幂等队列（与命令类端点不同，无 CommandReceipt 回执可追，
     * 单发即达；上报失败由调用方静默降级）。token 空串 = 清除/退订；客户端仅在 APNs 明确要求时
     * 使用，登出不发退订——登出即撤销当前设备，撤销设备拒绝上报（403 HUB-AUTH-004），服务端
     * 推送目标按 devices.status=active 筛选，撤销即时停止推送，客户端退订是多余的。
     */
    suspend fun reportApnsToken(token: String) {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.registerApnsToken(ApnsTokenRequest(token)) }
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
 * wp-13/wp-4：投影装饰器——快照/校准/事件语义逐方法原样透传，仅在 applyEvent 委托返回后触发
 * [tap]（事件此时已应用、事务已提交；wp-4 起 tap 恒组装，内含登记喂帧 + 可选用户 eventTap）。
 * engine 侧的幂等去重（同 id 丢弃）与水位裁决发生在 applyEvent 之前，tap 只见真实应用的事件，
 * 不会重复触发。
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
