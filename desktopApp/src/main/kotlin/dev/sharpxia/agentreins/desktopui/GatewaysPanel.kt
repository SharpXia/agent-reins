package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.UiAgentRef
import dev.sharpxia.agentreins.hub.UiGateway

/**
 * wp-11：网关与 Agent 清单（独立页，nav 第三入口 + 设置页「网关与 Agent」卡入口；
 * iOS GatewaysView parity——快照 gateways + agentsJson 结构化行透出 + online 时点）。
 *
 * 数据面 = HubClient.observeGateways()：hubGateways 快照整体覆盖（wp-2 填充）+
 * gateway.online/offline 事件翻转维护的 online 时点，行随库变更推送，无本地刷新动作
 * （与设备清单的 REST 拉取不同源，不设「刷新」按钮）。agentsJson 已在门面解析为结构化
 * [UiAgentRef]（解析失败单行降级空清单、网关行本体不丢——HubClient.kt observeGateways 注）；
 * 「Agent 清单只取 active 行」由服务端 Gateways 口径保证，客户端原样透出不建关系。
 */
@Composable
internal fun GatewaysPanel(client: DefaultHubClient, modifier: Modifier = Modifier) {
    val gateways by client.observeGateways().collectAsState(initial = emptyList())
    if (gateways.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint(
                "暂无网关",
                "网关接入并完成首快照后自动列入此处",
            )
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Text("网关与 Agent", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(8.dp))
            gateways.forEach { gw -> GatewayCard(gw, Modifier.padding(vertical = 2.dp)) }
        }
    }
}

/** 网关卡：在线态圆点 + 名称（空名兜底「网关」）+ 在线/离线徽标 + id + 最近活跃 + Agent 清单。 */
@Composable
private fun GatewayCard(gw: UiGateway, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnlineDot(gw.online)
                Spacer(Modifier.width(8.dp))
                Text(
                    gw.name.ifBlank { "网关" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TagChip(
                    if (gw.online) "在线" else "离线",
                    if (gw.online) Color(0xFF2E9E44) else Color(0xFF999999),
                )
            }
            MonoText(gw.gatewayId, color = Color(0xFF999999))
            // 最近活跃：lastSeenAt = 快照字段或 gateway.online/offline 事件 ts（信封 ISO-8601），
            // 尽力格式化为本地时间、失败原样透出（formatTs 三形态兼容）
            gw.lastSeenAt?.takeIf { it.isNotBlank() }?.let { seen ->
                Text(
                    "最近活跃 ${formatTs(seen)}",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                )
            }
            if (gw.agents.isEmpty()) {
                Text(
                    "该网关暂无 active Agent",
                    fontSize = 12.sp,
                    color = Color(0xFFAAAAAA),
                )
            } else {
                gw.agents.forEach { agent -> AgentRefRow(agent) }
            }
        }
    }
}

/** Agent 只读行：label 缺省兜底 agentId（AgentRef 镜像原样透出，不建关系不发明状态）。 */
@Composable
private fun AgentRefRow(agent: UiAgentRef) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("👤", fontSize = 11.sp, color = Color(0xFF888888))
        Spacer(Modifier.width(6.dp))
        Text(
            agent.label.ifBlank { agent.agentId },
            fontSize = 13.sp,
        )
    }
}

/** 在线态圆点（iOS Circle().fill(gw.online ? green : secondary) 同语义）。 */
@Composable
private fun OnlineDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                if (online) Color(0xFF2E9E44) else Color(0xFF999999),
                CircleShape,
            ),
    )
}

/**
 * 设置页「网关与 Agent」入口卡（iOS HubSettingsView Section("网关") NavigationLink parity）：
 * 标题 + 在线计数摘要，点击跳转网关独立页（pane 由壳层切换）。
 */
@Composable
internal fun GatewaysEntryCard(
    gateways: List<UiGateway>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().clickable { onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("网关与 Agent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (gateways.isEmpty()) {
                Text("未接入", fontSize = 11.sp, color = Color(0xFF999999))
            } else {
                val onlineCount = gateways.count { it.online }
                Text(
                    "$onlineCount/${gateways.size} 在线",
                    fontSize = 11.sp,
                    color = if (onlineCount > 0) Color(0xFF2E9E44) else Color(0xFF999999),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 14.sp, color = Color(0xFF999999))
        }
    }
}
