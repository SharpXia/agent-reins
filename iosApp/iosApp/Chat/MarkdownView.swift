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

    var body: some View {
        LiteMarkdownBody(content: content).equatable()
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

    static func == (lhs: LiteMarkdownBody, rhs: LiteMarkdownBody) -> Bool { lhs.content == rhs.content }

    var body: some View {
        let _ = ScrollBench.shared.count(.markdownBody)
        Group {
            if LiteMarkdownParser.isPlain(content) {
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

    var body: some View {
        let columns = max(header.count, rows.map(\.count).max() ?? 0)
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

    private func cell(_ s: AttributedString, bold: Bool) -> some View {
        Text(s)
            .font(.system(size: 14, weight: bold ? .semibold : .regular))
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .frame(maxWidth: .infinity, alignment: .leading)
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

/// ChatView 进页预热任务的入口（保持旧函数名，调用点不动）。
@MainActor
func prewarmMarkdown(_ contents: [String]) async {
    await LiteMarkdownCache.prewarm(contents)
}
