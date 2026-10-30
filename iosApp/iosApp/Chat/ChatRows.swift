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
        case let .turnBlock(id, _, _, _): return id
        case let .boundary(id, _, _): return id
        case let .compactMark(id, _, _): return id
        }
    }

    case dayHeader(id: String, atMs: Int64)
    case entry(id: String, entry: BridgeEntry)
    case toolGroup(id: String, items: [BridgeEntry])
    /// 回合块（真机反馈③）：思考 + 工具 + 过程文本统一折叠，摆在该回合最终输出上方。
    case turnBlock(id: String, thinking: [BridgeEntry], tools: [BridgeEntry], interim: [BridgeEntry])
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
/// 回合折叠（真机反馈③，按 mini-x 实证形态重做）：回合 = 用户消息之后、下一条用户
/// 消息之前的全部思考/工具/过程文本，加上该回合的最终输出。思考与工具进缓冲，过程
/// 文本（如「让我用网页搜索…」）也进组；回合闭合（下一条用户消息/回合尾）时，折叠成
/// 单个 turnBlock 摆在最终输出上方。流式中的行（streaming 消息/运行中工具）即时收拢
/// 已完成回合并原样渲染，不进组——直播可见性优先，重载后即合为单块。
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

    func addEntryRow(_ entry: BridgeEntry) {
        if entry.kind == "message" { addDayHeader(entry.timestampMs, entry.id) }
        rows.append(.entry(id: uniqueId(entry.id), entry: entry))
    }

    var pendThinking: [BridgeEntry] = []
    var pendTools: [BridgeEntry] = []
    var pendInterim: [BridgeEntry] = []  // system 旁白（commentary 过程叙述，live 尾部垫底元凶）
    var pendMsgs: [BridgeEntry] = []     // 过程文本 + 最终输出（最后一条 = 主内容）

    /// 活尾（流式中无闭合回合）：思考卡/工具卡/过程文本原样渲染保持现场。
    func flushTail() {
        for t in pendThinking { rows.append(.entry(id: uniqueId(t.id), entry: t)) }
        if pendTools.count >= 2 {
            rows.append(.toolGroup(id: uniqueId("grp:\(pendTools[0].id):\(pendTools.count)"), items: pendTools))
        } else {
            for t in pendTools { rows.append(.entry(id: uniqueId(t.id), entry: t)) }
        }
        for m in pendInterim { rows.append(.entry(id: uniqueId(m.id), entry: m)) }
        for m in pendMsgs { addEntryRow(m) }
        pendThinking = []; pendTools = []; pendInterim = []; pendMsgs = []
    }

    /// 回合闭合：折叠条（思考+工具+过程文本）摆在该回合最终输出上方；无可折叠内容时
    /// 过程消息直接落行。
    func flushTurn() {
        if pendThinking.isEmpty && pendTools.isEmpty {
            for m in pendInterim { rows.append(.entry(id: uniqueId(m.id), entry: m)) }
            for m in pendMsgs { addEntryRow(m) }
            pendInterim = []; pendMsgs = []
            return
        }
        let anchor = pendMsgs.last
        let interim = pendInterim + pendMsgs.dropLast()
        addDayHeader(anchor?.timestampMs ?? pendThinking.first?.timestampMs ?? pendTools.first?.timestampMs ?? 0,
                     anchor?.id ?? pendThinking.first?.id ?? pendTools.first?.id ?? "turn")
        rows.append(.turnBlock(
            id: uniqueId("turn:\(pendThinking.first?.id ?? pendTools.first?.id ?? anchor?.id ?? "x")"),
            thinking: pendThinking, tools: pendTools, interim: interim))
        if let a = anchor { addEntryRow(a) }
        pendThinking = []; pendTools = []; pendInterim = []; pendMsgs = []
    }

    for entry in entries {
        switch entry.kind {
        case "thinking":
            pendThinking.append(entry)
        case "tool":
            if entry.state == "running" {
                // 运行中工具保持现场：已完成回合收拢成块，未闭合（有过程文本）按活尾渲染
                if pendMsgs.isEmpty { flushTurn() } else { flushTail() }
                addEntryRow(entry)
            } else {
                pendTools.append(entry)
            }
        case "message" where entry.role == "system":
            // commentary 旁白（agent 过程叙述，live 追加在尾部）= 过程文本进组
            pendInterim.append(entry)
        case "message" where entry.role == "assistant" && entry.streaming:
            // 流式中的输出：先闭合已完成回合（折叠在其上方），流式行原样渲染
            flushTurn()
            addEntryRow(entry)
        case "message" where entry.role == "assistant":
            pendMsgs.append(entry)
        default:
            flushTurn()
            addEntryRow(entry)
        }
    }
    if pendMsgs.isEmpty { flushTail() } else { flushTurn() }
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
