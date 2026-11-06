import SwiftUI
import UIKit
import composeApp

/// Hub 主界面（P3 最小闭环，P4 起为唯一界面：网关直连面已退役）：会话 / 设置-设备。
/// 导航纪律（0.5.1 真机点击崩溃修复）：KMP 类不可作 value-based 导航的 Hashable 值——
/// 列表推栈用 destination-closure 型 NavigationLink，dev 直达用 chatPush(isPresented)，
/// 全栈零 NavigationPath/zero value-destination。
/// Dev 直达（自动化走查）：AGENTREINS_DEV_TAB=settings；AGENTREINS_DEV_CONV=1 打开首个会话。
struct HubRootView: View {
    @State private var hub = HubStore.shared

    var body: some View {
        VStack(spacing: 0) {
            // wp-10：连接提示条挂顶层（TabView 之上，跨 tab 常驻）：断连/重建/接管在途显示，
            // 追平（linked）自动消失——wp-8 降级态面在连接维度的补位。
            if hub.isLoggedIn, hub.syncPhase != "linked" {
                HubSyncPhaseBanner(phase: hub.syncPhase)
            }
            // tab 态在 HubStore（wp-13：通知点按需从任意 tab 带回会话栈），此处只绑定。
            TabView(selection: $hub.selectedTab) {
                NavigationStack { HubHomeView() }
                    .tabItem { Label("会话", systemImage: "bubble.left.and.bubble.right.fill") }
                    .tag(0)
                NavigationStack { HubSettingsView() }
                    .tabItem { Label("设置", systemImage: "gearshape.fill") }
                    .tag(1)
            }
        }
        .environment(hub)
        .task {
            if ProcessInfo.processInfo.environment["AGENTREINS_DEV_TAB"] == "settings" { hub.selectedTab = 1 }
            if ProcessInfo.processInfo.environment["AGENTREINS_DEV_CONV"] == "1" {
                try? await Task.sleep(nanoseconds: 2_500_000_000)
                if let first = hub.conversations.first {
                    hub.selectedTab = 0
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
                    hub.selectedTab = 0
                    hub.devOpen(t)
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    hub.send(text)
                }
            }
        }
    }
}

/// wp-10：连接状态提示条（iOS 顶层挂载，HubRootView 的 TabView 之上）。非 linked 相位常驻
/// 显示，追平（linked）自动消失——验收③「断网出现提示条、恢复后自动消失；410 重建期间显示
/// 重建态」。文案与桌面 SyncPhaseBanner 同族（两端体验 parity）：catching = 引导/追平在途
/// （灰面弱提示）；reconnecting / rebuilding / superseded = 连接降级（琥珀警示面）。
struct HubSyncPhaseBanner: View {
    let phase: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: iconName)
            Text(text)
            Spacer(minLength: 0)
        }
        .font(.footnote)
        .foregroundStyle(foregroundColor)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(backgroundColor)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("连接状态：\(text)")
    }

    private var text: String {
        switch phase {
        case "reconnecting": return "连接已断开，正在重连…"
        case "rebuilding": return "同步游标已失效，正在重建本地快照…"
        case "superseded": return "连接已被本机新会话接管，等待恢复…"
        case "catching": return "正在同步…"
        default: return ""
        }
    }

    private var iconName: String {
        switch phase {
        case "reconnecting": return "wifi.slash"
        case "rebuilding": return "arrow.triangle.2.circlepath"
        case "superseded": return "person.2.fill"
        default: return "arrow.triangle.2.circlepath"
        }
    }

    private var backgroundColor: Color {
        phase == "catching" ? Color(.systemGray6) : Color.orange.opacity(0.18)
    }

    private var foregroundColor: Color {
        phase == "catching" ? Color.secondary : Color.orange
    }
}

/// 会话列表：投影库 Flow 推送；label 空的卡片以 agentId 兜底命名（v1.1：sessionKey 不出 UI 面）。
/// wp-11：工具栏「新建」入口按 #16 聚合语义开放（无主线卡 agent 可新建、已有主线卡 agent 置灰说明）。
struct HubHomeView: View {
    @State private var hub = HubStore.shared
    @State private var showNewConversation = false

    var body: some View {
        Group {
            if hub.conversations.isEmpty {
                ContentUnavailableView("暂无会话", systemImage: "bubble.left",
                                       description: Text("网关接入后会话将自动出现，也可从右上角新建"))
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
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showNewConversation = true
                } label: {
                    Image(systemName: "plus.message")
                }
                .accessibilityLabel("新建会话")
            }
        }
        .sheet(isPresented: $showNewConversation) {
            NewConversationSheet()
        }
        .task { hub.watchConversations(); hub.watchGateways() }
    }
}

/// wp-11：新建会话入口页（#16 聚合语义，2026-11-04 拍板「§2.4 维持现状 + 文档写明」的 UI 面）。
///
/// - 入口只对**无主线卡 agent** 开放：新 session 兜底成卡，经校准入列（成功即触发一次校准，
///   秒级；兜底 R4 周期 / 重进列表）；
/// - 已有主线卡 agent **置灰并说明**「新会话将作为后台任务会话静默」——不推翻拍板、不制造
///   无反馈交互：置灰是终态语义而非错误，行内常驻说明；
/// - 提交失败（回执 null = REST 未成功）显式降级态可重试，不静默。
private struct NewConversationSheet: View {
    @State private var hub = HubStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if hub.gateways.isEmpty {
                    ContentUnavailableView("暂无可用 Agent", systemImage: "cpu",
                                           description: Text("网关接入并列出 active Agent 后可在此新建会话"))
                } else {
                    ForEach(hub.gateways, id: \.gatewayId) { gw in
                        Section {
                            if gw.agents.isEmpty {
                                Text("该网关暂无 active Agent").font(.footnote).foregroundStyle(.secondary)
                            } else {
                                ForEach(gw.agents, id: \.agentId) { agent in
                                    NewConversationAgentRow(gateway: gw, agent: agent)
                                }
                            }
                        } header: {
                            HStack(spacing: 6) {
                                Circle().fill(gw.online ? Color.green : Color.secondary)
                                    .frame(width: 7, height: 7)
                                Text(gw.name.isEmpty ? "网关" : gw.name)
                            }
                        }
                    }
                }
                if let err = hub.createConversationError {
                    Section {
                        Label {
                            Text(err)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle.fill")
                        }
                        .font(.footnote)
                        .foregroundStyle(.red.opacity(0.9))
                    }
                }
            }
            .navigationTitle("新建会话")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// wp-11：新建会话入口的单 agent 行——#16 语义四态：可新建 / 提交中 / 已提交等待成卡 / 已有主线卡置灰。
