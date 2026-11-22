package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 批次二链接化单测（2026-11-23，desktopApp jvmTest）：linkifySpans 切分闭集（只切平铺 Text、
 * Code/Bold 内不切、句尾标点剥离）、normalizeUrl 归一（www 补 scheme、其余 null）、
 * looksLikeMarkdown 裸 URL 放行（解析路径覆盖）与裸 URL 解析产物逐字等价（wp-6 契约不破）。
 * 双路径口径与 iOS 对齐锚已落码注释：解析路径链接化 + 解析异常回退不链接化。
 */
class LinkifySpansTest {

    // ── linkifySpans：切分 ──

    @Test
    fun httpAndWwwMixedSplitsIntoTextAndLink() {
        assertEquals(
            listOf(
                MdSpan.Text("看 "),
                MdSpan.Link("https://e.com/a", "https://e.com/a"),
                MdSpan.Text(" 和 "),
                MdSpan.Link("www.b.com/x", "https://www.b.com/x"),
                MdSpan.Text(" 完"),
            ),
            linkifySpans(listOf(MdSpan.Text("看 https://e.com/a 和 www.b.com/x 完"))),
        )
    }

    @Test
    fun inlineCodeAndBoldAreNotLinkified() {
        // 行内代码内 URL 不转（iOS autolink 只跳显式链接与行内代码——此形态两端同语义）
        assertEquals(
            listOf(MdSpan.Code("see https://e.com/x")),
            linkifySpans(listOf(MdSpan.Code("see https://e.com/x"))),
        )
        // Bold/Italic/BoldItalic 内不转——桌面比 iOS 更窄的知情口径：iOS autolink 对粗体内
        // URL 会切 .link run（可点），桌面不切（见 linkifySpans 注，自检轮 R1-4）
        assertEquals(
            listOf(MdSpan.Bold("https://e.com/bold")),
            linkifySpans(listOf(MdSpan.Bold("https://e.com/bold"))),
        )
        assertEquals(
            listOf(MdSpan.Italic("www.e.com/i"), MdSpan.BoldItalic("x")),
            linkifySpans(listOf(MdSpan.Italic("www.e.com/i"), MdSpan.BoldItalic("x"))),
        )
    }

    @Test
    fun midWordWwwDoesNotLinkify() {
        // www 分支前置词边界（自检轮 R1-14，对齐 iOS NSDataDetector 自带词边界语义）：词中
        // www.（域中段/邮箱本地部）不产假链接，整段保持 Text（纯文本等价面逐字保留）
        assertEquals(
            listOf(MdSpan.Text("foo.www.bar/baz")),
            linkifySpans(listOf(MdSpan.Text("foo.www.bar/baz"))),
        )
        assertEquals(
            listOf(MdSpan.Text("user@www.example.com")),
            linkifySpans(listOf(MdSpan.Text("user@www.example.com"))),
        )
        // 正向对照：句首/空白后/括号后的 www 仍命中
        assertEquals(
            listOf(
                MdSpan.Link("www.e.com/start", "https://www.e.com/start"),
                MdSpan.Text(" 与 "),
                MdSpan.Link("www.b.com/x", "https://www.b.com/x"),
                MdSpan.Text("）"),
            ),
            linkifySpans(listOf(MdSpan.Text("www.e.com/start 与 www.b.com/x）"))),
        )
    }

    @Test
    fun zeroWidthBreakInsideUrlKeepsDisplayAndCleansUrl() {
        // 自检轮 R1-1/R1-17 单测面：软断行 U+200B 混进 URL 匹配段（表格单元格管线常态）——
        // Link 展示文本保留断点（折行面），url 剥净（openUri/剪贴板面）
        assertEquals(
            listOf(
                MdSpan.Text("看 "),
                MdSpan.Link("https://e.com/aa\u200Bbb", "https://e.com/aabb"),
                MdSpan.Text(" 完"),
            ),
            linkifySpans(listOf(MdSpan.Text("看 https://e.com/aa\u200Bbb 完"))),
        )
    }

    @Test
    fun trailingPunctuationStrippedAndKeptInText() {
        // 句尾标点剥离：标点留在后续 Text 段（纯文本等价面不丢字符）
        assertEquals(
            listOf(
                MdSpan.Text("见 "),
                MdSpan.Link("https://e.com/a", "https://e.com/a"),
                MdSpan.Text("。"),
            ),
            linkifySpans(listOf(MdSpan.Text("见 https://e.com/a。"))),
        )
        assertEquals(
            listOf(
                MdSpan.Link("https://e.com/a", "https://e.com/a"),
                MdSpan.Text(", 再见"),
            ),
            linkifySpans(listOf(MdSpan.Text("https://e.com/a, 再见"))),
        )
        // 文件名形态不受误剥（尾字符非标点）
        assertEquals(
            listOf(MdSpan.Link("https://e.com/a.png", "https://e.com/a.png")),
            linkifySpans(listOf(MdSpan.Text("https://e.com/a.png"))),
        )
    }

