import SwiftUI

let markdownCacheMinimumLength = 120

/// Markdown 渲染（表格/嵌套列表/代码块/引用，Foundation cmark-gfm 同源解析）。
///
/// 前身是 MarkdownUI：其主题系统/环境读取/逐块修饰器树在 LazyVStack 每物化一行时整棵
/// 重建（Release 模拟器 ScrollBench 实测：卡顿 14–20 次/53.8 ms/s → 换纯 Text 降到
/// 2–8 次/2.6–10.8 ms/s，主线程热点全在 MarkdownUI 建树+测量）。本渲染器把一行的
/// 成本压到「解析一次缓存 + 每块少量 Text」：解析按块切片缓存（NSCache），视图侧只做
/// 排版。已知取舍：代码块自动折行（无横向滚动）；远程图片不取（按空处理）。
struct MarkdownView: View {
    let content: String
    /// 流式行（StreamingTypewriter 逐字前缀喂入）：true = 跳过 JSON 卡嗅探，仅 final 行嗅探
    /// （对位桌面 jsonCardPayloadOrNull 的 streaming 闸——半流前缀可瞬时合法如 `[]`/`{}`，
    /// 出卡后随载荷继续流入解析失效即回落，「闪卡-消失」比晚一拍出卡更伤）。
    var streaming: Bool = false
    /// v1.4 查看器（入口 b）：Markdown 图片块点按回调——参数 = 本消息全部可内联渲染的
    /// https 图块 URL 序（页集）+ 被点图块序。nil = 不接查看器（点按无动作）。
    var onOpenImage: (([URL], Int) -> Void)? = nil
    /// 流式光标（2026-11-28 流式逐字轮）：非 nil = 在已揭示前缀的末块文本尾挂 ▌，nil = 无
    /// 光标（final 行/非流式调用点零变化）。可见性翻转走 MarkdownCursor.visible。
    var cursor: MarkdownCursor? = nil

    var body: some View {
        LiteMarkdownBody(content: content, streaming: streaming, onOpenImage: onOpenImage, cursor: cursor)
            .equatable()
    }
}

/// 流式光标（StreamingTypewriter 专用）：▌ 恒在已揭示前缀末块文本尾，visible=false 时以
/// 透明色占位——字符不增减、仅颜色翻转，等待期闪烁不触发布局（尾行满宽时光标若随显隐
/// 增减字符会在「行尾/下一行」间跳）。
struct MarkdownCursor: Equatable {
    var visible: Bool
    static let glyph = "▌"
}

/// JSON 卡嗅探（ADR-10 纯展示层判定）：仅 final 行（streaming=false）参与——content 去首尾
/// 空白后首字符 ∈ [/{ 且 JSONSerialization 解析成功才判为 JSON 卡候选——progress_line
///（`[1] status=…`）、`[logged]`、`[main 264be05]` 等 `[` 开头非 JSON 文本解析必然失败，
/// 天然回落既有渲染。流式行整段绕过嗅探：StreamingTypewriter 逐字喂出的前缀可能瞬时恰好
/// 构成合法 JSON（如 `[]`/`{}`），不能靠「不完整即解析失败」兜底；折叠态在 JsonCardView
/// 的 @State（视图层），不进任何缓存。
/// 判定只在此处发生，结果不写入 LiteMarkdownCache（缓存只存既有 markdown 块解析产物）。
private enum JsonCardSniffer {
    /// 解析成功返回顶层对象/数组；失败返回 nil（调用方走既有 markdown 管线，行为兜底不变）。
    static func sniff(_ content: String) -> Any? {
        let t = content.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let first = t.first, first == "[" || first == "{" else { return nil }
        guard let data = t.data(using: .utf8),
              let value = try? JSONSerialization.jsonObject(with: data) else { return nil }
        // fragmentsAllowed 未开：数字/字符串等标量顶层本就不可能进（首字符已限定 [/{）
        return value
    }

    /// 工具列表卡判定：顶层数组且 ≥1 元素为对象、id 以 openclaw: 开头且含 label 字符串
    /// （openclaw 工具列表形态，与桌面同构）。
    static func toolListItems(_ value: Any) -> [JsonToolItem]? {
        guard let arr = value as? [[String: Any]] else { return nil }
        let items = arr.compactMap { dict -> JsonToolItem? in
            guard let id = dict["id"] as? String, id.hasPrefix("openclaw:") else { return nil }
            let label = dict["label"] as? String ?? id
            return JsonToolItem(id: id, label: label, raw: dict)
        }
        return items.isEmpty ? nil : items
    }

    struct JsonToolItem: Identifiable {
        let id: String
        let label: String
        let raw: [String: Any]

        /// pretty 排序键后的原始 JSON 文本（单条展开/复制用）。
        var rawText: String { JsonCardSniffer.pretty(raw) }
    }

    /// pretty（排序键）文本：与桌面同构；失败回落原文（调用方已确保 value 来自合法 JSON）。
    static func pretty(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value,
                                                     options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]),
              let s = String(data: data, encoding: .utf8) else { return String(describing: value) }
        return s
    }
}

/// JSON 卡片（与桌面 MarkdownText 同构的两形态）：
/// - 工具列表卡：label 列表 + id 小字辅助；单条点开展开该条原始 JSON，复制 = 该条原始 JSON；
/// - 通用 JSON 卡：pretty（排序键）等宽 13（对齐既有 .code 字号/底色），超 8 行默认折叠
///   （收起显示前 8 行 +「展开 N 行」），展开/收起 + 复制按钮。
/// 折叠态存本视图 @State（视图层），不进 LiteMarkdownCache；行随 LazyVStack 物化重建即回默认态。
private struct JsonCardView: View {
    @Environment(\.inkColors) private var c
    let raw: String
    /// 嗅探产物（调用方已解析成功，直接持有免二次解析）。
    let value: Any

