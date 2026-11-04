import SwiftUI
import composeApp

/// Hub 主界面（P3 最小闭环，P4 起为唯一界面：网关直连面已退役）：会话 / 设置-设备。
/// 导航纪律（0.5.1 真机点击崩溃修复）：KMP 类不可作 value-based 导航的 Hashable 值——
/// 列表推栈用 destination-closure 型 NavigationLink，dev 直达用 chatPush(isPresented)，
/// 全栈零 NavigationPath/zero value-destination。
/// Dev 直达（自动化走查）：AGENTREINS_DEV_TAB=settings；AGENTREINS_DEV_CONV=1 打开首个会话。
struct HubRootView: View {
    @State private var hub = HubStore.shared
    @State private var tab = 0

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HubHomeView() }
                .tabItem { Label("会话", systemImage: "bubble.left.and.bubble.right.fill") }
                .tag(0)
            NavigationStack { HubSettingsView() }
                .tabItem { Label("设置", systemImage: "gearshape.fill") }
                .tag(1)
        }
        .environment(hub)
        .task {
            if ProcessInfo.processInfo.environment["AGENTREINS_DEV_TAB"] == "settings" { tab = 1 }
            if ProcessInfo.processInfo.environment["AGENTREINS_DEV_CONV"] == "1" {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if let first = hub.conversations.first {
                    tab = 0
                    hub.devOpen(first)
                }
            }
            // Dev 发送探针：AGENTREINS_DEV_SEND_KEY=<label 或 conversationId 前缀> + AGENTREINS_DEV_SEND=<文本>
            // 发到指定既有会话（v1.1 起 sessionKey 不再出 UI 面，定位键改 label/conversationId；
            // 必须指向专用测试会话，勿发真实会话）。轮询等投影就绪（首快照+WSS 追平秒级），最多 20s。
            if let keyPrefix = ProcessInfo.processInfo.environment["AGENTREINS_DEV_SEND_KEY"],
               let text = ProcessInfo.processInfo.environment["AGENTREINS_DEV_SEND"] {
                var target: UiConversation?
                for _ in 0..<20 {
                    target = hub.conversations.first {
                        $0.conversationId.hasPrefix(keyPrefix) || $0.label.contains(keyPrefix)
                    }
                    if target != nil { break }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
                if let t = target {
                    tab = 0
                    hub.devOpen(t)
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    hub.send(text)
                }
            }
        }
    }
}

/// 会话列表：投影库 Flow 推送；label 空的卡片以 agentId 兜底命名（v1.1：sessionKey 不出 UI 面）。
struct HubHomeView: View {
    @State private var hub = HubStore.shared

    var body: some View {
        Group {
            if hub.conversations.isEmpty {
                ContentUnavailableView("暂无会话", systemImage: "bubble.left",
                                       description: Text("网关接入后会话将自动出现"))
            } else {
                List(hub.conversations, id: \.conversationId) { c in
                    // destination-closure 型推栈（不走 value/Hashable，见 HubRootView 导航纪律）
                    NavigationLink {
                        HubChatView(conv: c)
                    } label: {
                        HubConversationRow(c: c)
                    }
                }
            }
        }
        .navigationTitle("AgentReins")
        .navigationDestination(isPresented: $hub.chatPush) {
            if let c = hub.selected { HubChatView(conv: c) }
        }
        .task { hub.watchConversations() }
    }
}

private struct HubConversationRow: View {
    let c: UiConversation

    private var title: String {
        if !c.label.isEmpty { return c.label }
        return c.agentId.isEmpty ? "会话" : c.agentId
    }

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: c.hasActiveRun ? "bolt.badge.a" : "person.crop.circle.fill")
                .foregroundStyle(c.hasActiveRun ? Color.orange : Color.secondary)
                .font(.title3)
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    Text(title).fontWeight(.semibold).lineLimit(1)
                    if c.reconciling {
                        Text("对账中").font(.caption2).foregroundStyle(.secondary)
                    }
                }
                if !c.preview.isEmpty {
                    Text(c.preview).font(.footnote).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer()
            if c.unread > 0 {
                Text("\(c.unread)")
                    .font(.caption2.bold())
                    .padding(.horizontal, 7).padding(.vertical, 3)
                    .background(Capsule().fill(.blue))
                    .foregroundStyle(.white)
            }
        }
        .padding(.vertical, 2)
    }
}

