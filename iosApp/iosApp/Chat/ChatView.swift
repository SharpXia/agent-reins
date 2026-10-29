import SwiftUI
import composeApp

/// 对话页（单 session 与 M4 拼接时间线共用；timeline.agentId 非 nil = 拼接模式）。
///
/// 顽疾原位修复对照（Compose 版 → SwiftUI 原生）：
/// - 钉底：LazyColumn scrollToItem+大 offset 钳制 hack → ScrollPosition(edge:.bottom)，
///   长消息超屏天然贴绝对底边（M4 反馈②③④的统一根因在原生侧不存在）。
/// - 键盘：imePadding 时序+重钉 → safeAreaInset + 系统键盘避让；回前台贴底兜底。
/// - 下拉加载：自研 nestedScroll 手势机（material3 松手回弹不触发的绕行）→ 系统 .refreshable。
/// - 边缘返回：自绘视差手势 → 系统 interactivePopGesture（导航栈原生行为）。
/// - 状态栏回顶/回底：系统 scrollsToTop 通道免费获得。
struct ChatView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @Environment(NavCoordinator.self) private var nav
    @State private var store = AppStore.shared

    /// 单 session 模式的会话键；拼接模式下随桥快照走。
    var sessionKey: String
    var readOnly: Bool = false
    /// M4 P5 分区锚定：时间线就绪后滚到该 session 的分区头（一次性）。
    var anchorSession: String? = nil

    private var timelineMode: Bool { store.timeline.agentId != nil }

    @State private var scroll = ScrollPosition(edge: .bottom)
    @State private var isAtBottom = true
    @State private var pinnedOnce = false
    @State private var keyboardShowing = false
    @State private var showStartFresh = false
    @State private var anchorConsumed = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            if let err = store.conn.lastError {
                ErrorBanner(message: err) { store.bridge.clearError() }
            }
            if store.isOffline {
                HStack(spacing: 6) {
                    Image(systemName: "wifi.slash")
                        .font(.system(size: 11))
                    Text("离线 · 显示本地缓存，连接后自动更新")
                        .ink(.textXs)
                }
                .foregroundColor(c.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 5)
                .background(c.surfaceTertiary)
            }
            chatList
        }
        .background(c.paper)
        .navigationBarHidden(true)
        .toolbar(.hidden, for: .tabBar)
        .confirmationDialog("从头开始？", isPresented: $showStartFresh, titleVisibility: .visible) {
            Button("开新会话") { store.bridge.startFresh() }
            Button("取消", role: .cancel) {}
        } message: {
            Text("这个会话的聊天记录将不再出现在新会话里。\n✓ 会保留：Agent 的指令、记忆与技能配置\n✗ 会留在旧会话：本次对话的全部消息")
        }
        .onReceive(
            NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)
        ) { _ in keyboardShowing = true }
        .onReceive(
            NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)
        ) { _ in keyboardShowing = false }
    }

    // ---- 顶栏 ----

    private var topBar: some View {
        HStack(spacing: 4) {
            Button {
                // 切走前落草稿 + 清选中（CMP 版返回同语义）
                store.bridge.selectSession(key: nil)
                dismiss()
            } label: {
                Image(systemName: "chevron.backward")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundColor(c.ink)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 1) {
                Text(titleText).ink(.headingSm).foregroundColor(c.ink).lineLimit(1)
                if !historyLoaded {
                    HStack(spacing: 8) {
                        TypingDots()
                        Text("加载中").ink(.textXs).foregroundColor(c.textTertiary)
                    }
                } else {
                    Text(subtitleText).ink(.textXs).foregroundColor(c.textTertiary).lineLimit(1)
                }
            }
            Spacer(minLength: 8)

            Menu {
                Button {
                    showStartFresh = true
                } label: {
                    Label("从头开始", systemImage: "arrow.counterclockwise")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(c.textSecondary)
                    .frame(width: 36, height: 36)
            }
        }
        .padding(.horizontal, 6)
        .padding(.vertical, 4)
    }

    private var historyLoaded: Bool {
        timelineMode ? !store.timeline.sections.isEmpty || timelineTried : store.conv.historyLoaded
    }
    @State private var timelineTried = false

    private var titleText: String {
        if timelineMode {
            let sections = store.timeline.sections
            let base = store.agentName(store.timeline.agentId ?? "")
            return sections.isEmpty ? base : "\(base) · 对话 · \(sections.count) 个会话"
        }
        return store.session(sessionKey)?.title ?? sessionKey
    }

    private var subtitleText: String {
        if timelineMode { return "" }
        let s = store.session(sessionKey)
        if let kind = s?.kind, !kind.isEmpty { return kind }
        return sessionKey
    }

    // ---- 列表 ----

    private var chatList: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 8) {
                if timelineMode {
                    timelineRows
                } else {
                    sessionRows
                }
                if runActive && !thinkingAtTail {
                    TypingIndicator().id("ar-typing")
                }
                Color.clear.frame(height: 2).id(ChatAnchorTag.bottom)
            }
            .scrollTargetLayout()
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .scrollPosition($scroll)
        .onScrollGeometryChange(for: Bool.self) { geo in
            let maxOffset = geo.contentSize.height - geo.containerSize.height
            return geo.contentOffset.y >= maxOffset - 48
        } action: { _, atBottom in
            if atBottom && !isAtBottom {
                pinnedOnce = true
            }
            isAtBottom = atBottom
        }
        .refreshable {
            if timelineMode {
                if let oldest = store.timeline.sections.last,
                   store.bridge.loadSection(key: oldest.key) {
                    await store.awaitSectionIdle()
                }
            } else if store.bridge.loadOlder() {
                await store.awaitOlderIdle()
            }
        }
        .scrollDismissesKeyboard(.immediately)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if readOnly {
                Text("只读浏览 · 派生任务会话")
                    .ink(.textXs).foregroundColor(c.textTertiary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(c.paper)
            } else {
                ComposerView()
                    .background(c.paper)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            if !isAtBottom && hasContent {
                Button {
                    Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                    scroll.scrollTo(edge: .bottom)
                } label: {
                    Image(systemName: "chevron.down")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(c.ink)
                        .padding(10)
                        .background(Circle().fill(c.elevated))
                        .overlay(Circle().strokeBorder(c.borderPrimary, lineWidth: 1))
                        .shadow(color: .black.opacity(0.12), radius: 4, y: 1)
                }
                .buttonStyle(.plain)
                .padding(12)
            }
        }
        // ---- 滚动协同 ----
        .task {
            pinnedOnce = false
            timelineTried = timelineMode && !store.timeline.sections.isEmpty
            // 开场锚底：ScrollPosition(edge:.bottom) 已声明，这里兜底一发
            try? await Task.sleep(nanoseconds: 220_000_000)
            if !pinnedOnce { scroll.scrollTo(edge: .bottom) }
            if timelineMode { timelineTried = true }
        }
        .onChange(of: store.conv.sendSignal) { old, new in
            // 发送后滚到底露出刚发的消息（M4 反馈④；Kotlin 侧发信号计数）
            guard new != old, new > 0 else { return }
            Task {
                try? await Task.sleep(nanoseconds: 120_000_000)
                scroll.scrollTo(edge: .bottom)
            }
        }
        .onChange(of: contentSignature) { _, _ in
            // 流式跟随：内容增长且用户在底 → 持续贴底（用户上拉即让位）
            if pinnedOnce && (runActive || streamingTail) && isAtBottom {
                scroll.scrollTo(edge: .bottom)
            }
        }
        .onChange(of: keyboardShowing) { showing in
            // 键盘压缩视口后重钉（M4 反馈④原生等价；系统避让为主，这里兜底）
            guard showing, isAtBottom, hasContent else { return }
            Task {
                try? await Task.sleep(nanoseconds: 180_000_000)
                scroll.scrollTo(edge: .bottom)
            }
        }
    }

    // ---- 行序列 ----

    @ViewBuilder
    private var timelineRows: some View {
        let marks = store.timeline.marks
        let sections = store.timeline.sections.map {
            TimelineSection(key: $0.key, label: $0.label, entries: $0.entries)
        }
        let rows = buildConversationRows(sections: sections, compactMarks: marks)
        ForEach(rows) { row in
            ChatRowView(
                row: row,
                tintFor: tintForSession,
                lastUserEntryId: lastUserEntryId,
                showTailSpinner: showTailSpinner,
                onBoundaryAppear: { key in
                    // 分区懒加载（M4 反馈②）：分区头进入视口即拉首页/翻更早页；最旧区交给下拉
                    guard key != store.timeline.sections.last?.key,
                          store.timeline.sectionLoading == nil else { return }
                    _ = store.bridge.loadSection(key: key)
                }
            )
            .onAppear {
                consumeAnchorIfNeeded(rows: rows)
            }
        }
    }

    @ViewBuilder
    private var sessionRows: some View {
        let rows = buildChatRows(store.conv.entries)
        if rows.isEmpty && !runActive && store.conv.historyLoaded {
            Text("暂无消息，发一条开始对话")
                .ink(.textSm).foregroundColor(c.textTertiary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 24)
        }
        ForEach(rows) { row in
            ChatRowView(
                row: row,
                tintFor: tintForSession,
                lastUserEntryId: lastUserEntryId,
                showTailSpinner: showTailSpinner,
                onBoundaryAppear: { _ in }
            )
        }
        ForEach(store.conv.pendingOutbox, id: \.idempotencyKey) { cmd in
            PendingOutboxItem(cmd: cmd)
        }
    }

    private func consumeAnchorIfNeeded(rows: [ChatRow]) {
        guard !anchorConsumed, let anchor = anchorSession, !rows.isEmpty else { return }
        guard let target = rows.first(where: {
            if case let .boundary(id, key, _) = $0 { return key == anchor }
            return false
        }) else { return }
        anchorConsumed = true
        Task {
            try? await Task.sleep(nanoseconds: 150_000_000)
            scroll.scrollTo(id: target.id, anchor: .top)
        }
    }

    // ---- 派生 ----

    private var runActive: Bool { store.conv.runActive }

    private var streamingTail: Bool {
        let entries = timelineMode ? (store.timeline.sections.first?.entries ?? []) : store.conv.entries
        return entries.last?.kind == "message" && entries.last?.streaming == true
    }

    private var thinkingAtTail: Bool {
        if timelineMode {
            return store.timeline.sections.first?.entries.last?.kind == "thinking"
        }
        return store.conv.entries.last?.kind == "thinking"
    }

    private var hasContent: Bool {
        timelineMode
            ? store.timeline.sections.contains { !$0.entries.isEmpty }
            : !store.conv.entries.isEmpty
    }

    private var contentSignature: String {
        if timelineMode {
            return store.timeline.sections.map { "\($0.key)#\($0.entries.count):\($0.entries.last?.id ?? "")" }
                .joined(separator: "|")
                + (store.timeline.sectionLoading == nil ? "" : "+loading")
        }
        return "\(store.conv.entries.count):\(store.conv.entries.first?.id ?? ""):\(store.conv.entries.last?.id ?? "")"
    }

    private var lastUserEntryId: String? {
        let entries = timelineMode
            ? (store.timeline.sections.first?.entries ?? [])
            : store.conv.entries
        return entries.last(where: { $0.kind == "message" && $0.role == "user" })?.id
    }

    private var showTailSpinner: Bool {
        guard runActive, let lastId = lastUserEntryId else { return false }
        let entries = timelineMode
            ? (store.timeline.sections.first?.entries ?? [])
            : store.conv.entries
        return entries.last?.id == lastId
    }

    private func tintForSession(_ sessionId: String?) -> AgentTintColors {
        let key = sessionId ?? sessionKey
        let agentId = store.session(key)?.agentId ?? sessionKeyAgentId(key) ?? "main"
        return store.tint(agentId, isDark: scheme == .dark)
    }
}

enum ChatAnchorTag: Hashable { case bottom }
