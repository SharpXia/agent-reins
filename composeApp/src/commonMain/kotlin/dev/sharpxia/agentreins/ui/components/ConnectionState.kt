package dev.sharpxia.agentreins.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.ui.theme.InkTheme

/** 连接状态空态：连接中/等待批准/失败/未连接四态（原 SessionsScreen.EmptyState 公共化）。 */
@Composable
fun ConnectionEmptyState(model: AppModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val c = InkTheme.colors
        val t = InkTheme.type
        when (val st = model.status) {
            is GatewayStatus.Connecting -> {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = c.ink)
                Spacer(Modifier.size(12.dp))
                Text("连接中…", style = t.headingXs, color = c.ink)
            }
            is GatewayStatus.PairingPending -> {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = c.ink)
                Spacer(Modifier.size(12.dp))
                Text("等待 owner 批准设备…", style = t.headingXs, color = c.ink)
                st.requestId?.let {
                    Spacer(Modifier.size(4.dp))
                    Text("requestId: $it", style = t.textSm, color = c.textTertiary)
                }
            }
            else -> {
                if (st is GatewayStatus.Error) {
                    Text("自动连接失败", style = t.headingXs, color = c.ink)
                    Spacer(Modifier.size(4.dp))
                    Text(st.message ?: "未知错误", style = t.textSm, color = c.danger.text, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(4.dp))
                    Text("回前台或到「设置 → 连接」重试", style = t.textSm, color = c.textTertiary)
                } else {
                    Text("未连接网关", style = t.headingXs, color = c.ink)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "到「设置 → 连接」扫码配对或填入地址与 API Key",
                        style = t.textMd,
                        color = c.textTertiary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
