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
import dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.EventProjector
import dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse
import dev.sharpxia.agentreins.hub.projection.MediaUpload
import dev.sharpxia.agentreins.hub.projection.SessionsResponse
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hub.projection.decodeAttachments
import dev.sharpxia.agentreins.hub.projection.encodeAttachments
import dev.sharpxia.agentreins.hub.db.createHubDriver
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hub.sync.DbEventCursorStore
import dev.sharpxia.agentreins.hub.sync.DefaultSyncEngine
import dev.sharpxia.agentreins.hub.sync.DefaultSyncScheduler
import dev.sharpxia.agentreins.hub.sync.SyncPhase
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * UI 消息附件行（v1.2.0 多模态 v1，设计稿 §6）：hubMessages.attachments 列的解码投影——
 * source=hub 行 [mediaId] 必带（经端点 #24 Bearer 拉取渲染，客户端用自定义 loader 带请求头，
 * 原生 AsyncImage 无法携带 Authorization 头）；source=external 行 [url] 必带（agent 外发
 * URL 原样，http 降级链接 chip）。桥面 DTO 纪律：字段均为 String/Long/Int/Boolean/可空 String。
 */
data class UiAttachment(
    /** hub / external（契约 MessageAttachment.source 闭集；未知值原样透出由 UI 兜底）。 */
    val source: String,
    val mediaId: String?,
    val url: String?,
    val mimeType: String?,
    val fileName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
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
    /**
     * 媒体附件（v1.2.0 additive，多模态 v1）：事件/快照/历史三路径与本地乐观行四路共列
     * （hubMessages.attachments，JSON 文本）；坏 JSON 降级空清单不炸行（decodeAttachments）。
     * 无附件行为空清单。
     */
    val attachments: List<UiAttachment> = emptyList(),
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

/**
 * UI 上传结果（v1.2.0 多模态 v1，端点 #23 MediaUploadResponse 投影，iOS 桥 [AgentBridge]
 * hubUploadMedia 回调面）：mediaId 是后续 message.send attachments 引用的唯一句柄——草稿
 * 阶段按本值挂附件 chip，发送时随 [SendMessageRequest.attachments.mediaId] 提交。
 * mimeType 为服务端魔数嗅探值（非请求声明值）；width/height 为请求回显，未提供为 null。
 */
data class UiMediaUpload(
    val mediaId: String,
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
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
     * refreshGateways 防抖窗（v1.3.0，设计稿 §3.2）：gateway.agents_changed 边沿可爆发
     * （新设备接入批量 roster、投影重放），2s 窗内多边沿合并为一次回拉（尾沿防抖——窗内
     * 新边沿重置计时，以最后一枚边沿为准启动回拉）。服务端 catchUp 重放阶段已调暗回调
     * （§3.1 处置），客户端 2s 防抖是本设计的第二重保险。
     */
    private val gatewayRefreshDebounceMs = 2_000L

    /** 在途防抖窗 Job（refreshGateways 重入即重置；stop 登出随同步栈一并取消）。 */
    private var refreshGatewaysJob: Job? = null

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
        // v1.3.0（多网关轮设计稿 §3.2）：gateway.agents_changed 边沿 → 出投影层回调 →
        // 2s 防抖回拉 GET /v1/gateways 行级 upsert（新 agent 免等快照/R4 校准即上卡）。
        val baseProjection = DefaultEventProjector(db, onGatewayAgentsChanged = { refreshGateways() })
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
        // 网关目录对账回拉（2026-11-15 死卡修复轮）：restore 路径不触发快照（快照才整体
        // 清灌 hubGateways）——登录/重连组装点拉一次 REST #6 对账（防抖通道，含差集删除），
        // 撤销网关卡免等 agents_changed 边沿即收敛。失败静默（runCatching 内，事件/快照兜底）。
        refreshGateways()
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
        refreshGatewaysJob?.cancel() // v1.3.0：登出随同步栈一并取消在途防抖回拉（防 wipe 后复活行）
        refreshGatewaysJob = null
        scheduler?.stop()
        scheduler = null
        phaseState.value = SyncPhase.Catching // wp-10：同步栈停转后复位，不留旧相位
    }

    /**
     * 后台/锁屏暂停同步栈（2026-11-07 UX 轮崩溃防护，iOS scenePhase 驱动）：停连接循环并
     * 收尾 WSS（scheduler 保留可 [resumeNetwork] 原地重启）。动机：进程挂起竞态下，底层
     * URLSession 错误回调（didComplete）可能在 Ktor Darwin 会话半终结窗口送达，其同步构造
     * 的异常无人消费 → Kotlin/Native abort（0.5.4 真机锁屏解锁 SIGABRT 九连实证）。锁屏前
     * 主动优雅断开，错误回调要么不发生（已收尾）要么落在已终结 job 上被静默丢弃。
     */
    fun suspendNetwork() {
        scheduler?.stop()
        phaseState.value = SyncPhase.Catching
    }

    /** 回前台恢复同步栈（与 [suspendNetwork] 成对；未 start 过/已 stop 登出态为幂等空转）。 */
    fun resumeNetwork() {
        scheduler?.start()
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

    /**
     * 本地删除单条消息（2026-11-07 UX 轮）：脏数据清理出口——仅删投影库本地行，不发任何
     * 服务端命令；observeMessages Flow 即推删除后列表。语义边界：服务端权威数据仍会经
     * 快照重建/校准回流（重建后行可能复活），本操作是本地清理不是服务端撤回。
     */
    fun deleteLocalMessage(conversationId: String, messageId: String) {
        db?.hubQueries?.deleteLocalMessage(conversationId, messageId)
    }

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
        // 占位乐观行开屏修复（2026-11-15 排序混乱修复轮，查询注见 Hub.sq）：幂等对账——
        // 占位行正文并入同 commandId 空权威行 + 已对位占位行清除。旧形态（回显缺失/skip）
        // 的残留行在开屏即自愈；修后 selectMessages Flow 推升序全序列。
        db.hubQueries.transaction {
            db.hubQueries.mergeStuckOptimisticContent(conversationId)
            db.hubQueries.deleteStuckOptimisticBound(conversationId)
            db.hubQueries.deleteWireUserDuplicates(conversationId)
        }
        return db.hubQueries.selectMessages(conversationId).asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiMessage(
                    messageId = it.messageId, seq = it.seq, role = it.role, content = it.content,
                    state = it.state, uiState = it.uiState, ts = it.ts, errorKind = it.reason,
                    // v9：attachments 列解码为结构化行（坏 JSON 容缺空清单，不炸行）
                    attachments = decodeAttachments(it.attachments).map { a ->
                        UiAttachment(
                            source = a.source, mediaId = a.mediaId, url = a.url, mimeType = a.mimeType,
                            fileName = a.fileName, sizeBytes = a.sizeBytes, width = a.width, height = a.height,
                        )
                    },
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
     * 测试注入点（desktopTest 专用）：覆写 v1.4.0 会话管理直调通道（deleteConversation/listAgentSessions
     * 门面的 REST 面）——fake 自管鉴权（不经 TokenManager.withBearer）；生产路径恒 null，
     * 走 runtime.authStack。仅供 GatewaySurfaceTest 门面用例（204 后行删/响应透出）注入。
     */
    internal var sessionAdminApiOverride: HubApi? = null

    /**
     * 向上翻页加载更早历史消息（wp-12）：REST #8 `?before=<seq>&limit=50` → 返回行按 messageId
     * upsert 合并进投影库 hubMessages（主键天然去重；与事件流同库同键，wp-1 合并器的 delta 只
     * 追加既有行、created 同键覆盖收敛，互不冲突——翻页只增行、不推进 cursor、不参与水位过滤，
     * 05 §3.11）。合并完成后 observeMessages Flow 自动推送 seq 升序全序列，UI 无需二次拼装。
     *
     * 返回 null = 通道不可用（未登录/无存储）、本地无游标可依（库内无任何已载行且 [before] 为
     * null）、REST 未成功或化身更替竞态丢弃（见下）——调用方呈现可重试降级态，不静默。成功时
     * [UiMessagesPage.hasMore] 为服务端权威（翻到头即 false，按钮随之隐藏）。
     *
     * 游标口径（2026-11-10 加载更早修复轮，真机 0.5.7「加载更早拉不出来」根因）：一律以
     * **本地最老已载行 seq 原始口径**（[selectOldestLoadedSeq]，MIN(seq)，含被 selectMessages
     * 显示过滤的心跳/合成行）为基准——服务端翻页按原始 seq keyset，显示过滤只作用 UI 面。
     * 此前两端 UI 层用「显示首行 seq」当游标下沿：整页被过滤的心跳带落库后显示下沿不动，
     * 游标被判「倒退」重置回落显示首行 → 同带反复重取，永远翻不进更早历史（mini_x 主线卡
     * seq 225..264 整带 48 行全为「无回复」心跳，模拟器 6 连触发同带重取实证）。
     * - [before] = null（会话期首翻）：直接取 MIN(seq) 当 before；
     * - [before] < MIN(seq)（会话期快照重建 HUB-SYNC-001/002：行清空只回灌尾页，旧游标成
     *   stale 低于新下沿）：同样回落 MIN(seq)，从已载下沿续翻，防以旧游标取数跳过重建后
     *   尾页首行之下的缺口带（wp-12 快照重建防护语义自 UI 层下沉到数据源头）；
     * - 其余（[before] >= MIN(seq)）：按调用方游标取数（UI 层每页以服务端 oldestSeq 推进，
     *   恒等于新下沿）。
     *
     * 化身围栏（05 §3.10）：取数前后各读一次 lifecycleRevision——翻页行携带取数时刻化身的 seq
     * 口径，取数途中化身更替（session.updated revision 变化，投影清行 + seq 重计）则整页丢弃，
     * 防旧化身行以旧 seq 复活；调用方重试即以新化身重新取数。
     */
    suspend fun loadEarlierMessages(conversationId: String, before: Long?, limit: Int = messagesPageSize): UiMessagesPage? {
        val db = db ?: return null
        val convBefore = db.hubQueries.selectConversation(conversationId).executeAsOneOrNull() ?: return null
        val oldestLoadedSeq = db.hubQueries.selectOldestLoadedSeq(conversationId).executeAsOneOrNull()?.oldestSeq
        val effectiveBefore = when {
            // 库内零行（2026-11-15 空时间线回灌修复）：非「真空会话」判定——事件旁路映射未
            // 建立时消息事件被 resolveConversation 永久 skip（游标照常推进，不重投），本地空
            // 但服务端有行（真机 hermes 实测：卡片 lastSeq=3、时间线空、「加载更早」点不出）。
            // 以会话 lastSeq+1 取「截至最新」尾页（契约 before≥1 内），回灌链才可达；
            // 真空会话（lastSeq=0）→ before=1 → 服务端空页 → Swift exhausted 收敛（原
            // return null 会走 autoEarlierBlocked 错误横幅，语义更差）。
            oldestLoadedSeq == null -> convBefore.lastSeq + 1L
            before == null || before < oldestLoadedSeq -> oldestLoadedSeq
            else -> before
        }
        if (effectiveBefore < 1) return null // 契约 Before minimum: 1（08 §4.3；兜底链后 effectiveBefore 恒非空）
        val page = try {
            messagesApiOverride?.messages(conversationId, effectiveBefore, limit.coerceIn(1, 200)) ?: run {
                val stack = runtime.authStack ?: return null
                stack.auth.withBearer {
                    stack.api.messages(conversationId, before = effectiveBefore, limit = limit.coerceIn(1, 200))
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
                    attachments = encodeAttachments(m.attachments.orEmpty()), // v9：历史行附件透传落列
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

    // ── v1.3.0：gateway.agents_changed 防抖回拉（多网关轮设计稿 §3.1/§3.2） ──────────

    /**
     * 网关目录回拉调度（2s 防抖，尾沿合并）：gateway.agents_changed 只带 {gatewayId} 不带
     * roster（06 §3.5）——agent 清单权威 = REST #6 GET /v1/gateways（GatewaySummary.agents
     * 与快照同源同形，双份转换漂移禁令），本方法把边沿爆发合并为一次回拉后落库
     * [pullGatewaysIntoProjection]。也供 UI 直调做手动刷新（同一防抖通道，不会并发双拉）。
     *
     * 失败静默降级（runCatching 吞掉、不阻断调用方协程）：事件是加速器不是权威（06 §3.5
     * 边界语义），下一枚边沿 / R4 校准 / 快照自愈。未登录 / 无存储为幂等空转；登出
     * [stop] 取消在途窗。
     */
    fun refreshGateways() {
        if (db == null) return
        refreshGatewaysJob?.cancel()
        refreshGatewaysJob = scope.launch {
            delay(gatewayRefreshDebounceMs)
            runCatching { pullGatewaysIntoProjection() }
        }
    }

    /**
     * 回拉执行体（[refreshGateways] 唯一落库路径）：withBearer（TokenManager 负责刷新重试）
     * 拉 GET /v1/gateways → hubGateways **行级 upsert + 差集对账删除**（INSERT OR REPLACE
     * 逐行，不 clear 表——与 applySnapshot 的「先清后灌」整体覆盖铁律区分：本路径是目录
     * 对账不是重建，既有行逐行被响应权威值覆盖，无行则插入；2026-11-15 死卡修复轮补
     * 差集删除——撤销网关行此前永远残留，真机「网关与 Agent」页死卡实证）。agentsJson
     * 编码沿用 [observeGateways] 同族 Json 配置的解析降级链（坏载荷降级空数组，网关行本体不丢）。
     */
    private suspend fun pullGatewaysIntoProjection() {
        val db = db ?: return
        val stack = runtime.authStack ?: return
        val page = stack.auth.withBearer { stack.api.gateways() }
        db.hubQueries.transaction {
            page.gateways.forEach { g ->
                db.hubQueries.insertGateway(
                    gatewayId = g.gatewayId,
                    name = g.name,
                    online = if (g.online) 1L else 0L,
                    lastSeenAt = g.lastSeenAt,
                    agentsJson = encodeAgentsJson(g.agents),
                    // v10：provider 落列（未知省略行落 ''——INSERT OR REPLACE 全列覆盖，缺省
                    // 不传会把既有 provider 打回 ''，评审 L5）
                    provider = g.provider ?: "",
                )
            }
            // 差集对账：响应清单外的本地行 = 撤销/异主残留，清掉（服务端已过滤 revoked）
            val received = page.gateways.map { it.gatewayId }.toSet()
            val stale = db.hubQueries.selectGateways().executeAsList()
                .map { it.gatewayId }
                .filter { it !in received }
            if (stale.isNotEmpty()) db.hubQueries.deleteGatewaysByIds(stale)
        }
    }

    /** agentsJson 落库编码（降级链：编码失败回退 '[]'，网关行本体不丢——与读取面降级对称）。 */
    private fun encodeAgentsJson(agents: List<AgentRef>): String =
        runCatching { agentsJson.encodeToString(AGENT_REF_LIST, agents) }.getOrDefault("[]")

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

    // ── v1.1 新端点门面（#16/#17/#18/#19；create 自 v1.4.0 入队，其余直调） ──

    /**
     * 会话删除（#29，v1.4.0 additive 多主会话轮；**纯 REST 直调不走幂等队列**——Hub 主权
     * tombstone 为 204 幂等终态，与 deleteGateway 同款直调先例，无 CommandReceipt 可追）：
     * withBearer 直调 REST（Token 刷新/重试由 TokenManager 承担）；204 后本地投影行即删
     * （hubConversations/hubConversations_byActive 收敛，乐观 UI——事件面 session.removed
     * 级联清映射/两跳暂存已在 DefaultEventProjector.onSessionRemoved，本门面不重复）。
     * 幂等终态：对已删除会话再次 DELETE 亦 204；「不存在/无权」统一 404 HUB-BIZ-002 防枚举。
     * 失败按 HubException 冒泡呈现，不静默；未登录抛 IllegalStateException。
     */
    suspend fun deleteConversation(conversationId: String) {
        val overrideApi = sessionAdminApiOverride
        if (overrideApi != null) {
            overrideApi.deleteConversation(conversationId) // desktopTest 注入通道：fake 自管鉴权
        } else {
            val stack = runtime.authStack ?: throw IllegalStateException("未登录")
            stack.auth.withBearer { stack.api.deleteConversation(conversationId) }
        }
        db?.hubQueries?.deleteConversation(conversationId)
    }

    /**
     * 按 Agent 列会话（#28，v1.4.0 additive 多主会话轮；**纯读直调不走幂等队列**——管理面
     * 清单查询，无 CommandReceipt）：管理面视图不收敛、不筛族（dashboard/主线族含
     * main-<idempotencyKey> 多主线/task/channel/subagent/cron 全键族可见），isMainSession =
     * 主线族谓词（消费面 rename 门据此判定）。返回 [SessionsResponse] 原样透出；失败按
     * HubException 冒泡呈现，不静默；未登录抛 IllegalStateException。
     */
    suspend fun listAgentSessions(gatewayId: String, agentId: String): SessionsResponse {
        val overrideApi = sessionAdminApiOverride
        if (overrideApi != null) return overrideApi.listAgentSessions(gatewayId, agentId) // desktopTest 注入通道
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.listAgentSessions(gatewayId, agentId) }
    }

    /**
     * 媒体上传门面（端点 #23，v1.2.0 additive 多模态 v1；**纯上传不走幂等队列**——上传是
     * REST 一次性资源创建，无 CommandReceipt 可追；失败由调用方提示重选/重传，上传成功后
     * mediaId 随 message.send 的 SendMessageRequest.attachments 提交，消息本体仍走幂等队列）。
     * [bytes] 为图片字节（服务端以魔数判定类型白名单与 ≤20MB 帽，[mime] 仅作 part 声明）；
     * [fileName]/[width]/[height] 为可选展示元数据（服务端对象键由 ULID+嗅探扩展名构成，
     * fileName 永不进存储路径）。返回 [MediaUpload]（mediaId + 服务端嗅探 mime/size 回显）；
     * 未登录抛 IllegalStateException，网络/契约错误按 HubException 冒泡给调用方呈现。
     */
    suspend fun uploadMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String? = null,
        width: Int? = null,
        height: Int? = null,
    ): MediaUpload {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.uploadMedia(conversationId, bytes, mime, fileName, width, height) }
    }

    /**
     * 媒体内容拉取门面（端点 #24，v1.2.0 additive 多模态 v1；**纯读不走幂等队列**——与
     * uploadMedia 同式直调）。客户端渲染面 Bearer 分支（设计稿 §2.4）：Token 刷新/重试由
     * TokenManager.withBearer 承担；调用方（桥 → Swift 媒体加载器）对失败显式呈失败态 +
     * 可重试，不静默。字节仅在渲染窗口内存持有，不落盘缓存（与 artifacts「不缓存不持久化
     * 下载字节」语义同源——客户端 NSCache 只存解码后 UIImage，进程级自然回收）。
     */
    suspend fun fetchMediaContent(mediaId: String): ByteArray {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.fetchMediaContent(mediaId) }
    }

    /**
     * 新开会话（#16，openclaw 原生指令）。v1.4.0（多主会话轮 2026-11-16）起经幂等队列：
     * 幂等键客户端生成、payloadJson 存路由信封（DefaultCommandClient.ConversationCreateEnvelope），
     * 网络不可达入队 PENDING_LOCAL 待 retryUnfinished 自动重放（同键同载荷幂等）；确定性拒绝
     * 队列条目 FAILED。
     * [newMainSession]（缺省 false）= mainSession=true **默认主线**（旧入口语义）：服务端组装
     * sessionKey=agent:<agentId>:main（客户端永不拼键，ADR-14），Gateway sessions.create 同名
     * key adopt 不 fork；true = 新开一条主线：服务端以请求幂等键确定性派生
     * agent:<agentId>:main-<idempotencyKey>——重试复用同键 = 同键形 = 同会话（客户端不拼键，
     * 多主线键形由服务端文法保证）。恒不双真（服务端互斥 400 HUB-BIZ-004）。
     * UI 入口只对无主线卡 agent 开放（入口侧筛选，本门面不拦）。回执 PERSISTED 后主动触发
     * 一次会话列表校准：新卡出现从 R4 周期节拍（分钟级）提速到秒级，且「90s 内或重进列表
     * 出现」的手工验收因此必然命中。校准失败不吞回执——列表在下一 R4 节拍重试。
     * 返回 receipt（非空）；未送达（网络不可达 PENDING_LOCAL）抛 IllegalStateException——
     * 调用方（iOS 桥/desktop 面板）runCatching 呈现「提交未送达」降级态，队列稍后自动重放。
     */
    suspend fun createConversation(
        gatewayId: String,
        agentId: String,
        label: String?,
        newMainSession: Boolean = false,
    ): CommandReceipt {
        val receipt = commands!!.createConversation(gatewayId, agentId, label, newMainSession)
            ?: throw IllegalStateException("提交未送达（可能为网络不可达），已入本地队列待重试")
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

    // ── v1.3.0 网关供给面管理门面（#25/#26/#27，多网关轮；纯 REST 直调不走幂等队列） ──

    /**
     * 注册网关（#25，v1.3.0 additive 多网关轮）：返回一次性明文 bridgeToken。**不持久化纪律
     * （契约 #25 description / 设计稿 §2.1 客户端义务）**：本门面不落投影库/凭据仓/偏好，
     * 明文仅经返回值交付调用方——管理面（网关管理 sheet）持有仅会话内存，复制给 Bridge
     * 设备 env（BRIDGE_GATEWAY_ID / BRIDGE_TOKEN）用完即弃，sheet 关闭即丢失（服务端只存
     * SHA-256 摘要、无法再次查看）。名称不判重、无幂等键：重复提交产生多个网关各持独立
     * Token，重试安全由调用方按钮防重（提交中禁用）保证。失败按 HubException 冒泡呈现，
     * 不静默；未登录抛 IllegalStateException（与 uploadMedia 同式）。
     */
    suspend fun createGateway(name: String): GatewayCreatedResponse {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.createGateway(CreateGatewayRequest(name)) }
    }

    /**
     * 轮换网关 bridgeToken（#26，v1.3.0 additive）：旧 Token 进 24h 宽限（宽限期内新旧双活）；
     * 新明文一次性下发的持久化纪律同 [createGateway]（不落盘，仅返回值交付）。并发轮换
     * last-write-wins（以最后一次签发为准）；目标网关已撤销（status='revoked'）时 401
     * HUB-AUTH-005 语义拒绝；「未知/非属主」404 HUB-BIZ-002 防枚举。
     */
    suspend fun rotateBridgeToken(gatewayId: String): BridgeTokenIssuedResponse {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.rotateBridgeToken(gatewayId) }
    }

    /**
     * 撤销网关（#27，v1.3.0 additive，204 终态且对属主幂等）：撤销后服务端读面按
     * status='active' 过滤——该网关自此从 GET /v1/gateways 消失；本门面成功后触发一次
     * [refreshGateways] 回拉对账。回拉收口（2026-11-15 死卡修复轮）：pullGatewaysIntoProjection
     * 已带响应清单差集对账删除——被撤销网关的本地投影行在本次回拉即收敛消失（不再留存至
     * 快照整体覆盖）。失败按 HubException 冒泡呈现，不静默。
     */
    suspend fun deleteGateway(gatewayId: String) {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        stack.auth.withBearer { stack.api.deleteGateway(gatewayId) }
        refreshGateways()
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