    @Test
    fun multipleUrlsAndChineseContext() {
        // CJK 不在 URL 排除集（正则字面口径，与 iOS NSDataDetector 宽松匹配同向）——中英文
        // 粘连 URL 会整体成 Link；句尾 CJK 标点由剥离集兜底（。，等不进 Link）。空格分隔的
        // 多 URL 逐一切分：
        assertEquals(
            listOf(
                MdSpan.Text("中文前 "),
                MdSpan.Link("https://a.com", "https://a.com"),
                MdSpan.Text(" 与 "),
                MdSpan.Link("www.b.cn", "https://www.b.cn"),
                MdSpan.Text(" 中文后"),
            ),
            linkifySpans(listOf(MdSpan.Text("中文前 https://a.com 与 www.b.cn 中文后"))),
        )
        // URL 后紧跟 CJK 句号收尾（行末）：句尾标点剥离兜底（Link 不含句号，标点留在后续
        // Text）——句中粘连 CJK（如「https://e.com/a。后续」）按正则字面口径整体成 Link，
        // 属已知观感面（见 MD_URL_REGEX 注），空格/标点收尾是剥离兜底的生效形态
        assertEquals(
            listOf(
                MdSpan.Text("详见 "),
                MdSpan.Link("https://e.com/a", "https://e.com/a"),
                MdSpan.Text("。"),
            ),
            linkifySpans(listOf(MdSpan.Text("详见 https://e.com/a。"))),
        )
    }

    @Test
    fun linkifySpansIsIdempotentOnExistingLinks() {
        val linked = linkifySpans(listOf(MdSpan.Text("看 https://e.com/a")))
        assertEquals(linked, linkifySpans(linked))
    }

    // ── normalizeUrl：归一 ──

    @Test
    fun normalizeUrlSchemeRules() {
        assertEquals("https://e.com/a", normalizeUrl("https://e.com/a")) // https 原样
        assertEquals("http://192.168.1.4:8080/x.png", normalizeUrl("http://192.168.1.4:8080/x.png")) // http 原样
        assertEquals("https://www.b.com/x", normalizeUrl("www.b.com/x")) // www 补 https
        assertNull(normalizeUrl("ftp://e.com/f")) // 其余 scheme null（不入 Link）
        assertNull(normalizeUrl("example.com/no-scheme")) // 无 scheme null
        assertNull(normalizeUrl("")) // 空 null
    }

    // ── 解析路径覆盖：闸门放行 + 解析产物逐字等价 ──

    @Test
    fun bareUrlMessagesGateThrough() {
        assertTrue(looksLikeMarkdown("https://example.com/path?a=1"))
        assertTrue(looksLikeMarkdown("www.example.com/x"))
        assertTrue(looksLikeMarkdown("详见 https://e.com/docs 或 www.b.cn"))
        // 既有纯文本口径不回归
        assertFalse(looksLikeMarkdown("普通中文回复，没有链接。"))
        assertFalse(looksLikeMarkdown("第一行\n第二行"))
    }

    @Test
    fun bareUrlParseProductIsVerbatimParagraph() {
        // 裸 URL 消息进解析管线：产物 = 单段落单 Text，与原文逐字一致（wp-6 契约不破）
        val raw = "https://example.com/path?a=1"
        val block = parseMarkdownBlocks(raw).single()
        val paragraph = assertIs<MdBlock.Paragraph>(block)
        assertEquals(listOf(listOf<MdSpan>(MdSpan.Text(raw))), paragraph.lines)
        // 且 linkifySpans 对解析产物产出 Link span（渲染层链接化出口）
        assertEquals(
            listOf(MdSpan.Link(raw, raw)),
            linkifySpans(paragraph.lines.single()),
        )
    }

    @Test
    fun urlWithUnderscoreParsesVerbatim() {
        // 含 _ 的 URL：词边界规则挡住强调误判（intraword 下划线不成强调），产物逐字等价
        val raw = "https://example.com/a_b_c?q=1"
        val paragraph = assertIs<MdBlock.Paragraph>(parseMarkdownBlocks(raw).single())
        assertEquals(listOf(listOf<MdSpan>(MdSpan.Text(raw))), paragraph.lines)
    }
}
