package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 代码底色/行内代码底色（原 CodeTint 字面量 0x14888888 散写，DSK-1 收敛）= surfaceTertiary
 * 薄底令牌：消息气泡（surfaceVariant）之上的浅灰叠层，随主题翻面。
 * 列表标记/弱化小字（原 ListMarkerInk 字面量 0xFF777777 散写）= textTertiary 令牌。
 * 两值经 [LocalDesktopInk] 组合域读取，不再持有静态字面量。
 */

/**
 * wp-18：桌面助手行轻量 Markdown 渲染器（iOS Chat/MarkdownView 的起步同位）。
 *
 * 覆盖起步集：标题（#–######）/ 列表（无序 + 有序，一层缩进近似）/ 围栏代码块 /
 * 粗体、斜体、粗斜体 + 行内代码；v1.2.0 多模态轮（设计稿 docs/multimodal-design-20261108.md
 * §2.2/§6 桌面行）增图片块双口径——https 内联渲染（2026-11-23 批次二轮起经 MediaCache 二级
 * 缓存）、http（含 agent 外发 LAN 地址）与其余 scheme 一律降级链接 chip（不发起请求、不做
 * 特例，生图输出观测面的唯一渲染通道）；2026-11-23 批次二轮增表格渲染（GFM 最小口径，见
 * MarkdownParser.parseTableBlock——单元格无 maxLines 无 Ellipsis、超宽表横向滚动兜底）与
 * 链接化（linkifySpans + 打开/复制气泡，正文平铺 Text span 里的 URL 可点）。
 * 其余已知取舍与 iOS 版同向：引用、嵌套强调不渲染（原样呈现为段落文本）。
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
        // 流式行补 ▌ 光标——wp-6 既有行为，回退分支不得忽略 showCursor）。解析异常回退路径
        // 不链接化（对齐 iOS parseTextChunk try? 失败也纯文本——知情注释）。
        Text(fallbackPlainText(content, showCursor), modifier = modifier)
        return
    }
    // 链接气泡态（2026-11-23 批次二轮）：任意块内 Link 点击记录 (展示文本, 归一 url)，
    // Popup 呈现「打开/复制」二选一（锚定=消息体底部居中退化行内锚，细节实机走查项）。
    var pendingLink by remember(content) { mutableStateOf<Pair<String, String>?>(null) }
    val onLinkTap: (String, String) -> Unit = { display, url -> pendingLink = display to url }
    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            blocks.forEach { MarkdownBlockView(it, onLinkTap) }
        }
        pendingLink?.let { (display, url) ->
            LinkActionPopup(displayUrl = display, normalizedUrl = url, onDismiss = { pendingLink = null })
        }
    }
}

@Composable
private fun MarkdownBlockView(block: MdBlock, onLinkTap: (String, String) -> Unit) {
    // DSK-1 散写收敛：墨水令牌单点读取（代码薄底/列表标记/链接强调色）
    val ink = LocalDesktopInk.current
    when (block) {
        is MdBlock.Heading -> Text(
            text = block.spans.toAnnotatedString(ink.surfaceTertiary, ink.accent, onLinkTap),
            style = headingStyle(block.level),
            modifier = Modifier.fillMaxWidth(),
        )
        is MdBlock.Paragraph -> Text(
            text = block.lines.toParagraphString(ink.surfaceTertiary, ink.accent, onLinkTap),
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
                    color = ink.surfaceTertiary, // 原 CodeTint 0x14888888
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
                        color = ink.textTertiary, // 原 ListMarkerInk 0xFF777777
                        modifier = Modifier.widthIn(min = 16.dp),
                    )
                    Spacer(Modifier.padding(end = 6.dp))
                    Text(
                        text = item.spans.toAnnotatedString(ink.surfaceTertiary, ink.accent, onLinkTap),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        is MdBlock.Image -> MarkdownImageBlock(block)
        is MdBlock.Table -> MarkdownTable(block, ink.surfaceTertiary, ink.accent, onLinkTap)
    }
}

// ── 表格渲染（2026-11-23 批次二轮；解析侧 GFM 最小口径见 MarkdownParser.parseTableBlock） ──

/** 单元格宽度（min 保列不塌缩；max=480dp 帽内换行——软断行保长 URL/哈希无「…」完整显示）。 */
private val MD_TABLE_CELL_MIN_WIDTH = 64.dp
private val MD_TABLE_CELL_MAX_WIDTH = 480.dp

