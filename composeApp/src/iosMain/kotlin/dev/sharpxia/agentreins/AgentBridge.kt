package dev.sharpxia.agentreins

import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.hubRootScopeGuard
import dev.sharpxia.agentreins.hub.HubAuthState
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.HubRuntime
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiGateway
import dev.sharpxia.agentreins.hub.UiMediaUpload
import dev.sharpxia.agentreins.hub.UiMessage
import dev.sharpxia.agentreins.hub.UiMessagesPage
import dev.sharpxia.agentreins.hub.UiWorkActivity
import dev.sharpxia.agentreins.hub.auth.DeviceInfo
import dev.sharpxia.agentreins.hub.db.createHubDriver
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.AttachmentRef
import dev.sharpxia.agentreins.hub.projection.CommandsList
import dev.sharpxia.agentreins.hub.projection.ModelsList
import dev.sharpxia.agentreins.hub.projection.SessionsResponse
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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

    // +hubRootScopeGuard（挂机崩溃修复轮）：最后防线——漏网协程异常降级为记录，防 abort
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + hubRootScopeGuard)

    private val hubRuntime = HubRuntime(scope)
    private var hubClient: DefaultHubClient? = null
    private var hubConvJob: Job? = null
    private var hubMsgJob: Job? = null
    private var hubApprovalJob: Job? = null
    private var hubGatewayJob: Job? = null
    private var hubWorkActivityJob: Job? = null
    private var hubPhaseJob: Job? = null

    /**
     * wp-13：run 终态通知回调（Swift 注册；见 [hubWatchRunTerminal]）。2026-11-15 推送
     * 降噪轮扩形：reason = run.failed 载荷 error 字段（失败原因，供通知 body 携带）；
     * localOrigin = 是否本端发起（Swift 据此分派：失败通知只认本端，回声行去重锚全量记录）。
     */
    private var runTerminalSink: ((conversationId: String, runId: String, status: String, reason: String, localOrigin: Boolean) -> Unit)? = null

    /** wp-13：审批请求通知回调（Swift 注册；见 [hubWatchApprovalRequested]）。 */
    private var approvalRequestedSink: ((conversationId: String) -> Unit)? = null

    /**
     * v1.3 通知摘要轮：assistant final 权威行回调（Swift 注册；见 [hubWatchReplyMessage]）——
     * 携带消息正文与附件数，供 Swift 侧生成通知摘要（剥 markdown/纯附件「[图片]×N」）。
     * 2026-11-15 推送降噪轮扩形：runId = 信封 runId（失败回声行去重锚；用户行回显等无
     * runId 形态为空串）。
     */
    private var replyMessageSink: ((conversationId: String, runId: String, content: String, attachmentCount: Int) -> Unit)? = null

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
     * 未映射会话为空串，调用方不得编造归属）、runId = 信封 runId、status ∈ completed/failed、
     * reason = run.failed 载荷 error 字段（缺省空串）、localOrigin = 本端发起判定（RunOriginRegistry）。
     * 2026-11-15 推送降噪轮（服务端 push/apns.go 同口径）：**失败 run 全量过本通道**——
     * Swift 侧要为所有失败 run 记回声行去重锚（服务端降噪镜像），本地通知仍只认 localOrigin；
     * 完成态维持只认本端（桥侧过滤，避免 heartbeat 周期 run 淹没回调）。回复通知（v1.3 起）
     * 走本通道 MESSAGE_CREATED 分支；审批走 [hubWatchApprovalRequested]。回调在主线程。
     */
    fun hubWatchRunTerminal(
        onEvent: (conversationId: String, runId: String, status: String, reason: String, localOrigin: Boolean) -> Unit,
    ) {
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
     * assistant final 权威行回调（v1.3 iOS 通知摘要轮；Swift 启动时注册一次，重复调用覆盖旧回调）：
     * message.created 事件（role=assistant）到达即回调 (conversationId, content, attachmentCount)。
     * 动机：回复通知的正文摘要需要消息正文与附件数（剥 markdown / 纯附件「[图片]×N」）——
     * 会话卡片 preview 只是裸 content，纯附件行剥不出附件计数；事件旁路是唯一可得面。
     * conversationId = hubConvKeys 解析（未映射 = 空串，Swift 侧按归属可得性显式降级，不编造）；
     * 载荷形状漂移（缺 role/content）按空值降级，不抛不崩。回调在主线程（同 [hubWatchRunTerminal]）。
     */
    fun hubWatchReplyMessage(
        onEvent: (conversationId: String, runId: String, content: String, attachmentCount: Int) -> Unit,
    ) {
        replyMessageSink = onEvent
    }

    /**
     * DefaultHubClient eventTap（wp-13）：投影应用后按信封类型分派通知触发面。取 run 终态
     * completed/failed——run.aborted 是用户主动 Stop 的自致结果，不提醒——与
     * approval.requested 与 message.created（v1.3 起：回复通知触发面自会话列表 unread 差异
     * 迁移到事件旁路，摘要需要正文与附件数）。conversationId 经 hubConvKeys 直查（信封 gatewayId + sessionKey，
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
                val localOrigin = registry != null && registry.isRunLocal(envelope.gatewayId, runId)
                // 2026-11-15 推送降噪轮：失败 run 全量放行（Swift 记回声行去重锚——服务端
                // push/apns.go 降噪镜像；heartbeat 等自驱 run 的失败回声行在端上同样不该
                // 以「新回复」二推）；完成态维持只认本端（通知面语义不变，heartbeat 周期
                // run 不淹没回调）。失败原因随载荷透传（data.error，缺省空串）。
                if (!localOrigin && status != "failed") return
                val reason = if (status == "failed") {
                    (envelope.data.jsonObject["error"] as? JsonPrimitive)?.contentOrNull ?: ""
                } else ""
                runTerminalSink?.invoke(conversationId, runId, status, reason, localOrigin)
            }
            EventEnvelope.APPROVAL_REQUESTED -> approvalRequestedSink?.invoke(conversationId)
            EventEnvelope.MESSAGE_CREATED -> {
                // v1.3 通知摘要轮：assistant final 权威行（role=assistant）→ 摘要数据面
                //（content + 附件数；用户行是本端消息回显，不提醒）。role 收紧为 assistant
                //（2026-11-12 空行风暴修复轮，服务端 pushAlert apns.go 同口径）：缺 role/
                // 其他 role fail-closed 不提醒——服务端对 d.Role != "assistant" 一律不推，
                // 客户端触发面必须镜像，否则桥上行的非 assistant 形态绕过闭值过滤。
                // 载荷解析宽松降级（缺键/形状漂移 = 空值，与 DefaultEventProjector
                //「宽松解析、缺键降级」同纪律）。runId 透传（2026-11-15 降噪轮：回声行去重锚）。
                val obj = runCatching { envelope.data.jsonObject }.getOrNull() ?: return
                val role = (obj["role"] as? JsonPrimitive)?.contentOrNull
                if (role != "assistant") return
                val content = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: ""
                val attachmentCount = (obj["attachments"] as? JsonArray)?.size ?: 0
                val runId = envelope.runId
                    ?: envelope.data.jsonObject["runId"]?.toString()?.trim('"')?.takeIf { it.isNotEmpty() }
                    ?: ""
                replyMessageSink?.invoke(conversationId, runId, content, attachmentCount)
            }
            else -> Unit // 其余事件不走通知面（回复通知见 MESSAGE_CREATED 分支）
        }
    }

    // ---------- 命令（幂等队列） ----------

    /** 发送消息（幂等队列 + 乐观行；进度经 command.acked/message.created 事件翻转）。 */
    fun hubSend(conversationId: String, text: String) {
        scope.launch { runCatching { hubClient?.commands?.sendMessage(conversationId, text) } }
    }

    /**
     * 发送带附件消息（v1.2.0 多模态 v1，设计稿 §6；幂等队列同 [hubSend]——附件引用进
     * SendMessageRequest.attachments，命令载荷只带 mediaId+元数据（无 URL、无字节，附件
     * 不经 WS 帧传输）。[attachments] = 端点 #23 上传返回的引用集合（≤4 项，mediaId 必带）。
     * 乐观行随行落 attachments 列供本地即时渲染；断线重试同键重发同一 mediaId 集合
     * （ADR-07 同键同载荷）。
     */
    fun hubSendWithAttachments(
        conversationId: String,
        text: String,
        attachments: List<AttachmentRef>,
    ) {
        scope.launch {
            runCatching { hubClient?.commands?.sendMessage(conversationId, text, attachments) }
        }
    }

    /**
     * 媒体上传（端点 #23，v1.2.0 additive；**纯上传直调不走幂等队列**，与 hubCommands/
     * hubDevices 同式：authStack + withBearer 直调 REST）。[bytes] 图片字节（服务端魔数判定
     * jpeg/png/webp/gif 白名单、≤20MB 帽；[mime] 仅作 part Content-Type 声明，服务端不信任）；
     * [fileName]/[width]/[height] 可选展示元数据（fileName 永不进服务端存储路径，防穿越）。
     * onDone 非 null = 上传成功（mediaId 随 hubSendWithAttachments 提交；草稿挂 chip 用）；
     * null = 通道不可用（未登录/网络不可达/4xx 5xx——HUB-BIZ-004 类型/大小拒绝等），error =
     * 人读失败原因，调用方须显式呈现失败 + 可重试，不静默。
     */
    fun hubUploadMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String?,
        width: Int?,
        height: Int?,
        onDone: (upload: UiMediaUpload?, error: String?) -> Unit,
    ) {
        scope.launch {
            val r = runCatching { hubClient?.uploadMedia(conversationId, bytes, mime, fileName, width, height) }
            val upload = r.getOrNull()
            if (upload != null) {
                onDone(
                    UiMediaUpload(
                        mediaId = upload.mediaId, mimeType = upload.mimeType, sizeBytes = upload.sizeBytes,
                        width = upload.width, height = upload.height,
                    ),
                    null,
                )
            } else {
                onDone(null, describeError(r.exceptionOrNull()))
            }
        }
    }

    /**
     * 媒体内容拉取（端点 #24 Bearer 分支，v1.2.0 additive；与 [hubUploadMedia] 同式直调，
     * **纯读不走幂等队列**）。Swift 消息行媒体加载器的唯一取数通道——凭据纪律（HubStore.swift
     * 头注释）：Token 只在 KMP TokenManager 内、Swift 层不持有，故 loader 不自行拼 URL 带
     * Bearer 头，而经本桥取字节。onDone bytes 非 null = 成功（原始字节，不落盘持久化）；
     * null = 通道不可用（未登录/网络不可达/404 HUB-BIZ-002/401 刷新失败等），error = 人读
     * 失败原因，调用方显式呈失败态 + 可重试，不静默。
     */
    fun hubFetchMedia(mediaId: String, onDone: (bytes: ByteArray?, error: String?) -> Unit) {
        scope.launch {
            val r = runCatching { hubClient?.fetchMediaContent(mediaId) }
            val bytes = r.getOrNull()
            if (bytes != null) {
                onDone(bytes, null)
            } else {
                onDone(null, describeError(r.exceptionOrNull()))
            }
        }
    }

    /**
     * 动态斜杠命令目录查询（端点 #21，v1.1 additive 2026-11-07；**纯读直调，不走幂等队列**——
     * 命令目录是查询面无副作用，写路径纪律不适用）。读机制与 [hubDevices] 同式（authStack +
     * withBearer 直调 REST）；onResult 非 null = 成功（可能为空目录，truncated 等其余字段按需
     * 消费）；null = 通道不可用（未登录/网络不可达/Hub FAILED）——调用方回落静态六命令目录、
     * 下次触发重试，尽力而为增强面（不弹错误 UI，与 hubReportApnsToken 同纪律）。
     */
    fun hubCommands(conversationId: String, onResult: (CommandsList?) -> Unit) {
        scope.launch {
            onResult(runCatching {
                val stack = hubRuntime.authStack ?: throw HubException.notLoggedIn()
                stack.auth.withBearer { stack.api.commands(conversationId) }
            }.getOrNull())
        }
    }

    /**
     * 会话模型目录查询（端点 #22，v1.1 additive 2026-11-08；**纯读直调，不走幂等队列**——
     * 模型目录是查询面无副作用，写路径纪律不适用）。读机制与 [hubCommands] 同式（authStack +
     * withBearer 直调 REST）；onResult 非 null = 成功（可能为空目录）；null = 通道不可用
     * （未登录/网络不可达/Hub FAILED）——调用方不渲染交互卡、下次触发重试，尽力而为增强面
     * （不弹错误 UI，与 hubCommands 同纪律）。
     */
    fun hubModels(conversationId: String, onResult: (ModelsList?) -> Unit) {
        scope.launch {
            onResult(runCatching {
                val stack = hubRuntime.authStack ?: throw HubException.notLoggedIn()
                stack.auth.withBearer { stack.api.models(conversationId) }
            }.getOrNull())
        }
    }

    /**
     * 后台/锁屏暂停同步栈（2026-11-07 UX 轮崩溃防护；iOS scenePhase 离开 active 时调用）：
     * 停连接循环并优雅收尾 WSS——挂起竞态下 URLSession 错误回调落在半终结会话上会 abort
     * （0.5.4 真机锁屏解锁 SIGABRT）。未登录/未启动为幂等空转。
     */
    fun hubSuspendSync() {
        scope.launch { runCatching { hubClient?.suspendNetwork() } }
    }

    /** 回前台恢复同步栈（与 [hubSuspendSync] 成对；幂等）。 */
    fun hubResumeSync() {
        scope.launch { runCatching { hubClient?.resumeNetwork() } }
    }

    /**
     * 本地删除单条消息（2026-11-07 UX 轮）：仅清投影库行（脏数据清理出口），无服务端命令；
     * observeMessages Flow 即推删除后列表。服务端权威数据经快照/校准可能回流（语义=本地清理）。
     */
    fun hubDeleteLocalMessage(conversationId: String, messageId: String) {
        scope.launch { runCatching { hubClient?.deleteLocalMessage(conversationId, messageId) } }
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
     * 调用方无需二次拼装。[before] 传 nil = 会话期首翻（或页未带 oldestSeq）：由
     * DefaultHubClient.loadEarlierMessages 按 DB 最老已载行 seq 原始口径（含被显示过滤的行）
     * 解析游标；传值低于本地下沿（会话期快照重建 stale 游标）同样回落（2026-11-10 加载更早
     * 修复轮：游标口径自 UI 层下沉数据源头，防整页被过滤的心跳带触发游标重置死循环）。
     * onDone page null = 请求未成功（未登录/网络不可达/本地无游标可依/化身更替竞态丢弃，
     * 见 DefaultHubClient.loadEarlierMessages）——调用方呈现可重试降级态，不静默；非 null 时
     * hasMore 为服务端权威（「加载更早」按钮可见性唯一依据，翻到头即 false）。
     */
    fun hubLoadEarlier(conversationId: String, before: Long?, onDone: (UiMessagesPage?) -> Unit) {
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
     * 新开会话（openclaw 原生指令；wp-11 #16 入口唯一写路径）。v1.4.0 多主会话轮（2026-11-16）
     * 起双旗标透传：[newMainSession] false = mainSession=true **默认主线**（服务端组装
     * sessionKey=agent:<agentId>:main，Gateway sessions.create 同名 key adopt 不 fork）；true =
     * 新开一条主线：服务端以请求幂等键确定性派生 agent:<agentId>:main-<idempotencyKey>——重试
     * 同键 = 同键形 = 同会话（客户端永不拼键，ADR-14）。分流判据在 Swift 侧（HubStore 按
     * hasMainCard 分流，评审 H2 通道）：无主线卡 agent 传 false（新 session 兜底成卡，2026-11-04
     * #16 聚合语义）；已有主线卡 agent 传 true（新主线独立成卡，恒不双真——服务端互斥 400）。
     * 结果经回调带回 receipt（null=失败，调用方须显式呈现，不静默）；成功后 DefaultHubClient
     * 内部即触发一次会话列表校准，新卡秒级入列（兜底成卡的 R4 路径不走周期节拍等待）。
     */
    fun hubCreateConversation(
        gatewayId: String,
        agentId: String,
        label: String?,
        newMainSession: Boolean,
        onDone: (String?) -> Unit,
    ) {
        scope.launch {
            val r = runCatching { hubClient?.createConversation(gatewayId, agentId, label, newMainSession) }
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

    // ---------- v1.4.0 会话管理面（#28/#29 多主会话轮；直调 REST 不走幂等队列） ----------

    /**
     * 按 Agent 列会话清单（#28，v1.4.0 additive 多主会话轮；**纯读直调不走幂等队列**——管理面
     * 清单查询，无 CommandReceipt 可追）。onDone page 非 null = 成功：[SessionsResponse] 原样
     * 透出（管理面视图不收敛、不筛族——dashboard/主线族（含 main-<idempotencyKey> 多主线）/
     * task/channel/subagent/cron 全键族可见；SessionSummary.isMainSession = 主线族谓词，消费面
     * rename 门据此判定，评审 M7）；null = 未成功（未登录/网络不可达/Hub FAILED），error = 人读
     * 失败原因，调用方显式呈现 + 可重试，不静默。
     */
    fun hubListAgentSessions(
        gatewayId: String,
        agentId: String,
        onDone: (page: SessionsResponse?, error: String?) -> Unit,
    ) {
        scope.launch {
            val r = runCatching {
                hubClient?.listAgentSessions(gatewayId, agentId) ?: throw HubException.notLoggedIn()
            }
            val page = r.getOrNull()
            if (page != null) {
                onDone(page, null)
            } else {
                onDone(null, describeError(r.exceptionOrNull()))
            }
        }
    }

    /**
     * 会话删除（#29，v1.4.0 additive 多主会话轮；**纯 REST 直调不走幂等队列**——Hub 主权
     * tombstone 为 204 幂等终态，与 [hubDeleteGateway] 同款直调先例，无 CommandReceipt 可追）。
     * 204 后本地投影行即删（HubClient.deleteConversation 内部落库，observeConversations Flow
     * 即推；session.removed 事件级联清映射/两跳暂存已在 DefaultEventProjector.onSessionRemoved
     * ——Swift 侧乐观移除与本路径双路径幂等）。onDone ok true = 删除已成功（对已删除会话再次
     * DELETE 亦 204）；false = 未成功（未登录/网络不可达），error = 人读失败原因，调用方显式
     * 呈现 + 可重试，不静默。
     */
    fun hubDeleteConversation(conversationId: String, onDone: (ok: Boolean, error: String?) -> Unit) {
        scope.launch {
            val r = runCatching {
                val c = hubClient ?: throw HubException.notLoggedIn()
                c.deleteConversation(conversationId)
            }
            if (r.isSuccess) onDone(true, null) else onDone(false, describeError(r.exceptionOrNull()))
        }
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

    // ---------- 网关供给面管理（#25/#26/#27 复植轮 2026-11-16；直调 REST 不走幂等队列） ----------

    /**
     * 注册网关（#25，v1.3.0 additive 多网关轮；与 [hubDevices] 同式：authStack + withBearer
     * 直调 HubClient 门面）。onDone gatewayId/bridgeToken 非 null = 成功：bridgeToken 为一次性
     * 明文（服务端只存 SHA-256 摘要、无法再次查看），调用方仅存会话内存、复制给 Bridge 设备
     * env 用完即弃（**不持久化纪律**，契约 #25 description / HubClient.createGateway 注释）。
     * null = 未成功（未登录/网络不可达/Hub FAILED），error = 人读失败原因，调用方显式呈现 +
     * 可重试，不静默。#25 名称不判重、无幂等键——重试安全由调用方按钮防重（提交中禁用）保证。
     * 成功后触发一次 refreshGateways 回拉，让新网关行尽快入列（Bridge 未拨出前为离线空目录）。
     */
    fun hubCreateGateway(
        name: String,
        onDone: (gatewayId: String?, bridgeToken: String?, error: String?) -> Unit,
    ) {
        scope.launch {
            val r = runCatching {
                hubClient?.createGateway(name) ?: throw HubException.notLoggedIn()
            }
            val created = r.getOrNull()
            if (created != null) {
                hubClient?.refreshGateways()
                onDone(created.gatewayId, created.bridgeToken, null)
            } else {
                onDone(null, null, describeError(r.exceptionOrNull()))
            }
        }
    }

    /**
     * 轮换网关 bridgeToken（#26，v1.3.0 additive；直调同 [hubCreateGateway]）。onDone
     * bridgeToken 非 null = 成功：新明文一次性下发（旧 Token 进 24h 宽限，宽限期内新旧双活），
     * 不持久化纪律同 [hubCreateGateway]。null = 未成功（未登录/网络不可达/目标网关已撤销 401
     * HUB-AUTH-005 语义拒绝/未知或非属主 404 HUB-BIZ-002 防枚举），error = 人读失败原因。
     */
    fun hubRotateBridgeToken(gatewayId: String, onDone: (bridgeToken: String?, error: String?) -> Unit) {
        scope.launch {
            val r = runCatching {
                hubClient?.rotateBridgeToken(gatewayId) ?: throw HubException.notLoggedIn()
            }
            val issued = r.getOrNull()
            if (issued != null) {
                onDone(issued.bridgeToken, null)
            } else {
                onDone(null, describeError(r.exceptionOrNull()))
            }
        }
    }

    /**
     * 撤销网关（#27，v1.3.0 additive，204 终态且对属主幂等；直调同 [hubDevices] 式）。onDone
     * error null = 撤销已成功：服务端读面按 status='active' 过滤（该网关自此从目录消失）、
     * 连接即时收口、历史保留只读、命令不再下发。本地投影行收敛已下沉 HubClient 门面——
     * deleteGateway 成功即触发 refreshGateways 回拉，pullGatewaysIntoProjection 差集对账
     * 即时删残留行（复植轮 Swift 呈现面不再自持撤销隐藏集，2026-11-15 死卡修复轮）。非 null =
     * 未成功（未登录/网络不可达/未知或非属主 404 HUB-BIZ-002），error = 人读失败原因，调用方
     * 显式呈现 + 可重试，不静默。
     */
    fun hubDeleteGateway(gatewayId: String, onDone: (error: String?) -> Unit) {
        scope.launch {
            val r = runCatching {
                val c = hubClient ?: throw HubException.notLoggedIn()
                c.deleteGateway(gatewayId)
            }
            if (r.isSuccess) onDone(null) else onDone(describeError(r.exceptionOrNull()))
        }
    }

    // ---------- Agent 状态（端点 #18，只读卡片在线点数据面；纯投影查询，无命令无副作用） ----------

    /**
     * Agent 状态（端点 #18，v1.3.0 additive；直调同 [hubDevices] 式：authStack + withBearer 走
     * [DefaultHubClient.agentStatus] 门面）。onDone status 非 null = 成功（gatewayOnline /
     * activeConversations / activeRuns / lastSeenAt 投影快照）；null = 未成功（未登录/网络不可达/
     * Hub FAILED），error = 人读失败原因——调用方按「拉不到不显示」降级（缓存 TTL 与在途去重
     * 由调用方持有），不阻塞 UI。复植轮（2026-11-16）：网关供给/轮换/撤销三管理桥面已复植
     * （见上方管理面段），本面维持 #18 只读读面。
     */
    fun hubAgentStatus(
        gatewayId: String,
        agentId: String,
        onDone: (status: AgentStatusResponse?, error: String?) -> Unit,
    ) {
        scope.launch {
            val r = runCatching {
                hubClient?.agentStatus(gatewayId, agentId) ?: throw HubException.notLoggedIn()
            }
            val status = r.getOrNull()
            if (status != null) {
                onDone(status, null)
            } else {
                onDone(null, describeError(r.exceptionOrNull()))
            }
        }
    }

    /** 失败原因人读化（契约错误 code+message，其余取 message；与 desktop 同式）。 */
    private fun describeError(e: Throwable?): String =
        (e as? HubException)?.let { "${it.payload.code} ${it.payload.message}" }
            ?: (e?.message ?: "未知错误")
}
