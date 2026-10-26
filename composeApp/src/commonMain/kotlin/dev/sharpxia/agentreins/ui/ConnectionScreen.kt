package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.GatewayStatus

/** 连接配置页：扫码配对（openclaw qr）+ 手动 token，均走 device pairing。 */
@Composable
fun ConnectionScreen(model: AppModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().dismissKeyboardOnTap().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("连接 OpenClaw 网关", style = MaterialTheme.typography.titleLarge)

        AssistChip(onClick = {}, label = { Text(statusLabel(model.status)) })

        OutlinedTextField(
            value = model.url,
            onValueChange = { model.url = it },
            label = { Text("网关地址") },
            placeholder = { Text("ws://<你的网关IP>:18789") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = model.apiKey,
            onValueChange = { model.apiKey = it },
            label = { Text("API Key") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (model.hasPairedCredential) {
            Text(
                "本机已配对过设备，Key 留空也可直连",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = model.setupCode,
                onValueChange = { model.setupCode = it },
                label = { Text("Setup Code（扫码或粘贴 oc-pair://…）") },
                singleLine = true,
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
        Button(
            onClick = { model.connect() },
            enabled = canConnect && !model.busy && model.status !is GatewayStatus.Connecting &&
                model.status !is GatewayStatus.Connected,
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

        if (model.status is GatewayStatus.Connected) {
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("断开")
            }
        }

        model.lastError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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

internal fun statusLabel(status: GatewayStatus): String = when (status) {
    is GatewayStatus.Disconnected -> "未连接"
    is GatewayStatus.Connecting -> "连接中…"
    is GatewayStatus.PairingPending -> "等待批准…"
    is GatewayStatus.Connected -> "已连接" + (status.gatewayName?.let { " · $it" } ?: "")
    is GatewayStatus.Error -> "连接失败" + (status.message?.let { "：$it" } ?: "")
}

/** 地址来源标签：ws=局域网明文（家庭网段既有边界），wss=远程 TLS（VPS 隧道路径）。 */
internal fun connectionSourceLabel(url: String): String = when {
    url.startsWith("wss://") -> "远程（TLS）"
    url.startsWith("ws://") -> "局域网（明文）"
    else -> "自定义"
}
