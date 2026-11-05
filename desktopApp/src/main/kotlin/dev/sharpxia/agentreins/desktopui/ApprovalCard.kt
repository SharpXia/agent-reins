package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.UiConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 审批决策瞬态（wp-10 桌面 parity，对齐 iOS HubStore.decisionsInFlight/decisionsSubmitted/
 * decisionFailures 三个集合）：本地不维护第二份审批状态——卡片行本身来自待审批投影 Flow
 * （[DefaultHubClient.observePendingApprovals]），这里只放「提交在途 / 已提交 / 提交失败」瞬态，
 * 决定生效以 approval.resolved 事件为准（行翻终态自动离场，卡片消失 = 收敛）。
 */
internal class ApprovalDecisionState {
    var inFlight by mutableStateOf(emptySet<String>())
        private set
    var submitted by mutableStateOf(emptySet<String>())
        private set
    var failed by mutableStateOf(emptySet<String>())
        private set

    /**
     * 审批决定（wp-9 门面 [DefaultHubClient.decideApproval]，decision ∈ approve/reject）：
     * 新意图新幂等键（重试同语义，HUB-BIZ-001 不换键纪律不破）。回执 true = 已取得 PERSISTED
     * （呈等待态）；false = 决策通道不可用（Hub FAILED 或网络不可达未提交）→ 降级态可重试。
     */
    fun decide(client: DefaultHubClient, approvalId: String, approve: Boolean, scope: CoroutineScope) {
        if (approvalId in inFlight) return // 防同卡双击双幂等键
        inFlight = inFlight + approvalId
        scope.launch {
            val ok = client.decideApproval(approvalId, if (approve) "approve" else "reject")
            inFlight = inFlight - approvalId
            if (ok) {
                failed = failed - approvalId
                submitted = submitted + approvalId
            } else {
                failed = failed + approvalId
            }
        }
    }

    /** 行离场收敛清理（对齐 iOS formIntersection）：瞬态只保留仍在待审批清单里的 id，防集合无界增长。 */
    fun retainAll(ids: Collection<String>) {
        val keep = ids.toSet()
        submitted = submitted intersect keep
        failed = failed intersect keep
    }
}

/**
 * 工具审批卡（wp-9 的 desktop 呈现）：toolName + payloadSummary + 会话归属 + 批准/拒绝决策动作。
 * 状态机（全部由 [ApprovalDecisionState] 瞬态 + 待审批投影 Flow 驱动）：
 * - 默认：批准 / 拒绝可点；
 * - 提交在途：按钮禁用 + 「提交中…」（防同卡双击双幂等键）；
 * - PERSISTED 回执：呈「已提交 · 等待确认」等待态——决定生效以 approval.resolved 事件为准，
 *   行翻终态后自动从待审批 Flow 离场（卡片消失 = 收敛）；
 * - 决策通道不可用（Hub FAILED——如 B-4 预检未通过 / HUB-BIZ-001，或网络不可达未提交）：
 *   显式红色降级态「决策通道不可用」+ 可重试，不静默——wp-9 scope 硬要求。
 *
 * 会话归属：conversationId 为 null/空 = 事件路由未学习（hubConvKeys 映射未命中）——「未关联会话」
 * 降级标注，不编造归属；已学习则从会话列表现值解析展示名（label 空 → agentId 兜底，与列表口径一致）。
 */
@Composable
internal fun ApprovalCard(
    a: UiApproval,
    conversations: List<UiConversation>,
    decisions: ApprovalDecisionState,
    client: DefaultHubClient,
    scope: CoroutineScope,
) {
    val inFlight = a.approvalId in decisions.inFlight
    val submitted = a.approvalId in decisions.submitted
    val failed = a.approvalId in decisions.failed

    val conversationTitle: String? = a.conversationId
        ?.takeIf { it.isNotEmpty() }
        ?.let { cid -> conversations.firstOrNull { it.conversationId == cid } }
        ?.let { c -> c.label.ifBlank { c.agentId.ifBlank { "会话" } } }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚙", color = Color(0xFFB7791F), fontSize = 14.sp)
                Text(
                    text = a.toolName,
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF333333),
                    maxLines = 1,
                    modifier = Modifier.padding(start = 6.dp).weight(1f),
                )
                TagChip("工具审批", Color(0xFFB7791F))
            }
            if (a.payloadSummary.isNotEmpty()) {
                // 有界载荷摘要原样透出（DataApprovalRequested.payloadSummary，消息体不透明 payload 不解析）
                MonoText(a.payloadSummary, color = Color(0xFF666666))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversationTitle ?: "未关联会话",
                    fontSize = 11.sp,
                    color = Color(0xFF777777),
                    maxLines = 1,
                )
                if (conversationTitle == null) {
                    Text(
                        text = "（归属未学习）",
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA),
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            when {
                failed -> {
                    // 决策失败（Hub FAILED / 网络不可达）显式降级态，不静默
                    Text(
                        text = "决策通道不可用",
                        fontSize = 12.sp,
                        color = Color(0xFFC53030),
                    )
                    Text(
                        text = "决定未送达（可能为预检未通过或网络不可达），可重试",
                        fontSize = 11.sp,
                        color = Color(0xFFC53030),
                    )
                }
                submitted -> Text(
                    text = "已提交 · 等待确认（生效以 approval.resolved 事件为准）",
                    fontSize = 11.sp,
                    color = Color(0xFF666666),
                )
                inFlight -> Text(
                    text = "提交中…",
                    fontSize = 11.sp,
                    color = Color(0xFF666666),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { decisions.decide(client, a.approvalId, approve = true, scope = scope) },
                    enabled = !inFlight && !submitted,
                    modifier = Modifier.weight(1f),
                ) { Text("批准") }
                OutlinedButton(
                    onClick = { decisions.decide(client, a.approvalId, approve = false, scope = scope) },
                    enabled = !inFlight && !submitted,
                    modifier = Modifier.weight(1f),
                ) { Text("拒绝") }
            }
        }
    }
}

/**
 * 待审批清单（设置页落点，对齐 iOS PendingApprovalsView）：全部 pending 审批，含归属未学习的行
 * （卡上标「未关联会话」）——那里没有会话页可挂，唯一可见处在此。
 */
@Composable
internal fun PendingApprovalsPanel(
    approvals: List<UiApproval>,
    conversations: List<UiConversation>,
    decisions: ApprovalDecisionState,
    client: DefaultHubClient,
    scope: CoroutineScope,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (approvals.isEmpty()) {
            Text(
                "暂无待审批（工具审批请求出现后自动列入此处）",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF888888),
            )
        } else {
            approvals.forEach { a ->
                ApprovalCard(a, conversations, decisions, client, scope)
            }
        }
    }
}
