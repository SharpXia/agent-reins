package dev.sharpxia.agentreins.desktopui

/**
 * wp-18：桌面助手行轻量 Markdown 解析器（纯 Kotlin，无 Compose 依赖，单测直接覆盖）。
 *
 * 与 iOS Chat/MarkdownView.swift（LiteMarkdownParser，cmark-gfm 同源）的起步同位：本解析器
 * 只覆盖「标题 / 列表 / 围栏代码块 / 粗斜体 + 行内代码」起步集——表格、引用、嵌套强调、远程
 * 图片不在本期（iOS 版已知取舍同理：解析不了的块按普通段落原样呈现，不丢文本）。
 *
 * 回退契约（验收硬要求）：[looksLikeMarkdown] 为 false 或解析抛异常时，调用方必须走 wp-6
 * MVP 纯文本路径原样渲染——解析器永不改变「纯文本等价」语义：任何未闭合/不构成 Markdown
 * 语素的字符序列，解析产物必须与原文本逐字一致（未配对 `*`/`` ` ``/`_` 按字面量保留）。
 */

/** 块级产物。行内 span 全部平铺（不支持嵌套强调——`**a *b* c**` 内层 `*` 按字面量）。 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    /** 段落：多行原文按行独立解析后合并（保留作者换行，聊天消息依赖换行排版）。 */
    data class Paragraph(val lines: List<List<MdSpan>>) : MdBlock
    data class CodeBlock(val code: String) : MdBlock
    data class ListBlock(val items: List<MdListItem>) : MdBlock
}

internal data class MdListItem(
    /** 字面标记（"-" / "*" / "+" / "1." / "2)"），照原样渲染不做归一（与 iOS item.marker 同口径）。 */
    val marker: String,
    /** 缩进层级：每 2 空格一层，上限 4（轻量近似，不做 CommonMark 全规则）。 */
    val depth: Int,
    val spans: List<MdSpan>,
)

/** 行内 span（平铺一层）。 */
internal sealed interface MdSpan {
    data class Text(val text: String) : MdSpan
    data class Bold(val text: String) : MdSpan
    data class Italic(val text: String) : MdSpan
    data class BoldItalic(val text: String) : MdSpan
    data class Code(val text: String) : MdSpan
}

/** 流式行光标（wp-6 既有形态：内容尾随 ▌）。 */
internal const val MD_CURSOR = "▌"