private struct NewConversationAgentRow: View {
    @State private var hub = HubStore.shared
    let gateway: UiGateway
    let agent: UiAgentRef

    private var hasMainCard: Bool { hub.hasMainCard(gatewayId: gateway.gatewayId, agentId: agent.agentId) }
    private var submitted: Bool {
        hub.createdConversationFor.contains("\(gateway.gatewayId)|\(agent.agentId)")
    }

    private var title: String { agent.label.isEmpty ? agent.agentId : agent.label }

    var body: some View {
        if hasMainCard {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle").foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(.secondary)
                    Text("已有主线卡 · 新会话将作为后台任务会话静默")
                        .font(.caption2).foregroundStyle(.tertiary).lineLimit(2)
                }
            }
            .accessibilityElement(children: .combine)
            .accessibilityHint("已有主线卡，新建会话入口已停用")
        } else {
            Button {
                hub.createConversation(gatewayId: gateway.gatewayId, agentId: agent.agentId)
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "plus.circle.fill").foregroundStyle(.blue)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(title)
                        if submitted {
                            Text("已提交 · 卡片校准后入列（或重进列表）")
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    Spacer()
                    if hub.creatingConversation {
                        ProgressView().controlSize(.small)
                    }
                }
            }
            .disabled(hub.creatingConversation || submitted)
            .accessibilityHint(submitted ? "已提交，等待校准成卡" : "新建该 Agent 的会话")
        }
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
/// 已读上报（wp-12）：maxRenderedSeq 驱动持续上报（桌面同型，只推进不回退）——打开首帧
/// 照报、会话期新行渲染即推进，回列表不再假未读；空列表呈「暂无消息」占位（桌面同型）。
/// 流式体验（wp-7）：自动滚屏只在「尾部变化且用户在底部」时跟随（onScrollGeometryChange +
/// onScrollPhaseChange 区分用户上翻与内容增长）；空内容 streaming 行呈 typing 三点脉冲；
/// 流式正文 typewriter 渐进揭示；消息列表写入侧经 HubStore 75ms 合帧。
/// 消息操作与控制（wp-8）：活跃 run 期间 composer 常驻 Stop（grokbot 式，替代发送键），
/// activeRunId 缺失（映射未学习）降级「结束中」提示态；消息行长按菜单（拷贝全文/失败行重发）。
/// 工具审批（wp-9）：归属本会话的 pending 审批卡内嵌于时间线上方，批准/拒绝走幂等队列。
struct HubChatView: View {
    @State private var hub = HubStore.shared
    @State private var draft = ""
    @State private var renaming = false
    @State private var renameDraft = ""
    @State private var compacting = false
    /// wp-7①：底部锚定态（初始锚定；只被用户驱动的滚动翻转，见 setAtBottom）。
    @State private var atBottom = true
    /// wp-7①：最近一次滚动几何（非观测盒——逐帧更新不得触发 body 重渲）。
    @State private var probeBox = ScrollProbeBox()
    /// wp-7①：当前滚动相是否用户驱动（tracking/interacting/decelerating）。
    @State private var userDrivenScroll = false
    @Environment(\.inkColors) private var ink
    let conv: UiConversation

    /// 距底容差（pt）：吸收 overscroll 回弹/行内边距；上翻超过该值即视为离底。
    private static let bottomSlack: CGFloat = 80

    /// wp-8②：当前会话实时行——conv 是导航时刻快照，hasActiveRun/activeRunId 随 run.* 事件
    /// 后续翻转，Stop 按钮态一律从会话列表现值取，不得用快照（会话列表 Flow 持续推送）。
    private var liveConv: UiConversation? {
        hub.conversations.first { $0.conversationId == conv.conversationId }
    }

    /// wp-8②：活跃 run 态——grokbot 式 Stop 常驻 composer 的驱动源；列表未及推送时按无 run 处理（安全降级）。
    private var isRunActive: Bool { liveConv?.hasActiveRun == true }

    /// wp-8②：Stop 可执行 = 活跃 run 且 activeRunId 已习得（wp-5 v4 投影）。映射未学习场景
    /// （activeRunId 为 null——DefaultEventProjector.setRun 在 resolveConversation 失败时直接
    /// return，不落库）置灰为「结束中」提示态，不假设 runId 恒有值、不误触。
    private var canCancelRun: Bool { !(liveConv?.activeRunId ?? "").isEmpty }

    /// wp-14：斜杠命令建议——draft 起手 "/" 且未进入参数书写段时给出目录前缀命中；普通消息、
    /// URL、多行草稿等其余输入零干扰不触发（筛选细则见 SlashCommandCatalog.suggestions）。
    private var slashSuggestions: [SlashCommand] {
        SlashCommandCatalog.suggestions(forDraft: draft)
    }

    /// wp-14：斜杠命令选中。无参命令即选即发——与发送键同一通道（hub.send → message.send 普通
    /// 文本，Gateway 原生斜杠语义，Hub/桥/服务端命令面零改动，normalize/index.ts 的 message.send
    /// 分支把 message 原样透传 chat.send）；带参命令预填命令文本（含尾随空格）续写参数，最终
    /// 发送仍走同一普通文本通道。
    private func selectSlashCommand(_ cmd: SlashCommand) {
        if cmd.takesArgument {
            draft = cmd.name + " "
        } else {
            hub.send(cmd.name)
            draft = ""
        }
    }

    /// wp-9：会话页内嵌审批卡的数据源——只挂归属本会话（conversationId 命中）的 pending 审批；
    /// 归属未学习（nil）的审批不得错挂进任意会话页，只在设置页待审批清单出现。
    private var sessionApprovals: [UiApproval] {
        hub.approvals.filter { $0.conversationId == conv.conversationId }
    }

    /// wp-9：审批横条（置顶于时间线上方）——审批是 run 的前置门，出现即需注意，不进滚动区防淹没。
    private var approvalBanner: some View {
        VStack(spacing: 8) {
            ForEach(sessionApprovals, id: \.approvalId) { a in
                ApprovalCardView(a: a)
            }
        }
        .padding(.horizontal, 14).padding(.top, 10)
    }