    /// 工具列表形态（nil = 通用 JSON 卡）。
    private var toolItems: [JsonCardSniffer.JsonToolItem]? { JsonCardSniffer.toolListItems(value) }

    /// 通用卡 pretty 文本与行数（折叠判定用）。
    private var prettyText: String { JsonCardSniffer.pretty(value) }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Image(systemName: "curlybraces.square").font(.caption2)
                Text(toolItems == nil ? "JSON" : "工具列表")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer(minLength: 0)
                Button {
                    UIPasteboard.general.string = raw
                } label: {
                    Label("复制", systemImage: "doc.on.doc")
                        .font(.caption2)
                        .labelStyle(.titleAndIcon)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
                .accessibilityLabel("复制 JSON")
            }
            if let items = toolItems {
                toolList(items)
            } else {
                genericCard
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 6).fill(c.surfaceTertiary))
        .overlay(RoundedRectangle(cornerRadius: 6).stroke(c.borderPrimary, lineWidth: 0.5))
    }

    /// 工具列表卡：单条点按展开/收起该条原始 JSON（展开态存本视图 @State）。
    private func toolList(_ items: [JsonCardSniffer.JsonToolItem]) -> some View {
        ToolListBody(items: items, ink: c)
    }

    /// 通用 JSON 卡：超 8 行默认折叠，展开/收起；折叠判定按 pretty 行数，行内容按渲染时现算
    /// （不落缓存，流式行收敛 final 后内容不再变，重算成本可忽略）。
    @State private var expanded = false

    private var genericCard: some View {
        GenericJsonBody(rawText: prettyText, ink: c, expanded: $expanded)
    }
}

/// 工具列表卡正文（独立视图持有展开行 id 的 @State——与通用卡折叠态同存视图层）。
private struct ToolListBody: View {
    let items: [JsonCardSniffer.JsonToolItem]
    let ink: InkColors
    @State private var expandedId: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(items) { item in
                VStack(alignment: .leading, spacing: 3) {
                    Button {
                        expandedId = expandedId == item.id ? nil : item.id
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(item.label)
                                .font(.system(size: 14))
                            Text(item.id)
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                                .truncationMode(.middle)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("展开 \(item.label)")
                    if expandedId == item.id {
                        HStack(alignment: .top, spacing: 6) {
                            Text(item.rawText)
                                .font(.system(size: 12, design: .monospaced))
                                .lineSpacing(2)
                                .textSelection(.enabled)
                            Button {
                                UIPasteboard.general.string = item.rawText
                            } label: {
                                Image(systemName: "doc.on.doc").font(.caption2)
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("复制该条 JSON")
                        }
                        .padding(.vertical, 4)
                    }
                }
                .padding(.vertical, 5)
                if item.id != items.last?.id {
                    Rectangle().fill(ink.borderPrimary.opacity(0.5)).frame(height: 0.5)
                }
            }
        }
    }
}

/// 通用 JSON 卡正文：等宽 13（对齐既有 .code 字号），超 8 行默认折叠。
private struct GenericJsonBody: View {
    let rawText: String
    let ink: InkColors
    @Binding var expanded: Bool

    /// 默认折叠阈值：收起显示前 8 行（与桌面同构）。
    private static let foldLines = 8

    private var lines: [String] { rawText.components(separatedBy: "\n") }

    var body: some View {
        let all = lines
        VStack(alignment: .leading, spacing: 0) {
            Text(all.prefix(expanded ? all.count : Self.foldLines).joined(separator: "\n"))
                .font(.system(size: 13, design: .monospaced))
                .lineSpacing(2)
                .textSelection(.enabled)
            if all.count > Self.foldLines {
                Button {
                    expanded.toggle()
                } label: {
                    Text(expanded ? "收起" : "展开 \(all.count - Self.foldLines) 行")
                        .font(.caption)
                        // tint 债清账轮（2026-11-21）：Color.accentColor → 令牌——全局 tint
                        // 仍=ink，字面量在暗色下渲染近白失去链接感（InkColors.accent 注释含实测对比度）。
                        .foregroundStyle(ink.accent)
                }
                .buttonStyle(.plain)
                .padding(.top, 4)
            }
        }
    }
}

/// Equatable 隔离层：快照替换 entries 数组时，未变消息不再重建块树。
///
/// 快路径（按行物化成本从低到高）：无 Markdown 语法的单行短消息直接 `Text(verbatim:)`
/// （不解析，形同纯文本——实测纯文本是滑动最顺的形态）；解析后只有一个段落块时直接
/// 出一个 `Text`；多块才走 VStack + ForEach。
private struct LiteMarkdownBody: View, Equatable {
    @Environment(\.inkColors) private var c
    let content: String
    /// 流式行闸：true = 跳过 JSON 卡嗅探（仅 final 嗅探，防半流瞬时合法前缀闪卡）。
    var streaming: Bool = false
    /// v1.4 查看器（入口 b）回调穿线：图片块点按上抛。不参与 ==——闭包身份不入相等性，
    /// 内容未变的行不因回调换实例而重渲（回调语义由捕获方 HubMessageRow/HubChatView 的
    /// @State 引用稳定性保证，同 onDelete 既有模式）。
    var onOpenImage: (([URL], Int) -> Void)? = nil
    /// 流式光标（nil = 无）；参与 ==——等待期闪烁只翻 visible，必须触发重渲。
    var cursor: MarkdownCursor? = nil

