import Foundation
import composeApp

// MARK: - 对话流行模型（ChatRows.kt 移植：日分隔 + 工具组 + id 撞号防御）

/// LazyVStack 的一行：日分隔头、原条目、连续已完成工具组、时间线分区头、压缩标记。
enum ChatRow: Identifiable {
    var id: String {
        switch self {
        case let .dayHeader(id, _): return id
        case let .entry(id, _): return id
        case let .toolGroup(id, _): return id
        case let .boundary(id, _, _): return id
        case let .compactMark(id, _, _): return id
        }
    }

    case dayHeader(id: String, atMs: Int64)
    case entry(id: String, entry: BridgeEntry)
    case toolGroup(id: String, items: [BridgeEntry])
    case boundary(id: String, sessionKey: String, label: String)
    case compactMark(id: String, sessionKey: String, atMs: Int64)
}

/// 时间线分区输入。
struct TimelineSection {
    let key: String
    let label: String?
    let entries: [BridgeEntry]
}

/// 条目序列 → 带日分隔的行序列（含 ≥2 连续已完成工具卡折叠组；id 撞号改写兜底）。
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

    func addEntry(_ entry: BridgeEntry) {
        if entry.kind == "message", entry.timestampMs > 0 {
            let key = dateKey(entry.timestampMs)
            if key != lastDay {
                rows.append(.dayHeader(id: uniqueId("day-\(key)-\(entry.id)"), atMs: entry.timestampMs))
                lastDay = key
            }
        }
        rows.append(.entry(id: uniqueId(entry.id), entry: entry))
    }

    var i = 0
    while i < entries.count {
        let entry = entries[i]
        if entry.kind == "tool", entry.state != "running" {
            var j = i
            var run: [BridgeEntry] = []
            while j < entries.count, entries[j].kind == "tool", entries[j].state != "running" {
                run.append(entries[j])
                j += 1
            }
            if run.count >= 2 {
                let key = uniqueId("grp:\(run[0].id):\(run.count)")
                rows.append(.toolGroup(id: key, items: run))
                i = j
                continue
            }
        }
        addEntry(entry)
        i += 1
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