    /// wp-15：工作过程披露条（置顶于时间线上方，与审批横条同位不进滚动区）——只在有已捕获
    /// 内容时出现（零噪声）；数据 = HubStore.workActivity（当前打开会话，open 时订阅）。
    /// 无工具活动/阶段帧的会话整条不渲染，历史会话也不虚构（断线窗口丢失面不回填）。
    @ViewBuilder
    private var workProcessSection: some View {
        if let wa = hub.workActivity, !wa.tools.isEmpty || !wa.phases.isEmpty {
            WorkProcessBar(activity: wa, runActive: isRunActive)
                .padding(.horizontal, 14).padding(.top, 10)
        }
    }

    /// wp-12：已渲染最大真实 seq（桌面 ConversationScreen.maxRenderedSeq 同口径）——
    /// 乐观行占位 seq（Int64.max）与 failed 行不计入；streaming 行（含 dstream 占位行）
    /// 已渲染即计入（与桌面同型：用户看着流式输出时水位随渲染推进，离场不留假未读）。
    /// 持续已读上报的驱动源：onAppear 首报 + onChange 增量推进（HubStore.markReadUpTo
    /// 基线只推进不回退），替代旧「appear+400ms 单次取值」一次性上报。
    private var maxRenderedSeq: Int64 {
        hub.messages
            .filter { $0.seq != HubStore.optimisticSeqSentinel && $0.state != "failed" }
            .map(\.seq)
            .max() ?? 0
    }

