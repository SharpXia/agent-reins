package dev.sharpxia.agentreins.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.ui.components.SandSheetAction
import dev.sharpxia.agentreins.ui.theme.InkTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * 工具调用组（真机反馈⑤-4）：连续 ≥2 张已完成工具卡收入一个可折叠组。
 * 折叠态 = 「N 次工具调用」+ 成功/失败计数 + 总耗时；展开态 = 逐张 [ToolCallCard]
 * （每张仍可独立展开看参数/结果）。
 */
@Composable
fun ToolGroupCard(items: List<ToolCallEvent>, modifier: Modifier = Modifier) {
    var expanded by remember(items.firstOrNull()?.id, items.size) { mutableStateOf(false) }
    val c = InkTheme.colors
    val failed = items.count { it.state == ToolCallEvent.State.FAILED }
    val totalMs = items.sumOf { it.durationMs ?: 0L }
    Surface(
        shape = RoundedCornerShape(InkTheme.shapes.md.dp),
        color = c.elevated,
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
                    tint = c.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${items.size} 次工具调用",
                        style = InkTheme.type.textSm,
                        color = c.ink,
                    )
                    Text(
                        if (failed > 0) "$failed 次失败" else "全部成功",
                        style = InkTheme.type.textXs,
                        color = if (failed > 0) c.danger.text else c.textTertiary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    formatDuration(totalMs),
                    style = InkTheme.type.textXs,
                    color = c.textTertiary,
                )
                Spacer(Modifier.width(8.dp))
                if (failed > 0) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "含失败",
                        tint = c.danger.text,
                        modifier = Modifier.size(15.dp),
                    )
                } else {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "全部成功",
                        tint = c.success.text,
                        modifier = Modifier.size(15.dp),
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = c.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = c.borderSecondary)
                    items.forEach { card ->
                        ToolCallCard(card)
                    }
                }
            }
        }
    }
}

/**
 * 工具调用卡片（M3.5 墨水化 + Canvas-lite）：折叠态 = 名称 + 摘要 + 耗时 + 状态；
 * 展开态 = 参数/结果；结果长文可进全屏查看器（等宽 + 复制，M3.5 Canvas-lite 裁剪版——
 * Grok 的 WebView 画布无协议支撑，做查看器形态）。
 */
@Composable
fun ToolCallCard(card: ToolCallEvent, modifier: Modifier = Modifier) {
    var expanded by remember(card.id) { mutableStateOf(false) }
    var fullscreen by remember(card.id) { mutableStateOf(false) }
    val c = InkTheme.colors
    val resultText = card.resultJson ?: "…"

    Surface(
        shape = RoundedCornerShape(InkTheme.shapes.md.dp),
        color = c.elevated,
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
                    tint = c.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        card.toolName.ifBlank { "工具调用" },
                        style = InkTheme.type.textSm,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    card.title?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = InkTheme.type.textXs,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                if (card.state == ToolCallEvent.State.RUNNING) {
                    // 运行态显式文案（反馈②：收起时仅显示正在执行的工具，替代歧义「—」）
                    Text(
                        "执行中",
                        style = InkTheme.type.textXs,
                        color = c.awaiting,
                    )
                } else {
                    Text(
                        formatDuration(card.durationMs),
                        style = InkTheme.type.textXs,
                        color = c.textTertiary,
                    )
                }
                Spacer(Modifier.width(8.dp))
                ToolStateIcon(card.state)
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = c.textTertiary,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = c.borderSecondary)
                    MonoBlock("参数", prettyJson(card.argumentsJson) ?: "（事件流未提供，来自对话历史的参数）")
                    MonoBlock(
                        label = "结果",
                        text = resultText,
                        onFullscreen = { fullscreen = true }.takeIf { resultText.length > 400 },
                    )
                    card.exitCode?.let {
                        Text(
                            "exit code: $it",
                            style = InkTheme.type.textXs,
                            color = if (it == 0) c.textTertiary else c.danger.text,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }

    if (fullscreen) {
        FullscreenOutputSheet(title = card.toolName.ifBlank { "工具输出" }, text = resultText, onDismiss = { fullscreen = false })
    }
}

/** Canvas-lite 全屏查看器：等宽滚动 + 复制 + 关闭（数据不出本机，无分享面）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenOutputSheet(title: String, text: String, onDismiss: () -> Unit) {
    val c = InkTheme.colors
    val clipboard = LocalClipboardManager.current
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        shape = RoundedCornerShape(topStart = InkTheme.shapes.xl.dp, topEnd = InkTheme.shapes.xl.dp),
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(title, style = InkTheme.type.headingSm, color = c.ink, modifier = Modifier.weight(1f))
                SandSheetAction("复制全部") { clipboard.setText(AnnotatedString(text)) }
            }
            HorizontalDivider(color = c.borderSecondary)
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(InkTheme.shapes.md.dp))
                    .background(c.surfaceTertiary),
            ) {
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = c.ink,
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
private fun ToolStateIcon(state: ToolCallEvent.State) {
    when (state) {
        ToolCallEvent.State.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp,
            color = InkTheme.colors.awaiting,
        )
        ToolCallEvent.State.SUCCEEDED -> Icon(
            Icons.Filled.Check,
            contentDescription = "成功",
            tint = InkTheme.colors.success.text,
            modifier = Modifier.size(15.dp),
        )
        ToolCallEvent.State.FAILED -> Icon(
            Icons.Filled.Close,
            contentDescription = "失败",
            tint = InkTheme.colors.danger.text,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun MonoBlock(label: String, text: String, onFullscreen: (() -> Unit)? = null) {
    val c = InkTheme.colors
    Text(label, style = InkTheme.type.textXs, color = c.textTertiary)
    Surface(
        shape = RoundedCornerShape(InkTheme.shapes.sm.dp),
        color = c.surfaceTertiary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Text(
                text,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = c.ink,
                modifier = Modifier
                    .padding(8.dp)
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState()),
            )
            if (onFullscreen != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(c.borderTertiary)
                        .clickable { onFullscreen() }
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("全屏查看", style = InkTheme.type.textSm, color = c.info.text)
                }
            }
        }
    }
}

/** JSON pretty print；非 JSON（如 exec 的命令输出）原样返回。 */
private val prettyJsonFormat = Json { prettyPrint = true }

fun prettyJson(raw: String?): String? = raw?.let { text ->
    runCatching {
        prettyJsonFormat.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
    }.getOrNull() ?: text
}