    static func == (lhs: LiteMarkdownBody, rhs: LiteMarkdownBody) -> Bool {
        lhs.content == rhs.content && lhs.streaming == rhs.streaming && lhs.cursor == rhs.cursor
    }

    var body: some View {
        let _ = ScrollBench.shared.count(.markdownBody)
        Group {
            // JSON 卡嗅探在 isPlain 之前：裸 JSON 可能被 isPlain 判为纯文本（单行无 markdown
            // 字符），必须在快路径前拦下；嗅探失败（progress_line 等）回落既有管线原样。
            // 流式行（streaming=true）整段绕过嗅探——半流前缀可瞬时合法（[]/{}），防闪卡。
            if !streaming, let value = JsonCardSniffer.sniff(content) {
                JsonCardView(raw: content, value: value)
            } else if LiteMarkdownParser.isPlain(content) {
                if let cursor {
                    Text(LiteMarkdownParser.withCursor(AttributedString(content), cursor))
                } else {
                    Text(verbatim: content)
                }
            } else {
                let blocks = ScrollBench.shared.time("md.blocksLookup") { LiteMarkdownCache.blocks(content) }
                if blocks.count == 1, case let .text(s) = blocks[0].kind {
                    Text(LiteMarkdownParser.withCursor(s, cursor))
                } else {
                    // v1.4 查看器：页集 = 本消息全部可内联渲染（https）的图块 URL，按块序；
                    // http 图块呈链接 chip 无查看入口，不进页集。
                    let imagePages = blocks.compactMap { block -> URL? in
                        if case let .image(url) = block.kind,
                           url.scheme?.lowercased() == "https" { return url }
                        return nil
                    }
                    // 光标只挂末块（LiteBlockView 按块型落到其尾文本）。
                    let lastId = blocks.last?.id
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(blocks) { block in
                            LiteBlockView(block: block, imagePages: imagePages,
                                          onOpenImage: onOpenImage,
                                          cursor: block.id == lastId ? cursor : nil)
                        }
                    }
                }
            }
        }
        .font(.system(size: 15))
        .lineSpacing(4)
        .foregroundColor(c.ink)
        // 链接着色收口（2026-11-21 tint 债清账轮）：Text 的 .link run 与卡内 Link(chip)
        // 的颜色来自 tint 隐式来源，全局 .tint(colors.ink) 会把它们染成近白/近黑、暗色下
        // 失去链接区分度——本作用域钉 accent 令牌恢复区分度（明暗实测对比度见
        // InkColors.accent 注释；外层 .foregroundColor 不改 .link run 的着色来源）。
        .tint(c.accent)
        // 需求2（2026-11-23 批次一）：正文可选中——环境传播一处覆盖本 Group 全部 Text 宿主
        //（快路径 Text(verbatim:)/单段落/列表项/quote/表格 cell/JSON 卡文本；流式行经
        // StreamingTypewriter 内部 MarkdownView 同达）。长按出系统选择游标、局部复制走系统
        // 菜单；行级 contextMenu 在触屏侧已迁行脚注时间戳（HubMessageRow 注）避免长按手势
        // 竞争，Catalyst 正文右键菜单保留。用户气泡 Text(m.content)（HubMessageRow）单独挂。
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct LiteBlockView: View {
    @Environment(\.inkColors) private var c
    let block: LiteBlock
    /// v1.4 查看器：本消息 https 图块页集（LiteMarkdownBody 预算好传下）+ 点按回调。
    var imagePages: [URL] = []
    var onOpenImage: (([URL], Int) -> Void)? = nil
    /// 流式光标：只挂末块，按块型落到其尾文本（段落/列表末项/引用/代码块/表格末格）；
    /// 分隔线/图片块无可挂文本=瞬时无光标（下一字符到达即成新段落块）。
    var cursor: MarkdownCursor? = nil

    var body: some View {
        switch block.kind {
        case let .text(s):
            Text(LiteMarkdownParser.withCursor(s, cursor))
        case let .list(items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(items) { item in
                    HStack(alignment: .firstTextBaseline, spacing: 7) {
                        Text(item.marker)
                            .monospacedDigit()
                            .foregroundColor(c.textSecondary)
                            .frame(minWidth: item.depth > 1 ? 10 : 14, alignment: .trailing)
                        Text(LiteMarkdownParser.withCursor(item.text, item.id == items.last?.id ? cursor : nil))
                    }
                    .padding(.leading, CGFloat(item.depth - 1) * 18)
                }
            }
        case let .code(code, _):
            Text(LiteMarkdownParser.withCursor(AttributedString(code), cursor))
                .font(.system(size: 13, design: .monospaced))
                .lineSpacing(2)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 6).fill(c.surfaceTertiary))
        case let .quote(s):
            HStack(alignment: .top, spacing: 10) {
                Rectangle().fill(c.borderPrimary).frame(width: 3)
                Text(LiteMarkdownParser.withCursor(s, cursor)).foregroundColor(c.textSecondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        case let .table(header, rows):
            LiteTableView(header: header, rows: rows, cursor: cursor)
        case .rule:
            Rectangle().fill(c.borderPrimary).frame(height: 1)
        case let .image(url):
            // v1.2.0 多模态（设计稿 §2.2 双口径）：https 内联渲染（占位 + 失败重试见
            // RemoteImageView）；http（含 agent 局域网地址）降级链接 chip——ATS 默认拦
            // http、客户端直连 agent 局域网地址在蜂窝网不可达，不做特例。
            // v1.4 查看器（入口 b）：点图块开全屏查看（页集含同消息全部 https 图块）；
            // 占位/失败态未加载照常可开（查看器页自行加载，命中同一 loader 缓存）。
            if url.scheme?.lowercased() == "https" {
                RemoteImageView(url: url)
                    .onTapGesture {
                        guard let onOpenImage,
                              let idx = imagePages.firstIndex(of: url) else { return }
                        onOpenImage(imagePages, idx)
                    }
            } else {
                ExternalLinkChip(url: url)
            }
        }
    }
}

/// 表格两趟测度布局（2026-11-28 表格截断修复轮，取代 SwiftUI Grid）。
///
/// 根因复盘：Grid 先以无约束提案问格的尺寸——`frame(minWidth:maxWidth:480)` 下的 Text 按
/// 单行报理想宽（超帽被钳到 480）与**单行高**，行高就此钉死；真正落位时宽度被钳、Text
/// 折成多行却只剩一行高，只能尾部「…」（Mac 大屏长中文单元格的截断形态）。探针实证：
/// 仅给格加 fixedSize(vertical) 会让多出的行叠到下一行（行高仍按单行测），更糟。
///
/// 本布局自己做两趟：① 每格以 `.unspecified` 问理想宽，按列取最宽钳 [minTrack, maxTrack]
/// 定列轨；② 每格按列轨宽重测得真实行高。格视图形状契约（LiteTableView.cell）：内容核
///「横向吃满提案、纵向取理想高」——frame(maxWidth: .infinity) + fixedSize(vertical)；外层
/// 再包 frame(maxHeight: .infinity)，落位时吃满整行高（表头底色/体行分隔线随行铺满，同行
/// 短格底色不断带），文本在格内垂直居中（与原 Grid(alignment: .leading) 落位同语义）。
/// 不读 proposal——表格恒在横向 ScrollView 内按自然宽排布。
private struct LiteTableLayout: Layout {
    let columns: Int
    let minTrack: CGFloat
    let maxTrack: CGFloat

