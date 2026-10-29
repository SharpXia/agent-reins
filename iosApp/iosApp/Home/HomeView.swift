import SwiftUI
import composeApp

/// 会话首页（M4 Conversation-first）：行 = conversation；抽屉 = agents；
/// 滑动操作 = 置顶/归档/隐藏；收纳视图 = 归档+隐藏。
struct HomeView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.colorScheme) private var scheme
    @State private var store = AppStore.shared
    @State private var showStash = false
    @State private var showDrawer = false
    @State private var showPlusMenu = false
    @State private var taskSheetConv: BridgeConversation?
    @State private var createInFlight = false

    var body: some View {
        Group {
            if store.lists.conversations.isEmpty {
                ConnectionEmptyState(conn: store.conn)
            } else {
                VStack(spacing: 0) {
                    header
                    if let err = store.conn.lastError {
                        ErrorBanner(message: err) { store.bridge.clearError() }
                    }
                    list
                }
            }
        }
        .background(c.paper)
        .navigationBarHidden(true)
        // 左缘右滑开抽屉（CMP 版 ModalNavigationDrawer 边缘手势的复刻）
        .overlay(alignment: .leading) {
            Color.clear
                .frame(width: 26)
                .frame(maxHeight: .infinity)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture(minimumDistance: 12)
                        .onEnded { v in
                            if v.translation.width > 48 && abs(v.translation.height) < 60 {
                                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                                showDrawer = true
                            }
                        }
                )
                .allowsHitTesting(store.lists.conversations.isEmpty == false)
        }
        .sheet(isPresented: $showDrawer) { AgentsDrawerView() }
        .sheet(item: $taskSheetConv) { conv in
            TaskDetailSheetView(conv: conv)
        }
    }

    // ---- 顶栏 ----

    private var header: some View {
        HStack(spacing: 2) {
            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                showDrawer = true
            } label: {
                Image(systemName: "line.3.horizontal")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundColor(c.ink)
                    .frame(width: 34, height: 34)
            }
            .buttonStyle(.plain)

            VStack(alignment: .leading, spacing: 1) {
                Text(showStash ? "收纳" : "会话").ink(.headingMd).foregroundColor(c.ink)
                Text(headerSubtitle).ink(.textSm).foregroundColor(c.textTertiary)
            }
            Spacer(minLength: 8)

            if store.totalUnread > 0 && !showStash {
                Button {
                    Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                    store.bridge.markAllRead()
                } label: {
                    Text("全部已读").ink(.textSm).foregroundColor(c.ink)
                        .padding(.horizontal, 8)
                }
                .buttonStyle(.plain)
            }

            Menu {
                let agents = store.lists.agents
                if agents.isEmpty {
                    Text("尚无 agent")
                } else {
                    ForEach(agents, id: \.agentId) { agent in
                        Button {
                            openOrCreate(agentId: agent.agentId)
                        } label: {
                            HStack {
                                Text(agent.name)
                                Spacer()
                                Text("\(agent.sessionCount) 个会话")
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
            } label: {
                Text("＋").font(.system(size: 20, weight: .medium)).foregroundColor(c.ink)
                    .padding(.horizontal, 8)
            }

            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                withAnimation { showStash.toggle() }
            } label: {
                let empty = store.archivedConvs.isEmpty && store.hiddenConvs.isEmpty
                Text(showStash ? "返回" : "收纳(\(store.archivedConvs.count + store.hiddenConvs.count))")
                    .ink(.textMd)
                    .foregroundColor(empty && !showStash ? c.textDisabled : c.ink)
                    .padding(.horizontal, 6)
            }
            .buttonStyle(.plain)
        }
        .padding(.leading, 8)
        .padding(.trailing, 8)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    private var headerSubtitle: String {
        if showStash {
            return "归档 \(store.archivedConvs.count) · 隐藏 \(store.hiddenConvs.count)"
        }
        if store.isOffline { return "离线中 · 显示缓存列表" }
        return "\(store.mainConvs.count) 个对话"
    }

    private func openOrCreate(agentId: String) {
        Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
        if let convId = store.bridge.conversationIdForAgent(agentId: agentId) {
            navigateToConversation(agentId: agentId, convId: convId)
        } else {
            store.bridge.createNewSession(agentId: agentId)
        }
    }

    @Environment(NavCoordinator.self) private var navCoordinator

    private func navigateToConversation(agentId: String, convId: String) {
        navCoordinator.push(.conversation(convId: convId, anchor: nil))
    }

    // ---- 列表 ----

    private var list: some View {
        List {
            if showStash {
                if !store.archivedConvs.isEmpty {
                    Section {
                        ForEach(store.archivedConvs, id: \.id) { conv in
                            row(conv, stash: true)
                        }
                    } header: {
                        SectionHeader(text: "归档").listRowInsets(EdgeInsets())
                    }
                }
                if !store.hiddenConvs.isEmpty {
                    Section {
                        ForEach(store.hiddenConvs, id: \.id) { conv in
                            row(conv, stash: true)
                        }
                    } header: {
                        SectionHeader(text: "已隐藏").listRowInsets(EdgeInsets())
                    }
                }
                if store.archivedConvs.isEmpty && store.hiddenConvs.isEmpty {
                    Text("没有归档或隐藏的对话")
                        .ink(.textMd).foregroundColor(c.textTertiary)
                        .padding(24)
                }
            } else {
                ForEach(store.mainConvs, id: \.id) { conv in
                    row(conv, stash: false)
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .environment(\.defaultMinListRowHeight, 10)
    }

    @ViewBuilder
    private func row(_ conv: BridgeConversation, stash: Bool) -> some View {
        let stashRow = conv.archived || conv.hidden
        ConversationRowView(conv: conv, stash: stashRow) {
            taskSheetConv = conv
        }
        .listRowBackground(c.paper)
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 0, leading: 0, bottom: 0, trailing: 0))
        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                if stashRow {
                    store.bridge.setConvHidden(id: conv.id, hidden: !conv.hidden)
                } else {
                    store.bridge.setConvArchived(id: conv.id, archived: true)
                }
            } label: {
                Label(stashRow ? (conv.hidden ? "取消隐藏" : "隐藏") : "归档",
                      systemImage: stashRow ? "eye.slash" : "archivebox")
            }
            .tint(stashRow && conv.hidden ? c.successText : c.warningText)

            Button {
                Feedback.hapticSuccess(enabled: store.misc.hapticsEnabled)
                if !stashRow {
                    store.bridge.setConvPinned(id: conv.id, pinned: !conv.pinned)
                } else if conv.archived {
                    store.bridge.setConvArchived(id: conv.id, archived: false)
                } else {
                    store.bridge.setConvHidden(id: conv.id, hidden: false)
                }
            } label: {
                Label {
                    Text({
                        if !stashRow { return conv.pinned ? "取消置顶" : "置顶" }
                        if conv.archived { return "恢复" }
                        return "恢复"
                    }())
                } icon: {
                    Image(systemName: stashRow ? "tray.and.arrow.up" : "pin")
                }
            }
            .tint(c.successText)
        }
        .contextMenu {
            if !stashRow {
                Button {
                    store.bridge.setConvPinned(id: conv.id, pinned: !conv.pinned)
                } label: {
                    Label(conv.pinned ? "取消置顶" : "置顶", systemImage: "pin")
                }
            }
            Button {
                store.bridge.setConvArchived(id: conv.id, archived: !conv.archived)
            } label: {
                Label(conv.archived ? "取消归档" : "归档",
                      systemImage: conv.archived ? "tray.and.arrow.up" : "archivebox")
            }
            Button {
                store.bridge.setConvHidden(id: conv.id, hidden: !conv.hidden)
            } label: {
                Label(conv.hidden ? "取消隐藏" : "隐藏",
                      systemImage: conv.hidden ? "eye" : "eye.slash")
            }
        }
    }
}

// MARK: - conversation 行

struct ConversationRowView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.colorScheme) private var scheme
    @State private var store = AppStore.shared
    @Environment(NavCoordinator.self) private var navCoordinator
    let conv: BridgeConversation
    let stash: Bool
    var onOpenTasks: () -> Void = {}

    var body: some View {
        Button {
            navCoordinator.push(.conversation(convId: conv.id, anchor: nil))
        } label: {
            content
        }
        .buttonStyle(.plain)
    }

    private var content: some View {
        let tint = store.tint(conv.agentId, isDark: scheme == .dark)
        return HStack(alignment: .center, spacing: 12) {
            AgentAvatar(tint: tint, name: store.agentName(conv.agentId))
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    if conv.pinned && !stash {
                        Image(systemName: "pin.fill")
                            .font(.system(size: 9))
                            .foregroundColor(tint.strong)
                    }
                    if conv.hidden {
                        Image(systemName: "eye.slash")
                            .font(.system(size: 10))
                            .foregroundColor(c.textTertiary)
                    }
                    Text(store.agentName(conv.agentId))
                        .ink(.textLg)
                        .fontWeight(conv.unread > 0 ? .semibold : .medium)
                        .foregroundColor(c.ink)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    if conv.kind == "channel", let channel = conv.channel {
                        ChannelBadge(channel: channel).padding(.trailing, 2)
                    }
                    Text(formatRelativeTime(conv.lastActiveAtMs))
                        .ink(.textXs)
                        .foregroundColor(c.textTertiary)
                }
                HStack(spacing: 6) {
                    if conv.running && !stash {
                        TypingDots()
                        Text(conv.derivedTasks > 0
                             ? "执行中 · 已派发 \(conv.derivedTasks) 个子任务"
                             : "执行中")
                            .ink(.textSm)
                            .foregroundColor(c.awaiting)
                        if conv.derivedTasks > 0 {
                            Button("详情", action: onOpenTasks)
                                .ink(.textXs)
                                .foregroundColor(c.infoText)
                                .buttonStyle(.plain)
                        }
                    } else {
                        Text(conv.preview ?? conv.id)
                            .ink(.textSm)
                            .foregroundColor(c.textTertiary)
                            .lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if conv.unread > 0 {
                        UnreadBadge(count: Int(conv.unread))
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}

// MARK: - Agents 抽屉

struct AgentsDrawerView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    @State private var store = AppStore.shared
    @Environment(NavCoordinator.self) private var navCoordinator

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Agents")
                .ink(.headingMd)
                .foregroundColor(c.ink)
                .padding(.leading, 20)
                .padding(.top, 24)
                .padding(.bottom, 6)
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(store.lists.agents, id: \.agentId) { agent in
                        drawerRow(agent)
                        if agent.agentId != store.lists.agents.last?.agentId {
                            SandDivider(indent: 66)
                        }
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .presentationBackground(c.elevated)
        .presentationDragIndicator(.hidden)
    }

    private func drawerRow(_ agent: BridgeAgent) -> some View {
        let tint = store.tint(agent.agentId, isDark: c.isDark)
        return HStack(spacing: 0) {
            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                dismiss()
                openAgentConversation(agent)
            } label: {
                HStack(spacing: 12) {
                    AgentAvatar(tint: tint, name: agent.name)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(agent.name).ink(.textLg).fontWeight(.medium).foregroundColor(c.ink)
                        Text("\(agent.sessionCount) 个会话").ink(.textXs).foregroundColor(c.textTertiary)
                    }
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            if store.misc.creatingSession {
                ProgressView().frame(width: 32)
            } else {
                Button {
                    Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                    dismiss()
                    openAgentConversation(agent)
                } label: {
                    Text("＋").font(.system(size: 17, weight: .medium)).foregroundColor(c.ink)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
            }

            Button {
                Feedback.hapticTap(enabled: store.misc.hapticsEnabled)
                dismiss()
                navCoordinator.push(.agent(agentId: agent.agentId))
            } label: {
                Image(systemName: "pencil")
                    .font(.system(size: 13))
                    .foregroundColor(c.textTertiary)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
        }
        .padding(.trailing, 8)
    }

    private func openAgentConversation(_ agent: BridgeAgent) {
        if let convId = store.bridge.conversationIdForAgent(agentId: agent.agentId) {
            navCoordinator.push(.conversation(convId: convId, anchor: nil))
        } else {
            store.bridge.createNewSession(agentId: agent.agentId)
        }
    }
}

// MARK: - 任务详情 Sheet（M4 P5）

struct TaskDetailSheetView: View {
    @Environment(\.inkColors) private var c
    @State private var store = AppStore.shared
    @Environment(NavCoordinator.self) private var navCoordinator
    let conv: BridgeConversation

    var body: some View {
        InkSheet(title: "派发中的子任务 · \(conv.taskKeys.count)") {
            ScrollView {
                VStack(spacing: 0) {
                    if conv.taskKeys.isEmpty {
                        Text("当前没有进行中的子任务")
                            .ink(.textMd).foregroundColor(c.textTertiary)
                            .padding(20)
                    }
                    ForEach(conv.taskKeys, id: \.self) { key in
                        Button {
                            navCoordinator.push(.chat(sessionKey: key, readOnly: true))
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("subagent · \(String(key.split(separator: ":").last ?? "").prefix(13))…")
                                        .ink(.textMd).foregroundColor(c.ink)
                                    Text("派生的后台任务会话").ink(.textXs).foregroundColor(c.textTertiary)
                                }
                                Spacer()
                                TypingDots()
                                Text("执行中").ink(.textSm).foregroundColor(c.awaiting)
                            }
                            .padding(.horizontal, 20)
                            .padding(.vertical, 12)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    Text("点击进入只读浏览（不发消息、不计未读）")
                        .ink(.textXs).foregroundColor(c.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                }
            }
        }
    }
}
