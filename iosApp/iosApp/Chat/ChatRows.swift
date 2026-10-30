import Foundation
import composeApp

// MARK: - 对话流行模型（ChatRows.kt 移植：日分隔 + 工具组 + id 撞号防御）

/// LazyVStack 的一行：日分隔头、原条目、连续已完成工具组、回合块、时间线分区头、压缩标记。
enum ChatRow: Identifiable {
    var id: String {
        switch self {
        case let .dayHeader(id, _): return id
        case let .entry(id, _): return id
        case let .toolGroup(id, _): return id
        case let .turnBlock(id, _, _): return id
        case let .boundary(id, _, _): return id
        case let .compactMark(id, _, _): return id
        }
    }

    case dayHeader(id: String, atMs: Int64)
    case entry(id: String, entry: BridgeEntry)
    case toolGroup(id: String, items: [BridgeEntry])
    /// 回合块（真机反馈③）：思考 + 已完成工具统一折叠在正式输出上方。
    case turnBlock(id: String, thinking: [BridgeEntry], tools: [BridgeEntry])
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
/// 回合折叠（真机反馈③）：思考条目与**已完成**工具条目进入缓冲，随紧随的助手正式
/// 输出折叠成单个 turnBlock 摆在其上方（工具 item 收进块内展开查看）；缓冲没有被
/// 助手消息消费时（流式尾部/用户消息插队/run 进行中）按原样吐出——思考卡与工具组
/// 保持原有尾态渲染，正式输出一到即折叠。
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

    func addDayHeader(_ atMs: Int64, _ refId: String) {
        guard atMs > 0 else { return }
        let key = dateKey(atMs)
        if key != lastDay {
            rows.append(.dayHeader(id: uniqueId("day-\(key)-\(refId)"), atMs: atMs))
            lastDay = key
        }
    }

    var pendThinking: [BridgeEntry] = []
    var pendTools: [BridgeEntry] = []

    /// 缓冲没等到助手消息：按尾态原样渲染（思考卡 + ≥2 连续已完成工具组）。
    func flushPendingAsTail() {
        for t in pendThinking {
            rows.append(.entry(id: uniqueId(t.id), entry: t))
        }
        if pendTools.count >= 2 {
            rows.append(.toolGroup(id: uniqueId("grp:\(pendTools[0].id):\(pendTools.count)"), items: pendTools))
        } else {
            for t in pendTools {
                rows.append(.entry(id: uniqueId(t.id), entry: t))
            }
        }
        pendThinking = []
        pendTools = []
    }

    func addMessage(_ entry: BridgeEntry) {
        addDayHeader(entry.timestampMs, entry.id)
        rows.append(.entry(id: uniqueId(entry.id), entry: entry))
    }

    for entry in entries {
        switch entry.kind {
        case "thinking":
            pendThinking.append(entry)
        case "tool":
            if entry.state == "running" {
                flushPendingAsTail()
                rows.append(.entry(id: uniqueId(entry.id), entry: entry))
            } else {
                pendTools.append(entry)
            }
        case "message" where entry.role == "assistant":
            let hasPending = !pendThinking.isEmpty || !pendTools.isEmpty
            if hasPending {
                // 日分隔随回合块走（块+正式输出同属消息时刻的一天）
                addDayHeader(entry.timestampMs, entry.id)
                rows.append(.turnBlock(
                    id: uniqueId("turn:\(pendThinking.first?.id ?? pendTools.first?.id ?? entry.id)"),
                    thinking: pendThinking, tools: pendTools))
                pendThinking = []
                pendTools = []
            }
            addMessage(entry)
        default:
            // 用户消息/系统行等：缓冲原样吐出再放本条
            flushPendingAsTail()
            if entry.kind == "message" {
                addMessage(entry)
            } else {
                rows.append(.entry(id: uniqueId(entry.id), entry: entry))
            }
        }
    }
    flushPendingAsTail()
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
