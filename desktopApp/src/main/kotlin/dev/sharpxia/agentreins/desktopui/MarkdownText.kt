package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        is MdBlock.CodeBlock -> Surface(
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
