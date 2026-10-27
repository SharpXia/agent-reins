package dev.sharpxia.agentreins.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.sharpxia.agentreins.ui.theme.InkTheme

/**
 * 墨水 Markdown（M3.3）：mikepenz multiplatform-markdown-renderer 0.40.x 封装，
 * 替换 M1 手写渲染器（无表格/嵌套/高亮的旧账一次清掉）。
 * 排版对齐墨水字阶：正文 text-lg 15/22，标题走 heading 阶，代码走 mono。
 * iOS lazy-list 滚动（issue #512）落地首验：长会话真机滚动卡顿则回退手写渲染器补表格。
 */
@Composable
fun InkMarkdown(content: String, modifier: Modifier = Modifier) {
    val t = InkTheme.type
    val c = InkTheme.colors
    Markdown(
        content,
        colors = markdownColor(
            text = c.ink,
            codeBackground = c.surfaceTertiary,
            inlineCodeBackground = c.surfaceTertiary,
            dividerColor = c.borderSecondary,
            tableBackground = c.elevated,
        ),
        typography = markdownTypography(
            text = t.textLg,
            code = t.mono,
            inlineCode = t.mono,
            h1 = t.headingMd,
            h2 = t.headingSm,
            h3 = t.headingXs,
            h4 = t.headingXs,
            h5 = t.headingXs,
            h6 = t.headingXs,
            quote = t.textLg,
            paragraph = t.textLg,
            ordered = t.textLg,
            bullet = t.textLg,
            list = t.textLg,
            textLink = TextLinkStyles(style = SpanStyle(color = c.info.text)),
            table = t.textSm,
        ),
        modifier = modifier,
    )
}
