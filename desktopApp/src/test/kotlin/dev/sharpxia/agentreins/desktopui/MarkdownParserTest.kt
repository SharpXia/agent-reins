package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wp-18 轻量 Markdown 解析器单测（desktopApp jvmTest）：回退闸门、块级解析、行内解析、
 * 流式光标。核心回归锚 = 「纯文本等价」——未配对定界符/非 Markdown 序列解析后必须与原文
 * 逐字一致（wp-6 纯文本回退不漂移的解析侧前提）。
 */
class MarkdownParserTest {

    // ── looksLikeMarkdown：回退闸门（false → wp-6 纯文本路径） ──

    @Test
    fun plainTextWithoutMarkdownTokensIsNotMarkdown() {
        assertFalse(looksLikeMarkdown("普通中文回复，没有 markdown。"))
        assertFalse(looksLikeMarkdown("第一行\n第二行\n\n第三行"))
        // 裸 URL 2026-11-23 批次二轮起放行（链接化：裸 URL 消息必须进解析管线吃链接化，不得
        // 落纯文本回退——原「不放行」口径随链接化轮翻转；解析产物仍逐字等价）
        assertTrue(looksLikeMarkdown("https://example.com/path?a=1"))
    }

    @Test
    fun markdownConstructsAreDetected() {
        assertTrue(looksLikeMarkdown("# 标题"))
        assertTrue(looksLikeMarkdown("前文\n## 二级"))
        assertTrue(looksLikeMarkdown("```kotlin\nval x = 1\n```"))
        assertTrue(looksLikeMarkdown("- 列表项"))
        assertTrue(looksLikeMarkdown("1. 有序列表"))
        assertTrue(looksLikeMarkdown("**粗体**"))
        assertTrue(looksLikeMarkdown("行内 `code` 片段"))
        // 宽松命中：单个 * ` _ 也路由到解析器（解析结果对纯文本序列逐字等价）
        assertTrue(looksLikeMarkdown("3 * 4 = 12"))
        assertTrue(looksLikeMarkdown("snake_case_name"))
    }

    // ── 块级：标题 ──

    @Test
    fun headingsParseWithLevelAndInlineSpans() {
        val blocks = parseMarkdownBlocks("# 标题一\n## **粗体**标题二\n### 三级\n####### 七个井号\n#无空格不识别")
        assertEquals(4, blocks.size)
        val h1 = assertIs<MdBlock.Heading>(blocks[0])
        assertEquals(1, h1.level)
        assertEquals(listOf<MdSpan>(MdSpan.Text("标题一")), h1.spans)
        val h2 = assertIs<MdBlock.Heading>(blocks[1])
        assertEquals(2, h2.level)
        assertEquals(listOf<MdSpan>(MdSpan.Bold("粗体"), MdSpan.Text("标题二")), h2.spans)
        assertEquals(3, assertIs<MdBlock.Heading>(blocks[2]).level)
        // 7 个 # 超出 h6 上限、# 后无空格：都是普通段落原样保留（连续段行并入同一段落块）
        val p = assertIs<MdBlock.Paragraph>(blocks[3])
        assertEquals(
            listOf(
                listOf<MdSpan>(MdSpan.Text("####### 七个井号")),
                listOf<MdSpan>(MdSpan.Text("#无空格不识别")),
            ),
            p.lines,
        )
    }

    // ── 块级：围栏代码块 ──

    @Test
    fun fencedCodeBlockPreservesContentVerbatim() {
        val blocks = parseMarkdownBlocks("前文\n```kotlin\nval a = \"x\"\n  缩进行\n```\n后文")
        assertEquals(3, blocks.size)
        assertIs<MdBlock.Paragraph>(blocks[0])
        val code = assertIs<MdBlock.CodeBlock>(blocks[1])
        assertEquals("val a = \"x\"\n  缩进行", code.code)
        assertIs<MdBlock.Paragraph>(blocks[2])
    }

    @Test
    fun unterminatedFenceTakesRestAsCode() {
        // 流式渐进安全：未闭合围栏把剩余内容整体按代码块，不当正文误渲染
        val blocks = parseMarkdownBlocks("说明\n```\nstill code\nno close")
        assertEquals(2, blocks.size)
        assertEquals("still code\nno close", assertIs<MdBlock.CodeBlock>(blocks[1]).code)
    }

