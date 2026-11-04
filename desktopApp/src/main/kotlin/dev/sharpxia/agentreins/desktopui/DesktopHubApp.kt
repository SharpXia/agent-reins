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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 桌面 Hub 面根组件（wp-6；整体落 desktopApp，不动 composeApp commonMain 的 App()——
 * App() 同时被 androidApp MainActivity setContent 渲染，Android 无任何 Hub 接线）。
 *
 * 组装（全部复用 shared 门面，平台无关）：DefaultHubClient.create（HubRuntime + 投影库 +
 * 平台凭据存储）→ 启动静默恢复（有 refreshToken 时）→ LoggedIn 后 start() 组装同步栈；
 * 登出 = stop → runtime.logout → wipeLocalData（验收⑤清库闭环）。
 */
@Composable
fun DesktopHubApp() {
    // 数据面 scope：与窗口同生命周期（合成器销毁即取消）；全在 Dispatchers.Default，不占 UI 线程
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    DisposableEffect(Unit) { onDispose { scope.cancel() } }
    val client = remember { DefaultHubClient.create(scope) }
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
        if (authState is HubAuthState.NeedsLogin || authState is HubAuthState.LoggedIn) submitting = false
    }

    MaterialTheme {
        when {
            authState is HubAuthState.LoggedIn -> {
                HubShell(client, authState as HubAuthState.LoggedIn)
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

private enum class HubPane { Conversations, Settings }

/** 已登录主壳：左栏（账号头 + 会话列表 + 导航）+ 右内容区（会话页 / 设置页）。 */
@Composable
private fun HubShell(client: DefaultHubClient, session: HubAuthState.LoggedIn) {
    val conversations by client.observeConversations().collectAsState(initial = emptyList())
    var selectedId by remember { mutableStateOf<String?>(null) }
    var pane by remember { mutableStateOf(HubPane.Conversations) }

    Row(modifier = Modifier.fillMaxSize()) {
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
                modifier = Modifier.weight(1f),
            )
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    TextButton(onClick = { pane = HubPane.Conversations }) {
                        Text(if (pane == HubPane.Conversations) "会话 ●" else "会话")
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
                HubPane.Settings -> SettingsScreen(client = client, session = session, onLoggedOut = {})
                HubPane.Conversations -> {
                    val selected = selectedId?.let { id -> conversations.firstOrNull { it.conversationId == id } }
                    if (selected != null) {
                        key(selected.conversationId) {
                            ConversationScreen(client, selected.conversationId, selected.label)
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