    struct Cache {
        var tracks: [CGFloat] = []
        var sizes: [CGSize] = []
        var measured = false
    }

    func makeCache(subviews: Subviews) -> Cache { Cache() }

    private func measure(_ subviews: Subviews, cache: inout Cache) {
        guard !cache.measured else { return }
        var tracks = [CGFloat](repeating: minTrack, count: columns)
        for (i, sub) in subviews.enumerated() {
            let ideal = sub.sizeThatFits(.unspecified).width
            tracks[i % columns] = min(maxTrack, max(tracks[i % columns], ideal))
        }
        cache.tracks = tracks
        cache.sizes = subviews.enumerated().map {
            $1.sizeThatFits(ProposedViewSize(width: tracks[$0 % columns], height: nil))
        }
        cache.measured = true
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) -> CGSize {
        measure(subviews, cache: &cache)
        return CGSize(width: cache.tracks.reduce(0, +), height: totalHeight(subviews, cache: cache))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout Cache) {
        measure(subviews, cache: &cache)
        var y = bounds.minY
        var rowStart = 0
        while rowStart < subviews.count {
            let rowEnd = min(rowStart + columns, subviews.count)
            let rowHeight = (rowStart..<rowEnd).map { cache.sizes[$0].height }.max() ?? 0
            var x = bounds.minX
            for j in rowStart..<rowEnd {
                let track = cache.tracks[j % columns]
                subviews[j].place(
                    at: CGPoint(x: x, y: y), anchor: .topLeading,
                    proposal: ProposedViewSize(width: track, height: rowHeight)
                )
                x += track
            }
            y += rowHeight
            rowStart = rowEnd
        }
    }

    private func totalHeight(_ subviews: Subviews, cache: Cache) -> CGFloat {
        var height: CGFloat = 0
        var rowStart = 0
        while rowStart < subviews.count {
            let rowEnd = min(rowStart + columns, subviews.count)
            height += (rowStart..<rowEnd).map { cache.sizes[$0].height }.max() ?? 0
            rowStart = rowEnd
        }
        return height
    }
}

private struct LiteTableView: View {
    @Environment(\.inkColors) private var c
    let header: [AttributedString]
    let rows: [[AttributedString]]
    /// 流式光标：挂末行末列格（无体行时挂表头末格）。
    var cursor: MarkdownCursor? = nil

    /// #5（2026-11-07 UX 轮）+ 需求1（2026-11-23 批次一）：单元格宽度钳位。列轨 = 本列
    /// 最宽格的理想宽，钳 [64, 480]：帽内换行完整呈现（行高按折行后真实高度测，见
    /// LiteTableLayout 头注）；超长不可断词（URL/哈希）另经 softBreakLongTokens 软断行；
    /// 超气泡表由外层横向 ScrollView 兜底横滑看全。480 帽 = 2026-11-23 批次一定值不变。
    private static let minCellWidth: CGFloat = 64
    private static let maxCellWidth: CGFloat = 480

