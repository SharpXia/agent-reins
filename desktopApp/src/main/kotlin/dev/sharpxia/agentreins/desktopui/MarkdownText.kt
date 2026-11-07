package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 代码底色/行内代码底色：消息气泡（surfaceVariant）之上的浅灰叠层，主题无关（本面既有灰阶惯例）。 */
private val CodeTint = Color(0x14888888)
private val ListMarkerInk = Color(0xFF777777)

/**
 * wp-18：桌面助手行轻量 Markdown 渲染器（iOS Chat/MarkdownView 的起步同位）。
 *
 * 覆盖起步集：标题（#–######）/ 列表（无序 + 有序，一层缩进近似）/ 围栏代码块 /
 * 粗体、斜体、粗斜体 + 行内代码。已知取舍与 iOS 版同向：表格、引用、嵌套强调、远程图片
 * 不渲染（原样呈现为段落文本）。
 *
 * wp-6 纯文本回退（验收硬要求）：[looksLikeMarkdown] 为 false 或解析抛异常时走纯文本
 * `Text`——与 wp-6 MVP 渲染逐字一致；解析器对未配对定界符逐字保留，纯文本语义不漂移。
 * 流式行（回归修复）：回退分支同样尾随 ▌ 光标（[fallbackPlainText]，与 wp-6 「content + ▌」
 * 及解析路径 cursor 语义一致）——无 Markdown 语素/解析异常的流式行不得丢光标。
 * 空正文占位（…▌ / （无文本内容））由调用方 [AssistantMessageRow] 先行分流，不进本组件。
 */
