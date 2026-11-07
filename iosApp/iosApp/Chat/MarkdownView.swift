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

    var body: some View {
        LiteMarkdownBody(content: content, streaming: streaming).equatable()
    }
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
                        .foregroundStyle(Color.accentColor)
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

    static func == (lhs: LiteMarkdownBody, rhs: LiteMarkdownBody) -> Bool {
        lhs.content == rhs.content && lhs.streaming == rhs.streaming
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
                Text(verbatim: content)
            } else {
                let blocks = ScrollBench.shared.time("md.blocksLookup") { LiteMarkdownCache.blocks(content) }
                if blocks.count == 1, case let .text(s) = blocks[0].kind {
                    Text(s)
                } else {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(blocks) { block in
                            LiteBlockView(block: block)
                        }
                    }
                }
            }
        }
        .font(.system(size: 15))
        .lineSpacing(4)
        .foregroundColor(c.ink)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct LiteBlockView: View {
    @Environment(\.inkColors) private var c
    let block: LiteBlock

    var body: some View {
        switch block.kind {
        case let .text(s):
            Text(s)
        case let .list(items):
            VStack(alignment: .leading, spacing: 4) {
                ForEach(items) { item in
                    HStack(alignment: .firstTextBaseline, spacing: 7) {
                        Text(item.marker)
                            .monospacedDigit()
                            .foregroundColor(c.textSecondary)
                            .frame(minWidth: item.depth > 1 ? 10 : 14, alignment: .trailing)
                        Text(item.text)
                    }
                    .padding(.leading, CGFloat(item.depth - 1) * 18)
                }
            }
        case let .code(code, _):
            Text(code)
                .font(.system(size: 13, design: .monospaced))
                .lineSpacing(2)
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 6).fill(c.surfaceTertiary))
        case let .quote(s):
            HStack(alignment: .top, spacing: 10) {
                Rectangle().fill(c.borderPrimary).frame(width: 3)
                Text(s).foregroundColor(c.textSecondary)
            }
            .fixedSize(horizontal: false, vertical: true)
        case let .table(header, rows):
            LiteTableView(header: header, rows: rows)
        case .rule:
            Rectangle().fill(c.borderPrimary).frame(height: 1)
        }
    }
}

private struct LiteTableView: View {
    @Environment(\.inkColors) private var c
    let header: [AttributedString]
    let rows: [[AttributedString]]

    /// #5（2026-11-07 UX 轮）：单元格最大宽度——超出即换行，超长不可断词（URL/哈希）不截断
    /// （横向滚动兜底可见），替代原 maxWidth:.infinity 均分列宽导致的「…」截断。
    private static let maxCellWidth: CGFloat = 280

    var body: some View {
        let columns = max(header.count, rows.map(\.count).max() ?? 0)
        // #5（2026-11-07 UX 轮）：表格自然宽度超出气泡即横向滑动看全，不再挤压列宽截断；
        // 窄表仍按内容收宽（minWidth 64 保证短列不至于挤成竖条）。边框/圆角随内容移动。
        ScrollView(.horizontal, showsIndicators: true) {
            Grid(alignment: .leading, horizontalSpacing: 0, verticalSpacing: 0) {
                if !header.isEmpty {
                    GridRow {
                        ForEach(0..<columns, id: \.self) { i in
                            cell(i < header.count ? header[i] : AttributedString(), bold: true)
                        }
                    }
                    .background(c.surfaceTertiary)
                }
                ForEach(rows.indices, id: \.self) { r in
                    Rectangle().fill(c.borderPrimary).frame(height: 1).gridCellUnsizedAxes(.horizontal)
                    GridRow {
                        ForEach(0..<columns, id: \.self) { i in
                            cell(i < rows[r].count ? rows[r][i] : AttributedString(), bold: false)
                        }
                    }
                }
            }
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(c.borderPrimary, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func cell(_ s: AttributedString, bold: Bool) -> some View {
        Text(s)
            .font(.system(size: 14, weight: bold ? .semibold : .regular))
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .frame(minWidth: 64, maxWidth: Self.maxCellWidth, alignment: .leading)
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
    /// 检测只在首次解析时发生。
    static func autolink(_ attr: inout AttributedString) {
        let plain = String(attr.characters)
        let lower = plain.lowercased()
        guard lower.contains("http") || lower.contains("www."), let detector = linkDetector else { return }
        let ns = plain as NSString
        for match in detector.matches(in: plain, range: NSRange(location: 0, length: ns.length)) {
            let literal = ns.substring(with: match.range).lowercased()
            guard literal.hasPrefix("http://") || literal.hasPrefix("https://") || literal.hasPrefix("www."),
                  let url = match.url,
                  let range = Range(match.range, in: attr) else { continue }
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
        return c
    }()

    static func blocks(_ raw: String) -> [LiteBlock] {
        let key = raw as NSString
        if let hit = cache.object(forKey: key) { return hit.blocks }
        let parsed = ScrollBench.shared.time("md.parse") { LiteMarkdownParser.parse(raw) }
        cache.setObject(Box(parsed), forKey: key)
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