    @Test
    fun tildeFenceIsSupported() {
        val blocks = parseMarkdownBlocks("~~~\ncode line\n~~~")
        assertEquals("code line", assertIs<MdBlock.CodeBlock>(blocks.single()).code)
    }

    // ── 块级：列表 ──

    @Test
    fun bulletAndOrderedListsKeepLiteralMarkers() {
        val blocks = parseMarkdownBlocks("- 项目甲\n* 项目乙\n+ 项目丙\n1. 第一步\n2) 第二步")
        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertEquals(
            listOf("-" to "项目甲", "*" to "项目乙", "+" to "项目丙", "1." to "第一步", "2)" to "第二步"),
            list.items.map { it.marker to it.spans.single().let { s -> (s as MdSpan.Text).text } },
        )
        assertEquals(List(list.items.size) { 0 }, list.items.map { it.depth })
    }

    @Test
    fun listDepthFollowsTwoSpaceIndentWithCap() {
        val blocks = parseMarkdownBlocks("- 顶\n  - 一层\n    - 二层\n          - 很深层")
        val list = assertIs<MdBlock.ListBlock>(blocks.single())
        assertEquals(listOf(0, 1, 2, 4), list.items.map { it.depth }) // 上限 4
    }

    @Test
    fun paragraphLineInterruptsList() {
        val blocks = parseMarkdownBlocks("- 甲\n正文行\n- 乙")
        val list1 = assertIs<MdBlock.ListBlock>(blocks[0])
        assertEquals(1, list1.items.size)
        assertIs<MdBlock.Paragraph>(blocks[1])
        val list2 = assertIs<MdBlock.ListBlock>(blocks[2])
        assertEquals(1, list2.items.size)
    }

    @Test
    fun blankLinesSplitParagraphs() {
        val blocks = parseMarkdownBlocks("第一段\n续行\n\n第二段")
        assertEquals(2, blocks.size)
        val p1 = assertIs<MdBlock.Paragraph>(blocks[0])
        assertEquals(2, p1.lines.size)
        val p2 = assertIs<MdBlock.Paragraph>(blocks[1])
        assertEquals(1, p2.lines.size)
    }

    // ── 图片块（v1.2.0 多模态：markdown 图片 URL 渲染面，设计稿 §6 桌面行） ──

    @Test
    fun standaloneImageLineParsesToImageBlock() {
        val blocks = parseMarkdownBlocks("前文\n![一只橙猫](https://example.com/cat.png)\n后文")
        assertEquals(3, blocks.size)
        val img = assertIs<MdBlock.Image>(blocks[1])
        assertEquals("https://example.com/cat.png", img.url)
        assertEquals("一只橙猫", img.alt)
        // 空 alt（生图输出常见形态）也成块
        val bare = parseMarkdownBlocks("![](https://example.com/out.png)").single()
        val bareImg = assertIs<MdBlock.Image>(bare)
        assertEquals("https://example.com/out.png", bareImg.url)
        assertEquals("", bareImg.alt)
    }

    @Test
    fun imageOnlyContentRoutesThroughGate() {
        // 图片行本身不含 * ` _ 语素，gate 需显式放行（否则纯图片消息误落纯文本回退）
        assertTrue(looksLikeMarkdown("![橙猫](https://example.com/cat.png)"))
        assertTrue(looksLikeMarkdown("  ![a](https://e.com/a.png)  "))
        assertEquals(1, markdownBlocksOrFallback("![a](https://e.com/a.png)", cursor = false)?.size)
        // 裸 URL（非图片行）随链接化轮（2026-11-23 批次二）放行——进解析管线吃链接化
        assertTrue(looksLikeMarkdown("https://example.com/path?a=1"))
    }

    @Test
    fun inlineAndMalformedImagesStayLiteral() {
        // 行内混排不成图片块（整行独占才成块）——按段落字面量保留（纯文本等价契约）
        val inline = assertIs<MdBlock.Paragraph>(
            parseMarkdownBlocks("看 ![图](https://e.com/a.png) 这张").single(),
        )
        assertEquals(
            listOf(listOf<MdSpan>(MdSpan.Text("看 ![图](https://e.com/a.png) 这张"))),
            inline.lines,
        )
        // 带标题形（url 含空格）→ 字面量
        assertIs<MdBlock.Paragraph>(parseMarkdownBlocks("""![a](https://e.com/a.png "标题")""").single())
        // 未闭合 → 字面量
        assertIs<MdBlock.Paragraph>(parseMarkdownBlocks("![a](https://e.com/a.png").single())
        // 列表项内的图片 → 列表行内字面量（不提升为图片块）
        val list = assertIs<MdBlock.ListBlock>(parseMarkdownBlocks("- ![x](https://e.com/x.png)").single())
        assertEquals(1, list.items.size)
    }