private val MD_HEADING = Regex("""^(#{1,6})\s+(.+)$""")
private val MD_LIST_ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.+)$""")
private const val MD_MAX_LIST_DEPTH = 4

/**
 * 是否含 Markdown 语素（wp-6 回退闸门）：false → 调用方直接走纯文本路径，不进解析器。
 * 判定刻意宽松（单个 `*`/`` ` ``/`_` 即命中）——宽松只会多走一次解析，解析结果对纯文本
 * 序列逐字等价（未配对定界符按字面量保留），不会误渲染。
 */
internal fun looksLikeMarkdown(text: String): Boolean {
    if (text.contains('*') || text.contains('`') || text.contains('_')) return true
    return text.lineSequence().any { line ->
        val t = line.trimStart()
        t.startsWith("```") || t.startsWith("~~~") ||
            MD_HEADING.matches(t) || MD_LIST_ITEM.matches(t)
    }
}

/**
 * 渲染闸门（MarkdownText 用）：[looksLikeMarkdown] 为 false 或解析抛异常 → null，调用方
 * 走 wp-6 纯文本回退（[fallbackPlainText]）；否则返回解析产物（[cursor] 已接到末块）。
 */
internal fun markdownBlocksOrFallback(text: String, cursor: Boolean): List<MdBlock>? =
    if (!looksLikeMarkdown(text)) null
    else runCatching { parseMarkdownBlocks(text, cursor) }.getOrNull()

/**
 * wp-6 纯文本回退渲染串：流式行同形补尾随 ▌（wp-6 MVP「content + ▌」形态）——回归修复：
 * 回退分支与解析路径的 cursor 语义一致，无 Markdown 语素/解析异常的流式行不得丢光标。
 * blank 正文由调用方 AssistantMessageRow 的流式空行占位（…▌）先行分流，不进本函数。
 */
internal fun fallbackPlainText(text: String, cursor: Boolean): String =
    if (cursor) text + MD_CURSOR else text

/**
 * 块级解析入口。[cursor] = true 时把流式光标接到最后一个块的末尾（段落行 / 标题 / 列表项
 * 尾部或代码块内），对应 wp-6 流式行「content + ▌」形态。
 */
internal fun parseMarkdownBlocks(text: String, cursor: Boolean = false): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraphLines = mutableListOf<List<MdSpan>>()
    val listItems = mutableListOf<MdListItem>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraphLines.toList())
            paragraphLines.clear()
        }
    }

    fun flushList() {
        if (listItems.isNotEmpty()) {
            blocks += MdBlock.ListBlock(listItems.toList())
            listItems.clear()
        }
    }

    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                flushParagraph(); flushList()
                val fence = trimmed.take(3)
                val code = StringBuilder()
                i++
                while (i < lines.size) {
                    val inner = lines[i]
                    if (inner.trimStart().startsWith(fence)) { i++; break } // 闭合围栏消费掉
                    code.append(inner).append('\n')
                    i++
                }
                // 未闭合围栏：剩余全部按代码块（流式渐进安全，不把后半段当正文误渲染）
                if (code.isNotEmpty()) code.deleteCharAt(code.length - 1)
                blocks += MdBlock.CodeBlock(code.toString())
            }
            line.isBlank() -> { flushParagraph(); flushList(); i++ }
            else -> {
                val heading = MD_HEADING.matchEntire(line.trimStart())
                val item = MD_LIST_ITEM.matchEntire(line)
                when {
                    heading != null -> {
                        flushParagraph(); flushList()
                        blocks += MdBlock.Heading(
                            level = heading.groupValues[1].length,
                            spans = parseInlineSpans(heading.groupValues[2].trim()),
                        )
                        i++
                    }
                    item != null -> {
                        flushParagraph()
                        val indent = item.groupValues[1].length
                        listItems += MdListItem(
                            marker = item.groupValues[2],
                            depth = (indent / 2).coerceAtMost(MD_MAX_LIST_DEPTH),
                            spans = parseInlineSpans(item.groupValues[3].trimEnd()),
                        )
                        i++
                    }
                    else -> {
                        flushList()
                        paragraphLines += parseInlineSpans(line.trimEnd())
                        i++
                    }
                }
            }
        }
    }
    flushParagraph(); flushList()

    if (cursor) {
        val last = blocks.lastOrNull()
        if (last != null) {
            blocks[blocks.lastIndex] = when (last) {
                is MdBlock.Paragraph -> last.copy(
                    lines = last.lines.mapIndexed { idx, l ->
                        if (idx == last.lines.lastIndex) l + MdSpan.Text(MD_CURSOR) else l
                    },
                )
                is MdBlock.Heading -> last.copy(spans = last.spans + MdSpan.Text(MD_CURSOR))
                is MdBlock.ListBlock -> last.copy(
                    items = last.items.mapIndexed { idx, it ->
                        if (idx == last.items.lastIndex) it.copy(spans = it.spans + MdSpan.Text(MD_CURSOR)) else it
                    },
                )
                is MdBlock.CodeBlock -> last.copy(code = last.code + MD_CURSOR)
            }
        }
    }
    return blocks
}

/** 行内解析：反引号代码段优先（段内不再解析强调），其余按 `*`/`_` 定界符扫描。 */
internal fun parseInlineSpans(line: String): List<MdSpan> {
    val spans = mutableListOf<MdSpan>()
    val plain = StringBuilder()
    var i = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            spans += MdSpan.Text(plain.toString())
            plain.clear()
        }
    }

    while (i < line.length) {
        val c = line[i]
        when {
            // 最小转义集：\* \_ \` \\ → 字面量（其余反斜杠原样保留）
            c == '\\' && i + 1 < line.length && line[i + 1] in "*_`\\" -> {
                plain.append(line[i + 1]); i += 2
            }
            c == '`' -> {
                val runLen = runLength(line, i, '`')
                val close = findBacktickRun(line, i + runLen, runLen)
                if (close == -1) {
                    plain.append(line[i]); i++ // 未闭合：反引号按字面量，不吞内容
                } else {
                    flushPlain()
                    var content = line.substring(i + runLen, close)
                    // CommonMark 惯例：两侧各恰一空格时剥掉一层
                    if (content.length >= 2 && content.first() == ' ' && content.last() == ' ') {
                        content = content.substring(1, content.length - 1)
                    }
                    spans += MdSpan.Code(content)
                    i = close + runLen
                }
            }
            c == '*' || c == '_' -> {
                val runLen = runLength(line, i, c)
                val delim = line.substring(i, i + runLen)
                // 开侧 flanking：定界符后不得是空格；`_` 另要求词边界（intraword 下划线不成强调，
                // snake_case_file_name 原样——`*` 无此约束，与 CommonMark 同口径）
                val openOk = (i + runLen < line.length && line[i + runLen] != ' ') &&
                    (c == '*' || (i == 0 || !line[i - 1].isLetterOrDigit()))
                val close = if (openOk) findClosingEmphasis(line, i + runLen, delim, c) else -1
                if (close == -1) {
                    plain.append(c); i++ // 未配对：逐字符保留（`**` 未闭合不能吞后续 `*`）
                } else {
                    flushPlain()
                    val inner = line.substring(i + runLen, close)
                    spans += when {
                        runLen >= 3 -> MdSpan.BoldItalic(inner)
                        runLen == 2 -> MdSpan.Bold(inner)
                        else -> MdSpan.Italic(inner)
                    }
                    i = close + runLen
                }
            }
            else -> { plain.append(c); i++ }
        }
    }
    flushPlain()
    return spans
}

/** 从 [start] 起的连续同字符段长度。 */
private fun runLength(line: String, start: Int, char: Char): Int {
    var n = 0
    while (start + n < line.length && line[start + n] == char) n++
    return n
}

/** 找与 [runLen] 等长的下一个反引号段起点（CommonMark 代码围栏等长配对的轻量近似）。 */
private fun findBacktickRun(line: String, from: Int, runLen: Int): Int {
    var idx = from
    while (idx <= line.length - runLen) {
        if (line[idx] == '`' && runLength(line, idx, '`') == runLen) return idx
        idx++
    }
    return -1
}

/**
 * 强调闭合定界符：同字面串的下一次出现，且闭合前不得是空格（右 flanking）；`_` 闭合后
 * 不得紧跟字母数字。找不到合规闭合返回 -1（按字面量处理）。
 */
private fun findClosingEmphasis(line: String, from: Int, delim: String, marker: Char): Int {
    var idx = from
    while (true) {
        idx = line.indexOf(delim, idx)
        if (idx == -1) return -1
        val prevOk = idx > from && line[idx - 1] != ' '
        val nextOk = if (marker == '_') {
            val n = idx + delim.length
            n >= line.length || !line[n].isLetterOrDigit()
        } else true
        if (prevOk && nextOk) return idx
        idx++
    }
}
