package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.GatewayStatus

/** 连接配置页（M2.6 换肤）：状态点 + 分区表单，逻辑与 M1 保持一致。 */
@Composable
fun ConnectionScreen(model: AppModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .dismissKeyboardOnTap()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("连接 OpenClaw 网关", style = MaterialTheme.typography.titleLarge)

        StatusChip(model.status)

        OutlinedTextField(
            value = model.url,
            onValueChange = { model.url = it },
            label = { Text("网关地址") },
            placeholder = { Text("ws://<你的网关IP>:18789") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = model.apiKey,
            onValueChange = { model.apiKey = it },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        )
        if (model.hasPairedCredential) {
            Text(
                "本机已配对过设备，Key 留空也可直连",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = model.setupCode,
                onValueChange = { model.setupCode = it },
                label = { Text("Setup Code（扫码或粘贴 oc-pair://…）") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.weight(1f),
            )
            if (qrScanSupported) {
                TextButton(onClick = { launchQrScanner(model::onQrScan) }, enabled = !model.busy) {
                    Text("扫码")
                }
            }
        }
        Text(
            "在网关机器执行 openclaw qr 生成配对二维码，扫码即连；若网关要求确认，批准后自动续连",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val canConnect = model.setupCode.isNotBlank() ||
            (model.url.isNotBlank() && (model.apiKey.isNotBlank() || model.hasPairedCredential))
        val connected = model.status is GatewayStatus.Connected
        Button(
            onClick = { model.connect() },
            enabled = canConnect && !model.busy && !connected &&
                model.status !is GatewayStatus.Connecting,
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onSurface,
                contentColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    model.status is GatewayStatus.PairingPending -> "等待批准…"
                    model.busy -> "连接中…"
                    model.setupCode.isNotBlank() -> "扫码配对"
                    else -> "连接"
                },
            )
        }

        if (connected) {
            // 连接来源标签（remote-access-design.md §6 backlog）：让用户看出走的哪条路
            model.connectedUrl?.let { u ->
                Text(
                    "${connectionSourceLabel(u)} · $u",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = { model.disconnect() },
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("断开")
            }
        }

        model.lastError?.let {
            Surface(
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        (model.status as? GatewayStatus.PairingPending)?.let { pending ->
            Text(
                "等待 owner 批准设备配对" +
                    (pending.requestId?.let { "（requestId=$it）" } ?: "") +
                    "——在网关机器执行 openclaw devices approve，批准后自动继续连接",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "v1.0 连接界面仅支持 OpenClaw 网关；多后端适配层（AgentClient）已预留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 状态徽标：色点 + 文案（明暗两版取 M3 语义色）。 */
@Composable
private fun StatusChip(status: GatewayStatus) {
    val (dotColor, label) = when (status) {
        is GatewayStatus.Disconnected -> MaterialTheme.colorScheme.onSurfaceVariant to "未连接"
        is GatewayStatus.Connecting -> MaterialTheme.colorScheme.tertiary to "连接中…"
        is GatewayStatus.PairingPending -> MaterialTheme.colorScheme.tertiary to "等待批准…"
        is GatewayStatus.Connected -> MaterialTheme.colorScheme.primary to
            "已连接" + (status.gatewayName?.let { " · $it" } ?: "")
        is GatewayStatus.Error -> MaterialTheme.colorScheme.error to
            "连接失败" + (status.message?.let { "：$it" } ?: "")
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Surface(color = dotColor, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 地址来源标签：ws=局域网明文（家庭网段既有边界），wss=远程 TLS（隧道/边缘路径）。 */
internal fun connectionSourceLabel(url: String): String = when {
    url.startsWith("wss://") -> "远程（TLS）"
    url.startsWith("ws://") -> "局域网（明文）"
    else -> "自定义"
}