@Composable
internal fun MarkdownText(
    content: String,
    showCursor: Boolean,
    modifier: Modifier = Modifier,
) {
    // JSON 卡嗅探（ADR-10 纯展示层判定）：消息级裸 JSON 优先整条出卡，未命中走既有管线原样
    // ——半流行（showCursor）不嗅探（防「闪卡-消失」抖动，纪律见 jsonCardPayloadOrNull）。
    val contentJsonCard = remember(content, showCursor) { jsonCardPayloadOrNull(content, showCursor) }
    if (contentJsonCard != null) {
        JsonCardView(payload = contentJsonCard, modifier = modifier)
        return
    }
    val blocks = remember(content, showCursor) {
        markdownBlocksOrFallback(content, showCursor)
    }
    if (blocks == null) {
        // wp-6 纯文本路径回退：原样 Text（调用方 modifier 携带气泡内边距，样式与 MVP 一致；
        // 流式行补 ▌ 光标——wp-6 既有行为，回退分支不得忽略 showCursor）
        Text(fallbackPlainText(content, showCursor), modifier = modifier)
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEach { MarkdownBlockView(it) }
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock) {
    when (block) {
        is MdBlock.Heading -> Text(
            text = block.spans.toAnnotatedString(),
            style = headingStyle(block.level),
            modifier = Modifier.fillMaxWidth(),
        )
        is MdBlock.Paragraph -> Text(
            text = block.lines.toParagraphString(),
            modifier = Modifier.fillMaxWidth(),
        )
        is MdBlock.CodeBlock -> {
            // ```json 围栏 / 内容可解析为 JSON 的块 → JSON 卡（流式未闭合围栏的内容剥光标后
            // 解析不动即回落下方既有渲染，半流纪律不破）；language=="json" 免首字符闸门
            val codeJsonCard = remember(block.code, block.language) {
                jsonCardPayloadForCode(block.code, block.language)
            }
            if (codeJsonCard != null) {
                JsonCardView(payload = codeJsonCard, modifier = Modifier.fillMaxWidth())
            } else {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = CodeTint,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // 自动折行（iOS 版同取舍：代码块无横向滚动）
                    Text(
                        text = block.code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            block.items.forEach { item ->
                Row(modifier = Modifier.fillMaxWidth().padding(start = (item.depth * 14).dp)) {
                    Text(
                        text = item.marker,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ListMarkerInk,
                        modifier = Modifier.widthIn(min = 16.dp),
                    )
                    Spacer(Modifier.padding(end = 6.dp))
                    Text(
                        text = item.spans.toAnnotatedString(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.Bold)

/** 行内 span → AnnotatedString（平铺样式：粗 / 斜 / 粗斜 / 等宽代码底色）。 */
private fun List<MdSpan>.toAnnotatedString(): AnnotatedString = buildAnnotatedString {
    forEach { span ->
        when (span) {
            is MdSpan.Text -> append(span.text)
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is MdSpan.BoldItalic -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
            ) { append(span.text) }
            is MdSpan.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = CodeTint),
            ) { append(span.text) }
        }
    }
}

/** 段落多行合并为单个 Text（作者换行保留）。 */
private fun List<List<MdSpan>>.toParagraphString(): AnnotatedString = buildAnnotatedString {
    forEachIndexed { idx, line ->
        if (idx > 0) append("\n")
        line.forEach { span ->
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.BoldItalic -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                ) { append(span.text) }
                is MdSpan.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = CodeTint),
                ) { append(span.text) }
            }
        }
    }
}

// ── JSON 卡（2026-11-07 动态目录轮，与 iOS Chat/MarkdownView 卡形态同构）──────────────
// ADR-10 纪律：JSON 卡是纯展示层判定——嗅探/解析只决定「怎么显示」，失败一律回落既有渲染；
// 折叠态存视图层 remember（不进解析产物），不破 wp-6「纯文本等价」回退语义。

/** 通用卡折叠展示行数：超出默认收起，只显示前 8 行（iOS 同值）。 */
private const val JSON_CARD_COLLAPSED_LINES = 8

/**
 * 显示用途的嗅探 JSON（严格模式）。刻意不用 isLenient：lenient 会把 `[logged]`/`[done]`
 * 这类单 token bracket_text 当未加引号字符串收进 JsonArray 出卡，违反「非 JSON 一律既有
 * 渲染」排除纪律；严格口径与 iOS JSONSerialization 同构，openclaw 载荷本就是机器序列化的
 * 严格 JSON，不受影响。解析失败即回落，不构成语义承诺。
 */
private val JsonSniff = Json

/** 通用卡 pretty 编码器：排序键 + 两空格缩进（显示层归一；复制按钮复制的就是这段）。 */
private val JsonPretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** JSON 卡载荷：工具列表卡（openclaw 工具数组）或通用卡（其余可解析 JSON）。 */
internal sealed interface JsonCardPayload {
    /** 工具列表卡：行 = label 主文本 + id 小字辅助；单条展开原始 JSON、复制原始 JSON。 */
    data class ToolList(val entries: List<JsonToolEntry>) : JsonCardPayload

    /** 通用卡：排序键 pretty 全文，超 8 行默认折叠。 */
    data class Generic(val prettyText: String) : JsonCardPayload
}

/** 工具列表卡单行（id/label 缺失或非字符串 = 非规范行，主文本以原始 JSON 兜底）。 */
internal data class JsonToolEntry(
    val label: String?,
    val id: String?,
    /** 该条原始 JSON（compact、保解析序）：展开态展示 + 复制按钮的内容。 */
    val rawJson: String,
)

/**
 * content 级嗅探（消息级裸 JSON）：去首尾空白后首字符 ∈ [/{ 且严格解析成功才出卡；
 * progress_line（`[1] status=…`）、`[logged]`、bracket_text 等解析不动即 null = 既有渲染。
 * [streaming]（流式行）恒 null——半流中间态即使恰好构成合法 JSON 也不进卡：载荷后随说明
 * 文字入流即解析失效回落，「闪卡-消失」抖动比晚一拍出卡更伤，终态才落卡。
 */
internal fun jsonCardPayloadOrNull(content: String, streaming: Boolean): JsonCardPayload? {
    if (streaming) return null
    val trimmed = content.trim()
    if (trimmed.isEmpty() || (trimmed.first() != '[' && trimmed.first() != '{')) return null
    val element = runCatching { JsonSniff.parseToJsonElement(trimmed) }.getOrNull() ?: return null
    return jsonCardPayloadOf(element)
}

/**
 * 围栏代码块分支：剥流式光标后解析（未闭合围栏的半流内容解析不动即回落既有代码渲染）。
 * [language]=="json" 免首字符闸门（info string 已声明形态），其余同 content 级口径。
 */
internal fun jsonCardPayloadForCode(code: String, language: String?): JsonCardPayload? {
    val body = stripMdCursor(code).trim()
    val jsonDeclared = language == "json"
    if (body.isEmpty() || (!jsonDeclared && body.first() != '[' && body.first() != '{')) return null
    val element = runCatching { JsonSniff.parseToJsonElement(body) }.getOrNull() ?: return null
    return jsonCardPayloadOf(element)
}

/** 卡形态分流：顶层数组且 ≥1 元素 id 以 openclaw: 开头且含 label 字符串 → 工具列表卡，其余通用卡。 */
private fun jsonCardPayloadOf(element: JsonElement): JsonCardPayload {
    if (element is JsonArray) {
        val entries = element.map(::jsonToolEntryOf)
        if (entries.any { it.id?.startsWith("openclaw:") == true && it.label != null }) {
            return JsonCardPayload.ToolList(entries)
        }
    }
    return JsonCardPayload.Generic(prettySorted(element))
}

private fun jsonToolEntryOf(element: JsonElement): JsonToolEntry {
    val obj = element as? JsonObject
    val id = obj?.get("id") as? JsonPrimitive
    val label = obj?.get("label") as? JsonPrimitive
    return JsonToolEntry(
        label = label?.takeIf { it.isString }?.content,
        id = id?.takeIf { it.isString }?.content,
        rawJson = element.toString(),
    )
}

/** 排序键 pretty（JsonObject 递归排序、JsonArray 保序、原始值原样）：显示层归一，便于对照阅读。 */
private fun prettySorted(element: JsonElement): String =
    JsonPretty.encodeToString(element.sortedForDisplay())

private fun JsonElement.sortedForDisplay(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.sortedBy { it.key }.associate { it.key to it.value.sortedForDisplay() },
    )
    is JsonArray -> JsonArray(map { it.sortedForDisplay() })
    else -> this
}

/** JSON 卡容器：与既有代码块同底色同圆角（CodeTint + 6dp），卡内两形态各自排版。 */
@Composable
private fun JsonCardView(payload: JsonCardPayload, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = CodeTint, modifier = modifier.fillMaxWidth()) {
        when (payload) {
            is JsonCardPayload.ToolList -> ToolListCardBody(payload.entries)
            is JsonCardPayload.Generic -> GenericCardBody(payload.prettyText)
        }
    }
}

