package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.HubAuthState
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.auth.DeviceInfo
import kotlinx.coroutines.launch

/**
 * 设置页（wp-6 验收⑤ + wp-10 审批清单落点 + wp-11 网关入口 + wp-9 通知偏好面 + wp-14 关闭行为
 * 面）：账号/设备概览
 * + 设备清单管理（撤销他机；清单失败错误态 + 撤销失败显式降级文案可重试，wp-11 设备管理错误态）
 * + 网关与 Agent 入口卡（wp-11：快照计数摘要，点击跳网关独立页——
 * iOS 设置页 Section("网关") NavigationLink parity）+ 待审批清单（wp-9 的 desktop 呈现：全部
 * pending 审批含归属未学习行——那里没有会话页可挂，唯一可见处在此；对齐 iOS 设置页「待审批」
 * 入口）+ 通知偏好节（wp-9：总开关 + 已静音会话清单管理，静音为本地偏好跨登出保留，iOS
 * HubStore.mutedConversations 同口径）+ 关闭行为卡（wp-14：关窗最小化到托盘 / 关窗退出，
 * 本地偏好跨登出保留，缺省关窗最小化；托盘「退出」菜单不受开关影响始终真退出）+
 * 登出清库闭环（stop → runtime.logout（撤销设备 + 清凭据）
 * → wipeLocalData 七表清空；重登后从空库 bootstrap，05 §3.5 / 方案 §6-P4；静音偏好不在清库面，
 * 跨登出保留）。
 */
