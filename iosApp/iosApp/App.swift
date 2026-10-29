import SwiftUI
import composeApp

@main
struct AgentReinsApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// 路由（等价 Compose 版 Routes：chat/conversation/agent/connection/sounds/themeDemo）。
enum AppRoute: Hashable {
    case chat(sessionKey: String, readOnly: Bool)
    case conversation(convId: String, anchor: String?)
    case agent(agentId: String)
    case connection
    case sounds
    case themeDemo
}

struct RootView: View {
    @State private var store = AppStore.shared

    var body: some View {
        Group {
            if store.conn.showOnboarding {
                OnboardingView()
            } else {
                MainTabsView()
            }
        }
        .inkTheme()
        .preferredColorScheme(store.colorSchemeOverride)
        .sheet(item: approvalItem) { req in
            ApprovalSheetView(request: req)
        }
    }

    private var approvalItem: Binding<BridgeApproval?> {
        Binding(
            get: { store.misc.pendingApproval },
            set: { _ in }
        )
    }
}

/// 导航协调：三个 tab 各一条 NavigationStack 路径，视图经环境对象 push。
@Observable
final class NavCoordinator {
    var sessionsPath = NavigationPath()
    var searchPath = NavigationPath()
    var settingsPath = NavigationPath()

    func push(_ route: AppRoute) {
        // 二级页归属来源 tab（与 Compose 版 InkBottomBar 选中态同语义）：
        // 会话域 push 会话栈；连接/提示音/令牌页 push 设置栈。
        switch route {
        case .connection, .sounds, .themeDemo:
            settingsPath.append(route)
        default:
            sessionsPath.append(route)
        }
    }
}

/// 4-tab IA 的原生形态（M3.1：会话/搜索/设置；Agents 收进会话页抽屉）。
struct MainTabsView: View {
    @State private var store = AppStore.shared
    @State private var nav = NavCoordinator()

    var body: some View {
        TabView {
            Tab("会话", systemImage: "house.fill") {
                NavigationStack(path: $nav.sessionsPath) {
                    HomeView()
                        .navigationDestination(for: AppRoute.self) { route($0) }
                }
            }
            Tab("搜索", systemImage: "magnifyingglass") {
                NavigationStack(path: $nav.searchPath) {
                    SearchView()
                        .navigationDestination(for: AppRoute.self) { route($0) }
                }
            }
            Tab("设置", systemImage: "gearshape.fill") {
                NavigationStack(path: $nav.settingsPath) {
                    SettingsView()
                        .navigationDestination(for: AppRoute.self) { route($0) }
                }
            }
        }
        .environment(nav)
        .onChange(of: store.eventSeq) { _, _ in
            // createNewSession 完成直达（navigationEvents）：切回会话 tab 并 push 会话页
            if let key = store.openSessionEvent {
                store.consumeOpenSession()
                nav.sessionsPath.append(AppRoute.chat(sessionKey: key, readOnly: false))
            }
            // Onboarding 直达 / dev 通道自动进会话（Compose 版 Nav 层同语义，一次性）
            let selected = store.conv.sessionKey
            if !selected.isEmpty, selected == store.misc.autoOpenKey || selected == store.conn.devAutoSelectKey {
                store.bridge.consumeAutoOpen()
                if nav.sessionsPath.isEmpty {
                    nav.sessionsPath.append(AppRoute.chat(sessionKey: selected, readOnly: false))
                }
            }
        }
    }

    @ViewBuilder
    private func route(_ r: AppRoute) -> some View {
        switch r {
        case let .chat(key, readOnly):
            ChatRoute(sessionKey: key, readOnly: readOnly)
        case let .conversation(convId, anchor):
            ConversationRouteView(convId: convId, anchor: anchor)
        case let .agent(agentId):
            AgentDetailView(agentId: agentId)
        case .connection:
            ConnectionView(showsBack: true)
        case .sounds:
            SoundsView()
        case .themeDemo:
            ThemeDemoView()
        }
    }
}

/// per-session 对话页路由（搜索/agent 主页/fork/任务详情直达）。
struct ChatRoute: View {
    @State private var store = AppStore.shared
    let sessionKey: String
    let readOnly: Bool

    var body: some View {
        ChatView(sessionKey: sessionKey, readOnly: readOnly)
            .onAppear {
                if store.conv.sessionKey != sessionKey {
                    store.bridge.openSessionByKey(key: sessionKey)
                }
            }
    }
}

/// 对话详情路由（M4）：主线 → 拼接时间线；渠道线程 → 单 session chat。
struct ConversationRouteView: View {
    @State private var store = AppStore.shared
    @Environment(\.dismiss) private var dismiss
    let convId: String
    let anchor: String?

    var body: some View {
        Group {
            if let conv = store.lists.conversations.first(where: { $0.id == convId }) {
                if conv.kind == "channel", let key = conv.sessionKeys.first {
                    ChatView(sessionKey: key, readOnly: false)
                } else {
                    ChatView(sessionKey: conv.sessionKeys.first ?? "", readOnly: false,
                             anchorSession: anchor)
                }
            } else {
                Color.clear.onAppear { dismiss() }
            }
        }
        .onAppear {
            if let conv = store.lists.conversations.first(where: { $0.id == convId }),
               conv.kind != "channel",
               store.timeline.agentId != conv.agentId {
                store.bridge.openTimeline(agentId: conv.agentId)
            }
        }
        .onDisappear {
            store.bridge.closeTimeline()
        }
    }
}

/// 审批卡 Sheet（M3.5）。
struct ApprovalSheetView: View {
    @Environment(\.inkColors) private var c
    @State private var store = AppStore.shared
    let request: BridgeApproval

    var body: some View {
        InkSheet(title: "需要你的批准") {
            VStack(alignment: .leading, spacing: 8) {
                Text(request.summary)
                    .ink(.textLg)
                    .foregroundColor(c.ink)
                    .padding(.horizontal, 20)
                if let detail = request.detailJson {
                    Text(String(detail.prefix(600)))
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundColor(c.textTertiary)
                        .padding(8)
                        .background(RoundedRectangle(cornerRadius: InkShape.sm).fill(c.surfaceTertiary))
                        .padding(.horizontal, 20)
                }
                HStack {
                    Spacer()
                    Button("拒绝") { store.bridge.respondApproval(approved: false) }
                        .ink(.textLg)
                        .foregroundColor(c.dangerText)
                    Button("批准") { store.bridge.respondApproval(approved: true) }
                        .ink(.textLg)
                        .foregroundColor(c.ink)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .padding(.bottom, 16)
        }
    }
}