    /// wp-12：历史翻页顶部行（时间线首行，随滚动区移动）——三态：
    /// - 在途：进度指示（按钮禁用防并发翻页）；
    /// - 可翻：「加载更早」按钮（点击 → HubStore.loadEarlier，按 oldestSeq 翻页）；
    /// - 翻到头（earlierExhausted 锁存）：收尾条替代按钮——hasMore=false 后入口消失（验收项）。
    /// 短会话（≤50 行，快照即全量）canLoadEarlier=false 且未翻过：整行不出现，界面零噪声。
    @ViewBuilder
    private var earlierLoaderRow: some View {
        if hub.loadingEarlier {
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text("加载更早…").font(.caption).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .center)
            .padding(.vertical, 2)
        } else if hub.canLoadEarlier {
            Button {
                hub.loadEarlier()
            } label: {
                Label("加载更早", systemImage: "chevron.up")
                    .font(.caption.weight(.medium))
            }
            .buttonStyle(.borderless)
            .frame(maxWidth: .infinity, alignment: .center)
            .accessibilityLabel("加载更早")
        } else if hub.earlierExhausted {
            Text("已到最早消息")
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .frame(maxWidth: .infinity, alignment: .center)
                .padding(.vertical, 2)
        }
    }

    /// wp-12：时间线（消息滚动区 + 滚动锚定/贴底跟随/翻页恢复接线，wp-7①/wp-12 原样平移）——
    /// body 中与空态占位互斥渲染：空→非空切换时 ScrollView 新建并 onAppear 锚底（语义不变）。
    private var timeline: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    // wp-12：历史翻页入口（顶部常驻行）——按钮态由 HubStore 状态机驱动
                    //（canLoadEarlier 乐观判定 + 首翻后服务端 hasMore 接管；翻到头锁存隐藏，
                    // 换「已到最早消息」收尾条）；在途呈进度态，防并发翻页（限流 60 次/分/设备）。
                    earlierLoaderRow
                    if let err = hub.loadEarlierError {
                        Label {
                            Text(err)
                        } icon: {
                            Image(systemName: "exclamationmark.triangle")
                        }
                        .font(.caption)
                        .foregroundStyle(.red.opacity(0.85))
                        .frame(maxWidth: .infinity, alignment: .center)
                    }
                    ForEach(hub.messages, id: \.messageId) { m in
                        HubMessageRow(m: m).id(m.messageId)
                    }
                    Color.clear.frame(height: 4).id("bottom")
                }
                .padding(.horizontal, 14).padding(.vertical, 10)
            }
            .onScrollGeometryChange(for: ScrollProbe.self) { geo in
                ScrollProbe(offsetY: geo.contentOffset.y,
                            contentHeight: geo.contentSize.height,
                            containerHeight: geo.containerSize.height,
                            insetBottom: geo.contentInsets.bottom)
            } action: { _, new in
                probeBox.latest = new
                // 流式 append 与程序化跟随滚动（animating）不改锚定态：内容增长会把
                // 哨兵推到视口下方，若据此解锚，下一帧起跟随永久停摆。
                guard userDrivenScroll else { return }
                setAtBottom(probe: new)
            }
            .onScrollPhaseChange { _, new in
                // 用户驱动相 = 手指/惯性；idle（含内容增长）与 animating（程序化跟随）除外。
                let userDriven: Bool
                switch new {
                case .tracking, .interacting, .decelerating: userDriven = true
                case .idle, .animating: userDriven = false
                @unknown default: userDriven = false
                }
                userDrivenScroll = userDriven
                // 手势/惯性结束在半途时用最终几何补判一次（惯性停在距底 30pt 也算在底）。
                if !userDriven, let p = probeBox.latest { setAtBottom(probe: p) }
            }
            .onChange(of: hub.messages.count) { _, _ in
                // wp-12：翻页行合入列表（顶部插入）后滚回触发前最老一行，保住阅读位置——
                // 不滚回则视口内容整体下移（读取位置丢失）。锚由 HubStore.loadEarlier 武装、
                // 仅在成功回执后消费（在途新尾消息到达不触发）。
                if let anchor = hub.takeRestoreScrollTarget() {
                    proxy.scrollTo(anchor, anchor: .top)
                }
            }
            .onChange(of: tailSignature) { _, _ in
                // wp-7①：跟随条件 = 尾部变化 且 用户在底部（上翻阅读时不拉回）。
                // 尾签覆盖行数、尾行身份/状态/内容量——比旧「只看 messages.count」
                // 多覆盖流式行原位增长与占位行收敛。
                guard atBottom else { return }
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onAppear { proxy.scrollTo("bottom", anchor: .bottom) }
        }
    }

    /// wp-12：时间线空态占位（桌面 ConversationScreen「暂无消息」同型：居中主行 + 灰色副行，
    /// 文案逐字同源）——空态纪律对齐 iOS 会话列表（ContentUnavailableView 同族）。打开瞬态
    /// 与真空会话同呈现，不虚构内容；首批快照落地即被 timeline 替换。
    private var timelineEmptyState: some View {
        ContentUnavailableView("暂无消息", systemImage: "bubble.left",
                               description: Text("发送第一条消息开始（历史随快照与事件流回填）"))
    }

    var body: some View {
        VStack(spacing: 0) {
            if !sessionApprovals.isEmpty {
                approvalBanner
            }
            workProcessSection
            // wp-12：时间线空态占位（桌面 ConversationScreen「暂无消息」同型，空态纪律
            // 对齐会话列表空态）——空列表（打开瞬态/真空会话）整块替换时间线呈居中占位，
            // 首批快照落地即被时间线替换（ScrollView 重建，onAppear 锚底照走）。
            if hub.messages.isEmpty {
                timelineEmptyState
            } else {
                timeline
            }
            // wp-14：斜杠命令建议面板——draft 由 "/" 起手时悬浮于 composer 上方，选中即动作；
            // 无命中/进入参数书写段自动收起，其余输入零干扰。
            if !slashSuggestions.isEmpty {
                SlashCommandPanel(suggestions: slashSuggestions, onSelect: selectSlashCommand)
                    .padding(.horizontal, 12).padding(.top, 6)
            }
            Divider()
            if isRunActive {
                // wp-14：队列模式提示文案（本轮仅文案提示）——chat.send 不透传 queueMode 参数
                //（schema ChatSendParams.queueMode steer/followup/collect/interrupt 本轮不接），
                // 运行中发送按 Gateway 默认队列语义排队，文案与该实际行为对齐；语义透传留待后续。
                Label("运行中发送将排队", systemImage: "hourglass")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 14).padding(.top, 6)
            }
            HStack(spacing: 10) {
                TextField("发消息…", text: $draft, axis: .vertical)
                    .lineLimit(1...4)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 18).fill(ink.surfaceTertiary))
                if isRunActive {
                    // wp-8②：Stop 按钮（grokbot 式常驻）——活跃 run 期间替代发送键；中止后部分
                    // 输出保留由 Gateway 权威收敛（run.aborted 回流投影库，UI 只翻转回发送键）。
                    HStack(spacing: 4) {
                        Button {
                            hub.stopRun()
                        } label: {
                            Image(systemName: "stop.circle.fill").font(.title2)
                        }
                        .disabled(!canCancelRun)
                        .opacity(canCancelRun ? 1 : 0.35)
                        .accessibilityLabel(canCancelRun ? "停止生成" : "结束中")
                        if !canCancelRun {
                            Text("结束中").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                } else {
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
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
        }
        .navigationTitle(title(conv))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    // wp-13：按会话静音开关（本地偏好，UserDefaults 持久化；静音会话的
                    // 审批/回复/run 终态本地通知一并抑制）。
                    Button {
                        hub.setMuted(!hub.mutedConversations.contains(conv.conversationId),
                                     conversationId: conv.conversationId)
                    } label: {
                        Label(hub.mutedConversations.contains(conv.conversationId) ? "取消静音" : "静音此会话",
                              systemImage: hub.mutedConversations.contains(conv.conversationId) ? "bell.fill" : "bell.slash")
                    }
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
        // wp-12：持续已读上报（桌面 maxRenderedSeq 驱动 LaunchedEffect 同型，替代旧
        // appear+400ms 单次取值）——onAppear 首报（首帧慢于旧 400ms 定时器也照报，快照
        // 先到不漏）+ onChange 增量推进（会话打开期间新 final/streaming 行渲染即上报，
        // 回列表不再假未读）；只推进不回退由 HubStore.markReadUpTo 基线承担。挂外层
        // VStack：空态（时间线被占位替换）与时间线两态都活着。
        .onAppear { hub.markReadUpTo(maxRenderedSeq) }
        .onChange(of: maxRenderedSeq) { _, latest in hub.markReadUpTo(latest) }
    }

    private func title(_ c: UiConversation) -> String {
        if !c.label.isEmpty { return c.label }
        return c.agentId.isEmpty ? "会话" : c.agentId
    }

    /// wp-7①：按滚动几何重判底部锚定态（仅写状态于翻转时，逐帧空转零重渲）。
    private func setAtBottom(probe p: ScrollProbe) {
        let nowAtBottom = (p.maxOffset - p.offsetY) <= Self.bottomSlack
        if nowAtBottom != atBottom { atBottom = nowAtBottom }
    }

    /// wp-7①：尾部签名——行数 + 尾行身份/状态/内容量与首尾切片，O(1) 构造
    /// （utf8.count / prefix / suffix 均不做全文遍历）。极小概率漏检「等长且首尾切片
    /// 相同的原地改写」，下一帧 append 自愈，不追。
    private var tailSignature: String {
        guard let last = hub.messages.last else { return "" }
        let c = last.content
        return "\(hub.messages.count)|\(last.messageId)|\(last.state)|\(c.utf8.count)|\(c.prefix(16))|\(c.suffix(64))"
    }
}

// ── wp-14：斜杠命令菜单（本地命令目录 + 建议面板） ──

/// wp-14：斜杠命令条目——客户端自建初版目录（scope 六命令）。
/// Gateway 原生斜杠语义：选中后的发送只是普通 message.send 文本（桥侧 chat.send 原样透传
/// message，Hub 命令面零改动、无协议风险），目录描述是客户端提示、不构成 Gateway 行为承诺。
private struct SlashCommand: Identifiable, Equatable {
    /// 含前导 "/"（如 "/compact"）；发送文本即此字符串（带参命令再续写参数）。
    let name: String
    /// 客户端目录描述（初版自建，非 Gateway 权威文档）。
    let summary: String
    /// true = 需接参数：选中预填输入框续写，不直接发送。
    let takesArgument: Bool

    var id: String { name }
}