    @Test
    fun imageBlockDoesNotCarryCursor() {
        // 图片语素是完整行：cursor 挂不上 Image 块（半流期尾随 ▌ 使行不成块、按段落字面量
        // + 光标呈现，终态收敛为 Image——设计注释既定行为，此处锚定）
        val img = assertIs<MdBlock.Image>(
            parseMarkdownBlocks("![a](https://e.com/a.png)", cursor = true).single(),
        )
        assertEquals("https://e.com/a.png", img.url)
    }

    // ── 行内：粗斜体 ──

    @Test
    fun boldItalicAndCombinationParse() {
        assertEquals(
            listOf<MdSpan>(MdSpan.Text("a "), MdSpan.Bold("粗"), MdSpan.Text(" b "), MdSpan.Italic("斜"), MdSpan.Text(" c "), MdSpan.BoldItalic("粗斜")),
            parseInlineSpans("a **粗** b *斜* c ***粗斜***"),
        )
        assertEquals(listOf<MdSpan>(MdSpan.Bold("双下划线")), parseInlineSpans("__双下划线__"))
        assertEquals(listOf<MdSpan>(MdSpan.Italic("单下划线")), parseInlineSpans("_单下划线_"))
    }

    @Test
    fun intrawordUnderscoreIsNotEmphasis() {
        // snake_case 不成斜体（`_` 词边界规则；`*` 无此约束，与 CommonMark 同口径）
        assertEquals(
            listOf<MdSpan>(MdSpan.Text("snake_case_name")),
            parseInlineSpans("snake_case_name"),
        )
    }

    @Test
    fun intrawordAsteriskIsEmphasisLikeCommonMark() {
        assertEquals(
            listOf<MdSpan>(MdSpan.Text("2"), MdSpan.Italic("3"), MdSpan.Text("4")),
            parseInlineSpans("2*3*4"),
        )
    }

    @Test
    fun unpairedDelimitersStayLiteral() {
        assertEquals(listOf<MdSpan>(MdSpan.Text("**未闭合粗体")), parseInlineSpans("**未闭合粗体"))
        assertEquals(listOf<MdSpan>(MdSpan.Text("一个 * 星号")), parseInlineSpans("一个 * 星号"))
        assertEquals(listOf<MdSpan>(MdSpan.Text("* 前后有空格 *")), parseInlineSpans("* 前后有空格 *"))
    }

    @Test
    fun minimalEscapesRenderLiteral() {
        assertEquals(listOf<MdSpan>(MdSpan.Text("*不是强调*")), parseInlineSpans("\\*不是强调\\*"))
        assertEquals(listOf<MdSpan>(MdSpan.Text("反斜杠\\原样")), parseInlineSpans("反斜杠\\原样"))
    }

    // ── 行内：行内代码 ──

    @Test
    fun inlineCodeIsolatesEmphasisInside() {
        assertEquals(
            listOf<MdSpan>(MdSpan.Text("用 "), MdSpan.Code("**x**"), MdSpan.Text(" 渲染")),
            parseInlineSpans("用 `**x**` 渲染"),
        )
    }

    @Test
    fun doubleBacktickRunMatchesSameLength() {
        assertEquals(
            listOf<MdSpan>(MdSpan.Code("含 ` 反引号"), MdSpan.Text(" 尾")),
            parseInlineSpans("`` 含 ` 反引号 `` 尾"),
        )
    }

    @Test
    fun unclosedBacktickStaysLiteral() {
        assertEquals(listOf<MdSpan>(MdSpan.Text("未闭合 `code")), parseInlineSpans("未闭合 `code"))
    }

    // ── 「纯文本等价」回归：整段往返 ──

