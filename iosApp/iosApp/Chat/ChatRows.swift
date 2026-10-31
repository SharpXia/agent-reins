import Foundation
import composeApp

// MARK: - 对话流行模型（ChatRows.kt 移植：日分隔 + 工具组 + id 撞号防御）

/// LazyVStack 的一行：日分隔头、原条目、时间线分区头、压缩标记。
/// （回合折叠/工具组卡已退役——SW 反馈⑤：过程物料完成后整体退场，不留对话内卡片。）
enum ChatRow: Identifiable {
    var id: String {
        switch self {
        case let .dayHeader(id, _): return id
        case let .entry(id, _): return id
        case let .boundary(id, _, _): return id
        case let .compactMark(id, _, _): return id
        }
    }

    case dayHeader(id: String, atMs: Int64)
    case entry(id: String, entry: BridgeEntry)
    case boundary(id: String, sessionKey: String, label: String)
    case compactMark(id: String, sessionKey: String, atMs: Int64)
}

/// 时间线分区输入。
struct TimelineSection {
    let key: String
    let label: String?
    let entries: [BridgeEntry]
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

    func dateKey(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
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
        rows.append(.entry(id: uniqueId(entry.id), entry: entry))
    }
    return rows
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