/**
 * 表格：表头行 + 分隔线 + 体行，单元格无 maxLines 无 Ellipsis（段落 Text 同款），列对齐
 * 随 aligns；超宽表由表级横向滚动兜底（Column 全量布局在滚动容器内，纵向仍交给时间线）。
 * 单元格 span 已于解析期软断行（softBreakSpans）——连续无空白的长 token 每 16 字符可换行。
 * 成本知情（自检轮 R1-16）：表级 Column/Row 全量组合（非 lazy，iOS LiteTableView 同为非懒
 * 布局既有结构），数百行大表一次性组合全部单元格进 LazyColumn 单行，首现物化可能掉帧——
 * 行内显示与横滑兜底正确性优先，行数帽/懒加载留待后续轮。
 */
@Composable
private fun MarkdownTable(
    table: MdBlock.Table,
    codeTint: Color,
    accent: Color,
    onLinkTap: (String, String) -> Unit,
) {
    // 横向滚动容器内的 Column/Row 不 fillMaxWidth（无限宽约束下 wrap 内容，窄表左对齐、
    // 宽表横向滚出）
    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column {
            Row {
                table.header.forEachIndexed { idx, spans ->
                    MarkdownTableCell(
                        spans = spans,
                        align = table.aligns.getOrNull(idx) ?: MdTableAlign.None,
                        codeTint = codeTint,
                        accent = accent,
                        onLinkTap = onLinkTap,
                        header = true,
                    )
                }
            }
            HorizontalDivider()
            table.rows.forEach { row ->
                Row {
                    row.forEachIndexed { idx, spans ->
                        MarkdownTableCell(
                            spans = spans,
                            align = table.aligns.getOrNull(idx) ?: MdTableAlign.None,
                            codeTint = codeTint,
                            accent = accent,
                            onLinkTap = onLinkTap,
                            header = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkdownTableCell(
    spans: List<MdSpan>,
    align: MdTableAlign,
    codeTint: Color,
    accent: Color,
    onLinkTap: (String, String) -> Unit,
    header: Boolean,
) {
    Text(
        text = spans.toAnnotatedString(codeTint, accent, onLinkTap),
        // 表头加粗（视觉分层，iOS LiteTableView 头行同观感）；无 maxLines/Ellipsis——完整显示
        fontWeight = if (header) FontWeight.Bold else null,
        textAlign = when (align) {
            MdTableAlign.Left, MdTableAlign.None -> TextAlign.Start
            MdTableAlign.Center -> TextAlign.Center
            MdTableAlign.Right -> TextAlign.End
        },
        modifier = Modifier
            .widthIn(min = MD_TABLE_CELL_MIN_WIDTH, max = MD_TABLE_CELL_MAX_WIDTH)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    )
}

// ── 图片块渲染（v1.2.0 多模态，设计稿 §2.2/§6 桌面行双口径） ─────────────────────

/** 图片块内联渲染高度帽（宽随气泡，Fit 保持纵横比——高图不撑爆时间线）。 */
private val MD_IMAGE_MAX_HEIGHT = 320.dp

/** 图片加载态（视图层 remember，不进解析产物——与 JSON 卡折叠态同纪律）。 */
private sealed interface MdImageLoad {
    data object Loading : MdImageLoad
    data class Ready(val bitmap: ImageBitmap) : MdImageLoad
    data object Failed : MdImageLoad
}

/**
 * 图片块双口径分流（设计稿 §2.2 原文口径）：https 内联渲染；http（探针观察到的 agent 外发
 * LAN 地址属此类，客户端直连在跨网环境不可达）与其余 scheme 一律降级链接 chip——不发起
 * 请求、不做特例。加载失败同样落链接 chip（显式降级不静默空块）。
 */
@Composable
private fun MarkdownImageBlock(block: MdBlock.Image) {
    if (block.url.startsWith("https://")) {
        MarkdownHttpsImage(url = block.url, alt = block.alt)
    } else {
        MarkdownImageLinkChip(url = block.url, alt = block.alt, stale = false)
    }
}

@Composable
private fun MarkdownHttpsImage(url: String, alt: String) {
    val ink = LocalDesktopInk.current
    var load by remember(url) { mutableStateOf<MdImageLoad>(MdImageLoad.Loading) }
    // 二级缓存取数（2026-11-23 批次二轮；旧实现 LaunchedEffect 内 openStream 直拉——LazyColumn
    // 行离屏销毁组合、再进视口即重下，且无超时无解码帽）：内存 LRU（96MB 字节帽）+ 磁盘
    // （sha256(stableCacheKey) 键、mtime LRU trim、256MB 惰性）+ 解码降采样 2048 帽（与 iOS
    // decodeForDisplay 同帽——行内显示帽 MD_IMAGE_MAX_HEIGHT=320dp，全分辨率解码无收益）。
    // 取数/解码在 MediaCache 自有 IO 作用域与在途合流，调用方随行离屏取消不中断写盘；
    // 桌面无图片查看器（无点击大图入口），「查看大图前不预加载原图」桌面为天然满足。
    // 失败（网络/解码/缓存不可用）仍降级链接 chip——不炸消息行、不空块。
    LaunchedEffect(url) {
        load = MediaCache.load(url)?.let { MdImageLoad.Ready(it) } ?: MdImageLoad.Failed
    }
    when (val l = load) {
        is MdImageLoad.Ready -> Image(
            bitmap = l.bitmap,
            contentDescription = alt.ifBlank { url },
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = MD_IMAGE_MAX_HEIGHT),
        )
        is MdImageLoad.Loading -> Text(
            "图片加载中…",
            fontSize = 11.sp,
            color = ink.textTertiary, // 原 ListMarkerInk 0xFF777777
            modifier = Modifier.padding(vertical = 2.dp),
        )
        is MdImageLoad.Failed -> MarkdownImageLinkChip(url = url, alt = alt, stale = true)
    }
}

/**
 * 链接 chip（http/加载失败降级面）：图标行 + URL 小字，URL 原样透出不改写不代理（与
 * MessageAttachment.source=external 客户端纪律同向）；stale = https 加载失败的失败态标注。
 * URL 小字可点（2026-11-23 批次二轮）：此前纯展示不可点，点按出「打开/复制」气泡，openUri
 * 同走 normalizeUrl 归一（与正文链接口径一致，知情注释）。
 */
@Composable
private fun MarkdownImageLinkChip(url: String, alt: String, stale: Boolean) {
    val ink = LocalDesktopInk.current
    var bubbleOpen by remember(url) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(8.dp), color = ink.surfaceTertiary, modifier = Modifier.fillMaxWidth()) { // 原 CodeTint
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(
                text = if (stale) {
                    "🖼 ${alt.ifBlank { "图片" }}（加载失败，链接如下）"
                } else {
                    "🔗 ${alt.ifBlank { "图片" }}（图片链接，http 不内联渲染）"
                },
                fontSize = 12.sp,
            )
            Text(
                text = url,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = ink.textTertiary, // 原 ListMarkerInk 0xFF777777
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { bubbleOpen = true },
            )
        }
    }
    if (bubbleOpen) {
        LinkActionPopup(displayUrl = url, normalizedUrl = normalizeUrl(url), onDismiss = { bubbleOpen = false })
    }
}

@Composable
private fun headingStyle(level: Int) = when (level) {
    1 -> MaterialTheme.typography.titleLarge
    2 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}.copy(fontWeight = FontWeight.Bold)

// ── 链接化 + 打开/复制气泡（2026-11-23 批次二轮；桌面从零建，双路径覆盖） ────────────

/**
 * URL 匹配（http(s) 显式 scheme 或 www. 前缀；空白与 `<>()` 终止；U+200B 不在终止集——
 * 表格单元格解析期软断行插在长 token 内的零宽字符须随 URL 整段成 Link，[linkifyText] 再
 * 从 url 侧剥净，展示文本保留断点）。CJK 不在排除集——中英文粘连 URL 会整体成 Link（与
 * iOS NSDataDetector 宽松匹配同向，知情口径）；句尾 CJK 标点由 [MD_URL_TRAILING_PUNCT]
 * 剥离兜底（标点收尾形态），句中粘连属已知观感面。www 分支带前置词边界（自检轮 R1-14）：
 * 词中 www.（`foo.www.bar` 域中段、`user@www.example.com` 邮箱本地部）不产假链接——对齐
 * iOS NSDataDetector 自带词边界语义；http(s) 分支自证 scheme 不需要边界。
 */
private val MD_URL_REGEX = Regex("""(https?://[^\s<>()]+|(?<![\w./@-])www\.[^\s<>()]+)""")

/** URL 句尾标点剥离集（行文句读紧跟 URL 的常见形态；本体不含 `)`——正则已排除括号）。 */
private val MD_URL_TRAILING_PUNCT = charArrayOf(
    '.', ',', ';', ':', '!', '?', '，', '。', '、', '；', '：', '！', '？', '"', '\'', '）', '】', '》', '」', '』',
)

/**
 * URL 归一（打开/复制共用；对齐 iOS NSDataDetector match.url 自带 scheme 口径）：http(s)://
 * 原样；www. 前缀补 https://（桌面 openUri=Desktop.browse 要求绝对 URI，裸 www 串必炸——
 * 不归一就无法打开）；其余 null 不入 Link span（调用方退回纯文本/禁用「打开」）。
 */
internal fun normalizeUrl(raw: String): String? = when {
    raw.startsWith("http://") || raw.startsWith("https://") -> raw
    raw.startsWith("www.") -> "https://" + raw
    else -> null
}

/**
 * 平铺 Text span → Text/Link 切分（解析路径链接化的渲染层出口）：只切 Text——Code/Bold/
 * Italic/BoldItalic 内不切，`[label](url)` markdown 链接语法不解析（仍按字面量呈现，仅 URL
 * 子串可点）。桌面为比 iOS 更窄的口径知情落注（自检轮 R1-4）：iOS autolink 只跳显式链接与
 * 行内代码（MarkdownView.autolink alreadyStyled 检查），粗体内 URL iOS 可点、桌面不可点。
 * 已切好的 Link 原样保留（幂等）。句尾标点剥离后留在后续 Text 段（纯文本等价面不丢字符）。
 */
internal fun linkifySpans(spans: List<MdSpan>): List<MdSpan> = buildList {
    spans.forEach { span ->
        when (span) {
            is MdSpan.Text -> addAll(linkifyText(span.text))
            else -> add(span) // Code/Bold/Italic/BoldItalic/Link 原样
        }
    }
}

private fun linkifyText(text: String): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    var lastEnd = 0
    for (match in MD_URL_REGEX.findAll(text)) {
        var url = match.value
        while (url.isNotEmpty() && url.last() in MD_URL_TRAILING_PUNCT) url = url.dropLast(1)
        if (url.isEmpty()) continue
        val start = match.range.first
        val end = start + url.length
        if (start > lastEnd) out += MdSpan.Text(text.substring(lastEnd, start))
        // 表格单元格解析期软断行插在长 token（URL 几乎必中）内的 U+200B 随匹配段整体成
        // Link：展示文本（[MdSpan.Link.text]）保留断点（表格折行面不回退），url 侧剥净——
        // 零宽字符进 openUri（URISyntaxException 被吞、打开静默失败）或剪贴板（粘贴不可用）
        // 都是坏面（自检轮 R1-1/R1-17；iOS 同输入干净：.link 属性取自解析期、断点只改展示）。
        val normalized = normalizeUrl(url.replace(MD_SOFT_BREAK, ""))
        if (normalized != null) {
            out += MdSpan.Link(text = url, url = normalized)
        } else {
            out += MdSpan.Text(url)
        }
        lastEnd = end
    }
    if (lastEnd < text.length) out += MdSpan.Text(text.substring(lastEnd))
    return out
}

/**
 * 链接操作气泡（打开/复制二选一；正文 Link 与 chip URL 同款）：打开走 LocalUriHandler
 * （桌面=Desktop.browse，不直持 AWT）只用归一 url；复制复制归一完整 URL（对齐 iOS
 * UIPasteboard 复制完整 URL 口径）——normalizedUrl=null（白名单外 scheme）时「打开」禁用、
 * 复制退回原样字符串。focusable=false（自检轮 R1-13）：外点即关由 Popup 无条件的
 * outside-press 监听承担（compose ui-desktop 1.12.2 实证：Popup 外点 press 经
 * setOutsidePointerEventListener 派发 onDismissRequest，不依赖 focusable），focusable=true
 * 反而打开即抢键盘焦点——composer 输入中点链接会失焦、后续键入落入气泡窗口、气泡在途期间
 * 根级时间线键盘导航收不到键，故关掉（气泡内无键盘交互面，两按钮纯点击）。
 * 锚定=挂载点父布局底部居中（首版退化行内锚，视觉细节实机走查项——知情注释）。
 */
@Composable
internal fun LinkActionPopup(displayUrl: String, normalizedUrl: String?, onDismiss: () -> Unit) {
    val ink = LocalDesktopInk.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
        alignment = Alignment.BottomCenter,
        offset = IntOffset(0, 8),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = displayUrl,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ink.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row {
                    TextButton(
                        onClick = {
                            normalizedUrl?.let { url -> runCatching { uriHandler.openUri(url) } }
                            onDismiss()
                        },
                        enabled = normalizedUrl != null,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("打开", fontSize = 12.sp) }
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(normalizedUrl ?: displayUrl))
                            onDismiss()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("复制", fontSize = 12.sp) }
                }
            }
        }
    }
}