    @Test
    fun parsedSpansRoundTripPlainEquivalent() {
        fun spansText(spans: List<MdSpan>): String = spans.joinToString("") {
            when (it) {
                is MdSpan.Text -> it.text
                is MdSpan.Bold -> it.text
                is MdSpan.Italic -> it.text
                is MdSpan.BoldItalic -> it.text
                is MdSpan.Code -> it.text
                // Link span 由渲染层 linkifySpans 产出、解析器从不产出——此分支只为穷尽
                is MdSpan.Link -> it.text
            }
        }
        fun flatten(text: String): String =
            parseMarkdownBlocks(text).joinToString("") { b ->
                when (b) {
                    is MdBlock.Paragraph -> b.lines.joinToString("\n") { spansText(it) }
                    is MdBlock.Heading -> spansText(b.spans)
                    is MdBlock.CodeBlock -> b.code
                    is MdBlock.ListBlock -> b.items.joinToString("\n") { spansText(it.spans) }
                    // 图片块按语法形态重建（图片是有意渲染语素，不参与「无配对语素逐字等价」
                    // 断言面——本用例输入均为无配对语素，此分支只为穷尽）
                    is MdBlock.Image -> "![${b.alt}](${b.url})"
                    // 表格按 GFM 形态重建（同上：本用例输入均不成表，此分支只为穷尽）
                    is MdBlock.Table -> (listOf(b.header) + b.rows)
                        .joinToString("\n") { row -> row.joinToString("|") { spansText(it) } }
                }
            }
        // 无配对语素 → 逐字往返等价
        assertEquals("3 * 4 = 12，x_y_z，`未闭合", flatten("3 * 4 = 12，x_y_z，`未闭合"))
        assertEquals("**孤星 * 序列", flatten("**孤星 * 序列"))
    }

    // ── 流式光标 ──

    @Test
    fun cursorAppendsToLastBlock() {
        val p = parseMarkdownBlocks("hello", cursor = true).single() as MdBlock.Paragraph
        assertEquals(listOf<MdSpan>(MdSpan.Text("hello"), MdSpan.Text(MD_CURSOR)), p.lines.single())

        val list = parseMarkdownBlocks("- 甲\n- 乙", cursor = true).single() as MdBlock.ListBlock
        assertEquals(MD_CURSOR, (list.items.last().spans.last() as MdSpan.Text).text)

        val code = parseMarkdownBlocks("```\ncode", cursor = true).single() as MdBlock.CodeBlock
        assertEquals("code▌", code.code)

        // 空内容无块：cursor 不产生异常
        assertTrue(parseMarkdownBlocks("", cursor = true).isEmpty())
    }

    @Test
    fun streamingMixedContentParsesProgressively() {
        // 流式中途：未闭合 ** 按字面量、已闭合语素正常渲染（渐进安全）
        val blocks = parseMarkdownBlocks("## 结论\n**部分加粗中", cursor = true)
        assertEquals(2, blocks.size)
        assertIs<MdBlock.Heading>(blocks[0])
        val p = assertIs<MdBlock.Paragraph>(blocks[1])
        assertEquals(
            listOf<MdSpan>(MdSpan.Text("**部分加粗中"), MdSpan.Text(MD_CURSOR)),
            p.lines.single(),
        )
    }

    // ── wp-6 纯文本回退（MarkdownText fallback 分支：gate→null + 流式补 ▌） ──

    @Test
    fun fallbackGateSendsPlainTextWithoutMarkdownTokensToNull() {
        // 无 Markdown 语素（最常见的纯中文流式输出）→ 不进解析器，调用方走纯文本回退
        assertNull(markdownBlocksOrFallback("正在梳理结论，稍等", cursor = true))
        assertNull(markdownBlocksOrFallback("第一行\n第二行", cursor = false))
        // 含 Markdown 语素 → 正常进解析器（gate 不吞 markdown 行）
        assertEquals(1, markdownBlocksOrFallback("# 标题", cursor = false)?.size)
    }

    @Test
    fun fallbackPlainTextKeepsStreamingCursor() {
        // 回归锚：wp-6 流式行「content + ▌」在纯文本回退分支同样成立（回退不得忽略光标）
        assertEquals("正在回复" + MD_CURSOR, fallbackPlainText("正在回复", cursor = true))
        // 终态行不加光标，逐字一致
        assertEquals("回复完成", fallbackPlainText("回复完成", cursor = false))
    }
}