/// wp-14：命令目录初版（客户端自建）。scope 命名的 Gateway 原生斜杠六命令；steer/followup
/// 对应 chat.send queueMode 词汇族（protocol.schema.json ChatSendParams.queueMode，
/// research/01:113；research/01:236 注明 sessions.steer 为 queueMode:"interrupt" 废弃别名）。
/// 目录动态化可后续接 Gateway commands.list（方法注册表在册、scope operator.read）——本轮
/// 按拍板「零改动」不接，静态目录可增行扩展。
private enum SlashCommandCatalog {
    static let all: [SlashCommand] = [
        SlashCommand(name: "/new", summary: "开启全新会话上下文", takesArgument: false),
        SlashCommand(name: "/compact", summary: "压缩上下文", takesArgument: false),
        SlashCommand(name: "/stop", summary: "停止当前运行", takesArgument: false),
        SlashCommand(name: "/status", summary: "查看会话状态", takesArgument: false),
        SlashCommand(name: "/steer", summary: "运行中插入引导指令（可接文本）", takesArgument: true),
        SlashCommand(name: "/followup", summary: "追加队列消息，本轮结束后执行（可接文本）", takesArgument: true),
    ]

    /// 触发与筛选：draft 以 "/" 起手且尚未出现空白（用户仍在选命令、未写参数）时，按整段
    /// draft 对命令名做前缀匹配；draft 为单独 "/" 时展示全目录；无命中返回空 = 面板隐藏，
    /// 不拦自由输入（用户仍可发送任意文本，含未知斜杠命令——Gateway 侧原生裁决）。
    static func suggestions(forDraft draft: String) -> [SlashCommand] {
        guard draft.hasPrefix("/") else { return [] }
        guard !draft.contains(" "), !draft.contains("\t"), !draft.contains("\n") else { return [] }
        if draft == "/" { return all }
        return all.filter { $0.name.hasPrefix(draft) }
    }
}

/// wp-14：斜杠命令建议面板——composer 上方轻量目录（输入 "/" 即出、选中即动作）。
/// ScrollView 承载溢出（目录超出可视行数时内滚）；纯本地视图零网络，不劫持键盘焦点。
private struct SlashCommandPanel: View {
    let suggestions: [SlashCommand]
    let onSelect: (SlashCommand) -> Void
    @Environment(\.inkColors) private var ink

    /// 面板可视高度上限（≈4 行），其余行内滚。
    private static let panelMaxHeight: CGFloat = 148

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(suggestions.enumerated()), id: \.element.id) { index, cmd in
                    Button {
                        onSelect(cmd)
                    } label: {
                        HStack(spacing: 10) {
                            Text(cmd.name)
                                .font(.callout.monospaced().weight(.semibold))
                                .foregroundStyle(Color.accentColor)
                            Text(cmd.summary)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(cmd.name)：\(cmd.summary)")
                    if index < suggestions.count - 1 {
                        Divider().padding(.leading, 12)
                    }
                }
            }
        }
        .frame(maxHeight: Self.panelMaxHeight)
        .background(RoundedRectangle(cornerRadius: 12).fill(ink.elevated))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(ink.borderTertiary, lineWidth: 1))
    }
}

/// wp-7①：滚动几何探针（onScrollGeometryChange 变换产物），供锚定态判定。
private struct ScrollProbe: Equatable {
    let offsetY: CGFloat
    let contentHeight: CGFloat
    let containerHeight: CGFloat
    let insetBottom: CGFloat

    var maxOffset: CGFloat { contentHeight + insetBottom - containerHeight }
}

/// wp-7①：滚动几何暂存盒（引用类型 + 非观测）——逐帧写入不触发 body 重渲，
/// 仅在手势结束补判时读取。
private final class ScrollProbeBox {
    var latest: ScrollProbe?
}