@Composable
private fun GenericCardBody(prettyText: String) {
    val clipboard = LocalClipboardManager.current
    val lines = remember(prettyText) { prettyText.lineSequence().toList() }
    val collapsible = lines.size > JSON_CARD_COLLAPSED_LINES
    // 折叠态只存视图层 remember（key=prettyText 即消息终态标识），不进解析产物
    var expanded by remember(prettyText) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(
            text = if (collapsible && !expanded) {
                lines.take(JSON_CARD_COLLAPSED_LINES).joinToString("\n")
            } else {
                prettyText
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (collapsible) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) "收起" else "展开 ${lines.size - JSON_CARD_COLLAPSED_LINES} 行",
                        fontSize = 11.sp,
                    )
                }
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString(prettyText)) }) {
                Text("复制", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ToolListCardBody(entries: List<JsonToolEntry>) {
    val clipboard = LocalClipboardManager.current
    var expandedIndex by remember(entries) { mutableStateOf<Int?>(null) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
        entries.forEachIndexed { index, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 单条点开展开该条原始 JSON（再点收起）；卡内零滚动，纵向交给消息时间线承载
                    .clickable { expandedIndex = if (expandedIndex == index) null else index }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label ?: "（未标注工具）",
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.id != null && entry.id != entry.label) {
                        Text(
                            text = entry.id,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ListMarkerInk,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (expandedIndex == index) {
                Text(
                    text = entry.rawJson,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(entry.rawJson)) }) {
                    Text("复制原始 JSON", fontSize = 11.sp)
                }
            }
            if (index < entries.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
