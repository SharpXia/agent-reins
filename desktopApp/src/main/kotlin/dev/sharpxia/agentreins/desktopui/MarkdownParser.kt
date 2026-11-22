package dev.sharpxia.agentreins.desktopui

/**
 * wp-18：桌面助手行轻量 Markdown 解析器（纯 Kotlin，无 Compose 依赖，单测直接覆盖）。
 *
 * 与 iOS Chat/MarkdownView.swift（LiteMarkdownParser，cmark-gfm 同源）的起步同位：本解析器
 * 覆盖「标题 / 列表 / 围栏代码块 / 粗斜体 + 行内代码」起步集；v1.2.0 多模态轮（设计稿
 * docs/multimodal-design-20261108.md §6 桌面行）增「整行独占」的图片块；2026-11-23 批次二轮
 * 增 GFM 最小口径表格（[MdBlock.Table]，列数一致才成表，无分隔行/块级打断落回段落字面量）
 * 与行内链接 span（[MdSpan.Link]，解析产物由 MarkdownText 渲染层 linkify 产出）——引用、
 * 嵌套强调仍不在本期（iOS 版已知取舍同理：解析不了的块按普通段落原样呈现，不丢文本）。
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
    /** [language] = 围栏 info string 首段（```json → "json"）：JSON 卡分支按它免首字符闸门；此前连语言都丢弃。 */
    data class CodeBlock(val code: String, val language: String? = null) : MdBlock
    data class ListBlock(val items: List<MdListItem>) : MdBlock
    /**
     * 图片块（v1.2.0 多模态，设计稿 §6 桌面行「MdBlock 增 Image 块」）：整行恰为一个
     * `![alt](url)` 语素才成块——行内混排、带标题形（url 含空格）、未闭合等一律按段落
     * 字面量保留（「纯文本等价」契约不破）。渲染双口径见 MarkdownText：https 内联 /
     * http 及其余 scheme 链接 chip（不发起请求）。
     */
    data class Image(val url: String, val alt: String) : MdBlock

    /**
     * 表格（2026-11-23 批次二轮，GFM 最小口径）：表头行含 `|` 且次行为分隔行形态、列数一致
     * 才成表——无分隔行 / 块级打断 / 半流中断一律落回段落字面量（wp-6「纯文本等价」契约不破）。
     * [header]/[rows] 单元格均为行内 span 平铺（短行按表头列数补空、长行截断，GFM 同口径）；
     * 单元格 span 已在解析期做长 token U+200B 软断行（[softBreakSpans]：连续无空白且 >24
     * 字符、且不含原生可断行 CJK 字符的 token 每 16 字符插断点，与 iOS cell 渲染期注入
     * softBreakLongTokens 同法同参同表——CJK 单元格原生可断行不吃零宽字符（自检轮 2
     * R2-2，复制面干净），URL/哈希完整显示无「…」验收不回退），[aligns] 与列一一对应。
     */
    data class Table(
        val header: List<List<MdSpan>>,
        val rows: List<List<List<MdSpan>>>,
        val aligns: List<MdTableAlign>,
    ) : MdBlock
}

/** 表格列对齐三态 + 无标注（GFM 分隔行 `:---` / `:---:` / `---:` / `---`）。 */
internal enum class MdTableAlign { Left, Center, Right, None }

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

    /**
     * 链接 span（2026-11-23 批次二轮）：不在解析期产出（[parseInlineSpans] 对无配对定界符
     * 逐字等价），由 MarkdownText.kt 渲染层 linkifySpans 对平铺 Text span 切分产出——
     * [text] = 原样匹配文本（展示/纯文本等价面），[url] = normalizeUrl 归一产物
     * （http(s) 原样 / www. 补 https://；打开与复制均用归一 url，桌面 Desktop.browse 要求
     * 绝对 URI）。
     */
    data class Link(val text: String, val url: String) : MdSpan
}

/** 流式行光标（wp-6 既有形态：内容尾随 ▌）。 */
internal const val MD_CURSOR = "▌"

/**
 * JSON 判定前剥离流式光标（MarkdownText 用）：流式行代码块末尾的 ▌（wp-6「content + ▌」
 * 语义）不是 JSON 语法成分，不剥会把合法终态误判成解析失败而永远回落既有渲染。
 */
internal fun stripMdCursor(text: String): String =
    if (text.endsWith(MD_CURSOR)) text.removeSuffix(MD_CURSOR) else text

