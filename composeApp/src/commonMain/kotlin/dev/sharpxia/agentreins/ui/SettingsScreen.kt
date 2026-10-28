package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.ui.components.SandCard
import dev.sharpxia.agentreins.ui.components.SectionHeader
import dev.sharpxia.agentreins.ui.components.SandRow
import dev.sharpxia.agentreins.ui.components.SandDivider
import dev.sharpxia.agentreins.ui.components.SandToggleRow
import dev.sharpxia.agentreins.ui.theme.InkTheme
import dev.sharpxia.agentreins.ui.theme.ThemeMode

/** 设置 tab（M3.1 首版：连接/外观/调试令牌页入口；M3.6 完整化：提示音子页 + 触感开关）。 */
@Composable
fun SettingsScreen(
    model: AppModel,
    onOpenConnection: () -> Unit,
    onOpenThemeDemo: () -> Unit,
    onOpenSounds: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Text(
            "设置",
            style = InkTheme.type.headingMd,
            color = InkTheme.colors.ink,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
        )
        SectionHeader("连接")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            val st = model.status
            SandRow(
                title = when (st) {
                    is GatewayStatus.Connected -> "已连接"
                    is GatewayStatus.Connecting -> "连接中…"
                    is GatewayStatus.PairingPending -> "等待设备批准"
                    is GatewayStatus.Error -> "连接错误"
                    GatewayStatus.Disconnected -> "未连接"
                },
                subtitle = model.connectedUrl ?: model.url.ifBlank { "未配置网关地址" },
                onClick = onOpenConnection,
            )
        }
        SectionHeader("外观")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            ThemeMode.entries.forEachIndexed { index, mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { model.applyThemeMode(mode) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    RadioButton(
                        selected = model.themeMode == mode,
                        onClick = { model.applyThemeMode(mode) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = InkTheme.colors.ink,
                            unselectedColor = InkTheme.colors.textTertiary,
                        ),
                    )
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        },
                        style = InkTheme.type.textLg,
                        color = InkTheme.colors.ink,
                    )
                }
                if (index < ThemeMode.entries.lastIndex) SandDivider(indent = 24)
            }
        }
        SectionHeader("提示音")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            SandRow(
                title = "通知音",
                subtitle = FeedbackPrefs.notificationSound.value?.label ?: "无",
                onClick = onOpenSounds,
            )
        }
        SectionHeader("触感")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            SandToggleRow(
                title = "触感反馈",
                subtitle = "发送、滑动操作与确认时的轻微振动（iOS）",
                checked = FeedbackPrefs.hapticsEnabled.value,
                onCheckedChange = { model.toggleHaptics(it) },
            )
        }
        SectionHeader("审批")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            SandToggleRow(
                title = "审批面板",
                subtitle = "开启后连接会申请 approvals 权限（网关 owner 需重新批准一次设备）",
                checked = model.approvalsEnabled,
                onCheckedChange = { model.toggleApprovals(it) },
            )
        }
        SectionHeader("调试")
        SandCard(Modifier.padding(horizontal = 16.dp)) {
            SandRow(
                title = "设计令牌",
                subtitle = "墨水系令牌与 Sand 组件样本页",
                onClick = onOpenThemeDemo,
            )
        }
        Spacer(Modifier.padding(24.dp))
        Text(
            "AgentReins · OpenClaw 移动端",
            style = InkTheme.type.textXs,
            color = InkTheme.colors.textDisabled,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
