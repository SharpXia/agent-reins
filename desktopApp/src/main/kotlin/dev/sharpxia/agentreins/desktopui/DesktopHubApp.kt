package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.HubAuthState
import dev.sharpxia.agentreins.hub.HubRuntime
import dev.sharpxia.agentreins.hub.auth.platformCredentialStore
import dev.sharpxia.agentreins.hub.db.createHubDriver
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hub.sync.SyncPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 桌面 Hub 面根组件（wp-6 壳 + wp-10 交互 parity + wp-18 渲染/通知；整体落 desktopApp，
 * 不动 composeApp commonMain 的 App()——App() 同时被 androidApp MainActivity setContent
 * 渲染，Android 无任何 Hub 接线）。
 *
 * 组装（全部复用 shared 门面，平台无关）：HubRuntime + 投影库 + 平台凭据存储 → 启动静默
 * 恢复（有 refreshToken 时）→ LoggedIn 后 start() 组装同步栈；登出 = stop → runtime.logout
 * → wipeLocalData（验收⑤清库闭环）。
 * wp-10：待审批 Flow 在壳层单点订阅（对齐 iOS HubStore.watchApprovals），审批决策瞬态
 * （[ApprovalDecisionState]）壳层持有、随行离场收敛清理，会话页/设置页共用同一份。
 * wp-18：客户端按 eventTap 构造（iOS AgentBridge 同模式）——通知协调器消费 shared 事件流
 * （审批/run 终态走事件旁路、回复走会话列表 Flow 差异面），助手行走 [MarkdownText]。
 * wp-14：关窗行为偏好（[DesktopWindowPreferences]）由 main.kt 创建传入（同一实例喂窗口
 * onCloseRequest 与设置页开关）；缺省 null = 不挂「关闭行为」卡（旧装配兼容）。
 */
@Composable
internal fun DesktopHubApp(windowPrefs: DesktopWindowPreferences? = null) {
    // 数据面 scope：与窗口同生命周期（合成器销毁即取消）；全在 Dispatchers.Default，不占 UI 线程
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    // wp-18：库与客户端同构展开（等价 companion create），保留库引用 + eventTap——与 iOS
    // AgentBridge（DefaultHubClient(scope, hubRuntime, hubDb, eventTap = ::onBridgeEvent)）
    // 同模式：库引用供通知面 hubConvKeys 直查，eventTap 为 wp-13 建的 shared 事件旁路面。
    val hubDb = remember { openHubDatabase(createHubDriver()) }
    // wp-9：按会话静音 + 通知总开关本地偏好（用户级持久化，跨登出/重启保留；Preferences
    // 不可用时降级为纯内存态，不崩）。lambda 注入 = 协调器决策时实时读取，右键/设置页
    // 改动即时生效，无重建协调器。
    val mutePrefs = remember { DesktopMutePreferences() }
    val coordinator = remember(hubDb) {
        DesktopNotificationCoordinator(
            conversationKeyResolver = { gatewayId, sessionKey ->
                hubDb?.hubQueries?.selectConversationByKey(gatewayId, sessionKey)
                    ?.executeAsOneOrNull()?.conversationId.orEmpty()
            },
            mutedConversationIds = { mutePrefs.mutedConversationIds() },
            notificationsEnabled = { mutePrefs.notificationsEnabled },
        ).also { it.start(scope) }
    }
    val client = remember(hubDb) {
        DefaultHubClient(
            scope,
            HubRuntime(scope, platformCredentialStore()),
            hubDb,
            eventTap = { envelope -> coordinator.onEventEnvelope(envelope) },
        )
    }
    // wp-4 修法二发起方门（两端协调器同口径 = RunOriginRegistry.isRunLocal 消费）：lambda 在
    // 通知时刻解引用 client.runOriginRegistry——stop→重登 start() 重建登记表后仍取新实例，
    // 不捕获旧引用。registry 缺省（未 start）时事件本就不流动，此处放行为兼容回退。
    coordinator.runOriginGate = { gatewayId, runId ->
        client.runOriginRegistry?.isRunLocal(gatewayId, runId) ?: true
    }
    val authState by client.runtime.state.collectAsState()

    // 启动静默恢复：入口地址 = HUB_BASE 环境变量 > 上次登录记忆；无地址/无凭据 → 登录页
    var restoreAttempted by remember { mutableStateOf(false) }
    LaunchedEffect(client) {
        val base = HubEnv.resolveBaseUrl()
        if (base != null) client.runtime.restore(base)
        restoreAttempted = true
    }

    // 登录就绪 → 组装同步栈（start 幂等；登出 stop 后重登会重新组装并从空库 bootstrap）
    val loggedIn = authState is HubAuthState.LoggedIn
    LaunchedEffect(loggedIn) {
        if (loggedIn) client.start()
    }

    // 登录表单提交中的本地 busy 标记（runtime.login 期间 state=Working，表单保持可见）
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(authState) {
        // wp-18：登录态离场（登出/凭据失效）重置通知面 unread 基线与抑制窗（清库后旧基线不复用）
        if (authState !is HubAuthState.LoggedIn) coordinator.reset()
        if (authState is HubAuthState.NeedsLogin || authState is HubAuthState.LoggedIn) submitting = false
    }

    MaterialTheme {
        when {
            authState is HubAuthState.LoggedIn -> {
                HubShell(client, authState as HubAuthState.LoggedIn, coordinator, mutePrefs, windowPrefs)
            }
            authState is HubAuthState.NeedsLogin ||
                (authState is HubAuthState.Idle && restoreAttempted) || submitting -> {
                var loginBase by remember { mutableStateOf(HubEnv.resolveBaseUrl() ?: "") }
                LoginScreen(
                    initialBaseUrl = loginBase,
                    busy = submitting,
                    errorMessage = (authState as? HubAuthState.NeedsLogin)?.reason,
                    onSubmitted = { base, username, password, deviceName ->
                        submitting = true
                        loginBase = base
                        HubEnv.rememberBaseUrl(base)
                        client.runtime.login(base, username, password, deviceName)
                    },
                )
            }
            else -> BootLoadingScreen()
        }
    }
}