@Composable
internal fun SettingsScreen(
    client: DefaultHubClient,
    session: HubAuthState.LoggedIn,
    onLoggedOut: () -> Unit,
    onOpenGateways: () -> Unit,
    pendingApprovals: List<UiApproval>,
    conversations: List<UiConversation>,
    decisions: ApprovalDecisionState,
    notificationsEnabled: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    mutedIds: Set<String>,
    onUnmute: (conversationId: String) -> Unit,
    // wp-14：关闭行为开关（onMinimizeToTrayOnClose = null = 不挂卡，旧装配兼容）
    minimizeToTrayOnClose: Boolean = true,
    onMinimizeToTrayOnClose: ((Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyDeviceId by remember { mutableStateOf<String?>(null) }
    // wp-11 设备管理错误态：撤销失败显式降级文案（此前 runCatching 吞错仅 reload 兜底，
    // 与同页审批卡「决策通道不可用」显式降级纪律不一致）。可重试 = 失败不清行、按钮恢复，再点「撤销」。
    var revokeError by remember { mutableStateOf<String?>(null) }
    var logoutArmed by remember { mutableStateOf(false) }
    // wp-11 网关入口计数摘要：observeGateways 单点订阅（快照 + gateway.online/offline 翻转）
    val gateways by client.observeGateways().collectAsState(initial = emptyList())

    fun describe(e: Throwable): String =
        (e as? HubException)?.let { "${it.payload.code} ${it.payload.message}" } ?: (e.message ?: e::class.simpleName ?: "未知错误")

    fun reloadDevices() {
        val stack = client.runtime.authStack ?: return
        scope.launch {
            runCatching { stack.auth.withBearer { stack.api.listDevices() } }
                .onSuccess { devices = it.devices; loadError = null }
                .onFailure { loadError = "设备清单加载失败：${describe(it)}" }
        }
    }

    LaunchedEffect(Unit) { reloadDevices() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("账号", style = MaterialTheme.typography.titleSmall)
                    Text("用户：${session.userDisplay ?: "（静默恢复会话）"}")
                    Text("本设备 ID：${session.deviceId ?: "未知"}")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设备清单", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { reloadDevices() }) { Text("刷新") }
                    }
                    when {
                        loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        devices == null -> Text("加载中…")
                        devices!!.isEmpty() -> Text("无设备")
                        else -> devices!!.forEach { d ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.displayName, style = MaterialTheme.typography.bodyMedium)
                                        if (d.current == true) {
                                            Spacer(Modifier.width(6.dp))
                                            TagChip("本机", MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    MonoText("${d.deviceId} · ${d.platform}")
                                }
                                if (d.current != true && busyDeviceId != d.deviceId) {
                                    TextButton(
                                        onClick = {
                                            val stack = client.runtime.authStack ?: return@TextButton
                                            busyDeviceId = d.deviceId
                                            scope.launch {
                                                runCatching {
                                                    stack.auth.withBearer { stack.api.deleteDevice(d.deviceId) }
                                                }.fold(
                                                    onSuccess = {
                                                        busyDeviceId = null
                                                        revokeError = null
                                                        reloadDevices()
                                                    },
                                                    onFailure = { e ->
                                                        busyDeviceId = null
                                                        revokeError = "设备撤销失败：${describe(e)}（可重试）"
                                                    },
                                                )
                                            }
                                        },
                                    ) { Text("撤销") }
                                }
                                if (busyDeviceId == d.deviceId) Text("处理中…")
                            }
                        }
                    }
                }
                // wp-11 设备管理错误态：撤销失败显式降级（重试 = 失败行仍在列，再点「撤销」）。
                if (revokeError != null) {
                    Text(
                        revokeError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // wp-11：网关与 Agent 入口卡（iOS 设置页 Section("网关") NavigationLink parity）——
            // 计数摘要随 observeGateways 推送（在线 x / 共 y；未接入呈占位），点击跳独立页
            GatewaysEntryCard(
                gateways = gateways,
                onOpen = onOpenGateways,
            )

            // wp-9：通知偏好节（总开关 + 已静音会话清单管理；静音改右键菜单入口同源，此处为
            // 查看与解除的唯一管理面——iOS 设置页静音清单管理 parity）
            NotificationPreferencesCard(
                notificationsEnabled = notificationsEnabled,
                onNotificationsEnabled = onNotificationsEnabled,
                mutedIds = mutedIds,
                conversations = conversations,
                onUnmute = onUnmute,
            )

            // wp-14：关闭行为卡（开 = 关窗最小化到托盘，进程与同步/通知面保持运行，托盘
            // 「显示主窗」唤回；关 = 关窗直接退出进程。托盘「退出」菜单始终真退出）。
            val closeBehaviorHandler = onMinimizeToTrayOnClose
            if (closeBehaviorHandler != null) {
                CloseBehaviorCard(
                    minimizeToTrayOnClose = minimizeToTrayOnClose,
                    onMinimizeToTrayOnClose = closeBehaviorHandler,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("待审批", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        if (pendingApprovals.isNotEmpty()) {
                            TagChip("${pendingApprovals.size} 项待处理", Color(0xFFB7791F))
                        }
                    }
                    PendingApprovalsPanel(
                        approvals = pendingApprovals,
                        conversations = conversations,
                        decisions = decisions,
                        client = client,
                        scope = scope,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("退出登录", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "登出将撤销本设备（服务端 Token 即时失效）并清空全部本地数据（命令队列/游标/消息），" +
                            "重登后从空库 bootstrap。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777777),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (!logoutArmed) {
                                    logoutArmed = true
                                    return@Button
                                }
                                logoutArmed = false
                                scope.launch {
                                    client.stop()
                                    client.runtime.logout() // 撤销设备 + 清凭据；state 翻转为 NeedsLogin
                                    client.wipeLocalData()
                                    onLoggedOut()
                                }
                            },
                        ) {
                            Text(if (logoutArmed) "确认登出并清库？" else "登出")
                        }
                        if (logoutArmed) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { logoutArmed = false }) { Text("取消") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * wp-14：关闭行为卡——窗口关闭按钮的行为开关（默认开 = 关窗最小化到系统托盘：进程与同步/
 * 通知面保持运行，托盘「显示主窗」可唤回；关 = 关窗直接退出进程）。行为是本地用户偏好
 * （DesktopWindowPreferences，跨登出保留，权威由 main.kt onCloseRequest 实时读取，开关
 * 即时生效）。托盘菜单的「退出」不受本开关影响，始终真正退出进程。
 */
@Composable
private fun CloseBehaviorCard(
    minimizeToTrayOnClose: Boolean,
    onMinimizeToTrayOnClose: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("关闭行为", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (minimizeToTrayOnClose) "最小化到托盘" else "直接退出",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (minimizeToTrayOnClose) Color(0xFF2E9E44) else Color(0xFF999999),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = minimizeToTrayOnClose, onCheckedChange = onMinimizeToTrayOnClose)
            }
            Text(
                "开启后点窗口关闭按钮会隐藏到系统托盘（后台同步与通知保持运行，托盘「显示主窗」" +
                    "可唤回，菜单「退出」真正退出）；关闭后关窗将直接退出应用。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF777777),
            )
        }
    }
}

/**
 * wp-9：通知偏好卡——总开关（关闭 = 本机不再弹任何通知，含审批提醒；开关即时生效，协调器
 * 经 DesktopMutePreferences 实时读取）+ 已静音会话清单（名称解析与协调器 displayLabel 同式：
 * label 空 → agentId → conversationId 兜底；解除静音即时生效）。静音为本地偏好，不在登出
 * 清库面（跨登出保留，iOS HubStore 同口径）。
 */
@Composable
private fun NotificationPreferencesCard(
    notificationsEnabled: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    mutedIds: Set<String>,
    conversations: List<UiConversation>,
    onUnmute: (conversationId: String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("通知", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (notificationsEnabled) "已开启" else "已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notificationsEnabled) Color(0xFF2E9E44) else Color(0xFF999999),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = notificationsEnabled, onCheckedChange = onNotificationsEnabled)
            }
            Text(
                "关闭后本机不再弹出任何本地通知（含审批提醒）。按会话静音在会话列表右键设置：" +
                    "静音的会话不再提醒新回复与运行终态，审批提醒不受静音影响。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF777777),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已静音会话", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                if (mutedIds.isNotEmpty()) TagChip("${mutedIds.size} 个", Color(0xFF777777))
            }
            if (mutedIds.isEmpty()) {
                Text(
                    "无静音会话（右键会话卡片可静音）",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            } else {
                mutedIds.forEach { id ->
                    val conv = conversations.firstOrNull { it.conversationId == id }
                    val label = conv?.let { it.label.ifBlank { it.agentId.ifBlank { id } } } ?: id
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { onUnmute(id) }) { Text("解除静音") }
                    }
                }
            }
        }
    }
}
