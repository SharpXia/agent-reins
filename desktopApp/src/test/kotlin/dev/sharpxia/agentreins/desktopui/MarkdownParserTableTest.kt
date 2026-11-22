package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 批次二表格解析单测（2026-11-23，desktopApp jvmTest）：GFM 最小口径——列数一致才成表、
 * 无分隔行/块级打断/半流中断一律落回段落字面量（wp-6 纯文本等价契约不破）、`\|` 不切列、
 * 三态对齐、短行补空长行截断、cursor 接尾单元格、单元格软断行幂等。
 * 验收口径与需求 1 对齐：所有表格完整显示无「…」（渲染层单元格无 maxLines/Ellipsis）。
 */
class MarkdownParserTableTest {

    // ── 成表：标准表 / 对齐 / 补空截断 ──

    @Test
    fun standardTableParsesWithHeaderRowsAndDefaultAlign() {
        val blocks = parseMarkdownBlocks("| A | B |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |")
        assertEquals(1, blocks.size)
        val table = assertIs<MdBlock.Table>(blocks.single())
        assertEquals(
            listOf(listOf<MdSpan>(MdSpan.Text("A")), listOf<MdSpan>(MdSpan.Text("B"))),
            table.header,
        )
        assertEquals(
            listOf(
                listOf(listOf<MdSpan>(MdSpan.Text("1")), listOf<MdSpan>(MdSpan.Text("2"))),
                listOf(listOf<MdSpan>(MdSpan.Text("3")), listOf<MdSpan>(MdSpan.Text("4"))),
            ),
            table.rows,
        )
        assertEquals(listOf(MdTableAlign.None, MdTableAlign.None), table.aligns)
    }

    @Test
    fun alignmentVariantsMapToLeftCenterRight() {
        val table = assertIs<MdBlock.Table>(
            parseMarkdownBlocks("| A | B | C |\n| :--- | :---: | ---: |\n| 1 | 2 | 3 |").single(),
        )
        assertEquals(listOf(MdTableAlign.Left, MdTableAlign.Center, MdTableAlign.Right), table.aligns)
    }

    @Test
    fun shortRowsPadAndLongRowsTruncateToHeaderColumnCount() {
        val table = assertIs<MdBlock.Table>(
            parseMarkdownBlocks("| A | B |\n| --- | --- |\n| 单格 |\n| 1 | 2 | 3 |").single(),
        )
        // 短行补空到 2 列
        assertEquals(2, table.rows[0].size)
        assertEquals(listOf<MdSpan>(MdSpan.Text("单格")), table.rows[0][0])
        assertEquals(emptyList(), table.rows[0][1])
        // 长行截断到 2 列（多余格忽略，GFM 同口径：第三格 "3" 被截掉）
        assertEquals(2, table.rows[1].size)
        assertEquals(listOf<MdSpan>(MdSpan.Text("1")), table.rows[1][0])
        assertEquals(listOf<MdSpan>(MdSpan.Text("2")), table.rows[1][1])
    }

    @Test
    fun unboundedPipeStaysInsideCell() {
        // `\|` 为字面量竖线不切列（GFM 转义同款）
        val table = assertIs<MdBlock.Table>(
            parseMarkdownBlocks("| a \\| b | c |\n| --- | --- |\n| d | e |").single(),
        )
        assertEquals(
            listOf(listOf<MdSpan>(MdSpan.Text("a | b")), listOf<MdSpan>(MdSpan.Text("c"))),
            table.header,
        )
    }

    @Test
    fun tableCanInterruptParagraphAndBeInterruptedByBlock() {
        // 表格打断段落（GFM 最小口径：列数一致即成表，与 cmark-gfm 更细判定差异知情）
        val blocks = parseMarkdownBlocks("正文前导\n| A | B |\n| --- | --- |\n| 1 | 2 |")
        assertEquals(2, blocks.size)
        assertIs<MdBlock.Paragraph>(blocks[0])
        assertIs<MdBlock.Table>(blocks[1])
        // 围栏打断表体（块级结构结束表格，不吞进单元格）
        val mixed = parseMarkdownBlocks("| A | B |\n| --- | --- |\n| 1 | 2 |\n```\ncode\n```")
        assertEquals(2, mixed.size)
        assertEquals(1, assertIs<MdBlock.Table>(mixed[0]).rows.size)
        assertEquals("code", assertIs<MdBlock.CodeBlock>(mixed[1]).code)
    }

    // ── 不成表：逐字原样（wp-6 纯文本等价） ──

    @Test
    fun missingSeparatorRowFallsBackLiteral() {
        val blocks = parseMarkdownBlocks("a | b\nc | d")
        assertEquals(1, blocks.size)
        val p = assertIs<MdBlock.Paragraph>(blocks.single())
        assertEquals(
            listOf(
                listOf<MdSpan>(MdSpan.Text("a | b")),
                listOf<MdSpan>(MdSpan.Text("c | d")),
            ),
            p.lines,
        )
    }

    @Test
    fun columnCountMismatchFallsBackLiteral() {
        // 表头 2 列、分隔行 1 列 → 不成表，逐字原样
        val blocks = parseMarkdownBlocks("a | b\n---")
        assertEquals(1, blocks.size)
        val p = assertIs<MdBlock.Paragraph>(blocks.single())
        assertEquals(
            listOf(
                listOf<MdSpan>(MdSpan.Text("a | b")),
                listOf<MdSpan>(MdSpan.Text("---")),
            ),
            p.lines,
        )
    }

