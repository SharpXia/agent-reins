import Foundation
import SwiftUI
import composeApp

/// SwiftUI 全局状态仓：持有 AgentBridge，把五路 Kotlin 快照转成 @Observable 属性。
/// 视图只读快照 + 调 bridge 动作，不触碰 Kotlin 内部状态（冻结约束下的唯一消费面）。
@Observable
final class AppStore {
    static let shared = AppStore()

    let bridge: AgentBridge
    private(set) var conn: BridgeConn = .empty()
    private(set) var lists: BridgeLists = .empty()
    private(set) var conv: BridgeConv = .empty()
    private(set) var timeline: BridgeTimeline = .empty()
    private(set) var misc: BridgeMisc = .empty()

    /// createNewSession 完成直达事件（Compose 版 navigationEvents 的等价消费口）。
    private(set) var openSessionEvent: String?

    /// Nav 层消费完直达事件后清除，防陈旧重放（与 Compose 版 consumeAutoOpen 同语义）。
    func consumeOpenSession() { openSessionEvent = nil }
    /// 会话已读等一次性 UI 提示的轻量信号。
    private(set) var eventSeq = 0

    private init() {
        bridge = MainViewControllerKt.AgentReinsBridge()
        bridge.start(
            onConn: { [weak self] snap in self?.conn = snap },
            onLists: { [weak self] snap in self?.lists = snap },
            onConv: { [weak self] snap in self?.conv = snap },
            onTimeline: { [weak self] snap in self?.timeline = snap },
            onMisc: { [weak self] snap in
                guard let self else { return }
                self.misc = snap
                self.eventSeq += 1
            },
            onEvent: { [weak self] type, payload in
                guard let self else { return }
                if type == "openSession" { self.openSessionEvent = payload }
                self.eventSeq += 1
            }
        )
    }

    // ---- 派生 ----

    var isOffline: Bool { conn.kind != "connected" }

    /// 首页主列表 / 收纳视图分组（与会话首页同语义：mainline 且 app 渠道）。
    var mainConvs: [BridgeConversation] {
        lists.conversations.filter { $0.kind == "mainline" && !$0.hidden && !$0.archived }
    }

    var archivedConvs: [BridgeConversation] {
        lists.conversations.filter { $0.kind == "mainline" && $0.archived && !$0.hidden }
    }

    var hiddenConvs: [BridgeConversation] {
        lists.conversations.filter { $0.kind == "mainline" && $0.hidden }
    }

    var totalUnread: Int { mainConvs.reduce(0) { $0 + Int($1.unread) } }

    func agentName(_ agentId: String) -> String {
        lists.agents.first { $0.agentId == agentId }?.name ?? agentId
    }

    func agentTintName(_ agentId: String) -> AgentTintName {
        if let raw = lists.agents.first(where: { $0.agentId == agentId })?.tint,
           let name = AgentTintName(rawValue: raw) {
            return name
        }
        return agentTintFor(agentId)  // returns AgentTintName
    }

    /// agent 表情（规格 avatar-spec-20261029 §9）：读桥已解析的枚举名，miss/非法串落
    /// `.calm` 常量（正常路径桥已解析，只有列表未就绪的瞬态会命中兜底；此处不做任何 hash）。
    func agentExpression(_ agentId: String) -> AgentExpression {
        guard let raw = lists.agents.first(where: { $0.agentId == agentId })?.expression else {
            return .calm
        }
        return AgentExpression(rawValue: raw) ?? .calm
    }

    func tint(_ agentId: String, isDark: Bool) -> AgentTintColors {
        agentTint(agentTintName(agentId), isDark: isDark)
    }

    func session(_ key: String) -> BridgeSession? {
        lists.sessions.first { $0.id == key }
    }

    /// 客户端四路会话过滤（标题/预览/key/agent，全词匹配，离线可用）。
    func filterSessions(_ query: String) -> [BridgeSession] {
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return [] }
        let terms = q.split(separator: " ").map(String.init).map { $0.lowercased() }
        return lists.sessions.filter { s in
            let hay = [s.title, s.preview, s.id, s.agentId].compactMap { $0 }.joined(separator: " ").lowercased()
            return terms.allSatisfy { hay.contains($0) }
        }
    }

    /// 某会话的派生任务清单（任务详情 Sheet 数据源，Kotlin 侧已按 agent 预派生）。
    func tasks(for conv: BridgeConversation) -> [String] { conv.taskKeys }

    /// loadOlder 是否真的派发（SwiftUI refreshable 据此决定等不等待）。
    func loadOlder() -> Bool { bridge.loadOlder() }

    /// 等待向上翻页结束（refreshable 挂起语义；CMP 版下拉 loading 等价物）。
    func awaitOlderIdle(timeout: TimeInterval = 10) async {
        let start = Date()
        while Date().timeIntervalSince(start) < timeout {
            if !conv.loadingOlder { return }
            try? await Task.sleep(nanoseconds: 80_000_000)
        }
    }

    func awaitSectionIdle(timeout: TimeInterval = 10) async {
        let start = Date()
        while Date().timeIntervalSince(start) < timeout {
            if timeline.sectionLoading == nil { return }
            try? await Task.sleep(nanoseconds: 80_000_000)
        }
    }

    /// 主题模式 → SwiftUI preferredColorScheme；nil = 跟随系统。
    var colorSchemeOverride: ColorScheme? {
        switch misc.themeMode {
        case "LIGHT": return .light
        case "DARK": return .dark
        default: return nil
        }
    }
}
