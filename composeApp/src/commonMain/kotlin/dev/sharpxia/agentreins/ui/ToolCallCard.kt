package dev.sharpxia.agentreins.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.model.ToolCallEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 工具调用卡片（M2 验收项）：折叠态 = 名称 + 摘要 + 耗时 + 状态；展开态 = 参数/结果
 * JSON（pretty print + 等宽，结果为纯文本时原样展示）。
 */
@Composable
fun ToolCallCard(card: ToolCallEvent, modifier: Modifier = Modifier) {
    var expanded by remember(card.id) { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .clickable { expanded = !expanded }
                .animateContentSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.toolName.ifBlank { "工具调用" },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.title?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDuration(card.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                ToolStateIcon(card.state)
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MonoBlock("参数", prettyJson(card.argumentsJson) ?: "（事件流未提供，来自对话历史的参数）")
                    MonoBlock("结果", card.resultJson ?: "…")
                    card.exitCode?.let {
                        Text(
                            "exit code: $it",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it == 0) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolStateIcon(state: ToolCallEvent.State) {
    when (state) {
        ToolCallEvent.State.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
        )
        ToolCallEvent.State.SUCCEEDED -> Icon(
            Icons.Filled.Check,
            contentDescription = "成功",
            tint = Color(0xFF2E7D32),
            modifier = Modifier.size(15.dp),
        )
        ToolCallEvent.State.FAILED -> Icon(
            Icons.Filled.Close,
            contentDescription = "失败",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun MonoBlock(label: String, text: String) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            modifier = Modifier
                .padding(8.dp)
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

/** JSON pretty print；非 JSON（如 exec 的命令输出）原样返回。 */
private val prettyJsonFormat = Json { prettyPrint = true }

fun prettyJson(raw: String?): String? = raw?.let { text ->
    runCatching {
        prettyJsonFormat.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
    }.getOrNull() ?: text
}
