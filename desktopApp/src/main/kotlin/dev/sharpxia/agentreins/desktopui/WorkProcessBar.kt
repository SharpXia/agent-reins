package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.UiRunPhase
import dev.sharpxia.agentreins.hub.UiToolActivity
import dev.sharpxia.agentreins.hub.UiWorkActivity

/**
 * wp-15：会话页「工作过程」披露条（对齐 iOS HubViews.WorkProcessBar / grokbot「Worked」形态，
 * 置顶于时间线上方、与审批横条同位不进滚动区）。
 *
 * 数据面 = HubClient.observeWorkActivity（投影库 hubToolEvents + hubRunStatus 合流快照）。
 * 已知丢失面（06 §10.2，UI 诚实义务）：tool.* 与 run.status 断线窗口或入站限流丢弃**不补发**，
 * 本条只呈现本地已捕获部分——计数语义是「已捕获 N 次」而非「本次 run 共 N 次」，不虚构完整；
 * 重连后已捕获部分保留（两表快照重建不清）。无工具活动且无阶段帧的会话整条不渲染（零噪声），
 * 历史会话也不虚构（断线窗口丢失面不回填）。run 终态清阶段行由投影保证（HubClient.kt UiRunPhase 注）。
 *
 * 判定纯函数在 [WorkProcessRules]（desktopTest 回归锚）。
 */
@Composable
internal fun WorkProcessBar(
    activity: UiWorkActivity,
    runActive: Boolean,
    modifier: Modifier = Modifier,
) {
    // 展开态为会话页本地瞬态：随会话切换由 HubShell key(conversationId) 重挂自然重置
    var expanded by remember { mutableStateOf(false) }
    val counts = remember(activity.tools) { toolActivityCounts(activity.tools) }
    val latestPhase = latestPhaseFrame(activity.phases)
    val inProgress = workBarInProgress(runActive, counts.running, latestPhase != null)
    val summary = workCountSummary(counts)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 折叠头：标题 + 进行中指示 + 已捕获计数摘要 + 展开/收起箭头（iOS DisclosureGroup 同位）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "🔧 工作过程",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (inProgress) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                }
                if (summary.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    if (expanded) "▾" else "▸",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                )
            }
            // 展开体：最新阶段帧（单帧）+ 全部已捕获工具行（firstTs 升序，SQL ORDER BY 保证）
            if (expanded) {
                HorizontalDivider(color = Color(0x22888888))
                latestPhase?.let { PhaseRow(it) }
                activity.tools.forEach { t ->
                    ToolRow(t)
                }
            }
        }
    }
}

/**
 * 阶段行：Gateway 启动 phase 枚举的人读呈现（闭集映射 + 原样兜底，非身份字段不涉 ADR-14）；
 * aborted 变体呈警示色（iOS exclamationmark.triangle 橙色同语义）。
 */
@Composable
private fun PhaseRow(p: UiRunPhase) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (p.phase == "aborted") "⚠" else "⚙",
            fontSize = 12.sp,
            color = if (p.phase == "aborted") Color(0xFFB7791F) else Color(0xFF888888),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                phaseDisplayText(p.phase),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
            // 有界附加说明（retry 重试 / aborted errorMessage/stopReason），缺省不渲染
            val detail = p.detail
            if (!detail.isNullOrEmpty()) {
                Text(
                    detail,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 工具行：三态徽标 + 工具名（缺省「工具调用」兜底，不虚构名称）+ 有界入参摘要单行；
 * 未知状态值保守呈现为在途（终态先到丢失面，不虚构终态）。
 */
@Composable
private fun ToolRow(t: UiToolActivity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (toolStatusState(t.status)) {
            ToolStatusState.Completed -> Text("✓", fontSize = 12.sp, color = Color(0xFF2E9E44))
            ToolStatusState.Failed -> Text("✗", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            ToolStatusState.Running -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                toolDisplayName(t.toolName),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 有界入参摘要（started 携带、可能被截断），缺省不渲染
            val summary = t.summary
            if (!summary.isNullOrEmpty()) {
                Text(
                    summary,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