private val MD_HEADING = Regex("""^(#{1,6})\s+(.+)$""")
private val MD_LIST_ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.+)$""")

/**
 * 图片行（v1.2.0 多模态）：整行（trim 后）恰为 `![alt](url)`。url 段禁空白与 `)`——带标题形
 * （`![a](u "t")`）与嵌套括号不成块，落回段落字面量；alt 段禁 `]`（嵌套/未闭合同理）。
 */
private val MD_IMAGE_LINE = Regex("""^!\[([^\]]*)\]\(([^\s)]+)\)$""")
private const val MD_MAX_LIST_DEPTH = 4

/** GFM 分隔行形态（表格闸门/解析共用）：可选边界竖线 + 每格 `:?-+:?`（至少一个 `-`）。 */
private val MD_TABLE_DELIM_ROW = Regex("""^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$""")
/** 分隔行单格形态（列级校验用）。 */
private val MD_TABLE_DELIM_CELL = Regex("""^\s*:?-+:?\s*$""")

/**
 * 是否含 Markdown 语素（wp-6 回退闸门）：false → 调用方直接走纯文本路径，不进解析器。
 * 判定刻意宽松（单个 `*`/`` ` ``/`_` 即命中）——宽松只会多走一次解析，解析结果对纯文本
 * 序列逐字等价（未配对定界符按字面量保留），不会误渲染。
 */
internal fun looksLikeMarkdown(text: String): Boolean {
    if (text.contains('*') || text.contains('`') || text.contains('_')) return true
    val lines = text.split('\n')
    // 表格语素（2026-11-23 批次二轮）：任一行含 | 且次行为分隔行形态（列数一致性等细则由
    // 解析器把关，闸门刻意宽松——多走一次解析，非表序列解析产物逐字等价）
    for (i in 0 until lines.size - 1) {
        if (lines[i].contains('|') && MD_TABLE_DELIM_ROW.matches(lines[i + 1])) return true
    }
    return lines.any { line ->
        val t = line.trimStart()
        t.startsWith("```") || t.startsWith("~~~") ||
            MD_HEADING.matches(t) || MD_LIST_ITEM.matches(t) ||
            // 图片行（v1.2.0 多模态）：`![..](..)` 不含 * ` _ 语素，需显式放行否则纯图片
            // 消息（生图输出常见形态）会误落纯文本回退
            MD_IMAGE_LINE.matches(t.trim()) ||
            // URL 语素（2026-11-23 批次二轮链接化）：裸 URL 消息必须进解析管线吃链接化，
            // 不得落纯文本回退（对齐 iOS isPlain 刻意排除 http/www 的口径，MarkdownView
            // .swift isPlain 返回句——确认评审收口改符号引用防漂移；含 _ 的 URL 已被词边界
            // 规则挡住不误判强调，解析产物逐字等价）。小写判定（确认评审收口，对齐 iOS
            // lowercased 前缀判定大小写不敏感）——大写 HTTP(S)/WWW. 消息同样进管线，否则
            // 桌面留纯文本不可点而 iOS 可点，双端口径劈叉。
            t.lowercase().contains("http://") || t.lowercase().contains("https://")
                || t.lowercase().contains("www.")
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
                // info string 首段作语言标注（```json extra → "json"）：dropWhile 兼容 4+ 反引号围栏，
                // 首段（首个空白前）即语言，其余参数按 CommonMark 惯例忽略
                val language = trimmed.dropWhile { it == '`' || it == '~' }.trim()
                    .substringBefore(' ').takeIf { it.isNotEmpty() }
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
                blocks += MdBlock.CodeBlock(code.toString(), language)
            }
            line.isBlank() -> { flushParagraph(); flushList(); i++ }
            else -> {
                val heading = MD_HEADING.matchEntire(line.trimStart())
                val item = MD_LIST_ITEM.matchEntire(line)
                val image = MD_IMAGE_LINE.matchEntire(line.trim())
                // 表格（2026-11-23 批次二轮）：表头含 | 且次行为分隔行才尝试成表（列数一致
                // 才成表、块级打断等细则在 parseTableBlock 内把关）；不成立落回段落字面量
                val table = if (line.contains('|')) parseTableBlock(lines, i) else null
                when {
                    heading != null -> {
                        flushParagraph(); flushList()
                        blocks += MdBlock.Heading(
                            level = heading.groupValues[1].length,
                            spans = parseInlineSpans(heading.groupValues[2].trim()),
                        )
                        i++
                    }
                    image != null -> {
                        // 图片块（v1.2.0 多模态）：整行独占才成块，alt/url 按 MD_IMAGE_LINE 捕获
                        flushParagraph(); flushList()
                        blocks += MdBlock.Image(
                            url = image.groupValues[2],
                            alt = image.groupValues[1],
                        )
                        i++
                    }
                    table != null -> {
                        // 表格块成块：表头前的段落/列表照常收口（表格可打断段落——GFM 最小
                        // 口径，列数一致即成表；知情注释：cmark-gfm 对段落内表格有更细判定）
                        flushParagraph(); flushList()
                        blocks += table.table
                        i = table.endExclusive
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
                // 图片块不承载光标：图片语素是完整行（半流期尾随 ▌ 使其不成块、按段落字面量
                // + 光标呈现，终态才收敛为 Image——与 JSON 卡「半流行不出卡」同向，无闪变）
                is MdBlock.Image -> last
                is MdBlock.Table -> last.copy(
                    rows = last.rows.mapIndexed { idx, row ->
                        // 光标接到最后一行末单元格（与 Paragraph/ListBlock 同型）；无表体
                        // （仅表头+分隔行，流式瞬态）不挂光标——下一帧表体到位即接上
                        if (idx == last.rows.lastIndex && row.isNotEmpty()) {
                            row.dropLast(1) + listOf(row.last() + MdSpan.Text(MD_CURSOR))
                        } else {
                            row
                        }
                    },
                )
            }
        }
    }
    return blocks
}

// ── 表格（2026-11-23 批次二轮，GFM 最小口径）+ 单元格软断行 ─────────────────────

/** 表格解析产物：[table] 块 + 消费结束下标（exclusive，供主循环跳行）。 */
internal data class MdTableParse(val table: MdBlock.Table, val endExclusive: Int)

/**
 * 表格解析（GFM 最小口径）：[lines[startIndex]] 为表头行（须含 `|` 且本身不是分隔行形态），
 * [lines[startIndex + 1]] 须为分隔行且列数与表头一致——任一不成立返回 null（调用方落回
 * 段落字面量，wp-6「纯文本等价」契约不破）。表体行消费至块级打断（空行/围栏/标题/列表/
 * 图片行）或无 `|` 行；短行按表头列数补空、长行截断（GFM 同口径）。单元格 span 在解析期
 * 做长 token 软断行（[softBreakSpans]，与 iOS cell 渲染期注入同法同参——验收=完整显示无「…」）。
 */
internal fun parseTableBlock(lines: List<String>, startIndex: Int): MdTableParse? {
    if (startIndex + 1 >= lines.size) return null
    val headerLine = lines[startIndex]
    if (!headerLine.contains('|')) return null
    val headerCells = tableRowCells(headerLine) ?: return null
    if (headerCells.isEmpty()) return null
    // 表头本身是分隔行形态 → 不是表（防「--- 行 + --- 行」误判成表头）
    if (headerCells.all { MD_TABLE_DELIM_CELL.matches(it) }) return null
    val delimLine = lines[startIndex + 1]
    if (!MD_TABLE_DELIM_ROW.matches(delimLine)) return null
    val delimCells = stripBoundaryCells(splitUnescapedPipe(delimLine))
    if (delimCells.isEmpty() || delimCells.size != headerCells.size) return null
    if (delimCells.any { !MD_TABLE_DELIM_CELL.matches(it) }) return null
    val aligns = delimCells.map { cell ->
        val t = cell.trim()
        val left = t.startsWith(':')
        val right = t.endsWith(':')
        when {
            left && right -> MdTableAlign.Center
            right -> MdTableAlign.Right
            left -> MdTableAlign.Left
            else -> MdTableAlign.None
        }
    }
    val columnCount = headerCells.size
    val rows = mutableListOf<List<List<MdSpan>>>()
    var end = startIndex + 2
    while (end < lines.size && isTableRowCandidate(lines[end])) {
        val cells = tableRowCells(lines[end]) ?: break
        // 短行补空 / 长行截断（GFM：列数以表头为准，多余格忽略）
        val padded = cells.take(columnCount) + List((columnCount - cells.size).coerceAtLeast(0)) { "" }
        rows += padded.map { softBreakSpans(parseInlineSpans(it)) }
        end++
    }
    return MdTableParse(
        table = MdBlock.Table(
            header = headerCells.map { softBreakSpans(parseInlineSpans(it)) },
            rows = rows,
            aligns = aligns,
        ),
        endExclusive = end,
    )
}

/** 表行 → 单元格（含 `|` 才是行；边界可选竖线剥除、格内 trim；`\|` 转义见 [splitUnescapedPipe]）。 */
private fun tableRowCells(line: String): List<String>? =
    if (line.contains('|')) stripBoundaryCells(splitUnescapedPipe(line)) else null

/**
 * 按「未转义竖线」切格：`\|` 为字面量竖线不切列（GFM 转义同款），切出的格保留原文
 * （剥边界/trim 在 [stripBoundaryCells] 做）；其余反斜杠不解释——非表行的 `\|` 仍走段落
 * 字面量路径原样保留（行内转义最小集不含 `|`，两路径口径互不干扰）。
 */
internal fun splitUnescapedPipe(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < line.length) {
        val c = line[i]
        if (c == '\\' && i + 1 < line.length && line[i + 1] == '|') {
            current.append('|'); i += 2
        } else if (c == '|') {
            cells += current.toString(); current.clear(); i++
        } else {
            current.append(c); i++
        }
    }
    cells += current.toString()
    return cells
}

/** 可选边界竖线产生的首/尾空格剥一个（GFM 惯例），余格 trim。 */
private fun stripBoundaryCells(cells: List<String>): List<String> {
    var out = cells
    if (out.size >= 2 && out.first().isBlank()) out = out.drop(1)
    if (out.size >= 2 && out.last().isBlank()) out = out.dropLast(1)
    return out.map { it.trim() }
}

/** 表体行候选：非空、含 `|`，且不是块级打断形态（空行/围栏/标题/列表/图片行结束表格）。 */
private fun isTableRowCandidate(line: String): Boolean {
    if (line.isBlank() || !line.contains('|')) return false
    val trimmed = line.trimStart()
    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return false
    if (MD_HEADING.matches(trimmed) || MD_IMAGE_LINE.matches(line.trim()) || MD_LIST_ITEM.matches(line)) return false
    return true
}

/** 单元格软断点字符：U+200B 零宽空格（视觉不可见、Text 可在断点折行）。 */
internal const val MD_SOFT_BREAK = "\u200B"

/** 长 token 门控阈值（与 iOS softBreakLongTokens threshold=24 同值对锁）：更短 token 折行伤观感。 */
internal const val MD_SOFT_BREAK_TOKEN_THRESHOLD = 24

/** span 版软断行（表格单元格解析期注入口，六种 span 变体全覆盖；语义见 [softBreakText] 注）。 */
internal fun softBreakSpans(spans: List<MdSpan>): List<MdSpan> = spans.map { span ->
    when (span) {
        is MdSpan.Text -> span.copy(text = softBreakText(span.text))
        is MdSpan.Bold -> span.copy(text = softBreakText(span.text))
        is MdSpan.Italic -> span.copy(text = softBreakText(span.text))
        is MdSpan.BoldItalic -> span.copy(text = softBreakText(span.text))
        is MdSpan.Code -> span.copy(text = softBreakText(span.text))
        is MdSpan.Link -> span.copy(text = softBreakText(span.text))
    }
}

/**
 * 单元格内不可断词 token 软断行（2026-11-23 批次二轮；自检轮 R1-3/R1-21 对齐 iOS 口径，
 * 自检轮 2 R2-2 CJK 断点重置 / R2-17 插位不劈代理对）：扫描 token（连续无空白、无 U+200B、
 * 无 [nativelyLineBreakable] 字符的段——空白/ZWSP/CJK 族视作断点重置），仅对长度超过
 * [MD_SOFT_BREAK_TOKEN_THRESHOLD] 的 token（URL/哈希/ULID 等无 CJK 形态——Compose Text
 * 拆不出词内断点，帽内放不下即尾部「…」）自 token 头起每 [every] 个字符插一个
 * [MD_SOFT_BREAK] 供 Text 折行——普通散文与 CJK 长单元格（原生可断行）不再吃断点，选中
 * 复制出干净文本（iOS softBreakLongTokens 同法同参同表：threshold 24 / stride 16 /
 * 空白、ZWSP 与原生可断行字符重置；零宽字符进复制面收敛为「仅无 CJK 的超长 token」= 批次
 * 一风险④知情存留面，两端注释互锚）。扫描计量：桌面按 UTF-16 code unit、iOS 按字素簇
 * （Character 迭代），阈值/步距语义等值、含非 BMP 字符的 token 长度差知情。幂等：ZWSP
 * 自身即 token 断点，已断行文本重应用时各 token 已 ≤ 阈值，零新增。保原字符、只插断点，
 * token 尾不留断点。
 */
internal fun softBreakText(text: String, every: Int = 16): String {
    if (text.length <= MD_SOFT_BREAK_TOKEN_THRESHOLD) return text
    // 第一遍：字符级扫 token，记录超过阈值的 token 内每个断点位（全局下标）。断点重置集 =
    // 空白/ZWSP/CJK 族原生可断行字符（R2-2，与 iOS MarkdownView.nativelyBreakable 同表对锁）
    val insertAt = mutableListOf<Int>()
    var tokenStart = -1
    for (i in text.indices) {
        val c = text[i]
        if (c.isWhitespace() || c == '\u200B' || nativelyLineBreakable(c)) {
            if (tokenStart >= 0 && i - tokenStart > MD_SOFT_BREAK_TOKEN_THRESHOLD) {
                collectInsertPositions(text, tokenStart, i, every, insertAt)
            }
            tokenStart = -1
        } else if (tokenStart == -1) {
            tokenStart = i
        }
    }
    // 尾 token（无尾随空白收束）：长度超阈值才按步插（末位不插，不在词尾留断点）
    if (tokenStart >= 0 && text.length - tokenStart > MD_SOFT_BREAK_TOKEN_THRESHOLD) {
        collectInsertPositions(text, tokenStart, text.length, every, insertAt)
    }
    if (insertAt.isEmpty()) return text
    // 第二遍：按位插断点（保原字符）
    val sb = StringBuilder(text.length + insertAt.size)
    var prev = 0
    for (pos in insertAt.sorted()) {
        sb.append(text, prev, pos).append(MD_SOFT_BREAK)
        prev = pos
    }
    sb.append(text, prev, text.length)
    return sb.toString()
}

/**
 * token 内断点位收集（自检轮 2 R2-17）：桌面扫描按 UTF-16 code unit（iOS 按字素簇，单位
 * 差异知情落注），插位撞上代理对中位（前高代理+后低代理）顺移一位到对后，顺移至词尾则
 * 放弃该位（不在词尾留断点）——桌面永不产出「孤立高代理 + ZWSP」碎字形。token 头起每
 * [every] 位一插，[tokenEndExclusive]（词尾）不插。
 */
private fun collectInsertPositions(
    text: String,
    tokenStart: Int,
    tokenEndExclusive: Int,
    every: Int,
    insertAt: MutableList<Int>,
) {
    var p = tokenStart + every
    while (p < tokenEndExclusive) {
        if (text[p - 1].isHighSurrogate() && text[p].isLowSurrogate()) {
            p += 1
            if (p >= tokenEndExclusive) return
        }
        insertAt += p
        p += every
    }
}

/**
 * 原生可断行字符（自检轮 2 R2-2，与 iOS MarkdownView.nativelyBreakable 同表对锁，单端增删
 * 区间必须双端同步）：CJK 标点/假名/扩展 A/统一表意/谚文音节/兼容表意/全角形——UAX #14
 * 在这些字符处原生给换行机会，Text 无需注入断点；计入 token 只会把中英混排长单元格误判成
 * 不可断词、污染复制面。BMP 外（扩展 B+）不覆盖：两端同退回「插零宽断点」旧行为（iOS 冷僻
 * 生僻字同口径知情注），桌面插位由 [collectInsertPositions] 顺移兜住不劈代理对。
 */
internal fun nativelyLineBreakable(c: Char): Boolean = when (c.code) {
    in 0x3000..0x303F, // CJK 标点（、。「」《》等）
    in 0x3041..0x30FF, // 平假名/片假名
    in 0x3400..0x4DBF, // CJK 扩展 A
    in 0x4E00..0x9FFF, // CJK 统一表意文字
    in 0xAC00..0xD7AF, // 谚文音节
    in 0xF900..0xFAFF, // CJK 兼容表意
    in 0xFF00..0xFFEF, // 全角/半角形（，．％等）
    -> true
    else -> false
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
