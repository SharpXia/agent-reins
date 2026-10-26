package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 聊天用最小 Markdown 渲染：围栏代码块 / 行内代码 / 粗体 / 斜体 / 标题 / 列表 / 链接。
 * 手写解析不引外部 KMP 依赖（本仓库引库摩擦大）；流式中的未闭合围栏按代码块兜底。
 * 覆盖面是 agent 回复的常见子集，非完整 CommonMark。
 */

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class CodeFence(val code: String) : MdBlock
    data class ListItem(val marker: String, val text: String) : MdBlock
}

private val listItemRegex = Regex("^([-*+]|\\d+[.)])\\s+(.*)$")

private fun parseBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flushParagraph() {
        val t = paragraph.toString().trim('\n', ' ')
        if (t.isNotBlank()) blocks += MdBlock.Paragraph(t)
        paragraph.clear()
    }
    var inFence = false
    val fence = StringBuilder()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("```")) {
            if (inFence) {
                blocks += MdBlock.CodeFence(fence.toString().trimEnd('\n'))
                fence.clear(); inFence = false
            } else {
                flushParagraph(); inFence = true
            }
            continue
        }
        if (inFence) { fence.appendLine(line); continue }
        when {
            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                blocks += MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim())
            }
            listItemRegex.matches(trimmed) -> {
                flushParagraph()
                val marker = trimmed.substringBefore(' ')
                blocks += MdBlock.ListItem(marker, trimmed.substringAfter(' ').trim())
            }
            trimmed.isEmpty() -> flushParagraph()
            else -> paragraph.appendLine(trimmed)
        }
    }
    if (inFence) blocks += MdBlock.CodeFence(fence.toString().trimEnd('\n')) // 流式未闭合兜底
    flushParagraph()
    return blocks
}

private data class MdSpan(
    val text: String,
    val code: Boolean = false,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val url: String? = null,
)

/** 行内扫描：`code` / **bold** / *italic* / [label](url)；嵌套不做（扁平解析，聊天场景够用）。 */
private fun parseInline(text: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val literal = StringBuilder()
    fun flush() {
        if (literal.isNotEmpty()) { spans += MdSpan(literal.toString()); literal.clear() }
    }
    var i = 0
    while (i < text.length) {
        when {
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) { flush(); spans += MdSpan(text.substring(i + 1, end), code = true); i = end + 1 }
                else { literal.append(text[i]); i++ }
            }
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i) { flush(); spans += MdSpan(text.substring(i + 2, end), bold = true); i = end + 2 }
                else { literal.append(text[i]); i++ }
            }
            text[i] == '*' -> {
                val end = text.indexOf('*', i + 1)
                if (end > i) { flush(); spans += MdSpan(text.substring(i + 1, end), italic = true); i = end + 1 }
                else { literal.append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val close = text.indexOf("](", i + 1)
                val urlEnd = if (close >= 0) text.indexOf(')', close + 2) else -1
                if (close > i && urlEnd > close) {
                    flush()
                    spans += MdSpan(text.substring(i + 1, close), url = text.substring(close + 2, urlEnd))
                    i = urlEnd + 1
                } else { literal.append(text[i]); i++ }
            }
            else -> { literal.append(text[i]); i++ }
        }
    }
    flush()
    return spans
}

/** Markdown 全量入口：段落文本/标题/列表/代码块分别排版。 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val bodyStyle = MaterialTheme.typography.bodyMedium
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in remember(text) { parseBlocks(text) }) {
            when (block) {
                is MdBlock.CodeFence -> CodeBlock(block.code)
                is MdBlock.Heading -> Text(
                    block.text,
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                )
                is MdBlock.ListItem -> Row(Modifier.padding(start = 2.dp)) {
                    Text(
                        if (block.marker.first().isDigit()) "${block.marker.dropLast(1)}." else "•",
                        style = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    InlineMarkdown(block.text, bodyStyle, Modifier.weight(1f))
                }
                is MdBlock.Paragraph -> InlineMarkdown(block.text, bodyStyle)
            }
        }
    }
}

@Composable
private fun InlineMarkdown(text: String, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val codeColor = MaterialTheme.colorScheme.onSurface
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text) {
        buildAnnotatedString {
            for (span in parseInline(text)) {
                if (span.url != null) {
                    withLink(LinkAnnotation.Url(span.url)) {
                        withStyle(
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = if (span.bold) FontWeight.SemiBold else style.fontWeight,
                            ),
                        ) { append(span.text) }
                    }
                } else {
                    withStyle(
                        SpanStyle(
                            color = if (span.code) codeColor else Color.Unspecified,
                            background = if (span.code) codeBg else Color.Unspecified,
                            fontFamily = if (span.code) FontFamily.Monospace else style.fontFamily,
                            fontWeight = if (span.bold) FontWeight.SemiBold else style.fontWeight,
                            fontStyle = if (span.italic) androidx.compose.ui.text.font.FontStyle.Italic else style.fontStyle,
                        ),
                    ) { append(span.text) }
                }
            }
        }
    }
    Text(annotated, style = style, modifier = modifier)
}

@Composable
private fun CodeBlock(code: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            softWrap = false,
            modifier = Modifier
                .padding(10.dp)
                .horizontalScroll(rememberScrollState()),
        )
    }
}
