import Foundation
import composeApp

// MARK: - 对话流行模型（ChatRows.kt 移植：日分隔 + 工具组 + id 撞号防御）

/// LazyVStack 的一行：日分隔头、原条目、长消息切片、时间线分区头、压缩标记。
/// （回合折叠/工具组卡已退役——SW 反馈⑤：过程物料完成后整体退场，不留对话内卡片。）
enum ChatRow: Identifiable {
    var id: String {
        switch self {
        case let .dayHeader(id, _): return id
        case let .entry(id, _): return id
        case let .entrySlice(id, _, _): return id
        case let .boundary(id, _, _): return id
        case let .compactMark(id, _, _): return id
        }
    }

    case dayHeader(id: String, atMs: Int64)
    case entry(id: String, entry: BridgeEntry)
    /// 长助手消息按块切成多行（见 `sliceLongMessage`）：`entry` 仍是整条消息（长按复制/
    /// 引用取全文），本片源码与排版信息在 `slice` 里。
    case entrySlice(id: String, entry: BridgeEntry, slice: MessageSlice)
    case boundary(id: String, sessionKey: String, label: String)
    case compactMark(id: String, sessionKey: String, atMs: Int64)
}

/// 长消息的一片。
struct MessageSlice {
    /// 本片 Markdown 源码
    let text: String
    /// 相对 LazyVStack 行距的顶部修正，让切缝处间距与整条渲染时一致（见 `sliceLongMessage`）
    let topAdjust: CGFloat
    /// 最后一片才显示发送时间
    let isLast: Bool
}

/// 时间线分区输入。
struct TimelineSection {
    let key: String
    let label: String?
    let entries: [BridgeEntry]
}

private enum ChatRowFormatters {
    static let day: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()
}

