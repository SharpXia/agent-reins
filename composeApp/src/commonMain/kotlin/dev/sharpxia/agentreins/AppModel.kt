package dev.sharpxia.agentreins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sharpxia.agentreins.db.CacheStore
import dev.sharpxia.agentreins.db.SqlCacheStore
import dev.sharpxia.agentreins.db.createAppDriver
import dev.sharpxia.agentreins.model.ApprovalRequest
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import dev.sharpxia.agentreins.openclaw.Conversation
import dev.sharpxia.agentreins.openclaw.ConversationKind
import dev.sharpxia.agentreins.openclaw.ConversationOverlay
import dev.sharpxia.agentreins.openclaw.ConversationRepository
import dev.sharpxia.agentreins.openclaw.ConnectionStore
import dev.sharpxia.agentreins.openclaw.GatewayRpcException
import dev.sharpxia.agentreins.openclaw.SessionCategory
import dev.sharpxia.agentreins.openclaw.SessionClassifier
import dev.sharpxia.agentreins.openclaw.OpenClawClient
import dev.sharpxia.agentreins.openclaw.Outbox
import dev.sharpxia.agentreins.openclaw.SearchHit
import dev.sharpxia.agentreins.openclaw.SessionOverlay
import dev.sharpxia.agentreins.openclaw.StoredConnection
import dev.sharpxia.agentreins.ui.AppSound
import dev.sharpxia.agentreins.ui.FeedbackPrefs
import dev.sharpxia.agentreins.ui.createSoundPlayer
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.ThemeMode
import dev.sharpxia.agentreins.crypto.secureRandomBytes
import dev.sharpxia.agentreins.openclaw.epochMs
import dev.sharpxia.agentreins.openclaw.parsePairingSetupCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.concurrent.Volatile

/**
 * 连接配置 + 网关客户端生命周期 + 会话/对话流状态。
 * M2：对话流为单一数据源（ConversationState），发送即乐观回显、流式归并、终局回填历史。
 * M2.5：冷启动先读缓存（断网可见上次列表）、草稿持久化（kv）、未读计数联动、
 *       发送走 outbox 队列（离线排队、重连自动补发）。
 */