/// 会话页：消息时间线（乐观态徽标 + 流式行 + 合成行分隔条）+ 发送条 + 会话操作菜单；
/// 渲染完整后自动上报已读。
struct HubChatView: View {
    @State private var hub = HubStore.shared
    @State private var draft = ""
    @State private var renaming = false
    @State private var renameDraft = ""
    @State private var compacting = false
    @Environment(\.inkColors) private var ink
    let conv: UiConversation

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(hub.messages, id: \.messageId) { m in
                            HubMessageRow(m: m).id(m.messageId)
                        }
                        Color.clear.frame(height: 4).id("bottom")
                    }
                    .padding(.horizontal, 14).padding(.vertical, 10)
                }
                .onChange(of: hub.messages.count) { _, _ in
                    proxy.scrollTo("bottom", anchor: .bottom)
                }
                .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
            }
            Divider()
            HStack(spacing: 10) {
                TextField("发消息…", text: $draft, axis: .vertical)
                    .lineLimit(1...4)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 18).fill(ink.surfaceTertiary))
                Button {
                    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    hub.send(text)
                    draft = ""
                } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.title2)
                }
                .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
        }
        .navigationTitle(title(conv))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        renameDraft = conv.label
                        renaming = true
                    } label: {
                        Label("重命名", systemImage: "pencil")
                    }
                    Button {
                        compacting = true
                        hub.compact(conv) { _ in compacting = false }
                    } label: {
                        Label(compacting ? "压缩中…" : "压缩上下文", systemImage: "rectangle.compress.vertical")
                    }
                    .disabled(compacting || conv.hasActiveRun)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
        .alert("重命名会话", isPresented: $renaming) {
            TextField("会话名称", text: $renameDraft)
            Button("保存") {
                let t = renameDraft.trimmingCharacters(in: .whitespaces)
                guard !t.isEmpty else { return }
                hub.rename(conv, to: t)
            }
            Button("取消", role: .cancel) {}
        }
        .onAppear { hub.open(conv) }
        .onDisappear { if hub.selected?.conversationId == conv.conversationId { hub.closeConversation() } }
        .task {
            try? await Task.sleep(nanoseconds: 400_000_000)
            let maxSeq = hub.messages.filter { $0.state == "final" }.map(\.seq).max() ?? 0
            if maxSeq > 0 { hub.markReadUpTo(maxSeq) }
        }
    }

    private func title(_ c: UiConversation) -> String {
        if !c.label.isEmpty { return c.label }
        return c.agentId.isEmpty ? "会话" : c.agentId
    }
}

private struct HubMessageRow: View {
    let m: UiMessage
    @Environment(\.inkColors) private var ink

    var body: some View {
        if m.role == "" {
            // 合成行（v1.1 additive：role="" = compaction/reset 标记行，无正文语义）渲染为分隔条
            HStack(spacing: 8) {
                Rectangle().fill(ink.surfaceTertiary).frame(height: 1)
                Label(m.content.isEmpty ? "上下文已压缩" : m.content, systemImage: "rectangle.compress.vertical")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                Rectangle().fill(ink.surfaceTertiary).frame(height: 1)
            }
            .padding(.vertical, 4)
        } else {
            HStack {
                if m.role == "user" { Spacer(minLength: 40) }
                VStack(alignment: m.role == "user" ? .trailing : .leading, spacing: 3) {
                    Group {
                        if m.content.isEmpty && m.state == "streaming" {
                            Text("…")
                        } else if m.role == "user" {
                            Text(m.content)
                        } else {
                            // 助手内容走自研轻量 Markdown 渲染器（0.4.8 滑动性能专项资产，P4 接入 Hub 面）
                            MarkdownView(content: m.content)
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 16)
                        .fill(m.role == "user" ? Color.accentColor.opacity(0.24) : ink.surfaceTertiary))
                    if m.role == "user" { statusBadge }
                }
                if m.role != "user" { Spacer(minLength: 40) }
            }
        }
    }

    @ViewBuilder
    private var statusBadge: some View {
        let ui = m.uiState
        HStack(spacing: 4) {
            if m.state == "streaming" {
                Image(systemName: "waveform").font(.caption2)
            } else if ui == "sending" {
                Image(systemName: "clock").font(.caption2)
                Text("发送中").font(.caption2)
            } else if ui == "failed" {
                Image(systemName: "exclamationmark.triangle.fill").font(.caption2).foregroundStyle(.red)
                Text("发送失败").font(.caption2).foregroundStyle(.red)
            } else if ui == "delivered" {
                Image(systemName: "checkmark").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .foregroundStyle(.secondary)
    }
}

/// 设置页：账号态 + 设备管理 + 登出（登出 = 服务端撤销设备 + 清 Keychain）。
struct HubSettingsView: View {
    @State private var hub = HubStore.shared
    @State private var confirmLogout = false

    var body: some View {
        List {
            Section("账号") {
                LabeledContent("用户", value: hub.userDisplay ?? "reins-dev")
                if let dev = hub.deviceId {
                    LabeledContent("本机设备", value: String(dev.prefix(12)) + "…")
                }
            }
            Section("已绑定设备") {
                if let devices = hub.devices {
                    ForEach(devices, id: \.deviceId) { d in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(d.displayName).font(.body)
                                Text(d.deviceId).font(.caption2.monospaced()).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if d.current == true {
                                Text("本机").font(.caption).padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(Capsule().fill(.blue.opacity(0.2)))
                            } else {
                                Button("撤销", role: .destructive) { hub.revokeDevice(d.deviceId) }
                                    .font(.caption)
                            }
                        }
                    }
                } else {
                    HStack { ProgressView(); Text("加载中…").foregroundStyle(.secondary) }
                }
            }
            Section("接入") {
                NavigationLink {
                    ServerSettingsView()
                } label: {
                    LabeledContent("服务器", value: URL(string: hub.baseUrl)?.host ?? hub.baseUrl)
                }
            }
            Section {
                Button("退出登录", role: .destructive) { confirmLogout = true }
            }
        }
        .navigationTitle("设置")
        .onAppear { hub.refreshDevices() }
        .confirmationDialog("撤销当前设备并清除本机凭据？", isPresented: $confirmLogout, titleVisibility: .visible) {
            Button("退出登录", role: .destructive) { hub.logout() }
            Button("取消", role: .cancel) {}
        }
    }
}