/// 条目序列 → 带日分隔的行序列。
///
/// 过程物料退场（SW 反馈⑤，取代先前的回合折叠）：已完成回合的思考/工具/旁白不进
/// 对话流（避免加载与完成时的跳动），只保留尾部进行中的现场——运行中工具、位于最后
/// 一条消息之后的思考卡/旁白；回合闭合（新消息到达/历史重载）即整体消失。
func buildChatRows(_ entries: [BridgeEntry]) -> [ChatRow] {
    var rows: [ChatRow] = []
    var lastDay: String? = nil
    var usedIds = Set<String>()

    // `buildChatRows` is evaluated again whenever the bridge publishes a snapshot. Keep
    // the formatter outside `dateKey`; constructing DateFormatter for every entry becomes
    // noticeable on long transcripts and competes with Markdown layout during scrolling.
    func dateKey(_ ms: Int64) -> String {
        ChatRowFormatters.day.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    func uniqueId(_ raw: String) -> String {
        var id = raw
        var n = 2
        while usedIds.contains(id) {
            id = "\(raw)#\(n)"
            n += 1
        }
        usedIds.insert(id)
        return id
    }

    let lastMsgIdx = entries.lastIndex { $0.kind == "message" }

    for (i, entry) in entries.enumerated() {
        let keep: Bool
        switch entry.kind {
        case "tool":
            keep = entry.state == "running" // 加载中工具保留，完成即消失
        case "thinking":
            keep = lastMsgIdx.map { i > $0 } ?? true // 仅尾部进行中的思考卡
        case "message" where entry.role == "system":
            keep = lastMsgIdx.map { i > $0 } ?? true // 旁白（commentary）同为 live 现场
        default:
            keep = true
        }
        guard keep else { continue }
        if entry.kind == "message", entry.timestampMs > 0 {
            let key = dateKey(entry.timestampMs)
            if key != lastDay {
                rows.append(.dayHeader(id: uniqueId("day-\(key)-\(entry.id)"), atMs: entry.timestampMs))
                lastDay = key
            }
        }
        if let slices = sliceLongMessage(entry) {
            for (index, slice) in slices.enumerated() {
                rows.append(.entrySlice(id: uniqueId("\(entry.id)#s\(index)"), entry: entry, slice: slice))
            }
        } else {
            rows.append(.entry(id: uniqueId(entry.id), entry: entry))
        }
    }
    return rows
}

/// 长消息切片门槛与目标片长（utf8 字节；中文约 130 字/片）。只切已完成的助手消息：
/// 流式尾部仍是单行（每个 delta 都在变，切了反而每帧重建行）。
///
/// 片长实测（Release 模拟器 ScrollBench，重内容会话，第二轮卡顿时间比）：不切 ~38 ms/s、
/// 1200 字节 ~32、400 字节 ~29 且两轮最稳——片越小，每帧要新物化的内容越少。
let sliceMinimumBytes = 400
private let sliceTargetBytes = 400

/// 切缝间距修正用到的三处行距：LazyVStack 行距（ChatView）、Markdown 块间距与列表项
/// 间距（MarkdownView）。修正量 = 整条渲染时该处应有的间距 − LazyVStack 行距。
private let rowSpacing: CGFloat = 8
private let blockSpacing: CGFloat = 10
private let listItemSpacing: CGFloat = 4

/// 把长助手消息按 Markdown 块边界切成多段，每段一行懒加载（滑动卡顿 P2）；不需要切时
/// 返回 nil。
///
/// 整条长消息是一行时，LazyVStack 在它进入视口那一帧要一次性建树+排版+光栅化上千 pt
/// 的内容（重内容会话里换成纯 Text 也照样掉帧的原因）；切片后每帧只物化视口附近几段。
/// 切分点只取顶格（无缩进）的块起点——空行之后、列表项行首、围栏开头（有序项保留源码
/// 编号，Foundation 解析沿用起始序号，已实证）；缩进行起片会被解析成代码块或丢失嵌套，
/// 围栏代码块内部永不切。找不到合适切点的超长单块保持整行。
func sliceLongMessage(_ entry: BridgeEntry) -> [MessageSlice]? {
    let content = entry.content
    guard entry.kind == "message", entry.role == "assistant", !entry.streaming,
          content.utf8.count >= sliceMinimumBytes else { return nil }

    var chunks: [[Substring]] = []
    var current: [Substring] = []
    var currentBytes = 0
    var inFence = false
    var previousBlank = false

    func flush() {
        guard !current.isEmpty else { return }
        chunks.append(current)
        current = []
        currentBytes = 0
    }

    for line in content.split(separator: "\n", omittingEmptySubsequences: false) {
        let trimmed = line.drop { $0 == " " || $0 == "\t" }
        let isFence = trimmed.hasPrefix("```") || trimmed.hasPrefix("~~~")
        let isBlank = trimmed.isEmpty
        let atColumnZero = line.first.map { $0 != " " && $0 != "\t" } ?? false
        if !inFence, atColumnZero, currentBytes >= sliceTargetBytes,
           previousBlank || isFence || isListItemStart(line) {
            flush()
        }
        if isFence { inFence.toggle() }
        current.append(line)
        currentBytes += line.utf8.count + 1
        previousBlank = isBlank
    }
    flush()

    // 去掉片首尾空行（片间间距由 LazyVStack 提供），全空片丢弃
    let pieces = chunks.compactMap { lines -> [Substring]? in
        let kept = Array(lines
            .drop { $0.allSatisfy(\.isWhitespace) }
            .reversed()
            .drop { $0.allSatisfy(\.isWhitespace) }
            .reversed())
        return kept.isEmpty ? nil : kept
    }
    guard pieces.count > 1 else { return nil }

    return pieces.enumerated().map { index, lines in
        var topAdjust: CGFloat = 0
        if index > 0, let lastPrevious = pieces[index - 1].last, let first = lines.first {
            // 上一片收在列表里（末行是列表项或其缩进续行）且本片以列表项开头 = 同一列表被切开
            let previousInList = isListItemStart(lastPrevious) || lastPrevious.first == " " || lastPrevious.first == "\t"
            topAdjust = (previousInList && isListItemStart(first) ? listItemSpacing : blockSpacing) - rowSpacing
        }
        return MessageSlice(
            text: lines.joined(separator: "\n"),
            topAdjust: topAdjust,
            isLast: index == pieces.count - 1
        )
    }
}

func isListItemStart(_ line: Substring) -> Bool {
    if line.hasPrefix("- ") || line.hasPrefix("* ") || line.hasPrefix("+ ") { return true }
    let digits = line.prefix { $0.isASCII && $0.isNumber }
    guard !digits.isEmpty, digits.count <= 9 else { return false }
    let rest = line.dropFirst(digits.count)
    return rest.hasPrefix(". ") || rest.hasPrefix(") ")
}

/// conversation 拼接时间线：多主 session「最旧在最上」拼接 + 分区头 + 压缩标记。
func buildConversationRows(
    sections: [TimelineSection],
    compactMarks: [String: Int64]
) -> [ChatRow] {
    var rows: [ChatRow] = []
    var usedIds = Set<String>()

    func uniqueId(_ raw: String) -> String {
        var id = raw
        var n = 2
        while usedIds.contains(id) {
            id = "\(raw)#\(n)"
            n += 1
        }
        usedIds.insert(id)
        return id
    }

    // sections 为最近活跃降序（index 0 = 当前锚定），时间线自旧至新
    for s in sections.reversed() {
        let label = (s.label?.isEmpty == false ? s.label! : nil)
            ?? (s.key.hasSuffix(":main") ? "常驻对话" : s.key.split(separator: ":").last.map(String.init) ?? s.key)
        let bid = uniqueId("bnd-\(s.key)")
        rows.append(.boundary(id: bid, sessionKey: s.key, label: label))
        for row in buildChatRows(s.entries) {
            usedIds.insert(row.id)
            rows.append(row)
        }
        if let at = compactMarks[s.key] {
            rows.append(.compactMark(id: uniqueId("cmp-\(s.key)"), sessionKey: s.key, atMs: at))
        }
    }
    return rows
}

/// 思考卡快照：尾部 6 行非空（思考在末尾增长）。
func thinkingTail(_ text: String, maxLines: Int = 6) -> String {
    let lines = text.split(separator: "\n", omittingEmptySubsequences: true)
    let tail = lines.suffix(maxLines).joined(separator: "\n")
    return tail
}