    @Test
    fun delimiterShapedHeaderIsNotATable() {
        // 「--- 行 + --- 行」：表头本身是分隔行形态 → 不成表（逐字原样）
        val blocks = parseMarkdownBlocks("| --- | --- |\n| --- | --- |")
        assertEquals(1, blocks.size)
        assertIs<MdBlock.Paragraph>(blocks.single())
    }

    @Test
    fun halfStreamLineBreaksOutAsParagraph() {
        // 半流不落表（同 Image 纪律）：分隔行未到位 / 分隔行带尾随内容均按段落字面量 + 光标
        val partial1 = parseMarkdownBlocks("| A | B", cursor = true).single()
        val p1 = assertIs<MdBlock.Paragraph>(partial1)
        assertEquals(MD_CURSOR, (p1.lines.single().last() as MdSpan.Text).text)

        val partial2 = parseMarkdownBlocks("| A | B |\n| --- | ---▌", cursor = true).single()
        val p2 = assertIs<MdBlock.Paragraph>(partial2)
        assertEquals(MD_CURSOR, (p2.lines.last().last() as MdSpan.Text).text)
    }

    // ── 光标 ──

    @Test
    fun cursorJoinsLastCellOfLastRow() {
        val table = assertIs<MdBlock.Table>(
            parseMarkdownBlocks("| A | B |\n| --- | --- |\n| 1 | 2", cursor = true).single(),
        )
        val lastRow = table.rows.last()
        assertEquals(
            listOf(listOf<MdSpan>(MdSpan.Text("1")), listOf<MdSpan>(MdSpan.Text("2"), MdSpan.Text(MD_CURSOR))),
            lastRow,
        )
    }

    // ── 闸门放行 ──

    @Test
    fun tableGatedThroughLooksLikeMarkdown() {
        // 表头含 | 且次行为分隔行形态 → 放行（否则表格消息误落纯文本回退）
        assertTrue(looksLikeMarkdown("| A | B |\n| --- | --- |\n| 1 | 2 |"))
        assertTrue(looksLikeMarkdown("a | b\n--- | ---"))
        // 无分隔行形态 → 不放行（多走一次解析也无害，但闸门如实收窄）
        assertFalse(looksLikeMarkdown("a | b\nc | d"))
        assertFalse(looksLikeMarkdown("| 单行竖线"))
    }

    // ── 单元格软断行 ──

    @Test
    fun softBreakInsertsEverySixteenVisibleChars() {
        // 19 字符 → 第 16 位后插一个 U+200B
        assertEquals(
            "abcdefghijklmnop" + MD_SOFT_BREAK + "qrs",
            softBreakText("abcdefghijklmnopqrs"),
        )
        // 恰 16 字符 → 末位不插（无尾随断点）
        assertEquals("abcdefghijklmnop", softBreakText("abcdefghijklmnop"))
        // 短文本原样
        assertEquals("短文本", softBreakText("短文本"))
    }

    @Test
    fun softBreakSpansAreIdempotent() {
        val original = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEF" // 42 字符 → 2 个断点
        val once = softBreakText(original)
        val twice = softBreakText(once)
        assertEquals(once, twice)
        // 断点数 = 每 16 可见字符 1 个
        assertEquals(2, once.count { it == '\u200B' })
        // 已有断点不计可见数（21 可见字符只在第 16 位补 1 个新断点，旧断点原样保留）
        val pre = "ab" + MD_SOFT_BREAK + "cdefghijklmnopqrstu"
        val reapplied = softBreakText(pre)
        assertEquals(2, reapplied.count { it == '\u200B' })
        assertEquals(pre.length + 1, reapplied.length)
        assertEquals(softBreakText(reapplied), reapplied) // 再应用恒等（幂等收敛）
    }

    @Test
    fun softBreakSpansCoverAllSpanVariantsAndKeepUrl() {
        val spans = listOf(
            MdSpan.Text("https://example.com/" + "a".repeat(30)),
            MdSpan.Code("x".repeat(20)),
            MdSpan.Link("www.example.com/" + "b".repeat(20), "https://www.example.com/b"),
        )
        val broken = softBreakSpans(spans)
        // 每个变体都被软断（文本变长）
        assertEquals(spans.size, broken.size)
        assertTrue(broken[0].let { it as MdSpan.Text }.text.contains('\u200B'))
        assertTrue(broken[1].let { it as MdSpan.Code }.text.contains('\u200B'))
        // Link 只断展示文本，url 原样（打开面不受软断影响）
        val link = broken[2] as MdSpan.Link
        assertTrue(link.text.contains('\u200B'))
        assertEquals("https://www.example.com/b", link.url)
    }

    // ── wp-6 纯文本等价回归（非表序列逐字原样，含 \| 未解释口径） ──

    @Test
    fun escapedPipeOutsideTableStaysLiteral() {
        // 非表行（无分隔行）的 `\|` 不在行内转义最小集——原样保留（与表格路径互不干扰）
        val blocks = parseMarkdownBlocks("a \\| b\nc \\| d")
        val p = assertIs<MdBlock.Paragraph>(blocks.single())
        assertEquals(
            listOf(
                listOf<MdSpan>(MdSpan.Text("a \\| b")),
                listOf<MdSpan>(MdSpan.Text("c \\| d")),
            ),
            p.lines,
        )
    }
}