    var body: some View {
        let columns = max(1, max(header.count, rows.map(\.count).max() ?? 0))
        let headerCursor: MarkdownCursor? = rows.isEmpty ? cursor : nil
        // #5（2026-11-07 UX 轮）：表格自然宽度超出气泡即横向滑动看全，不再挤压列宽截断；
        // 窄表仍按内容收宽（minWidth 64 保证短列不至于挤成竖条）。边框/圆角随内容移动。
        ScrollView(.horizontal, showsIndicators: true) {
            LiteTableLayout(columns: columns, minTrack: Self.minCellWidth, maxTrack: Self.maxCellWidth) {
                if !header.isEmpty {
                    ForEach(0..<columns, id: \.self) { i in
                        // 外层 maxHeight 吃满整行高：表头底色随行铺满（原 GridRow background 同语义）。
                        cell(i < header.count ? header[i] : AttributedString(), bold: true,
                             cursor: i == columns - 1 ? headerCursor : nil)
                            .frame(maxHeight: .infinity)
                            .background(c.surfaceTertiary)
                    }
                }
                ForEach(rows.indices, id: \.self) { r in
                    ForEach(0..<columns, id: \.self) { i in
                        let isLast = r == rows.count - 1
                        let isTail = isLast && i == columns - 1
                        cell(i < rows[r].count ? rows[r][i] : AttributedString(), bold: false,
                             cursor: isTail ? cursor : nil)
                            .frame(maxHeight: .infinity)
                            .overlay(alignment: .bottom) {
                                // 体行分隔线：每体行底一条（末行无）——格吃满行高，短格的线也在行底。
                                if !isLast { Rectangle().fill(c.borderPrimary).frame(height: 1) }
                            }
                    }
                }
            }
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(c.borderPrimary, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    /// 格内容核：横向吃满列轨提案（frame maxWidth）、纵向取理想高（fixedSize vertical）——
    /// LiteTableLayout 两趟测度对格的形状契约，缺一则行高回退单行测度（见布局头注）。
    private func cell(_ s: AttributedString, bold: Bool, cursor: MarkdownCursor? = nil) -> some View {
        // 需求1（2026-11-23 批次一）：渲染期软断行——超长不可断词 token（URL/哈希）插零宽
        // 空格给 Text 可断点，帽内换行完整呈现；只作用表格 cell，不进 LiteMarkdownCache
        // 解析产物（正文快路径/块 Text/Equatable 隔离层均不受影响）。
        Text(LiteMarkdownParser.withCursor(
            LiteMarkdownParser.softBreakLongTokens(s), cursor
        ))
            .font(.system(size: 14, weight: bold ? .semibold : .regular))
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .frame(maxWidth: .infinity, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct LiteBlock: Identifiable {
    enum Kind {
        case text(AttributedString)
        case list([LiteListItem])
        case code(String, language: String?)
        case quote(AttributedString)
        case table(header: [AttributedString], rows: [[AttributedString]])
        case rule
        /// v1.2.0 多模态（设计稿 §2.2）：整行 markdown 图片节点 `![alt](url)`——Foundation
        /// AttributedString 解析不支持图片（静默丢弃），生图输出与外链图由此进渲染面。
        /// 渲染口径（与桌面同型）：https 内联、http（含 agent 局域网地址）降级链接 chip。
        case image(URL)
    }

    let id: Int
    let kind: Kind
}

struct LiteListItem: Identifiable {
    let id: Int
    let depth: Int
    let marker: String
    var text: AttributedString
}

enum LiteMarkdownParser {
    static let options = AttributedString.MarkdownParsingOptions(
        allowsExtendedAttributes: false,
        interpretedSyntax: .full,
        failurePolicy: .returnPartiallyParsedIfPossible
    )

    static func parse(_ raw: String) -> [LiteBlock] {
        // v1.2.0 多模态：整行图片节点先行抽出（逐行扫描，非图行按原序归并成块交给既有
        // Foundation 管线）；合并产物重编号保证 id 唯一（image 块与文本块 id 空间不重叠）。
        var blocks: [LiteBlock] = []
        var pending: [Substring] = []

        func flushPending() {
            guard !pending.isEmpty else { return }
            let chunk = pending.joined(separator: "\n")
            pending.removeAll(keepingCapacity: true)
            blocks.append(contentsOf: parseTextChunk(chunk))
        }

        for line in raw.split(separator: "\n", omittingEmptySubsequences: false) {
            if let url = imageOnlyLine(line) {
                flushPending()
                blocks.append(LiteBlock(id: blocks.count, kind: .image(url)))
            } else {
                pending.append(line)
            }
        }
        flushPending()
        return blocks.enumerated().map { LiteBlock(id: $0.offset, kind: $0.element.kind) }
    }

    /// 既有 Foundation 解析管线（原 parse 主体，未动）——只处理不含整行图片节点的文本段。
    private static func parseTextChunk(_ raw: String) -> [LiteBlock] {
        guard var attr = try? AttributedString(markdown: raw, options: options) else {
            return [LiteBlock(id: 0, kind: .text(AttributedString(raw)))]
        }
        autolink(&attr)
        var builder = Builder()
        for (intent, range) in attr.runs[\.presentationIntent] {
            builder.consume(AttributedString(attr[range]), intent: intent)
        }
        return builder.finish()
    }

    /// 整行图片节点判定：`![alt](url)` 独占一行（允许首尾空白）→ 提取 URL。
    /// 仅认 http(s) 协议（其余协议不外发渲染，按普通文本走既有管线）；正则为编译期字面量。
    /// 段内混排图片不成块（Foundation 渲染 alt 文本，既有行为不追）。
    /// 流式行半截前缀（如 `![a](https://x` 缺右括号）不匹配 → 文本，补全后下一帧成块。
    private static let imageLineRegex = try! NSRegularExpression(
        pattern: #"^!\[[^\]\n]*\]\(\s*(\S+?)\s*\)\s*$"#,
    )

    static func imageOnlyLine(_ line: Substring) -> URL? {
        let s = line.trimmingCharacters(in: .whitespaces)
        guard let m = imageLineRegex.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)),
              let r = Range(m.range(at: 1), in: s),
              let url = URL(string: String(s[r])) else { return nil }
        let scheme = url.scheme?.lowercased()
        return scheme == "https" || scheme == "http" ? url : nil
    }

    /// 相邻段落/标题合并进同一个 Text（少建视图）时的段间距：普通换行 + 一个 2pt 字号
    /// 的空行。空行高度随其字号（≈2.4pt），加上前后各一次 lineSpacing(4) 共约 10pt——
    /// 与块间距（VStack 10）、长消息切缝（行距 8 + 修正 2）一致；"\n\n" 会空出整行（≈26pt）。
    static let paragraphGap: AttributedString = {
        var tiny = AttributedString("\n")
        tiny[AttributeScopes.SwiftUIAttributes.FontAttribute.self] = .system(size: 2)
        return AttributedString("\n") + tiny
    }()

    /// 无需解析的消息：单行、不含任何 Markdown 语法字符与网址（网址要自动链接）。
    /// 主对话里大量 "OK"/一句话回复走这里，行成本等同纯文本。
    static func isPlain(_ s: String) -> Bool {
        guard s.utf8.count <= 600 else { return false }
        for byte in s.utf8 {
            switch byte {
            case UInt8(ascii: "\n"), UInt8(ascii: "\r"), UInt8(ascii: "*"), UInt8(ascii: "_"),
                 UInt8(ascii: "`"), UInt8(ascii: "#"), UInt8(ascii: "|"), UInt8(ascii: ">"),
                 UInt8(ascii: "<"), UInt8(ascii: "["), UInt8(ascii: "~"), UInt8(ascii: "\\"),
                 UInt8(ascii: "&"):
                return false
            default:
                continue
            }
        }
        if s.hasPrefix("    ") || s.hasPrefix("\t") || isListItemStart(Substring(s)) { return false }
        let lower = s.lowercased()
        return !lower.contains("http") && !lower.contains("www.")
    }

    private static let linkDetector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)

    /// 裸网址自动链接（cmark-gfm autolink 扩展的等价物，Foundation 解析器不做）：只认
    /// http(s):// 与 www. 开头，跳过已是显式链接与行内代码的片段。解析结果有缓存，
    /// 检测只在首次解析时发生。自检轮 R2-5：www 裸链 scheme 钉死 https——NSDataDetector
    /// 对 www 匹配自带 http://（业界默认），桌面 normalizeUrl（MarkdownText.kt）恒补
    /// https://，两端复制/打开目标同串（口径互锚）。
    static func autolink(_ attr: inout AttributedString) {
        let plain = String(attr.characters)
        let lower = plain.lowercased()
        guard lower.contains("http") || lower.contains("www."), let detector = linkDetector else { return }
        let ns = plain as NSString
        for match in detector.matches(in: plain, range: NSRange(location: 0, length: ns.length)) {
            let literal = ns.substring(with: match.range).lowercased()
            guard literal.hasPrefix("http://") || literal.hasPrefix("https://") || literal.hasPrefix("www."),
                  let range = Range(match.range, in: attr) else { continue }
            // www 匹配重写 scheme（display 文本不动，只换 link 目标）；构造失败按桌面口径
            // 退回纯文本不入链——不回落 http://，防两端再漂移。
            let url: URL? = literal.hasPrefix("www.")
                ? URL(string: "https://" + ns.substring(with: match.range))
                : match.url
            guard let url else { continue }
            let alreadyStyled = attr[range].runs.contains {
                $0.link != nil || $0.inlinePresentationIntent?.contains(.code) == true
            }
            if !alreadyStyled { attr[range].link = url }
        }
    }

    /// 行内代码补底色（Text 按 inlinePresentationIntent 画等宽字，但不画背景）；
    /// `<br>` 转换行（GFM 表格单元格不能真换行，agent 常用它，Foundation 原样留作文本）。
    static func styledInline(_ s: AttributedString) -> AttributedString {
        var out = s
        if out.characters.contains("<") {
            for tag in ["<br>", "<br/>", "<br />"] {
                while let range = out.range(of: tag, options: .caseInsensitive) {
                    out.replaceSubrange(range, with: AttributedString("\n"))
                }
            }
        }
        let codeRanges = out.runs[\.inlinePresentationIntent].compactMap { intent, range in
            intent?.contains(.code) == true ? range : nil
        }
        for range in codeRanges {
            out[range][AttributeScopes.SwiftUIAttributes.BackgroundColorAttribute.self] = Color.primary.opacity(0.08)
        }
        return out
    }

    /// 流式光标拼接（2026-11-28 流式逐字轮）：▌ 追加到已揭示前缀的末段文本尾。字体属性随
    /// 末 run（标题内光标同号），不继承末 run 的链接/行内样式（尾字符恰是链接或行内代码时
    /// 光标不得变成可点链接）；visible=false 以透明色占位——字符恒在串内，闪烁只翻颜色
    /// 不动布局（见 MarkdownCursor 注）。
    static func withCursor(_ s: AttributedString, _ cursor: MarkdownCursor?) -> AttributedString {
        guard let cursor else { return s }
        var out = s
        var caret = AttributedString(MarkdownCursor.glyph)
        if let font = s.runs.last?.attributes[AttributeScopes.SwiftUIAttributes.FontAttribute.self] {
            caret.font = font
        }
        if !cursor.visible { caret.foregroundColor = .clear }
        out += caret
        return out
    }

    /// 需求1（2026-11-23 批次一）表格单元格软断行：连续无空白、无 U+200B 的超长 token
    /// （URL/哈希/ULID——SwiftUI Text 拆不出词内断点，帽内放不下即尾部「…」）每 16 字符插
    /// 一个 U+200B 零宽空格给出换行断点，视觉不可见、Text 可在断点折行，单元格「帽内换行 +
    /// 外层横滑兜底」永不产生省略号。只作用表格 cell 渲染期（LiteTableView.cell），不进
    /// LiteMarkdownCache 解析产物。自检轮 R2-2：CJK/假名/谚文/全角形视作 token 断点重置
    /// （UAX #14 下 Text 原生可断行，零注入零收益）——中文长单元格不再被当不可断词，
    /// 选中复制面随之干净。知情取舍收窄：仅无 CJK 的超长 token（URL/哈希）复制仍带零宽
    /// 字符（批次一风险④ 的存留面）。阈值 24（更短 token 折行伤观感）、步距 16（帽 480pt
    /// 内的安全断距）。
    static func softBreakLongTokens(_ s: AttributedString) -> AttributedString {
        let threshold = 24
        let stride = 16
        let zwsp: Character = "\u{200B}"
        let chars = Array(s.characters)
        // 第一遍：字符级扫 token（空白/换行/ZWSP/原生可断行字符视作断点重置），token 超
        // 阈值时记下每 16 字符处的全局插入位（token 末位除外，不在词尾留断点）。
        var insertAt = Set<Int>()
        var tokenStart: Int?
        for (i, ch) in chars.enumerated() {
            if ch.isWhitespace || ch == zwsp || nativelyBreakable(ch) {
                if let start = tokenStart {
                    var p = start + stride
                    while p < i {
                        if i - start > threshold { insertAt.insert(p) }
                        p += stride
                    }
                }
                tokenStart = nil
            } else if tokenStart == nil {
                tokenStart = i
            }
        }
        if let start = tokenStart, chars.count - start > threshold {
            var p = start + stride
            while p < chars.count { insertAt.insert(p); p += stride }
        }
        guard !insertAt.isEmpty else { return s }
        // 第二遍：逐 run 重建（插入位属性随所在 run 保留——attributes 取自 Run，文本取自
        // s[run.range] 切片），跨 run 的 token 由全局下标对齐插入位。幂等：ZWSP 本身是断点，
        // 重入不再叠加插入。
        var out = AttributedString()
        var offset = 0
        for run in s.runs {
            let slice = s[run.range]
            var piece = ""
            for ch in slice.characters {
                if insertAt.contains(offset) { piece.append(zwsp) }
                piece.append(ch)
                offset += 1
            }
            out += AttributedString(piece, attributes: run.attributes)
        }
        return out
    }

    /// 原生可断行字符（自检轮 R2-2）：CJK 统一表意/扩展 A/兼容、假名、谚文音节、CJK 标点
    /// 与全角形——UAX #14 在这些字素处给出原生换行机会，Text 无需注入断点；计入 token 只会
    /// 把中英混排长单元格误判成不可断词、污染复制面。冷僻扩展 B+ 平面不覆盖（表格 cell 内
    /// 25+ 连续生僻字的形态可忽略；该形态退回旧行为 = 插零宽断点，仅显示语义无回归）。
    private static func nativelyBreakable(_ ch: Character) -> Bool {
        ch.unicodeScalars.contains { scalar in
            switch scalar.value {
            case 0x3000...0x303F,   // CJK 标点（、。「」《》等）
                 0x3041...0x30FF,   // 平假名/片假名
                 0x3400...0x4DBF,   // CJK 扩展 A
                 0x4E00...0x9FFF,   // CJK 统一表意文字
                 0xAC00...0xD7AF,   // 谚文音节
                 0xF900...0xFAFF,   // CJK 兼容表意
                 0xFF00...0xFFEF:   // 全角/半角形（，．％等）
                return true
            default:
                return false
            }
        }
    }

    private struct Builder {
        var blocks: [LiteBlock] = []
        var text = AttributedString()
        var items: [LiteListItem] = []
        var lastItemIdentity: Int?
        var tableIdentity: Int?
        var tableHeader: [AttributedString] = []
        var tableRows: [[AttributedString]] = []
        var rowIdentity: Int?
        var row: [AttributedString] = []
        var rowIsHeader = false

        mutating func emit(_ kind: LiteBlock.Kind) {
            blocks.append(LiteBlock(id: blocks.count, kind: kind))
        }

        mutating func flushText() {
            guard !text.characters.isEmpty else { return }
            emit(.text(text))
            text = AttributedString()
        }

        mutating func flushList() {
            guard !items.isEmpty else { return }
            emit(.list(items))
            items = []
            lastItemIdentity = nil
        }

        mutating func flushRow() {
            guard rowIdentity != nil else { return }
            if rowIsHeader { tableHeader = row } else { tableRows.append(row) }
            row = []
            rowIdentity = nil
            rowIsHeader = false
        }

        mutating func flushTable() {
            guard tableIdentity != nil else { return }
            flushRow()
            emit(.table(header: tableHeader, rows: tableRows))
            tableIdentity = nil
            tableHeader = []
            tableRows = []
        }

        mutating func consume(_ segment: AttributedString, intent: PresentationIntent?) {
            var header: Int?
            var code = false
            var language: String?
            var rule = false
            var quote = false
            var listDepth = 0
            var ordinal: Int?
            var itemIdentity: Int?
            var ordered: Bool?
            var table: Int?
            var rowId: Int?
            var headerRow = false
            // components 由内向外（paragraph < listItem < list…）：最近列表项/列表类型先出现
            for component in intent?.components ?? [] {
                switch component.kind {
                case .header(let level): header = level
                case .codeBlock(let hint): code = true; language = hint
                case .thematicBreak: rule = true
                case .blockQuote: quote = true
                case .listItem(let n):
                    if itemIdentity == nil { itemIdentity = component.identity; ordinal = n }
                case .orderedList:
                    listDepth += 1
                    if ordered == nil { ordered = true }
                case .unorderedList:
                    listDepth += 1
                    if ordered == nil { ordered = false }
                case .table: table = component.identity
                case .tableHeaderRow: rowId = component.identity; headerRow = true
                case .tableRow: rowId = component.identity
                default: break
                }
            }

            if let table {
                flushText()
                flushList()
                if tableIdentity != table { flushTable(); tableIdentity = table }
                if rowIdentity != rowId { flushRow(); rowIdentity = rowId; rowIsHeader = headerRow }
                row.append(LiteMarkdownParser.styledInline(segment))
                return
            }
            flushTable()

            if code {
                flushText()
                flushList()
                var s = String(segment.characters)
                while s.hasSuffix("\n") { s.removeLast() }
                emit(.code(s, language: language))
                return
            }
            if rule {
                flushText()
                flushList()
                emit(.rule)
                return
            }
            // 列表项（含松散列表的续段：无 list 包裹但 listItem identity 相同）
            if let itemIdentity {
                flushText()
                let body = LiteMarkdownParser.styledInline(segment)
                if itemIdentity == lastItemIdentity, !items.isEmpty {
                    items[items.count - 1].text += AttributedString("\n") + body
                } else {
                    let marker = ordered == true ? "\(ordinal ?? 1)." : (listDepth > 1 ? "◦" : "•")
                    items.append(LiteListItem(id: items.count, depth: max(1, listDepth), marker: marker, text: body))
                    lastItemIdentity = itemIdentity
                }
                return
            }
            flushList()
            if quote {
                flushText()
                emit(.quote(LiteMarkdownParser.styledInline(segment)))
                return
            }
            var body = LiteMarkdownParser.styledInline(segment)
            if let header {
                let size: CGFloat = header == 1 ? 22 : header == 2 ? 19 : header == 3 ? 17 : 15
                body[AttributeScopes.SwiftUIAttributes.FontAttribute.self] = .system(size: size, weight: .semibold)
            }
            if !text.characters.isEmpty { text += LiteMarkdownParser.paragraphGap }
            text += body
        }

        mutating func finish() -> [LiteBlock] {
            flushText()
            flushList()
            flushTable()
            return blocks
        }
    }
}

/// 解析结果缓存：行滚回视口重建 view 不再重解析。流式文本走 ShimmerText 不经此处，
/// 不会被逐 delta 的中间态污染，因此短消息也一并缓存（此前 <120 字符每次物化都重解析）。
private enum LiteMarkdownCache {
    final class Box {
        let blocks: [LiteBlock]
        init(_ blocks: [LiteBlock]) { self.blocks = blocks }
    }

    static let cache: NSCache<NSString, Box> = {
        let c = NSCache<NSString, Box>()
        c.countLimit = 512
        // 总量帽（2026-11-28 流式逐字轮）：逐字揭示下每个前缀都是新键（一条长回复可入数百
        // 条渐长前缀），仅限条数时长回复可达数百 MB 瞬时驻留；按 UTF-16 码元计费封顶。
        c.totalCostLimit = 4_000_000
        return c
    }()

    static func blocks(_ raw: String) -> [LiteBlock] {
        let key = raw as NSString
        if let hit = cache.object(forKey: key) { return hit.blocks }
        let parsed = ScrollBench.shared.time("md.parse") { LiteMarkdownParser.parse(raw) }
        cache.setObject(Box(parsed), forKey: key, cost: raw.utf16.count)
        return parsed
    }

    /// 主 actor 切片预热：进页先把首屏/尾部消息解析好（AST 不跨 actor，切片让出主线程）。
    @MainActor
    static func prewarm(_ rawContents: [String]) async {
        var seen = Set<String>()
        seen.reserveCapacity(rawContents.count)
        for raw in rawContents {
            guard !Task.isCancelled else { return }
            guard raw.utf8.count >= markdownCacheMinimumLength,
                  seen.insert(raw).inserted else { continue }

            autoreleasepool {
                _ = blocks(raw)
            }
            try? await Task.sleep(nanoseconds: 2_000_000)
        }
    }
}

/// 会话页进页预热任务的入口（Hub 会话页接入渲染引擎时调用）。
@MainActor
func prewarmMarkdown(_ contents: [String]) async {
    await LiteMarkdownCache.prewarm(contents)
}

/// 列表项起始判定（`- `/`* `/`+ ` 或 `1. `/`1) `）：快路径「无 Markdown 语法的短消息」判断用。
/// 原随长消息切片逻辑同居 ChatRows.swift，P4 随旧会话页退役后收归本文件。
func isListItemStart(_ line: Substring) -> Bool {
    if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("+ ") { return true }
    let digits = line.prefix { $0.isASCII && $0.isNumber }
    guard !digits.isEmpty, digits.count <= 9 else { return false }
    let rest = line.dropFirst(digits.count)
    return rest.hasPrefix(". ") || rest.hasPrefix(") ")
}