/** 行内 span → AnnotatedString（平铺样式：粗 / 斜 / 粗斜 / 等宽代码底色；codeTint = surfaceTertiary 令牌）。 */
private fun List<MdSpan>.toAnnotatedString(
    codeTint: Color,
    accent: Color,
    onLinkTap: (String, String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    linkifySpans(this@toAnnotatedString).forEach { span ->
        when (span) {
            is MdSpan.Text -> append(span.text)
            is MdSpan.Link -> withLink(
                LinkAnnotation.Url(
                    span.url,
                    TextLinkStyles(
                        SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                    ),
                    LinkInteractionListener { onLinkTap(span.text, span.url) },
                ),
            ) { append(span.text) }
            is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
            is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
            is MdSpan.BoldItalic -> withStyle(
                SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
            ) { append(span.text) }
            is MdSpan.Code -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, background = codeTint),
            ) { append(span.text) }
        }
    }
}

/** 段落多行合并为单个 Text（作者换行保留；codeTint = surfaceTertiary 令牌）。 */
private fun List<List<MdSpan>>.toParagraphString(
    codeTint: Color,
    accent: Color,
    onLinkTap: (String, String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    forEachIndexed { idx, line ->
        if (idx > 0) append("\n")
        linkifySpans(line).forEach { span ->
            when (span) {
                is MdSpan.Text -> append(span.text)
                is MdSpan.Link -> withLink(
                    LinkAnnotation.Url(
                        span.url,
                        TextLinkStyles(
                            SpanStyle(color = accent, textDecoration = TextDecoration.Underline),
                        ),
                        LinkInteractionListener { onLinkTap(span.text, span.url) },
                    ),
                ) { append(span.text) }
                is MdSpan.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(span.text) }
                is MdSpan.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(span.text) }
                is MdSpan.BoldItalic -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic),
                ) { append(span.text) }
                is MdSpan.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = codeTint),
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

/** JSON 卡容器：与既有代码块同底色同圆角（surfaceTertiary 薄底 + 6dp，原 CodeTint 散写已收敛），卡内两形态各自排版。 */
@Composable
private fun JsonCardView(payload: JsonCardPayload, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = LocalDesktopInk.current.surfaceTertiary, modifier = modifier.fillMaxWidth()) {
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
    val ink = LocalDesktopInk.current
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
                            color = ink.textTertiary, // 原 ListMarkerInk 0xFF777777
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