class AppModel(
    /** Dev 直连预填（desktop 壳的 AGENTREINS_DEV_* 入口）；普通构造留空，走连接页。
     *  命名注意：不能叫 url/apiKey——无 val/var 的构造参数会在 init 块与属性初始化器
     *  作用域内遮蔽同名属性，init 的自动连接判据将永远读到空串（冷启动失联的根因）。 */
    initialUrl: String = "",
    initialApiKey: String = "",
) {
    var url by mutableStateOf(initialUrl)
    var apiKey by mutableStateOf(initialApiKey)
    /** Dev 模式：构造后立即连接（desktop 壳）。 */
    var devAutoConnect = false
    /** Dev 模式：会话列表就绪后自动打开该会话。 */
    var devAutoSelectKey: String? = null
    /** 扫码/粘贴得到的 setup code（oc-pair://…）；非空时连接走扫码配对流程。 */
    var setupCode by mutableStateOf("")
    /** 备用网关地址（QR urls 字段 / 上次连接持久化），多地址 failover 候选（remote-access-design.md §6）。 */
    var alternateUrls by mutableStateOf<List<String>>(emptyList())
    /** 实际连上的地址（可能与主地址不同——failover 命中备用时），连接页显示来源标签用。 */
    var connectedUrl by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<GatewayStatus>(GatewayStatus.Disconnected)
        private set
    var sessions by mutableStateOf<List<Session>>(emptyList())
        private set
    /** 当前会话发送队列待补发条数，聊天页显示排队提示。 */
    var queuedCount by mutableStateOf(0)
        private set
    /** 当前会话的 PENDING 待发命令（聊天页「未发出」气泡 + 重试/删除 UI）。 */
    var pendingOutbox by mutableStateOf<List<Outbox.Command>>(emptyList())
        private set
    var selectedSession by mutableStateOf<String?>(null)
        private set
    /** 当前选中会话的对话流状态（聊天页唯一数据源）。 */
    var conversation by mutableStateOf<ConversationState?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
    /** 本机已有配对设备凭证（deviceToken），API Key 可留空直连。 */
    var hasPairedCredential by mutableStateOf(false)
        private set

    // ---- M3.1：Agent 本地注册表（名称/身份色，kv 持久化；协议无 agent 写入面） ----
    var agentProfiles by mutableStateOf<Map<String, AgentProfile>>(emptyMap())
        private set

    // ---- M3.1：外观偏好（跟随系统/亮/暗，kv 持久化） ----
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    // ---- M3.5：审批面板（可选 scope：开启后连接带 operator.approvals，需 owner 重批准一次） ----
    var approvalsEnabled by mutableStateOf(false)
        private set

    /** 待审批请求（审批 Sheet 的数据源；一次一个，超窗的由网关 approval.history 兜回）。 */
    var pendingApproval by mutableStateOf<ApprovalRequest?>(null)
        private set

    // ---- M3.6：Onboarding-lite（首次连接 → 直达主会话） ----
    /** 引导完成标记（kv 持久化）。已有存量连接的老用户（beta-0.2.7 升级）仅首连补一次直达。 */
    var onboardingDone by mutableStateOf(false)
        private set

    /** 首次连接成功后要直达的会话键（主会话），Nav 层据它 navigate 到 chat。 */
    var autoOpenKey by mutableStateOf<String?>(null)
        private set

    /** 待「直达主会话」判定（连接成功置位，第一份非空列表消费）。 */
    private var pendingFirstOpen = false

    /**
     * 引导页退出条件用「构造期一次性事实」而非活值（review R1-P1-1）： ConnectionStore
     * 只在连接成功后落盘，故「构造时有无存档连接」即「是否全新安装」。
     * 若用 url/apiKey 活值判定，用户在引导内连接页打下第一个字符就会被弹回空列表。
     */
    private val hadStoredConnection = runCatching { ConnectionStore.load() }.getOrNull() != null

    /** 是否展示引导页：从未完成引导且非存量升级用户。 */
    val showOnboarding: Boolean
        get() = !onboardingDone && !hadStoredConnection

    /** M3.6 服务端搜索最近一次结果（Search tab 渲染；null=未搜或失败）。 */
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    /** 草稿（per-session 持久化到 kv，500ms 去抖）。 */
    private val draftState = mutableStateOf("")
    var draft: String
        get() = draftState.value
        set(value) {
            draftState.value = value
            scheduleDraftPersist()
        }

    /** 本地缓存（M2.5 数据基座）。driver 不可用时 Noop 降级，全部能力退化为无缓存。 */
    private val cache: CacheStore = SqlCacheStore.open(createAppDriver())
    private val gatewayId = SqlCacheStore.DEFAULT_GATEWAY_ID

    /** M3.6 音效播放（iOS 真声，其余平台 no-op）。事件挂点：连接成功/新回复/审批请求。 */
    private val sounds = createSoundPlayer()

    /** 通知音按会话防抖（流式 run 每条 delta 一次 activity，逐事件响铃不可闻）。 */
    private val soundGates = mutableMapOf<String, Long>()

    /** 连接成功 BLIP 每次应用运行只响一声；用户显式断开后重连再响（review R1-P2-8）。 */
    private var connectChimed = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: OpenClawClient? = null
    private var deviceToken: String? = null
    private var statusJob: Job? = null
    private var activeRunsJob: Job? = null
    private var sessionsJob: Job? = null
    private var activityJob: Job? = null
    private var outboxJob: Job? = null
    private var approvalsJob: Job? = null
    private var conversationJob: Job? = null
    private var draftPersistJob: Job? = null
    /** 用户主动断开时置位，回前台不自动重连；connect() 成功路径清零。 */
    private var explicitDisconnect = false

    /** 向上翻页加载中（M4 反馈⑤：下拉 loading 指示的数据源；防连发重复请求）。 */
    var loadingOlder by mutableStateOf(false)
        private set

    // ---- 会话列表本地 overlay（M2.6：pinned/archived 本地显式状态，网关同步留 M3+） ----
    // mutableStateOf：collector 在 Default 线程写、UI 线程读写，走 snapshot 保证可见性
    /** 网关/缓存视图（未叠 overlay 的原始列表）。 */
    private var rawSessions by mutableStateOf<List<Session>>(emptyList())
    /** 本地覆盖层（kv 持久化，跨重启）。 */
    private var sessionOverlay by mutableStateOf(emptyMap<String, SessionOverlay.Entry>())

    // ---- M4 P5：conversation 行内执行状态（operator 全量广播派生） ----
    /** 全局在途 run 的 sessionKey 集（含派生 subagent 会话）。 */
    var activeRunKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 会话级在途发送（M4 反馈④：气泡左侧小 loading 的数据源）。 */
    var sendingSessionKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /**
     * conversation 的行内状态：执行中（该 agent 任一会话有在途 run）+ 已派发子任务数
     * （在途且 surface 为 subagent:）。纯派生，无独立状态。
     */
    fun conversationRunStatus(conv: Conversation): Pair<Boolean, Int> {
        if (activeRunKeys.isEmpty()) return false to 0
        val agentKeys = sessions.asSequence()
            .filter { (it.agentId ?: sessionKeyAgentId(it.id)) == conv.agentId }
            .mapTo(mutableSetOf()) { it.id }
        if (agentKeys.isEmpty()) return false to 0
        val active = activeRunKeys.filterTo(mutableSetOf()) { it in agentKeys }
        val derived = active.count { SessionClassifier.classify(it) == SessionCategory.DERIVED }
        return (active.isNotEmpty()) to derived
    }

    /** conversation 时间线进入时的一次性分区锚（agent 主页 sessions 行定位；ChatScreen 消费后清）。 */
    var pendingConvAnchor by mutableStateOf<String?>(null)

    // ---- M4 P3：对话详情拼接时间线（1 conversation = 该 agent 全部主 session） ----
    /** 时间线分区：key + 创建时 label（分区头文案）+ 该 session 的对话流状态。 */
    data class ConvSection(val key: String, val label: String?, val state: ConversationState?)

    /** 当前对话的 agentId（null = 非 conversation 路由）。 */
    var convAgentId by mutableStateOf<String?>(null)
        private set
    /** 拼接时间线分区（最近活跃降序；index 0 = 输入锚定的当前主 session）。 */
    var convSections by mutableStateOf<List<ConvSection>>(emptyList())
        private set
    private val convLoadedKeys = mutableSetOf<String>()
    /** 当前在加载的分区（null=空闲；UI 快照流据此实现「钉在分区头时连续翻页」）。 */
    var convSectionLoading by mutableStateOf<String?>(null)
        private set
    private var convJob: Job? = null

    /**
     * 打开某 agent 的 conversation 时间线：主线 session 全集按活跃降序成区，select 当前
     * （驱动事件/草稿/outbox），并收集全部区流（含未打开 session——operator 收全量广播，
     * 每个有活动的 session 都会有流）。
     */
    fun openConversationTimeline(agentId: String) {
        if (convAgentId == agentId && convSections.isNotEmpty()) return
        val c = client ?: return
        val keys = convMainlineKeys(agentId)
        if (keys.isEmpty()) return
        convAgentId = agentId
        convLoadedKeys.clear()
        rebuildConvSections(keys)
        // 时间线把该 agent 全部主线分区一次性摆出=整段已读（SW 真机反馈①）：首页徽标是
        // Σ分区未读，此前只随 select 清最新分区，旧分区徽标残留成「进了会话不清未读」。
        sessions.filter { it.id in keys && it.unread > 0 }.forEach { c.clearUnread(it.id) }
        sessions.firstOrNull { it.id == keys.first() }?.let { select(it) }
        convLoadedKeys.add(keys.first())
    }

    /**
     * 按分区加载历史（M4 真机反馈②重做）：分区头进入视口即触发——未拉过→拉首页；
     * 已拉过且有更多→翻更早一页。返回是否真的派发了加载（UI 据此捕获位置锚）。
     * 旧实现只挂「绝对顶部」，空分区（probe 残留）拉完即卡死整条链=「下拉加载不可用」，
     * 且中间分区永远没有机会加载。
     */
    fun loadSectionHistoryIfNeeded(sectionKey: String): Boolean {
        if (convSectionLoading != null) return false
        val c = client ?: return false
        val section = convSections.firstOrNull { it.key == sectionKey } ?: return false
        if (section.key !in convLoadedKeys) {
            convSectionLoading = sectionKey
            scope.launch {
                try {
                    c.loadHistory(sectionKey)
                } catch (e: Exception) {
                    lastError = e.message
                } finally {
                    convLoadedKeys.add(sectionKey)
                    convSectionLoading = null
                }
            }
            return true
        }
        val st = section.state ?: return false
        if (!st.hasMoreHistory) return false
        convSectionLoading = sectionKey
        scope.launch {
            try {
                c.loadHistoryOlder(sectionKey)
            } catch (e: Exception) {
                lastError = e.message
            } finally {
                convSectionLoading = null
            }
        }
        return true
    }

    /** 离开对话路由时清时间线态（DisposableEffect onDispose）。 */
    fun closeConversationTimeline() {
        convAgentId = null
        convSections = emptyList()
        convJob?.cancel()
        convJob = null
        convLoadedKeys.clear()
        convSectionLoading = null
    }

    /** 该 agent 的时间线成员：主线（main/ar-*）且未被本地归档/隐藏的 session，活跃降序。 */
    private fun convMainlineKeys(agentId: String): List<String> =
        ConversationRepository.mainlineKeysOf(
            agentId,
            sessions.filter {
                (it.agentId ?: sessionKeyAgentId(it.id)) == agentId && !it.hidden && !it.archived
            },
        )

    private fun rebuildConvSections(keys: List<String>) {
        val c = client ?: return
        val byKey = sessions.associateBy { it.id }
        convSections = keys.map { ConvSection(it, byKey[it]?.title, null) }
        convJob?.cancel()
        convJob = scope.launch {
            val flows = keys.map { k -> c.conversation(k) }
            combine(flows) { arr -> arr.toList() }.collect { states ->
                convSections = convSections.mapIndexed { i, s -> s.copy(state = states[i]) }
            }
        }
    }

    // ---- M4 P2：conversation 派生（首页数据源）+ conversation 级本地 overlay ----
    /** 首页 conversation 列表（由 sessions 派生；置顶/归档/隐藏走 conv 级 overlay）。 */
    var conversations by mutableStateOf<List<Conversation>>(emptyList())
        private set
    private var convOverlay by mutableStateOf(emptyMap<String, ConversationOverlay.Entry>())

    /** overlay 叠加后发布到 UI 状态（网关刷新与本地操作共用同一出口）。 */
    private fun publishSessions() {
        sessions = SessionOverlay.apply(rawSessions, sessionOverlay)
        conversations = ConversationRepository.build(sessions, convOverlay)
    }

    fun setConversationPinned(conv: Conversation, pinned: Boolean) {
        convOverlay = ConversationOverlay.toggled(convOverlay, conv.id, pinned = pinned)
        scheduleOverlayPersist()
        publishSessions()
    }

    fun setConversationArchived(conv: Conversation, archived: Boolean) {
        convOverlay = ConversationOverlay.toggled(convOverlay, conv.id, archived = archived)
        scheduleOverlayPersist()
        publishSessions()
    }

    fun setConversationHidden(conv: Conversation, hidden: Boolean) {
        convOverlay = ConversationOverlay.toggled(convOverlay, conv.id, hidden = hidden)
        scheduleOverlayPersist()
        publishSessions()
    }

    /** 该 agent 的 conversation id（主线优先；仅渠道往来时落渠道线程）。 */
    fun conversationIdForAgent(agentId: String): String? =
        conversations.firstOrNull { it.agentId == agentId && it.kind == ConversationKind.MAINLINE }?.id
            ?: conversations.firstOrNull { it.agentId == agentId }?.id

    /** 会话列表刷新后同步时间线成员（新会话加入/会话隐藏移除）；当前锚定变了则切 select。 */
    private fun refreshConvSections() {
        val agentId = convAgentId ?: return
        if (convSections.isEmpty()) return
        val keys = convMainlineKeys(agentId)
        if (keys == convSections.map { it.key }) return
        rebuildConvSections(keys)
        val current = keys.firstOrNull()
        if (current != null && selectedSession != current) {
            sessions.firstOrNull { it.id == current }?.let { select(it) }
        }
    }

    /** 归档/置顶（本地即时生效 + kv 持久化；离线可用）。 */
    fun setSessionArchived(session: Session, archived: Boolean) {
        sessionOverlay = SessionOverlay.toggled(sessionOverlay, session.id, archived = archived)
        scheduleOverlayPersist()
        publishSessions()
    }

    fun setSessionPinned(session: Session, pinned: Boolean) {
        sessionOverlay = SessionOverlay.toggled(sessionOverlay, session.id, pinned = pinned)
        scheduleOverlayPersist()
        publishSessions()
    }

    /** 隐藏/取消隐藏（M3.2 隐私分区；纯本地，归档视图可恢复）。 */
    fun setSessionHidden(session: Session, hidden: Boolean) {
        sessionOverlay = SessionOverlay.toggled(sessionOverlay, session.id, hidden = hidden)
        scheduleOverlayPersist()
        publishSessions()
    }

    /** Agent 显示名（空串回落 agentId）。 */
    fun setAgentName(agentId: String, name: String) =
        upsertAgent(agentId) { it.copy(name = name.trim().ifBlank { agentId }) }

    /** Agent 身份色。 */
    fun setAgentTint(agentId: String, tint: AgentTintName) =
        upsertAgent(agentId) { it.copy(tint = tint) }

    /** 审批面板开关：重启连接生效（scope 升级会触发一次 owner 批准，属预期）。 */
    fun toggleApprovals(enabled: Boolean) {
        approvalsEnabled = enabled
        runCatching { cache.kvPut(gatewayId, APPROVALS_KEY, if (enabled) "1" else "0") }
    }

    /** 审批应答（approval.resolve；参数形态待网关启用审批后实测校准）。 */
    fun respondApproval(approved: Boolean) {
        val req = pendingApproval ?: return
        pendingApproval = null
        scope.launch {
            runCatching { client?.resolveApproval(req.id, approved) }
                .onFailure { lastError = "审批应答失败：${it.message}" }
        }
    }

    fun applyThemeMode(mode: ThemeMode) {
        themeMode = mode
        runCatching { cache.kvPut(gatewayId, THEME_KEY, mode.name) }
    }

    /** 音效总开关（M3.6 反馈①）：关闭后所有事件音（含固定的连接/审批音）静默。 */
    fun toggleSound(enabled: Boolean) {
        FeedbackPrefs.soundEnabled.value = enabled
        persistFeedbackPrefs()
    }

    /** 触感开关（M3.6 设置页；kv 持久化，平台 actual 门控时读取）。 */
    fun toggleHaptics(enabled: Boolean) {
        FeedbackPrefs.hapticsEnabled.value = enabled
        persistFeedbackPrefs()
    }

    /** 通知音（新回复）选择；null = 静音。 */
    fun setNotificationSound(sound: AppSound?) {
        FeedbackPrefs.notificationSound.value = sound
        persistFeedbackPrefs()
    }

    private fun persistFeedbackPrefs() {
        val s = FeedbackPrefs.notificationSound.value
        val raw = "h=${if (FeedbackPrefs.hapticsEnabled.value) "1" else "0"}" +
            ";snd=${if (FeedbackPrefs.soundEnabled.value) "1" else "0"}" +
            ";s=${s?.name ?: "none"}"
        runCatching { cache.kvPut(gatewayId, FEEDBACK_KEY, raw) }
    }

    /** 事件音统一出口：总开关门控（反馈①），通知音另有 per-call 选择。 */
    private fun playSound(sound: AppSound) {
        if (FeedbackPrefs.soundEnabled.value) sounds.play(sound)
    }

    private fun upsertAgent(agentId: String, transform: (AgentProfile) -> AgentProfile) {
        val current = agentProfiles[agentId] ?: AgentProfile.default(agentId)
        agentProfiles = agentProfiles + (agentId to transform(current))
        runCatching { cache.kvPut(gatewayId, AgentRegistry.KV_KEY, AgentRegistry.serialize(agentProfiles)) }
    }

    /** overlay 持久化：300ms 去抖（滑动连做合并写；单 job 串行天然防乱序）。 */
    private var overlayPersistJob: Job? = null

    private fun scheduleOverlayPersist() {
        val snapshot = sessionOverlay
        val convSnapshot = convOverlay
        overlayPersistJob?.cancel()
        overlayPersistJob = scope.launch {
            delay(300)
            runCatching { cache.kvPut(gatewayId, OVERLAY_KEY, SessionOverlay.serialize(snapshot)) }
            runCatching { cache.kvPut(gatewayId, CONV_OVERLAY_KEY, ConversationOverlay.serialize(convSnapshot)) }
        }
    }

    init {
        // 记住上次连接：预填地址与 Key；deviceToken 是扫码配对成功后的免 token 凭证
        runCatching { ConnectionStore.load() }.getOrNull()?.let {
            if (this.url.isBlank()) this.url = it.url
            if (this.apiKey.isBlank()) this.apiKey = it.apiKey
            alternateUrls = it.urls
            deviceToken = it.deviceToken
            hasPairedCredential = it.deviceToken != null
        }
        // 冷启动先读缓存（M2.5 验收项：断网冷启动可见上次列表；连接成功后被网关权威覆盖）
        runCatching { cache.loadSessions(gatewayId) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { rawSessions = it }
        // overlay（pinned/archived 本地状态）随后叠加并发布
        sessionOverlay = SessionOverlay.parse(runCatching { cache.kvGet(gatewayId, OVERLAY_KEY) }.getOrNull())
        convOverlay = ConversationOverlay.parse(runCatching { cache.kvGet(gatewayId, CONV_OVERLAY_KEY) }.getOrNull())
        publishSessions()
        agentProfiles = AgentRegistry.parse(runCatching { cache.kvGet(gatewayId, AgentRegistry.KV_KEY) }.getOrNull())
        themeMode = runCatching { cache.kvGet(gatewayId, THEME_KEY) }.getOrNull()
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
        approvalsEnabled = runCatching { cache.kvGet(gatewayId, APPROVALS_KEY) }.getOrNull() == "1"
        // M3.6 反馈偏好与引导标记
        runCatching { cache.kvGet(gatewayId, FEEDBACK_KEY) }.getOrNull()?.let { raw ->
            val parts = raw.split(';').mapNotNull {
                val idx = it.indexOf('=')
                if (idx <= 0) null else it.take(idx) to it.substring(idx + 1)
            }.toMap()
            FeedbackPrefs.hapticsEnabled.value = parts["h"] != "0"
            FeedbackPrefs.soundEnabled.value = parts["snd"] != "0"
            FeedbackPrefs.notificationSound.value =
                parts["s"]?.takeIf { it != "none" }?.let { name ->
                    AppSound.entries.firstOrNull { it.name == name }
                }
        }
        onboardingDone = runCatching { cache.kvGet(gatewayId, ONBOARDING_KEY) }.getOrNull() == "1"
        // iOS 进后台 WS 必死：回前台自动用已存配置重连（显式断开除外）
        scope.launch {
            appForegroundEvents.collect {
                delay(800) // 等 resume 后 WS 错误态落地再判断
                if (!explicitDisconnect && !busy) {
                    val st = status
                    val connectedLike = st is GatewayStatus.Connected ||
                        st is GatewayStatus.Connecting || st is GatewayStatus.PairingPending
                    if (!connectedLike && url.isNotBlank() && (deviceToken != null || apiKey.isNotBlank())) {
                        resetAutoRetry() // 前台事件 = 网络环境可能变化，退避计数清零重新起算
                        connect()
                    }
                }
            }
        }
        // 响应式重连（真机反馈：应「条件满足再连」而非定时盲试）：网络路径恢复即连。
        // StateFlow 回放当前值——冷启动即做一次判定；此后 Wi-Fi/VPN 路径每次变化再触发。
        scope.launch {
            networkAvailable.collect { available ->
                if (!available || explicitDisconnect || busy) return@collect
                val st = status
                val connectedLike = st is GatewayStatus.Connected ||
                    st is GatewayStatus.Connecting || st is GatewayStatus.PairingPending
                if (!connectedLike && url.isNotBlank() && (deviceToken != null || apiKey.isNotBlank())) {
                    resetAutoRetry()
                    connect()
                }
            }
        }
        // 已有可用配置（deviceToken 免扫码 / 共享 Key）则启动即连——不再要求每次手动点连接
        // 已有可用配置（deviceToken 免扫码 / 共享 Key）则启动即连——不再要求每次手动点连接
        if (url.isNotBlank() && (deviceToken != null || apiKey.isNotBlank())) connect()
        if (devAutoConnect) connect() // dev 通道重复调用被 busy 守卫挡掉
    }

    /** 向上翻到顶时懒加载更早一页历史（hasMoreHistory / 加载中去重）。 */
    /** 向上加载更早一页历史（M4 反馈⑤：返回是否真的派发了加载——下拉阈值判定据此决定
     *  显示 loading 还是直接回弹）。 */
    fun loadOlderMessages(): Boolean {
        val key = selectedSession ?: return false
        if (loadingOlder) return false
        val c = client ?: return false
        val st = conversation ?: return false
        if (st.entries.isEmpty() || !st.hasMoreHistory) return false
        loadingOlder = true
        scope.launch {
            try {
                c.loadHistoryOlder(key)
            } catch (e: Exception) {
                lastError = e.message
            } finally {
                loadingOlder = false
            }
        }
        return true
    }

    /** 发送信号（M4 反馈④）：每次用户发送 +1，聊天页据此滚到底部看到刚发的消息。 */
    var sendSignal by mutableStateOf(0)
        private set

    fun connect() {
        if (busy) return
        val code = setupCode.trim()
        // 多地址 failover（remote-access-design.md §6）：候选按序 [主地址, *备用地址]，
        // 传输层失败（超时/DNS/TLS）才轮换下一候选；RPC 拒绝说明地址可达，不轮换。
        val candidates: List<String>
        val apiKeyForConfig: String
        val bootstrapToken: String?
        when {
            code.isNotEmpty() -> try {
                val setup = parsePairingSetupCode(code)
                url = setup.url
                alternateUrls = setup.urls
                candidates = (listOf(setup.url) + setup.urls).distinct()
                bootstrapToken = setup.bootstrapToken
                apiKeyForConfig = ""
            } catch (e: IllegalArgumentException) {
                lastError = e.message
                return
            }
            url.isNotBlank() && apiKey.isNotBlank() -> {
                candidates = (listOf(url.trim()) + alternateUrls).distinct()
                bootstrapToken = null
                apiKeyForConfig = apiKey.trim()
            }
            url.isNotBlank() && deviceToken != null -> {
                candidates = (listOf(url.trim()) + alternateUrls).distinct()
                bootstrapToken = null
                apiKeyForConfig = ""
            }
            else -> {
                lastError = "填写网关地址与 API Key，或扫码配对"
                return
            }
        }
        busy = true
        lastError = null
        explicitDisconnect = false
        connectedUrl = null
        // 直达判定必须早于 connect()（review R1-P1-2）：refreshSessions 在 connect 返回前
        // 就把首份列表写进 StateFlow，置位晚了首份发射已被消费、StateFlow 不再发射时直达静默失效
        if (!onboardingDone) pendingFirstOpen = true
        autoRetryJob?.cancel() // 手动/重入连接优先，别让在途退避重试插进来
        // 旧客户端先收尾（重连场景）：WS 任务与 HttpClient 不能泄漏
        val old = client
        if (old != null) scope.launch { runCatching { old.disconnect() } }
        val c = OpenClawClient(scope, cache)
        client = c
        // collector 必须在 connect() 前挂上：PairingPending 等中间状态发生在 connect() 返回之前
        statusJob?.cancel()
        sessionsJob?.cancel()
        activityJob?.cancel()
        outboxJob?.cancel()
        statusJob = scope.launch { c.status.collect { status = it } }
        activeRunsJob?.cancel()
        activeRunsJob = scope.launch {
            launch { c.activeRuns.collect { activeRunKeys = it } }
            launch { c.sendingSessions.collect { sendingSessionKeys = it } }
        }
        approvalsJob?.cancel()
        approvalsJob = scope.launch {
            c.approvals.collect { req ->
                if (approvalsEnabled) {
                    val isNew = pendingApproval == null
                    pendingApproval = req
                    if (isNew) playSound(AppSound.CHIME_A) // 审批是关键事件，固定音不受通知音选择影响（受总开关）
                }
            }
        }
        sessionsJob = scope.launch {
            c.sessions.collect { list ->
                rawSessions = list
                publishSessions()
                // conversation 路由中列表刷新（新 ar-* 会话等）：时间线成员重建 + 锚定切换
                refreshConvSections()
                // 通知音防抖表随列表修剪，不随会话消失无限增长（review R3-P2-4）
                soundGates.keys.retainAll(list.mapTo(mutableSetOf()) { it.id })
                // Onboarding-lite「直达主会话」：首次连接后的第一份非空列表即挑主会话。
                // dev 通道有自己的目标会话，不抢（review R1-P2-5）。
                if (pendingFirstOpen && list.isNotEmpty() && devAutoSelectKey == null) {
                    pendingFirstOpen = false
                    autoOpenKey = (list.firstOrNull { it.id == MAIN_SESSION_KEY } ?: list.firstOrNull())?.id
                    autoOpenKey?.let { k -> list.firstOrNull { it.id == k }?.let { select(it) } }
                }
                // Dev 直连：列表就绪后自动打开目标会话
                val auto = devAutoSelectKey ?: autoOpenKey
                if (auto != null && selectedSession == null) {
                    list.firstOrNull { it.id == auto }?.let { select(it) }
                }
            }
        }
        // 非当前会话的 agent 事件 → 未读 +1（A8 叠加）+ 通知音（M3.6，3s 防抖：
        // 流式 run 每条 delta 都发 activity，逐事件响铃会哒哒连响——review R1-P1-3）。
        // 当前会话不计数不出声。
        activityJob = scope.launch {
            c.sessionActivity.collect { key ->
                // 当前会话之外，正在读的 timeline 分区同样在场（SW 真机反馈①）：
                // 时间线开着时旧分区来事件不该 +1 未读/响铃，否则越读徽标越大。
                if (key != selectedSession && convSections.none { it.key == key }) {
                    c.incrementUnread(key)
                    FeedbackPrefs.notificationSound.value?.let { sound ->
                        val now = epochMs()
                        if (now - (soundGates[key] ?: 0L) > SOUND_DEBOUNCE_MS) {
                            soundGates[key] = now
                            playSound(sound)
                        }
                    }
                }
            }
        }
        outboxJob = scope.launch {
            // 变更序号驱动（而非条数）：PENDING→SENT 不改条数，靠条数会漏刷新
            // （「未发出」气泡不消失/同屏与回显重复，review P2-1）
            c.outboxChanges.collect {
                val key = selectedSession
                queuedCount = if (key == null) 0 else runCatching {
                    cache.loadOutbox(gatewayId).count { it.sessionKey == key }
                }.getOrDefault(0)
                refreshPendingOutbox()
            }
        }
        scope.launch {
            try {
                var lastTransportError: Exception? = null
                var successUrl: String? = null
                for ((index, candidate) in candidates.withIndex()) {
                    val config = GatewayConfig(
                        baseUrl = candidate,
                        apiKey = apiKeyForConfig,
                        bootstrapToken = bootstrapToken,
                        deviceToken = if (bootstrapToken == null) deviceToken else null,
                        extraScopes = if (approvalsEnabled) listOf("operator.approvals") else emptyList(),
                    )
                    try {
                        // connect() 入口自清理，同一 client 逐候选重试安全
                        // 快速失败（真机反馈⑦-4）：不可达候选（如家里的 LAN 地址在户外）
                        // 3.5s 内判死即轮换。预算分级（R 轮 P0/P1/P2）：
                        //   bootstrap 配对 = 170s（网关侧等人工批准 150s，硬超时=首连必死）
                        //   末位/唯一候选 = 15s（远程中继慢握手不误判）
                        //   其余候选 = 3.5s 快速轮换
                        val budget = when {
                            bootstrapToken != null -> PAIRING_CANDIDATE_TIMEOUT_MS
                            index == candidates.lastIndex -> SLOW_CANDIDATE_TIMEOUT_MS
                            else -> FAST_CANDIDATE_TIMEOUT_MS
                        }
                        kotlinx.coroutines.withTimeout(budget) {
                            c.connect(config)
                        }
                        successUrl = candidate
                        break
                    } catch (e: GatewayRpcException) {
                        throw e // 配对/鉴权等协议拒绝：地址已可达，换地址无意义
                    } catch (e: TimeoutCancellationException) {
                        lastTransportError = e
                        runCatching { c.disconnect() } // 掐灭超时后残留的 ghost wsJob（R 轮 P1）
                        lastError = if (index < candidates.lastIndex) {
                            "地址 ${candidate} 不可达，尝试备用地址…"
                        } else {
                            "地址 ${candidate} 连接超时"
                        }
                    } catch (e: Exception) {
                        lastTransportError = e
                        if (index < candidates.lastIndex) {
                            lastError = "地址 ${candidate} 连接失败（${e.message ?: e::class.simpleName}），尝试备用地址…"
                        }
                    }
                }
                val connected = successUrl
                    ?: throw (lastTransportError ?: IllegalStateException("connect failed"))
                connectedUrl = connected
                lastError = null // failover 过程中的候选失败提示在成功后清掉
                resetAutoRetry() // 连上了，退避重试收队
                if (!connectChimed) {
                    playSound(AppSound.BLIP) // 连接成功提示音（固定音；弱网自动重连不连响）
                    connectChimed = true
                }
                // Onboarding-lite：首次连接（含老用户升级后首连）即完成标记并预告直达主会话
                if (!onboardingDone) {
                    onboardingDone = true
                    runCatching { cache.kvPut(gatewayId, ONBOARDING_KEY, "1") }
                    pendingFirstOpen = true // 列表到达后由 sessions collector 挑主会话
                }
                // 扫码/连接成功即记住本次配置与设备凭证（M1 存 Documents，M3 迁 Keychain）
                // 主地址保持用户输入/QR 主地址（到家优先 LAN），备用列表原样保存
                deviceToken = c.deviceToken ?: deviceToken
                hasPairedCredential = deviceToken != null
                runCatching {
                    ConnectionStore.save(
                        StoredConnection(
                            url = url.trim(),
                            apiKey = apiKey.trim(),
                            deviceToken = deviceToken,
                            urls = alternateUrls,
                        ),
                    )
                }
                setupCode = ""
                // 恢复上次浏览的会话（重连场景）
                selectedSession?.let { openConversation(c, it) }
            } catch (e: GatewayRpcException) {
                lastError = e.message
                status = GatewayStatus.Error(e.message)
            } catch (e: Exception) {
                lastError = e.message
                status = GatewayStatus.Error(e.message)
            } finally {
                busy = false
                // 自动连接失败（冷启动时网络/VPN 路由常未就绪）：有界退避重试，
                // 覆盖「启动后几十秒内链路才通」的时序；成功/前台事件/手动操作会重置
                if (status is GatewayStatus.Error) scheduleAutoRetry()
            }
        }
    }

    // ---- 自动连接退避重试（真机反馈：冷启动无法连接，网络/VPN 路由未就绪的时序问题） ----
    private var autoRetryJob: Job? = null
    private var autoRetryAttempts = 0

    private fun scheduleAutoRetry() {
        autoRetryJob?.cancel()
        if (explicitDisconnect) return
        if (deviceToken == null && apiKey.isBlank()) return // 没有可用凭据不空转
        if (autoRetryAttempts >= AUTO_RETRY_MAX_ATTEMPTS) return
        val delayMs = 2_000L shl autoRetryAttempts.coerceAtMost(4) // 2s→4s→8s→16s→32s
        autoRetryAttempts++
        autoRetryJob = scope.launch {
            delay(delayMs)
            val st = status
            val retryable = st is GatewayStatus.Disconnected || st is GatewayStatus.Error
            if (!explicitDisconnect && retryable) {
                connect()
            }
        }
    }

    /** 成功/回前台时重置退避计数，取消在途重试。 */
    private fun resetAutoRetry() {
        autoRetryAttempts = 0
        autoRetryJob?.cancel()
        autoRetryJob = null
    }

    /** 扫码结果入口：拿到 setup code 即自动发起配对连接。 */
    fun onQrScan(payload: String?) {
        if (payload.isNullOrBlank()) return
        setupCode = payload
        connect()
    }

    fun select(session: Session?) {
        // 切走前把旧会话在途草稿立即落库（P1：500ms 去抖窗口内切会话不能串草稿/丢草稿）。
        // P3 修复：无条件写入（含空值）——只写非空会让「已发送清空的草稿」写不进去，
        // 旧槽位残留已发送文本，切回时复活。
        selectedSession?.let { old ->
            draftPersistJob?.cancel()
            runCatching { cache.kvPut(gatewayId, draftKey(old), draftState.value) }
        }
        selectedSession = session?.id
        conversation = null
        conversationJob?.cancel()
        conversationJob = null
        session ?: return
        // 切会话即清未读 + 恢复该会话草稿与排队计数（重启/切换均生效）
        queuedCount = runCatching {
            cache.loadOutbox(gatewayId).count { it.sessionKey == session.id }
        }.getOrDefault(0)
        refreshPendingOutbox()
        draftState.value = runCatching { cache.kvGet(gatewayId, draftKey(session.id)) }.getOrNull() ?: ""
        val c = client ?: return
        c.clearUnread(session.id)
        openConversation(c, session.id)
    }

    /** 按会话键直达（服务端搜索命中本地列表没有的会话时）：与 select 同语义。 */
    fun openSessionByKey(sessionKey: String) {
        sessions.firstOrNull { it.id == sessionKey }?.let { return select(it) }
        selectedSession?.let { old ->
            draftPersistJob?.cancel()
            runCatching { cache.kvPut(gatewayId, draftKey(old), draftState.value) }
        }
        selectedSession = sessionKey
        conversation = null
        conversationJob?.cancel()
        conversationJob = null
        queuedCount = runCatching {
            cache.loadOutbox(gatewayId).count { it.sessionKey == sessionKey }
        }.getOrDefault(0)
        refreshPendingOutbox()
        draftState.value = runCatching { cache.kvGet(gatewayId, draftKey(sessionKey)) }.getOrNull() ?: ""
        val c = client ?: return
        c.clearUnread(sessionKey)
        openConversation(c, sessionKey)
    }

    /** 当前会话 PENDING 待发命令快照（聊天页「未发出」气泡的数据源）。在途条目排除
     *  （真机反馈⑥-5）：RPC 已发出、回执未达的命令由发送按钮反馈，不冒「未发出」气泡。 */
    private fun refreshPendingOutbox() {
        val key = selectedSession ?: run { pendingOutbox = emptyList(); return }
        val sending = client
        pendingOutbox = runCatching {
            cache.loadOutbox(gatewayId).filter {
                it.sessionKey == key && it.status == Outbox.Command.Status.PENDING &&
                    sending?.isSending(it.idempotencyKey) != true
            }
        }.getOrDefault(emptyList())
    }

    /** 删除一条待发消息（用户放弃发送）。删除即从队列消失，不再补发。 */
    fun deleteQueued(cmd: Outbox.Command) {
        runCatching { cache.deleteOutbox(cmd.idempotencyKey, gatewayId) }
        queuedCount = runCatching {
            cache.loadOutbox(gatewayId).count { it.sessionKey == selectedSession }
        }.getOrDefault(0)
        refreshPendingOutbox()
    }

    /** 立即重试待发队列（在线时即刻补发；离线由重连自动补发兜底）。 */
    fun retryQueuedNow() {
        client?.retryPendingNow()
    }

    private fun openConversation(c: OpenClawClient, sessionKey: String) {
        conversationJob?.cancel()
        conversationJob = scope.launch {
            val flow = c.conversation(sessionKey)
            runCatching { c.loadHistory(sessionKey) }
                .onFailure { lastError = it.message }
            flow.collect { conversation = it }
        }
    }

    /**
     * 发送（M2.5 outbox 语义）：client 在线时立即发；失败/离线进队列（消息不静默丢失），
     * 重连后由 client.flushOutbox 自动补发（同幂等键）。
     */
    fun send() {
        val key = selectedSession ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        val quoted = quotedText
        draft = ""
        clearQuote()
        // 引用以 Markdown blockquote 头部拼接（M3.4 QuoteBar；截前三行防引用淹没正文）
        val composed = if (quoted != null) {
            val lines = quoted.lines().filter { it.isNotBlank() }.take(3)
            val ellipsis = if (quoted.lines().count { it.isNotBlank() } > 3) "\n> …" else ""
            (lines.joinToString("\n> ").let { "> $it" } + ellipsis + "\n\n" + text)
        } else {
            text
        }
        sendText(key, composed)
    }

    /** 消息长按「重发」：把一段文本再次走发送管线（不经草稿，M2.6）。 */
    fun resendText(text: String) {
        val key = selectedSession ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        sendText(key, t)
    }

    private fun sendText(sessionKey: String, text: String) {
        sendSignal++ // 聊天页消费：滚到底部露出刚发的消息（M4 反馈④）
        val c = client
        if (c == null) {
            // 尚无 client（冷启动离线）：直接入缓存队列，连接成功后由 flush 补发
            enqueueOffline(sessionKey, text)
            return
        }
        scope.launch {
            try {
                c.send(sessionKey, text)
            } catch (e: GatewayRpcException) {
                lastError = when (e.code) {
                    "SEND_QUEUED" -> "已进入发送队列，重连后自动补发"
                    // runtime 不可用如实上抛（M4 反馈：交给用户决策）——消息保留在队列（气泡可重试/放弃）
                    "RUNTIME_UNAVAILABLE" -> e.message
                    "SEND_TIMEOUT" -> null // 结果未确认：outbox 待确认态展示，不弹错误条（真机反馈 bug①）
                    else -> e.message
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
    }

    /** 无 client 时的离线入队（与 client.send 的限额/键规则一致）。 */
    private fun enqueueOffline(sessionKey: String, text: String) {
        if (!cache.available) {
            // 存储不可用 = 没有队列：诚实报错，不谎报"已排队"（消息不静默丢失原则）
            lastError = "当前离线且本地存储不可用，消息未发出，请连接后重试"
            draft = text
            return
        }
        val now = epochMs()
        val existing = runCatching { cache.loadOutbox(gatewayId) }.getOrDefault(emptyList())
        Outbox.checkLimit(existing, now)?.let {
            lastError = it
            draft = text
            return
        }
        runCatching {
            cache.upsertOutbox(
                Outbox.Command(
                    idempotencyKey = Outbox.newKey(now, randomSuffix()),
                    sessionKey = sessionKey,
                    text = text,
                    createdAtMs = now,
                ),
                gatewayId,
            )
            queuedCount = cache.loadOutbox(gatewayId).count { it.sessionKey == sessionKey }
            refreshPendingOutbox()
            lastError = "当前离线，消息已排队（连接后自动补发）"
        }
    }

    /** idempotencyKey 随机后缀（hexString 是 shared internal，这里本地实现同语义）。 */
    private fun randomSuffix(): String = secureRandomBytes(4)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---- M3.4：停止 / 分支 / 从头开始 / 引用 ----

    /** Composer 终止键（M4 反馈②）：本地立即解锁 → abort → 失败（runtime 不可用等）
     *  则走命令通道兜底发 /stop。任何情况都不留卡死态。 */
    fun stopRun() {
        val key = selectedSession ?: return
        scope.launch {
            val c = client ?: return@launch
            c.clearActiveRun(key)
            val aborted = runCatching { c.abort(key) }.isSuccess
            if (!aborted) sendText(key, "/stop")
        }
    }

    /** 终止（命令通道，M4 反馈：runtime 不可用时 abort 无目标——/stop 走网关命令处理器，
     *  与 /status 同通道）。本地同步解锁。 */
    fun stopByCommand() {
        val key = selectedSession ?: return
        scope.launch { client?.clearActiveRun(key) }
        sendText(key, "/stop")
    }

    /** 引用消息（MessageActionsSheet「引用」）：Composer 上方 QuoteBar 展示，随下一条消息发出。 */
    var quotedText by mutableStateOf<String?>(null)
        private set

    fun quoteMessage(text: String) {
        quotedText = text
    }

    fun clearQuote() {
        quotedText = null
    }

    /**
     * 从某条消息分支出新会话（sessions.fork，M3.0 探针证实普通 scope 可用）。
     * 成功后刷新列表并切进新会话；失败透传 lastError。
     */
    fun forkFrom(message: Message) {
        val c = client ?: run { lastError = "未连接网关，无法分支"; return }
        val source = selectedSession ?: return
        val anchorId = message.entryId
            ?: message.id.takeIf { !it.startsWith("user-") }
            ?: run { lastError = "该消息不支持分支（缺 transcript 锚点）"; return }
        scope.launch {
            try {
                val newKey = c.fork(source, anchorId)
                clearQuote()
                c.refreshSessions()
                sessions.firstOrNull { it.id == newKey }?.let { select(it) }
                    ?: run { // 列表未及时含新键：直接选（conversation 按键惰性建流）
                        selectedSession = newKey
                        openConversation(c, newKey)
                    }
            } catch (e: Exception) {
                lastError = "分支失败：${e.message}"
            }
        }
    }

    /**
     * 新建干净会话（M3.6 反馈①：列表入口，可选 agent）：sessions.create 开全新
     * surface 键（agent 状态在网关侧保留，会话从零开始）。完成后经 [navigationEvents]
     * 通知 Nav 直达新会话页（真机反馈⑤-1：不再留在入口页）。
     * [creatingSession] 供抽屉行显示在途态并防双击（真机反馈⑥-1：静默失败不可见）。
     */
    /** 状态栏回顶 Phase-1 诊断开关（iosMain 经 AGENTREINS_DEV_SCROLLPROBE=1 注入）：会话页顶栏出 scrollToItem(0) 测试按钮。 */
    var devScrollProbe by mutableStateOf(false)

    var creatingSession by mutableStateOf(false)
        private set

    fun createNewSession(agentId: String, stayInConversation: Boolean = false) {
        if (creatingSession) return
        val c = client ?: run { lastError = "未连接网关，无法新建会话"; return }
        val ts = epochMs()
        val newKey = "agent:$agentId:ar-${ts.toString(36)}"
        creatingSession = true
        scope.launch {
            try {
                try {
                    c.createSession(newKey, label = newSessionLabel(ts))
                } catch (e: GatewayRpcException) {
                    // 网关对会话 label 全表唯一（INVALID_REQUEST "label already in use"）——
                    // 固定文案第二单起必撞；同一秒二单的残余撞车补毫秒尾重试一次。
                    if (e.code != "INVALID_REQUEST" || e.message?.contains("label already in use") != true) throw e
                    c.createSession(newKey, label = "${newSessionLabel(ts)}·${ts % 1000}")
                }
                clearQuote()
                c.refreshSessions()
                sessions.firstOrNull { it.id == newKey }?.let { select(it) }
                    ?: run {
                        selectedSession = newKey
                        openConversation(c, newKey)
                    }
                // conversation 内新开：不跳路由——时间线经 refreshConvSections 追加分区，
                // 输入锚定随 select 切到新主 session（M4 需求 D3-①）
                if (stayInConversation) return@launch
                // 导航经事件流（主线程消费），不再从 Default 协程直调 navigate（毁导航栈）
                if (!navigationEvents.tryEmit(newKey)) {
                    lastError = "会话已创建，但跳转失败——请从列表进入"
                }
            } catch (e: Exception) {
                lastError = "新建会话失败：${e.message}"
            } finally {
                creatingSession = false
            }
        }
    }

    // ---- M4 P4：对话内操作（工具栏动作列表三动作） ----

    /** 压缩标记（sessionKey → 时刻）：时间线在该区尾渲染「已压缩上下文」。 */
    var compactMarks by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** 对话内新开主 session（工具栏动作①）：不跳路由，同屏追加分区头。 */
    fun createSessionInConversation() {
        val agentId = convAgentId ?: return
        createNewSession(agentId, stayInConversation = true)
    }

    /** 压缩当前主 session 上下文（工具栏动作②；admin scope 不足时诚实报错）。 */
    fun compactContext() {
        val key = selectedSession ?: return
        val c = client ?: run { lastError = "未连接网关，无法压缩上下文"; return }
        scope.launch {
            try {
                c.compact(key)
                compactMarks = compactMarks + (key to epochMs())
            } catch (e: GatewayRpcException) {
                lastError = if (e.code == "FORBIDDEN" || e.message?.contains("scope") == true) {
                    "压缩上下文需要网关 admin 权限，当前连接不支持（协议 scope 限制）"
                } else {
                    "压缩失败：${e.message}"
                }
            } catch (e: Exception) {
                lastError = "压缩失败：${e.message}"
            }
        }
    }

    /** 当前 agent 状态指令（工具栏动作③）：预置 prompt 按普通消息发入当前主 session。 */
    fun sendAgentStatusPrompt() {
        val key = selectedSession ?: return
        sendText(key, AGENT_STATUS_PROMPT)
    }

    /** 新会话列表标题 `新会话 MM-dd HH:mm:ss`：网关不自动起标题，缺 label 时列表落到 key 尾段。 */
    private fun newSessionLabel(ts: Long): String {
        val t = Instant.fromEpochMilliseconds(ts).toLocalDateTime(TimeZone.currentSystemDefault())
        fun p(n: Int) = n.toString().padStart(2, '0')
        return "新会话 ${p(t.monthNumber)}-${p(t.dayOfMonth)} ${p(t.hour)}:${p(t.minute)}:${p(t.second)}"
    }

    /**
     * 从头开始（重置语义，Grok Bot 对齐）：会话历史留在原会话，同一 agent 开一条全新
     * surface 键。agent 状态（指令/记忆在网关侧）保留。
     */
    fun startFresh() {
        val key = selectedSession ?: return
        val agentId = key.removePrefix("agent:").substringBefore(':')
        createNewSession(agentId)
    }

    /**
     * 一键已读（M3.6 反馈③）：全部未读会话打本地已读标记（overlay 持久化，跨重启）。
     * **同时清 client 侧 localUnread**（真机反馈⑤-1）：gateway refresh 会把 localUnread
     * 回写列表，只写 overlay 不清计数 → 已读会话再次刷出未读。
     */
    fun markAllRead() {
        val targets = sessions.filter { it.unread > 0 }
        if (targets.isEmpty()) return
        val now = epochMs()
        targets.forEach {
            sessionOverlay = SessionOverlay.toggled(sessionOverlay, it.id, readMarkMs = now)
        }
        scheduleOverlayPersist()
        publishSessions()
        client?.let { c -> targets.forEach { c.clearUnread(it.id) } }
    }

    // ---- M3.6：服务端搜索（sessions.search）+ 直达消费 ----

    /**
     * 导航事件（真机反馈⑤-1）：createNewSession 的完成回调原本在 Default 协程里直接调
     * navController.navigate——Navigation 非线程安全，跨线程调用会毁掉导航栈（列表可滑
     * 但 navigate 全部失效）。改为事件流，Nav 层在组合协程（主线程）消费后导航。
     */
    val navigationEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** 该命令是否仍在队列（未被用户删除）。 */
    /** UI 自增、结果协程比对（review R1-P2-1：跨线程普通 var 无可见性保证）。 */
    @Volatile
    private var searchSeq = 0

    /**
     * 服务端消息全文搜索（sessions.search，M3.6 探针证实 {query,limit} 可用）。
     * 结果只在高频词连续请求的最后一次生效（seq 防乱序回填）；失败静默清空——
     * 搜索页同时有客户端会话过滤结果，服务端失败不该打断主路径。
     */
    fun searchServer(query: String) {
        val q = query.trim()
        val c = client
        if (q.length < 2 || c == null) {
            searchSeq++
            searchHits = emptyList()
            searching = false
            return
        }
        val seq = ++searchSeq
        searching = true
        searchHits = emptyList() // 新查询先清旧命中，RTT 窗口内不展示陈旧片段（review R3-P2-6）
        scope.launch {
            try {
                val hits = c.searchSessions(q)
                if (seq == searchSeq) searchHits = hits
            } catch (e: Exception) {
                if (seq == searchSeq) searchHits = emptyList()
            } finally {
                if (seq == searchSeq) searching = false
            }
        }
    }

    /** Nav 层消费完直达跳转后清除，防返回列表时被再次吸进会话。 */
    fun consumeAutoOpen() {
        autoOpenKey = null
    }

    fun disconnect() {
        explicitDisconnect = true
        connectedUrl = null
        resetAutoRetry() // 用户主动断开不自动重连
        connectChimed = false // 下次手动重连再给一声 BLIP
        scope.launch { client?.disconnect() }
        selectedSession = null
        conversation = null
        conversationJob?.cancel()
        conversationJob = null
        // sessions 保留缓存列表（M2.5：断开后仍可离线浏览上次列表）
    }

    /** 草稿持久化：500ms 去抖写 kv。**调度时捕获值快照**——job 触发时才读状态会把
     *  切换后的新会话草稿写进旧会话槽位（P1 竞态）；配合 select() 的切换前冲刷双保险。 */
    private fun scheduleDraftPersist() {
        val key = selectedSession ?: return
        val snapshot = draftState.value
        draftPersistJob?.cancel()
        draftPersistJob = scope.launch {
            delay(500)
            runCatching { cache.kvPut(gatewayId, draftKey(key), snapshot) }
        }
    }

    private fun draftKey(sessionKey: String) = "draft:$sessionKey"

    private companion object {
        /** session overlay（pinned/archived 本地状态）的 kv 槽位（M2.6）。 */
        const val OVERLAY_KEY = "sessionOverlay:v1"

        /** conversation 级 overlay 的 kv 槽位（M4 P2）。 */
        const val CONV_OVERLAY_KEY = "conversationOverlay:v1"

        /** 「当前 agent 状态」（M4 P4 工具栏动作③）：**任务指令**（/ 开头，真机反馈③——
         *  不是自然语言提示词），由网关侧 agent 的命令通道处理。 */
        const val AGENT_STATUS_PROMPT = "/status"

        /** 外观偏好 kv 槽位（M3.1）。 */
        const val THEME_KEY = "appearance:v1"

        /** 审批面板开关 kv 槽位（M3.5）。 */
        const val APPROVALS_KEY = "approvalsPanel:v1"

        /** 反馈偏好 kv 槽位（M3.6）：`h=1|0;snd=1|0;s=<AppSound>|none`。 */
        const val FEEDBACK_KEY = "feedback:v1"

        /** Onboarding 完成标记 kv 槽位（M3.6）。 */
        const val ONBOARDING_KEY = "onboarding:v1"

        /** 「主会话」键（直达目标；网关无该键时回落列表首个）。 */
        const val MAIN_SESSION_KEY = "agent:main:main"

        /** 自动连接退避重试上限（2s→4s→8s→16s→32s，约 1 分钟窗口）。 */
        const val AUTO_RETRY_MAX_ATTEMPTS = 5

        /** 非当前会话通知音防抖窗口（review R1-P1-3）。 */
        const val SOUND_DEBOUNCE_MS = 3_000L

        /** failover 单候选快速失败超时（真机反馈⑦-4）：不可达地址 3.5s 判死轮换。 */
        const val FAST_CANDIDATE_TIMEOUT_MS = 3_500L

        /** failover 末位候选预算：须覆盖「握手 + 恢复上次会话 + 历史回填」全程——
         *  预算掐在恢复动作上会把刚连好的连接拆掉（连接成功边缘被杀=永远连不上的
         *  真机反馈⑧-1 根因），给足 30s。 */
        const val SLOW_CANDIDATE_TIMEOUT_MS = 30_000L

        /** bootstrap 配对候选预算：网关侧等人工批准 150s + 余量（R 轮 P0 豁免）。 */
        const val PAIRING_CANDIDATE_TIMEOUT_MS = 170_000L
    }
}