private struct HubMessageRow: View {
    let m: UiMessage
    @State private var hub = HubStore.shared
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
                            // wp-7②：typing 指示动画——快照来源 streaming 行与 wp-5 live delta
                            // 占位行（dstream:<runId>，首 delta 前 content 为空）同型呈现。
                            TypingIndicator()
                        } else if m.role == "user" {
                            // wp-8③：空 SENDING 用户行维持发送中态——不渲染空气泡，占位点显示、
                            // 状态徽标照常翻转（sending→delivered）。
                            Text(m.content.isEmpty ? "…" : m.content)
                                .foregroundStyle(m.content.isEmpty ? Color.secondary : Color.primary)
                        } else if m.state == "streaming" {
                            // wp-7 可选增强：流式行 typewriter 渐进揭示——数据 2s 一块、渲染逐字放出
                            StreamingTypewriter(content: m.content)
                        } else if m.content.isEmpty {
                            // wp-8③：final/failed 空正文助手行渲染占位文案，不再出现空气泡
                            //（勘察确认投影层零过滤：空 content 行照常入库，防线只能在渲染侧）。
                            Text("（无文本内容）")
                                .font(.footnote)
                                .italic()
                                .foregroundStyle(.secondary)
                        } else {
                            // 助手内容走自研轻量 Markdown 渲染器（0.4.8 滑动性能专项资产，P4 接入 Hub 面）
                            MarkdownView(content: m.content)
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(RoundedRectangle(cornerRadius: 16)
                        .fill(m.role == "user" ? Color.accentColor.opacity(0.24) : ink.surfaceTertiary))
                    // 消息时间（桌面 MessageRow formatTs 同族 parity，2026-11-06）：气泡下
                    // caption2 弱化行——用户行与状态徽标同行（桌面同款），解析失败/缺省省略。
                    if m.role == "user" {
                        HStack(spacing: 6) {
                            messageTimeLabel
                            statusBadge
                        }
                    } else {
                        messageTimeLabel
                    }
                    if let reason = failedReasonLabel {
                        // wp-8③：失败原因（wp-5 双路径共列 reason：事件路径 message.failed.reason =
                        // 快照路径 MessageRow.errorKind）；未分类失败（reason=NULL）不虚构文案。
                        Label(reason, systemImage: "exclamationmark.triangle")
                            .font(.caption2)
                            .foregroundStyle(.red.opacity(0.85))
                    }
                }
                .contextMenu { messageContextMenu }
                if m.role != "user" { Spacer(minLength: 40) }
            }
        }
    }

    // ── wp-8①：长按菜单（拷贝全文 / 失败行重发） ──

    /// 消息时间标签（formatMessageTimeISO 解析失败/缺省 → 空视图，不占位）。
    @ViewBuilder
    private var messageTimeLabel: some View {
        let t = formatMessageTimeISO(m.ts)
        if !t.isEmpty {
            Text(t)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    /// 重发语义（ADR-07）：用户显式重发 = 新意图新键——sendMessage 每次调用生成新幂等键
    /// （DefaultCommandClient.kt:93 newUuid），不做键复用，Hub commands 表落独立记录。
    @ViewBuilder
    private var messageContextMenu: some View {
        if !m.content.isEmpty {
            Button {
                UIPasteboard.general.string = m.content
            } label: {
                Label("拷贝全文", systemImage: "doc.on.doc")
            }
        }
        if canResend {
            Button {
                hub.send(m.content)
            } label: {
                Label("重发", systemImage: "arrow.clockwise")
            }
        }
    }

    /// 可重发行 = 失败的用户行（发送失败）；助手失败行无对应命令面（无 run.rerun），只提供拷贝。
    /// 空正文不可重发（发送条本就禁空，重发空 payload 无意义）。
    private var canResend: Bool {
        m.role == "user"
            && (m.uiState == "failed" || m.state == "failed")
            && !m.content.isEmpty
    }

    // ── wp-8③：失败原因（六值脱敏闭集 → 人读文案） ──

    private var failedReasonLabel: String? {
        guard m.state == "failed" || m.uiState == "failed" else { return nil }
        switch m.errorKind {
        case "refusal": return "内容拒绝"
        case "timeout": return "超时"
        case "rate_limit": return "限流"
        case "context_length": return "上下文超限"
        case "state_contention": return "状态冲突"
        case "unknown": return "未知原因"
        default: return nil
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

/// wp-9：工具审批卡——toolName + payloadSummary + 会话归属 + 批准/拒绝决策动作。
///
/// 状态机（全部由 HubStore 瞬态集合 + 待审批投影 Flow 驱动，本地不维护第二份审批状态）：
/// - 默认：批准（primary）/ 拒绝（destructive）可点；
/// - 提交在途：按钮禁用 + ProgressView「提交中…」（防同卡双击双幂等键）；
/// - PERSISTED 回执（approval.decide 202）：呈「已提交 · 等待确认」等待态——决定生效以
///   approval.resolved 事件为准，行翻终态后自动从待审批 Flow 离场（卡片消失 = 收敛）；
/// - 决策通道不可用（Hub FAILED——如 B-4 预检未通过 / HUB-BIZ-001，或网络不可达未提交）：
///   显式红色降级态「决策通道不可用」+ 重试（重试 = 新意图新幂等键，HUB-BIZ-001 不换键语义不破），
///   不静默——wp-9 scope 硬要求。
struct ApprovalCardView: View {
    let a: UiApproval
    @State private var hub = HubStore.shared
    @Environment(\.inkColors) private var ink

    /// 会话归属：conversationId 为 null/空 = 事件路由未学习（hubConvKeys 映射未命中，
    /// DefaultEventProjector.onApprovalRequested 落 null）——「未关联会话」降级标注，不编造归属；
    /// 已学习则从会话列表解析展示名（label 空 → agentId 兜底，与列表口径一致）。
    private var conversationTitle: String? {
        guard let cid = a.conversationId, !cid.isEmpty else { return nil }
        guard let c = hub.conversations.first(where: { $0.conversationId == cid }) else { return nil }
        if !c.label.isEmpty { return c.label }
        return c.agentId.isEmpty ? "会话" : c.agentId
    }

    private var inFlight: Bool { hub.decisionsInFlight.contains(a.approvalId) }
    private var submitted: Bool { hub.decisionsSubmitted.contains(a.approvalId) }
    private var failed: Bool { hub.decisionFailures.contains(a.approvalId) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: "wrench.and.screwdriver")
                    .foregroundStyle(.orange)
                Text(a.toolName)
                    .font(.callout.monospaced().weight(.semibold))
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                Text("工具审批")
                    .font(.caption2)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(Capsule().fill(Color.orange.opacity(0.18)))
                    .foregroundStyle(.orange)
            }
            if !a.payloadSummary.isEmpty {
                Text(a.payloadSummary)
                    .font(.footnote.monospaced())
                    .lineLimit(1...6)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 4) {
                Image(systemName: "bubble.left").font(.caption2)
                Text(conversationTitle ?? "未关联会话")
                    .font(.caption2)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if conversationTitle == nil {
                    Text("（归属未学习）").font(.caption2).foregroundStyle(.tertiary)
                }
            }
            .foregroundStyle(.secondary)

            if failed {
                // wp-9：决策失败（Hub FAILED / 网络不可达）显式降级态，不静默
                Label {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("决策通道不可用").font(.caption.weight(.semibold))
                        Text("决定未送达（可能为预检未通过或网络不可达），可重试").font(.caption2)
                    }
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                }
                .foregroundStyle(.red.opacity(0.9))
            } else if submitted {
                Label("已提交 · 等待确认", systemImage: "clock")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 10) {
                Button {
                    hub.decideApproval(a.approvalId, approve: true)
                } label: {
                    Label("批准", systemImage: "checkmark.circle.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(inFlight || submitted)
                .accessibilityLabel("批准 \(a.toolName)")

                Button {
                    hub.decideApproval(a.approvalId, approve: false)
                } label: {
                    Label("拒绝", systemImage: "xmark.circle")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(inFlight || submitted)
                .accessibilityLabel("拒绝 \(a.toolName)")

                if inFlight { ProgressView().controlSize(.small) }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 14).fill(ink.surfaceTertiary))
    }
}

// ── wp-15：工作过程折叠披露条（tool.* 客户端捕获，对齐 grokbot「Worked」形态） ──

/// wp-15：会话页「工作过程」披露条。数据面 = HubStore.workActivity（投影库 hubToolEvents +
/// hubRunStatus 合流快照，AgentBridge.hubWatchWorkActivity 推送）。
///
/// 已知丢失面（06 §10.2，UI 诚实义务）：tool.*/run.status 断线窗口或入站限流丢弃**不补发**，
/// 本条只呈现本地已捕获部分——计数语义是「已捕获 N 次」而非「本次 run 共 N 次调用」，不虚构完整；
/// 重连后已捕获部分保留（v6 两表快照重建不清）。
private struct WorkProcessBar: View {
    @Environment(\.inkColors) private var ink
    @State private var expanded = false
    let activity: UiWorkActivity
    /// 活跃 run 态（会话实时行）：在途工具/阶段配合它呈现「进行中」而非静止图标。
    let runActive: Bool

    private var runningCount: Int { activity.tools.filter { $0.status == "running" }.count }
    private var completedCount: Int { activity.tools.filter { $0.status == "completed" }.count }
    private var failedCount: Int { activity.tools.filter { $0.status == "failed" }.count }

    /// 最新阶段帧（Flow 按 ts 升序推送，取末帧）；run 已终态时投影清行，此处天然为 nil。
    private var latestPhase: UiRunPhase? { activity.phases.last }

    /// 头部计数摘要（零值段省略；全零但有阶段帧时只显阶段）。
    private var countSummary: String {
        var parts: [String] = []
        if runningCount > 0 { parts.append("进行中 \(runningCount)") }
        if completedCount > 0 { parts.append("完成 \(completedCount)") }
        if failedCount > 0 { parts.append("失败 \(failedCount)") }
        return parts.joined(separator: " · ")
    }

    var body: some View {
        VStack(spacing: 0) {
            DisclosureGroup(isExpanded: $expanded) {
                VStack(alignment: .leading, spacing: 8) {
                    if let phase = latestPhase {
                        phaseRow(phase)
                    }
                    ForEach(activity.tools, id: \.toolCallKey) { t in
                        toolRow(t)
                    }
                }
                .padding(.top, 6)
            } label: {
                HStack(spacing: 8) {
                    Label("工作过程", systemImage: "wrench.and.screwdriver")
                        .font(.subheadline.weight(.medium))
                    if runActive, runningCount > 0 || latestPhase != nil {
                        ProgressView().controlSize(.mini)
                    }
                    if !countSummary.isEmpty {
                        Text(countSummary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
            }
            .font(.subheadline)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 12).fill(ink.surfaceTertiary))
    }

    /// 阶段行：Gateway 启动 phase 枚举的人读呈现（闭集映射 + 原样兜底，非身份字段不涉 ADR-14）。
    private func phaseRow(_ p: UiRunPhase) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Image(systemName: p.phase == "aborted" ? "exclamationmark.triangle" : "gearshape.2")
                .font(.caption)
                .foregroundStyle(p.phase == "aborted" ? AnyShapeStyle(.orange) : AnyShapeStyle(.secondary))
                .padding(.top, 2)
            VStack(alignment: .leading, spacing: 2) {
                Text(Self.phaseText(p.phase)).font(.caption.weight(.medium))
                if let detail = p.detail, !detail.isEmpty {
                    Text(detail).font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }

    /// 工具行：状态徽标 + 工具名（缺省「工具调用」，不虚构名称）+ 有界入参摘要单行。
    private func toolRow(_ t: UiToolActivity) -> some View {
        HStack(alignment: .top, spacing: 8) {
            switch t.status {
            case "completed":
                Image(systemName: "checkmark.circle.fill").font(.caption).foregroundStyle(.green).padding(.top, 2)
            case "failed":
                Image(systemName: "xmark.octagon.fill").font(.caption).foregroundStyle(.red).padding(.top, 2)
            default: // running（含未知值保守呈现为在途）
                ProgressView().controlSize(.mini).padding(.top, 2)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(t.toolName.isEmpty ? "工具调用" : t.toolName)
                    .font(.caption.weight(.medium))
                if let summary = t.summary, !summary.isEmpty {
                    Text(summary)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
            }
        }
    }

    /// Gateway 启动阶段闭集 → 人读文本（protocol.schema.json ChatEvent status 变体 phase 枚举）；
    /// 未知值原样透出（加易读性的命名替换即可，不改语义）。
    private static func phaseText(_ phase: String) -> String {
        switch phase {
        case "waiting_for_state": return "等待会话状态"
        case "preparing_workspace": return "准备工作区"
        case "naming_worktree": return "命名工作树"
        case "creating_worktree": return "创建工作树"
        case "running_setup": return "运行初始化"
        case "provisioning_environment": return "准备运行环境"
        case "preparing_context": return "准备上下文"
        case "memory_flushing": return "整理记忆"
        case "starting_model": return "启动模型"
        case "aborted": return "已中止"
        default: return phase.isEmpty ? "运行中" : phase
        }
    }
}

/// wp-9：待审批清单（设置页「待审批」计数入口的落点）：全部 pending 审批，
/// 含归属未学习的行（卡上标「未关联会话」）——那里没有会话页可挂，唯一可见处在此。
struct PendingApprovalsView: View {
    @State private var hub = HubStore.shared

    var body: some View {
        Group {
            if hub.approvals.isEmpty {
                ContentUnavailableView("暂无待审批", systemImage: "checkmark.shield",
                                       description: Text("工具审批请求出现后自动列入此处"))
            } else {
                List(hub.approvals, id: \.approvalId) { a in
                    ApprovalCardView(a: a)
                        .listRowInsets(EdgeInsets(top: 6, leading: 12, bottom: 6, trailing: 12))
                        .listRowSeparator(.hidden)
                }
            }
        }
        .navigationTitle("待审批")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// wp-11：网关与 Agent 清单（设置页「网关」入口落点）：快照 gateways（wp-2 填充）+ agentsJson
/// （Hub.sq hubGateways.agents，此前只存不显）以结构化行透出；online 时点由 gateway.online/offline
/// 事件维护，lastSeenAt 为 Gateway 最后活跃时间。
struct GatewaysView: View {
    @State private var hub = HubStore.shared

    var body: some View {
        Group {
            if hub.gateways.isEmpty {
                ContentUnavailableView("暂无网关", systemImage: "cpu",
                                       description: Text("网关接入并完成首快照后自动列入此处"))
            } else {
                List(hub.gateways, id: \.gatewayId) { gw in
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            Circle().fill(gw.online ? Color.green : Color.secondary)
                                .frame(width: 8, height: 8)
                            Text(gw.name.isEmpty ? "网关" : gw.name).fontWeight(.semibold)
                            Spacer()
                            Text(gw.online ? "在线" : "离线")
                                .font(.caption)
                                .foregroundStyle(gw.online ? Color.green : Color.secondary)
                        }
                        Text(gw.gatewayId)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.tertiary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        if let seen = gw.lastSeenAt, !seen.isEmpty {
                            Text("最近活跃 \(seen)").font(.caption2).foregroundStyle(.secondary)
                        }
                        if gw.agents.isEmpty {
                            Text("该网关暂无 active Agent").font(.caption).foregroundStyle(.tertiary)
                        } else {
                            ForEach(gw.agents, id: \.agentId) { agent in
                                HStack(spacing: 6) {
                                    Image(systemName: "person.crop.circle").font(.caption)
                                        .foregroundStyle(.secondary)
                                    Text(agent.label.isEmpty ? agent.agentId : agent.label)
                                        .font(.callout)
                                }
                            }
                        }
                    }
                    .padding(.vertical, 2)
                }
            }
        }
        .navigationTitle("网关与 Agent")
        .navigationBarTitleDisplayMode(.inline)
        .task { hub.watchGateways() }
    }
}

/// wp-7②：流式 typing 指示——三点波脉冲，替代静态「…」。
/// TimelineView 定步驱动而非 repeatForever 动画：LazyVStack 行物化/整列表重渲不丢相位，
/// 重渲频率 = 步长倒数（~2.2Hz）可忽略。
private struct TypingIndicator: View {
    @Environment(\.inkColors) private var ink

    /// 单步时长：三点轮转一圈 ≈1.35s。
    private static let step: Double = 0.45

    var body: some View {
        TimelineView(.periodic(from: .now, by: Self.step)) { context in
            let phase = Int(context.date.timeIntervalSinceReferenceDate / Self.step) % 3
            HStack(spacing: 4) {
                ForEach(0..<3, id: \.self) { i in
                    Circle()
                        .fill(ink.ink.opacity(i == phase ? 0.85 : 0.30))
                        .frame(width: 6, height: 6)
                        .scaleEffect(i == phase ? 1.0 : 0.62)
                }
            }
            .animation(.easeInOut(duration: Self.step), value: phase)
        }
    }
}

/// wp-7 可选增强：流式行 typewriter 渐进揭示（grokbot 逐字体感）。
///
/// 数据侧 2s 合并大块到达（bridge 2s 窗，delta-coalesce DEFAULT_COALESCE_WINDOW_MS=2_000），
/// 渲染侧从「已见前缀」向新内容逐字放出：步长 = max(8, 积压/12) @ 16ms——慢流 ≈500 字/秒
/// 打字感，大块积压 ~12 步（≈200ms）内追平，积压不跨下一块。
///
/// 语义边界：
/// - 挂载首帧贴齐现有内容（快照行/滚动往返物化不重打），仅本行存活期间的 append 逐字；
/// - 改写跳变（replace=true 语义、内容收缩、非前缀续写）不逐字、直接贴齐；
/// - 行收敛 final 时外层分支切回 MarkdownView 整段渲染，剩余积压一次性补齐。
private struct StreamingTypewriter: View {
    let content: String
    /// 已揭示字符数；nil = 未初始化（挂载即贴齐现内容）。
    @State private var revealedCount: Int? = nil
    /// 上一帧内容（区分 append 前缀增长与改写跳变）。
    @State private var lastSeen = ""

    private static let tickNanos: UInt64 = 16_000_000

    var body: some View {
        MarkdownView(content: String(content.prefix(revealedCount ?? content.count)))
            .onAppear {
                if revealedCount == nil { revealedCount = content.count; lastSeen = content }
            }
            .task(id: content) {
                let target = content.count
                guard let progress = revealedCount else {
                    // onAppear 竞态兜底：仍未初始化则贴齐，不重打
                    revealedCount = target
                    lastSeen = content
                    return
                }
                guard content.hasPrefix(lastSeen) else {
                    revealedCount = target
                    lastSeen = content
                    return
                }
                lastSeen = content
                var shown = min(progress, target)
                revealedCount = shown
                while shown < target && !Task.isCancelled {
                    shown = min(target, shown + max(8, (target - shown) / 12))
                    revealedCount = shown
                    try? await Task.sleep(nanoseconds: Self.tickNanos)
                }
            }
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
            Section("审批") {
                // wp-9：待审批计数入口——计数来自待审批投影 Flow（含归属未学习的审批）；
                // 0 时入口保留（空态由清单页呈现），避免入口随计数闪烁。
                NavigationLink {
                    PendingApprovalsView()
                } label: {
                    HStack {
                        Text("待审批")
                        Spacer()
                        if hub.approvals.isEmpty {
                            Text("暂无").font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("\(hub.approvals.count)")
                                .font(.caption2.bold())
                                .padding(.horizontal, 7).padding(.vertical, 3)
                                .background(Capsule().fill(Color.orange))
                                .foregroundStyle(.white)
                        }
                    }
                }
            }
            Section("网关") {
                // wp-11：网关维度入口——快照 gateways + agentsJson（AgentRef 结构化清单）透出；
                // 在线态由 gateway.online/offline 事件在投影库维护，行随库变更推送。
                NavigationLink {
                    GatewaysView()
                } label: {
                    HStack {
                        Text("网关与 Agent")
                        Spacer()
                        if hub.gateways.isEmpty {
                            Text("未接入").font(.caption).foregroundStyle(.secondary)
                        } else {
                            Text("\(hub.gateways.count)")
                                .font(.caption2.bold())
                                .padding(.horizontal, 7).padding(.vertical, 3)
                                .background(Capsule().fill(.blue))
                                .foregroundStyle(.white)
                        }
                    }
                }
            }
            Section("已绑定设备") {
                // wp-11 设备管理错误态：拉取失败显式「错误 + 重试」降级（devicesCache nil 时
                // 取代永「加载中…」；有缓存时错误行与缓存行并存，重试按钮补拉），不静默。
                if let error = hub.devicesError {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("设备清单加载失败：\(error)")
                            .font(.caption)
                            .foregroundStyle(.red)
                        Button("重试") { hub.refreshDevices() }
                            .font(.caption)
                    }
                }
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
                } else if hub.devicesError == nil {
                    HStack { ProgressView(); Text("加载中…").foregroundStyle(.secondary) }
                }
                // wp-11 设备管理错误态：撤销失败显式降级文案，可重试（再点「撤销」），不静默。
                if let failure = hub.revokeFailure {
                    Text("撤销失败：\(failure)")
                        .font(.caption)
                        .foregroundStyle(.red)
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
