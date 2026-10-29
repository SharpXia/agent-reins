import SwiftUI
import composeApp

/// Onboarding-lite（M3.6）：全新安装引导页——品牌区 + 三点卡 + 连接 CTA → 内嵌连接页。
struct OnboardingView: View {
    @Environment(\.inkColors) private var c
    @State private var connecting = false

    var body: some View {
        Group {
            if !connecting {
                hero
            } else {
                ConnectionView(showsBack: false)
                    .overlay(alignment: .topLeading) {
                        Button { connecting = false } label: {
                            Image(systemName: "chevron.backward")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundColor(c.ink)
                                .frame(width: 40, height: 40)
                                .padding(.leading, 8)
                        }
                        .buttonStyle(.plain)
                    }
            }
        }
        .background(c.paper)
    }

    private var hero: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 56)
            RoundedRectangle(cornerRadius: InkShape.xl)
                .fill(c.ink)
                .frame(width: 72, height: 72)
                .overlay(Text("A").ink(.headingLg).foregroundColor(c.paper))
            Text("AgentReins").ink(.headingLg).foregroundColor(c.ink).padding(.top, 20)
            Text("把你的 OpenClaw 网关装进口袋。\n随时查看会话、审批工具调用、跟进 Agent 动向。")
                .ink(.textMd)
                .foregroundColor(c.textTertiary)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Spacer()
            SandCard {
                point("加密配对", "扫码或粘贴 setup code 完成设备级签名握手")
                SandDivider(indent: 16)
                point("实时对话", "流式回复、Markdown 排版、工具输出全屏查看")
                SandDivider(indent: 16)
                point("断线不丢", "消息离线排队，重连自动补发")
            }
            Button {
                connecting = true
            } label: {
                Text("连接网关")
                    .ink(.textLg)
                    .foregroundColor(c.paper)
                    .frame(maxWidth: .infinity)
                    .frame(height: 48)
                    .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.ink))
            }
            .buttonStyle(.plain)
            .padding(.top, 24)
            Text("自托管网关 · 数据不经过第三方")
                .ink(.textXs)
                .foregroundColor(c.textDisabled)
                .padding(.top, 10)
            Spacer(minLength: 16)
        }
        .padding(.horizontal, 24)
    }

    private func point(_ title: String, _ subtitle: String) -> some View {
        HStack(alignment: .center, spacing: 10) {
            Circle().fill(c.ink).frame(width: 6, height: 6)
            VStack(alignment: .leading, spacing: 1) {
                Text(title).ink(.textLg).foregroundColor(c.ink)
                Text(subtitle).ink(.textXs).foregroundColor(c.textTertiary)
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
    }
}

/// Agent 域页：身份头（改名/选色）+ 该 agent 的会话组（M4 sessions 治理落点）。
struct AgentDetailView: View {
    @Environment(\.inkColors) private var c
    @Environment(\.colorScheme) private var scheme
    @Environment(\.dismiss) private var dismiss
    @Environment(NavCoordinator.self) private var nav
    @State private var store = AppStore.shared
    @State private var showEdit = false
    let agentId: String

    private var agent: BridgeAgent? {
        store.lists.agents.first { $0.agentId == agentId }
    }

    /// 该 agent 的全部会话（未归档；AgentRegistry.sessionsOf 同语义）。
    private var sessions: [BridgeSession] {
        store.lists.sessions
            .filter { ($0.agentId ?? sessionKeyAgentId($0.id)) == agentId && !$0.archived }
            .sorted { ($0.lastActiveAtMs) > ($1.lastActiveAtMs) }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 4) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "chevron.backward")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundColor(c.ink)
                        .frame(width: 36, height: 36)
                }
                .buttonStyle(.plain)
                Text("Agent").ink(.headingSm).foregroundColor(c.ink)
                Spacer()
            }
            .padding(.horizontal, 8)

            identityHeader
                .contentShape(Rectangle())
                .onTapGesture { showEdit = true }

            Text("会话 · \(sessions.count)")
                .ink(.textSm)
                .foregroundColor(c.textTertiary)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

            if sessions.isEmpty {
                EmptyStateView(icon: "person.crop.circle", title: "该 agent 暂无可见会话",
                               subtitle: "派生/系统会话仅在运行中出现")
                Spacer()
            } else {
                List {
                    ForEach(sessions, id: \.id) { s in
                        sessionRow(s)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
            }
        }
        .background(c.paper)
        .navigationBarHidden(true)
        .sheet(isPresented: $showEdit) {
            AgentEditSheet(agentId: agentId)
        }
    }

    private var identityHeader: some View {
        let tint = store.tint(agentId, isDark: scheme == .dark)
        let name = agent?.name ?? agentId
        return HStack(spacing: 12) {
            AgentAvatar(tint: tint, name: name, size: 52)
            VStack(alignment: .leading, spacing: 2) {
                Text(name).ink(.headingMd).foregroundColor(c.ink)
                Text(agentId).ink(.textSm).foregroundColor(c.textTertiary)
            }
            Spacer()
            Image(systemName: "pencil")
                .font(.system(size: 13))
                .foregroundColor(c.textTertiary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .overlay(alignment: .bottomLeading) {
            AgentTitlePill(name: "agent", tint: tint.strong, pillFill: tint.pill)
                .padding(.leading, 80)
                .padding(.bottom, 2)
        }
        .padding(.bottom, 20)
    }

    private func sessionRow(_ s: BridgeSession) -> some View {
        Button {
            // 主线/渠道行 → 对应 conversation（渠道对话唯一入口）；派生/系统行保持 per-session
            switch classifySession(s.id) {
            case .mainline:
                nav.push(.conversation(convId: convId(agentId), anchor: s.id))
            case .channel:
                nav.push(.conversation(convId: convId(agentId, channel: channelBucket(of: s.id) ?? "channel"),
                                       anchor: s.id))
            default:
                nav.push(.chat(sessionKey: s.id, readOnly: false))
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if s.pinned {
                        Image(systemName: "pin.fill").font(.system(size: 9)).foregroundColor(c.textTertiary)
                    }
                    Text(s.title ?? s.id)
                        .ink(.textLg)
                        .foregroundColor(c.ink)
                        .fontWeight(s.unread > 0 ? .semibold : .regular)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    Text(formatRelativeTime(s.lastActiveAtMs)).ink(.textXs).foregroundColor(c.textTertiary)
                }
                Text(s.preview?.isEmpty == false ? s.preview! : s.id)
                    .ink(.textSm).foregroundColor(c.textTertiary).lineLimit(1)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .listRowBackground(c.paper)
        .listRowSeparator(.hidden)
    }
}

/// 身份定制 Sheet：别名输入 + 10 色选择。
struct AgentEditSheet: View {
    @Environment(\.inkColors) private var c
    @Environment(\.dismiss) private var dismiss
    @State private var store = AppStore.shared
    @State private var name = ""
    @State private var loaded = false
    let agentId: String

    var body: some View {
        InkSheet(title: "编辑 Agent") {
            VStack(alignment: .leading, spacing: 10) {
                TextField("显示名", text: $name)
                    .ink(.textMd)
                    .foregroundColor(c.ink)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(RoundedRectangle(cornerRadius: InkShape.md).fill(c.surfaceTertiary))
                    .padding(.horizontal, 20)
                    .onAppear {
                        guard !loaded else { return }
                        loaded = true
                        name = store.lists.agents.first { $0.agentId == agentId }?.name ?? agentId
                    }
                Text("身份色").ink(.textSm).foregroundColor(c.textTertiary)
                    .padding(.horizontal, 20)
                HStack(spacing: 8) {
                    ForEach(AgentTintName.allCases, id: \.self) { tintName in
                        let t = agentTint(tintName, isDark: c.isDark)
                        let selected = store.lists.agents.first { $0.agentId == agentId }?.tint == tintName.rawValue
                        Circle()
                            .fill(t.strong)
                            .frame(width: 30, height: 30)
                            .overlay(
                                Circle().strokeBorder(
                                    selected ? c.ink : c.borderPrimary,
                                    lineWidth: selected ? 3 : 1
                                )
                            )
                            .onTapGesture {
                                store.bridge.setAgentTint(agentId: agentId, tint: tintName.rawValue)
                                store.bridge.setAgentName(agentId: agentId, name: name)
                            }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 10)
                HStack {
                    Spacer()
                    Button("取消") { dismiss() }
                        .ink(.textLg).foregroundColor(c.ink)
                    Button("保存") {
                        store.bridge.setAgentName(agentId: agentId, name: name)
                        dismiss()
                    }
                    .ink(.textLg).foregroundColor(c.ink)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
            }
        }
    }
}
