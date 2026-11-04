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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.sharpxia.agentreins.hub.auth.DeviceInfo
import kotlinx.coroutines.launch

/**
 * 设置页（wp-6 验收⑤）：账号/设备概览 + 设备清单管理（撤销他机）+ 登出清库闭环
 * （stop → runtime.logout（撤销设备 + 清凭据）→ wipeLocalData 七表清空；
 * 重登后从空库 bootstrap，05 §3.5 / 方案 §6-P4）。
 */
@Composable
internal fun SettingsScreen(
    client: DefaultHubClient,
    session: HubAuthState.LoggedIn,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyDeviceId by remember { mutableStateOf<String?>(null) }
    var logoutArmed by remember { mutableStateOf(false) }

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
                                                }
                                                busyDeviceId = null
                                                reloadDevices()
                                            }
                                        },
                                    ) { Text("撤销") }
                                }
                                if (busyDeviceId == d.deviceId) Text("处理中…")
                            }
                        }
                    }
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
