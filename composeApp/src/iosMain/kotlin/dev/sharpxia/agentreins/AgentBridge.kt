package dev.sharpxia.agentreins

import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.HubAuthState
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.HubRuntime
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiGateway
import dev.sharpxia.agentreins.hub.UiMessage
import dev.sharpxia.agentreins.hub.UiMessagesPage
import dev.sharpxia.agentreins.hub.UiWorkActivity
import dev.sharpxia.agentreins.hub.auth.DeviceInfo
import dev.sharpxia.agentreins.hub.db.createHubDriver
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject

/**
 * SwiftUI 壳 ↔ shared/hub 桥（Hub 化 P4 定稿：网关直连面已整体退役，本桥只剩 Hub 面）。
 *
 * 职责：账号态订阅、登录/恢复/登出、投影库 Flow → Swift 回调、命令透传、设备管理。
 * 所有回调在主线程派发（scope = Dispatchers.Main）。
 *
 * DTO 纪律（跨 Kotlin/Swift 边界）：[UiConversation]/[UiMessage]/[DeviceInfo] 是 shared 的
 * 只读模型，字段均为 String/Long/Int/Boolean/可空 String，Swift 直接消费，不另建中间 DTO。
 */
class AgentBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val hubRuntime = HubRuntime(scope)
    private var hubClient: DefaultHubClient? = null
    private var hubConvJob: Job? = null
    private var hubMsgJob: Job? = null
    private var hubApprovalJob: Job? = null
    private var hubGatewayJob: Job? = null
    private var hubWorkActivityJob: Job? = null
    private var hubPhaseJob: Job? = null

    /** wp-13：run 终态通知回调（Swift 注册；见 [hubWatchRunTerminal]）。 */
    private var runTerminalSink: ((conversationId: String, runId: String, status: String) -> Unit)? = null

    /** wp-13：审批请求通知回调（Swift 注册；见 [hubWatchApprovalRequested]）。 */
    private var approvalRequestedSink: ((conversationId: String) -> Unit)? = null

    /** 投影库句柄进程内唯一（登出清表后复用同一连接，重登从空库 bootstrap）。 */
    private val hubDb: HubDatabase? by lazy { openHubDatabase(createHubDriver()) }

    // ---------- 账号态 ----------

    /**
     * Hub 账号态订阅（主线程回调）：kind = idle/working/needsLogin/loggedIn；
     * message = NeedsLogin.reason；user = LoggedIn.userDisplay；deviceId = LoggedIn.deviceId。
     * 进入 loggedIn 时自动组装并启动 Hub 同步栈（幂等）。
     */
    fun hubObserve(onState: (kind: String, message: String?, user: String?, deviceId: String?) -> Unit) {
        scope.launch {
            // 投影/同步栈异常不得带崩宿主 App（0.5.1 教训：collect 异常经 uncaught handler SIGABRT）
            runCatching {
                hubRuntime.state.collect { st ->
                    when (st) {
                        HubAuthState.Idle -> onState("idle", null, null, null)
                        HubAuthState.Working -> onState("working", null, null, null)
                        is HubAuthState.NeedsLogin -> onState("needsLogin", st.reason, null, null)
                        is HubAuthState.LoggedIn -> {
                            ensureHubStarted()
                            onState("loggedIn", null, st.userDisplay, st.deviceId)
                        }
                    }
                }
            }
        }
    }

    private fun ensureHubStarted() {
        if (hubClient == null) {
            // wp-13：eventTap = 通知触发面（run 终态区分 completed/failed，投影 Flow 面无法还原事件类型）。
            val client = DefaultHubClient(scope, hubRuntime, hubDb, eventTap = ::onBridgeEvent)
            hubClient = client
            client.start()
        }
    }

    /** 启动恢复：Keychain 有 refreshToken 则静默刷新进 loggedIn，否则 needsLogin。 */
    fun hubRestore(baseUrl: String) = hubRuntime.restore(baseUrl)

    fun hubLogin(baseUrl: String, username: String, password: String, deviceName: String) =
        hubRuntime.login(baseUrl, username, password, deviceName)

    /**
     * 登出闭环（方案 §6-P4 验收⑤）= 撤销当前设备（服务端 Token 即时失效）+ 清 Keychain 凭据
     * + 停同步栈 + 清全部本地投影数据（七表）。重登后从空库 bootstrap。
     */
    fun hubLogout() {
        hubConvJob?.cancel(); hubConvJob = null
        hubMsgJob?.cancel(); hubMsgJob = null
        hubApprovalJob?.cancel(); hubApprovalJob = null
        hubGatewayJob?.cancel(); hubGatewayJob = null
        hubWorkActivityJob?.cancel(); hubWorkActivityJob = null
        hubPhaseJob?.cancel(); hubPhaseJob = null
        val client = hubClient
        hubClient = null
        client?.stop()
        client?.wipeLocalData()
        hubRuntime.logout()
    }

    // ---------- 投影库数据面（Flow → Swift 回调） ----------

    /** 会话列表订阅（投影库变更即回调；重复调用先取消旧订阅）。 */
    fun hubWatchConversations(onList: (List<UiConversation>) -> Unit) {
        hubConvJob?.cancel()
        hubConvJob = scope.launch {
            runCatching { hubClient?.observeConversations()?.collect { onList(it) } }
        }
    }

    /** 指定会话消息订阅（打开会话页调用；重复调用切订阅）。 */
    fun hubWatchMessages(conversationId: String, onList: (List<UiMessage>) -> Unit) {
        hubMsgJob?.cancel()
        hubMsgJob = scope.launch {
            runCatching { hubClient?.observeMessages(conversationId)?.collect { onList(it) } }
        }
    }

    /** 关闭会话页时取消消息订阅（避免后台继续推已离开会话的列表）。 */
    fun hubStopWatchingMessages() {
        hubMsgJob?.cancel(); hubMsgJob = null
        // wp-15：工作过程订阅随会话页同生命周期（开/关成对；离开会话页不再推送其工具活动）
        hubWorkActivityJob?.cancel(); hubWorkActivityJob = null
    }

    /**
     * 会话工作过程订阅（wp-15：「工作过程」折叠披露条数据面；打开会话页调用，随
     * [hubStopWatchingMessages] 成对取消）。快照 = hubToolEvents（tool.* 三态捕获行）+
     * hubRunStatus（run.status 阶段帧）合流，任一表变更即推送。断线窗口丢失为已知面
     * （06 §10.2），重连后保留已捕获部分。
     */
    fun hubWatchWorkActivity(conversationId: String, onSnapshot: (UiWorkActivity) -> Unit) {
        hubWorkActivityJob?.cancel()
        hubWorkActivityJob = scope.launch {
            runCatching { hubClient?.observeWorkActivity(conversationId)?.collect { onSnapshot(it) } }
        }
    }

    /**
     * 待审批列表订阅（wp-9 审批卡 + 设置页计数入口共用一份 Flow；重复调用先取消旧订阅）。
     * 仅 status=pending 行：approval.resolved 事件翻终态后行自动离场，卡片状态收敛即由本
     * Flow 驱动。登录态建立（hubClient 就绪）后订阅才有数据——未登录时收集空流即结束。
     */
    fun hubWatchApprovals(onList: (List<UiApproval>) -> Unit) {
        hubApprovalJob?.cancel()
        hubApprovalJob = scope.launch {
            runCatching { hubClient?.observePendingApprovals()?.collect { onList(it) } }
        }
    }

    /**
     * 网关列表订阅（wp-11 网关与 agent 维度展示 + #16 新建会话入口数据面；重复调用先取消旧订阅）。
     * 数据源 = 投影库 hubGateways Flow：快照整体覆盖（wp-2 填充，agentsJson 含 AgentRef 结构化
     * 清单）+ gateway.online/offline 事件翻转。Flow 是纯投影库面（不依赖登录态），未登录时为空
     * 清单、bootstrap 后推送——与 [hubWatchConversations] 同模式，无完成后不再推送之虞。
     */
    fun hubWatchGateways(onList: (List<UiGateway>) -> Unit) {
        hubGatewayJob?.cancel()
        hubGatewayJob = scope.launch {
            runCatching { hubClient?.observeGateways()?.collect { onList(it) } }
        }
    }

    /**
     * 连接相位订阅（wp-10 两端连接提示条数据面；Swift 登录态建立时机调用，重复调用换订阅）：
     * onPhase 为 phase.name 字符串 ∈ catching/linked/reconnecting/rebuilding/superseded
     * （SyncScheduler.phase，[SyncPhase] 纯观察面——不改变同步语义与恢复流程）。linked =
     * 已连接且已追平，是提示条唯一消失相位。数据源 = hubClient.syncPhase 稳定流（HubClient
     * 组装时注入 phaseSink，UI 订阅不随 scheduler start/stop 换源）；hubClient 未就绪时本订阅
     * 空转（与 [hubWatchApprovals] 同语义），重挂时机由登录态回调承担。
     */
    fun hubWatchPhase(onPhase: (String) -> Unit) {
        hubPhaseJob?.cancel()
        hubPhaseJob = scope.launch {
            // TF beta-0.5.4 真机缺陷修复：name 是 PascalCase 枚举名（"Catching"），Swift 侧按
            // 小写契约比较（注释上行）——恒不匹配导致提示条永不消失、文字恒空、恒琥珀底。
            runCatching { hubClient?.syncPhase?.collect { onPhase(it.name.lowercase()) } }
        }
    }

    // ---------- wp-13：通知面（iOS 本地通知触发面） ----------

    /**
     * run 终态事件回调（wp-13 通知面；Swift 启动时注册一次，重复调用覆盖旧回调）：
     * conversationId = 桥侧经 hubConvKeys 映射解析（与投影 resolveConversation 同一通道①；
     * 未映射会话为空串，调用方不得编造归属）、runId = 信封 runId、status ∈ completed/failed。
     * 回调在主线程（同步引擎运行于本桥 scope = Dispatchers.Main）。回复通知不走本通道：
     * assistant final 以会话列表 Flow 的 unread 增量为源（「非当前打开」是 Swift UI 态，
     * 静音判定与点按跳转也都需要 conversationId）；审批走 [hubWatchApprovalRequested]。
     */
    fun hubWatchRunTerminal(onEvent: (conversationId: String, runId: String, status: String) -> Unit) {
        runTerminalSink = onEvent
    }

    /**
     * 审批请求事件回调（wp-13 通知面；Swift 启动时注册一次，重复调用覆盖旧回调）：
     * approval.requested 到达即回调，conversationId = hubConvKeys 解析（未映射 = 空串，调用方
     * 降级为无归属提醒）。走事件旁路而非审批 Flow 差异的原因：Flow 首推打底后，登录 bootstrap
     * 快照带回的积压 pending 行会被误判为「新审批」（快照不经过 applyEvent，事件旁路天然不带
     * bootstrap 假阳性；断线补投的事件经 cursor/幂等去重后仍会触发——对客户端确属新事件）。
     * 回调在主线程（同 [hubWatchRunTerminal]）。
     */
    fun hubWatchApprovalRequested(onEvent: (conversationId: String) -> Unit) {
        approvalRequestedSink = onEvent
    }

    /**
     * DefaultHubClient eventTap（wp-13）：投影应用后按信封类型分派通知触发面。取 run 终态
     * completed/failed——run.aborted 是用户主动 Stop 的自致结果，不提醒——与
     * approval.requested（回复通知仍走投影库 Flow 面：unread 增量与「非当前打开」判定都在
     * Swift 会话列表态）。conversationId 经 hubConvKeys 直查（信封 gatewayId + sessionKey，
     * run/approval 域必带 sessionKey；wp-4 起两跳连结行也在此解析出——通知面放开两跳归属）；
     * 未映射 = 空串透传，由 Swift 侧显式降级。
     * wp-4 修法二发起方门（两端协调器同口径 = RunOriginRegistry.isRunLocal 消费）：run 终态仅
     * 「本端发起」（message.send 登记）提醒；他端发起 / compact 触发静默（未读增量仍由会话列表
     * 承载）。registry 缺省（未 start）时事件不流动，此处不判空放行为不可达回退。
     */
    private fun onBridgeEvent(envelope: EventEnvelope) {
        val sk = envelope.sessionKey ?: return
        val conversationId = hubDb?.hubQueries
            ?.selectConversationByKey(envelope.gatewayId, sk)
            ?.executeAsOneOrNull()?.conversationId.orEmpty()
        when (envelope.type) {
            EventEnvelope.RUN_COMPLETED, EventEnvelope.RUN_FAILED -> {
                val status = if (envelope.type == EventEnvelope.RUN_COMPLETED) "completed" else "failed"
                val runId = envelope.runId
                    ?: envelope.data.jsonObject["runId"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() }
                    ?: return
                val registry = hubClient?.runOriginRegistry
                if (registry != null && !registry.isRunLocal(envelope.gatewayId, runId)) return
                runTerminalSink?.invoke(conversationId, runId, status)
            }
            EventEnvelope.APPROVAL_REQUESTED -> approvalRequestedSink?.invoke(conversationId)
            else -> Unit // 其余事件不走通知面（回复 = 会话列表 Flow 的 unread 增量）
        }
    }

    // ---------- 命令（幂等队列） ----------

    /** 发送消息（幂等队列 + 乐观行；进度经 command.acked/message.created 事件翻转）。 */
    fun hubSend(conversationId: String, text: String) {
        scope.launch { runCatching { hubClient?.commands?.sendMessage(conversationId, text) } }
    }

    /**
     * 取消当前活跃 run（Stop 按钮 → DefaultCommandClient.cancelRun，幂等队列 + REST #11 run.cancel）。
     * 调用方须先从会话实时行取 activeRunId（wp-5 v4 投影）；缺失时不得调用——映射未学习场景
     * （DefaultEventProjector.setRun 在 resolveConversation 失败时不落库）降级为 UI 提示态，
     * 不假设 runId 恒有值。中止后的输出收敛由 Gateway 权威裁决（run.aborted 事件回流投影库）。
     */
    fun hubCancelRun(runId: String) {
        scope.launch { runCatching { hubClient?.commands?.cancelRun(runId) } }
    }

    /** 会话渲染完整后上报已读（readUpTo = 已渲染最大 seq）。 */
    fun hubMarkRead(conversationId: String, readUpTo: Long) {
        scope.launch { runCatching { hubClient?.markRead(conversationId, readUpTo) } }
    }

    /**
     * 历史翻页（wp-12）：向上翻取更早历史消息（REST #8 `?before=<seq>` 向上翻页），行按
     * messageId upsert 合并进投影库——observeMessages Flow 即推送合并后 seq 升序全序列，
     * 调用方无需二次拼装。onDone page null = 请求未成功（未登录/网络不可达/化身更替竞态丢弃，
     * 见 DefaultHubClient.loadEarlierMessages）——调用方呈现可重试降级态，不静默；非 null 时
     * hasMore 为服务端权威（「加载更早」按钮可见性唯一依据，翻到头即 false）。
     */
    fun hubLoadEarlier(conversationId: String, before: Long, onDone: (UiMessagesPage?) -> Unit) {
        scope.launch {
            onDone(runCatching { hubClient?.loadEarlierMessages(conversationId, before) }.getOrNull())
        }
    }

    /**
     * 审批决定（wp-9）：decision ∈ approve/reject，经幂等队列 approval.decide 提交。
     * onDone true = 已取得 PERSISTED 回执（生效以 approval.resolved 事件收敛）；false = 决策通道
     * 不可用（Hub FAILED——如 B-4 预检未通过，或网络不可达未提交；approval.decide 不自动重放，
     * 见 DefaultCommandClient.retryUnfinished）——调用方须显式呈现「决策通道不可用」降级态，不静默。
     */
    fun hubDecideApproval(approvalId: String, decision: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { hubClient?.decideApproval(approvalId, decision) == true }
                .getOrDefault(false)
            onDone(ok)
        }
    }

    // ---------- v1.1 新端点（#16–#19） ----------

    /**
     * 新开会话（openclaw 原生指令；wp-11 #16 入口唯一写路径）。#16 聚合语义（2026-11-04 拍板）：
     * 无主线卡 agent 调用 → 新 session 兜底成卡（入口侧已保证只对无主线卡 agent 开放）；已有
     * 主线卡 agent 调用 → 新 session 静默（后台任务会话）。结果经回调带回 receipt（null=失败，
     * 调用方须显式呈现，不静默）；成功后 DefaultHubClient 内部即触发一次会话列表校准，新卡
     * 秒级入列（兜底成卡的 R4 路径不走周期节拍等待）。
     */
    fun hubCreateConversation(gatewayId: String, agentId: String, label: String?, onDone: (String?) -> Unit) {
        scope.launch {
            val r = runCatching { hubClient?.createConversation(gatewayId, agentId, label) }
            onDone(r.getOrNull()?.commandId)
        }
    }

    /** 会话重命名；成功回调 true。 */
    fun hubRenameConversation(conversationId: String, label: String, onDone: (Boolean) -> Unit) {
        scope.launch { onDone(runCatching { hubClient?.renameConversation(conversationId, label) }.isSuccess) }
    }

    /** 压缩上下文；成功回调 true。 */
    fun hubCompactConversation(conversationId: String, onDone: (Boolean) -> Unit) {
        scope.launch { onDone(runCatching { hubClient?.compactConversation(conversationId) }.isSuccess) }
    }

    /**
     * wp-17：APNs 设备 token 上报（REST #20，204 幂等覆盖写；hex 串，实际 128 字符）。尽力而为
     * 的一次性上报，不经回调——失败（未登录/网络不可达/撤销设备 403 HUB-AUTH-004）静默吞掉，
     * 不得影响登录与同步主路径；APNs 换发 token 时系统会再次注册，届时重报收敛。
     */
    fun hubReportApnsToken(token: String) {
        scope.launch { runCatching { hubClient?.reportApnsToken(token) } }
    }

    // ---------- 设备管理 ----------

    /**
     * 设备清单（设备管理页）。wp-11 设备管理错误态：onDone devices 非 null = 成功；null =
     * 拉取失败（未登录 / 网络不可达 / Hub FAILED），error = 人读失败原因（契约错误为
     * code+message）——调用方须显式呈现「错误 + 重试」降级态，不静默。此前失败不回调，
     * 清单页永「加载中…」（与同页审批卡显式降级纪律不一致，本版收敛）。
     */
    fun hubDevices(onDone: (devices: List<DeviceInfo>?, error: String?) -> Unit) {
        scope.launch {
            val r = runCatching {
                val stack = hubRuntime.authStack ?: throw HubException.notLoggedIn()
                stack.auth.withBearer { stack.api.listDevices() }
            }
            val page = r.getOrNull()
            if (page != null) {
                onDone(page.devices, null)
            } else {
                onDone(null, describeError(r.exceptionOrNull()))
            }
        }
    }

    /**
     * 撤销设备（可撤任意自有设备含当前；撤当前即远端登出，本地随后清凭据重登录）。
     * wp-11 设备管理错误态：onDone ok true = DELETE 已成功（生效以服务端为准）；false =
     * 撤销未送达（未登录 / 网络不可达 / Hub FAILED）——调用方须显式呈现失败提示 + 可重试，
     * 不静默。此前 onDone 恒调、失败被 runCatching 吞掉。
     */
    fun hubRevokeDevice(deviceId: String, onDone: (ok: Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching {
                val stack = hubRuntime.authStack ?: throw HubException.notLoggedIn()
                stack.auth.withBearer { stack.api.deleteDevice(deviceId) }
            }.isSuccess
            onDone(ok)
        }
    }

    /** 失败原因人读化（契约错误 code+message，其余取 message；与 desktop 同式）。 */
    private fun describeError(e: Throwable?): String =
        (e as? HubException)?.let { "${it.payload.code} ${it.payload.message}" }
            ?: (e?.message ?: "未知错误")
}