/** 恢复/登录在途占位。 */
@Composable
private fun BootLoadingScreen() {
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Text("正在恢复会话…", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF888888))
        }
    }
}

private enum class HubPane { Conversations, Gateways, Settings }

/** 已登录主壳：左栏（账号头 + 会话列表 + 导航）+ 右内容区（会话页 / 设置页）。
 *  wp-7：会话操作面（列表右键重命名/压缩 + 新建会话入口）在 [ConversationListPanel] 内自持
 *  瞬态并直调 shared v1.1 门面，壳层只传 client，不承接会话操作状态。
 *  wp-14：关窗行为偏好（[DesktopWindowPreferences] 内存镜像权威，UI 镜像只喂设置页开关，
 *  窗口 onCloseRequest 决策在 main.kt 实时读权威值）。 */
@Composable
private fun HubShell(
    client: DefaultHubClient,
    session: HubAuthState.LoggedIn,
    coordinator: DesktopNotificationCoordinator,
    mutePrefs: DesktopMutePreferences,
    windowPrefs: DesktopWindowPreferences?,
) {
    val conversations by client.observeConversations().collectAsState(initial = emptyList())
    // wp-10：待审批单点订阅 + 决策瞬态（壳层持有跨面板存活；行离场 approval.resolved 时收敛清理）
    val pendingApprovals by client.observePendingApprovals().collectAsState(initial = emptyList())
    val decisions = remember { ApprovalDecisionState() }
    LaunchedEffect(pendingApprovals) { decisions.retainAll(pendingApprovals.map { it.approvalId }) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var pane by remember { mutableStateOf(HubPane.Conversations) }
    // wp-9：静音态 UI 镜像（内存镜像读出，供右键菜单/设置页响应式呈现；权威在
    // mutePrefs——协调器经 lambda 实时读取它，这里的镜像只喂 UI）
    var mutedIds by remember { mutableStateOf(mutePrefs.mutedConversationIds()) }
    var notificationsEnabled by remember { mutableStateOf(mutePrefs.notificationsEnabled) }
    fun setMuted(conversationId: String, muted: Boolean) {
        mutePrefs.setMuted(muted, conversationId)
        mutedIds = mutePrefs.mutedConversationIds()
    }
    fun setNotificationsEnabled(enabled: Boolean) {
        mutePrefs.setNotificationsEnabled(enabled)
        notificationsEnabled = mutePrefs.notificationsEnabled
    }
    // wp-14：关窗行为 UI 镜像（权威在 windowPrefs——main.kt onCloseRequest 决策实时读它，
    // 这里的镜像只喂设置页开关；windowPrefs = null（旧装配）不挂卡）。
    var minimizeToTrayOnClose by remember { mutableStateOf(windowPrefs?.minimizeToTrayOnClose ?: true) }
    fun setMinimizeToTrayOnClose(enabled: Boolean) {
        windowPrefs?.setMinimizeToTrayOnClose(enabled)
        minimizeToTrayOnClose = windowPrefs?.minimizeToTrayOnClose ?: enabled
    }
    // wp-18 通知规则数据面：会话列表推送喂回复差异面（unread 增量）；当前打开会话供
    // 「非当前打开」判定（iOS HubStore.selected 同口径）。规则态由协调器消费协程独占，
    // 壳层只投递，不在组合线程做判定。
    LaunchedEffect(conversations) { coordinator.pushConversations(conversations) }
    LaunchedEffect(selectedId) { coordinator.openConversationId = selectedId }

    // wp-10：连接相位观察面（断连/重建/接管提示条数据源）。syncPhase 为客户端级稳定流
    // （DefaultHubClient 注入 phaseSink），scheduler start/stop 重建不换源。
    val syncPhase by client.syncPhase.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        if (syncPhase != SyncPhase.Linked) {
            SyncPhaseBanner(syncPhase)
        }
        Row(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.width(340.dp).fillMaxHeight()) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text("AgentReins Hub", style = MaterialTheme.typography.titleSmall)
                        Text(
                            session.userDisplay ?: "已登录（静默恢复）",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF777777),
                        )
                        MonoText("设备 ${session.deviceId ?: "—"}", modifier = Modifier.padding(top = 2.dp))
                    }
                }
                ConversationListPanel(
                    conversations = conversations,
                    selectedId = selectedId.takeIf { pane == HubPane.Conversations },
                    onSelect = { selectedId = it; pane = HubPane.Conversations },
                    // wp-7：会话操作面（右键重命名/压缩 + 新建会话入口）直接消费 shared 门面
                    // （createConversation/renameConversation/compactConversation，
                    // HubClient.kt v1.1 门面段）；createConversation 自动携带 mainSession=true
                    client = client,
                    mutedIds = mutedIds,
                    onToggleMute = ::setMuted,
                    modifier = Modifier.weight(1f),
                )
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                        TextButton(onClick = { pane = HubPane.Conversations }) {
                            Text(if (pane == HubPane.Conversations) "会话 ●" else "会话")
                        }
                        // wp-11：网关与 Agent 独立页入口（iOS 设置页「网关与 Agent」NavigationLink 同面，
                        // 桌面以 pane 切换承载导航）
                        TextButton(onClick = { pane = HubPane.Gateways }) {
                            Text(if (pane == HubPane.Gateways) "网关 ●" else "网关")
                        }
                        TextButton(onClick = { pane = HubPane.Settings }) {
                            Text(if (pane == HubPane.Settings) "设置 ●" else "设置")
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (pane) {
                    HubPane.Settings -> SettingsScreen(
                        client = client,
                        session = session,
                        onLoggedOut = {},
                        // wp-11：设置页「网关与 Agent」卡点击 → 网关独立页（iOS NavigationLink 同语义）
                        onOpenGateways = { pane = HubPane.Gateways },
                        pendingApprovals = pendingApprovals,
                        conversations = conversations,
                        decisions = decisions,
                        notificationsEnabled = notificationsEnabled,
                        onNotificationsEnabled = ::setNotificationsEnabled,
                        mutedIds = mutedIds,
                        onUnmute = { id -> setMuted(id, false) },
                        // wp-14：关闭行为开关（缺省关窗最小化到托盘；windowPrefs = null 不挂卡）
                        minimizeToTrayOnClose = minimizeToTrayOnClose,
                        onMinimizeToTrayOnClose = ::setMinimizeToTrayOnClose.takeIf { windowPrefs != null },
                    )
                    // wp-11：网关与 Agent 独立页（observeGateways 零消费 → 结构化清单呈现）
                    HubPane.Gateways -> GatewaysPanel(client)
                    HubPane.Conversations -> {
                        val selected = selectedId?.let { id -> conversations.firstOrNull { it.conversationId == id } }
                        if (selected != null) {
                            key(selected.conversationId) {
                                ConversationScreen(
                                    client = client,
                                    conversationId = selected.conversationId,
                                    label = selected.label,
                                    conversations = conversations,
                                    // wp-9：只挂归属本会话的 pending 审批；归属未学习（null）的审批不得
                                    // 错挂进任意会话页，只在设置页待审批清单出现
                                    approvals = pendingApprovals.filter { it.conversationId == selected.conversationId },
                                    decisions = decisions,
                                )
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                EmptyHint(
                                    "选择左侧会话开始",
                                    "会话与消息来自 Hub 投影（快照 + 事件流 + R4 校准），本端不发明状态",
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * wp-10：连接状态提示条（桌面端，挂 HubShell 顶部跨全宽）。非 Linked 相位常驻显示，
 * 追平（Linked）自动消失——验收③「断网出现提示条、恢复后自动消失；410 重建期间显示重建态」。
 * 文案与 iOS HubSyncPhaseBanner 同族（两端体验 parity）：Catching = 引导/追平在途（灰面弱
 * 提示）；Reconnecting/Rebuilding/Superseded = 连接降级（琥珀警示面）。
 */
@Composable
private fun SyncPhaseBanner(phase: SyncPhase) {
    val degraded = phase != SyncPhase.Catching
    val text = when (phase) {
        SyncPhase.Catching -> "正在同步…"
        SyncPhase.Reconnecting -> "连接已断开，正在重连…"
        SyncPhase.Rebuilding -> "同步游标已失效，正在重建本地快照…"
        SyncPhase.Superseded -> "连接已被本机新会话接管，等待恢复…"
        SyncPhase.Linked -> return
    }
    Surface(color = if (degraded) Color(0xFFB45309) else Color(0xFFE0E0E0)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = if (degraded) Color.White else Color(0xFF555555),
            )
        }
    }
}
