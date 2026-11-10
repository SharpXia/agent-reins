import SwiftUI
import UIKit
import PhotosUI
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
            if hub.isLoggedIn, hub.syncBannerVisible {
                HubSyncPhaseBanner(phase: hub.syncPhase)
            }
            // tab 态在 HubStore（wp-13：通知点按需从任意 tab 带回会话栈），此处只绑定。
            // #3（2026-11-07 二轮）：提示条改去抖显示（syncBannerVisible）——解锁/回前台的
            // 快速重连（1–2s 追平）不再逐次弹条；首次登录 bootstrap / 真断线等慢同步照常显示。
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
            if let convKey = ProcessInfo.processInfo.environment["AGENTREINS_DEV_CONV"], !convKey.isEmpty {
                // "1" = 首个会话（既有口径）；非 "1" 值 = label/conversationId 前缀直达指定会话
                // （长会话锚定走查用，如 main）。轮询等投影就绪（登录 bootstrap 数秒）最多 20s。
                var target: UiConversation?
                for _ in 0..<20 {
                    // 空-label 会话（agent 主线卡 label=""，列表显示走 agentId）按 agentId 兜底匹配。
                    target = hub.conversations.first {
                        convKey == "1" || $0.label.contains(convKey)
                            || $0.agentId.contains(convKey) || $0.conversationId.hasPrefix(convKey)
                    }
                    if target != nil { break }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
                if let target {
                    hub.selectedTab = 0
                    hub.devOpen(target)
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
            // Dev 历史回灌走查探针：AGENTREINS_DEV_EARLIER=1 时对当前打开会话循环调
            // requestEarlierAuto()（与上滑近顶自动翻页同一 store 入口），串行走完翻页链直至
            // exhausted/无会话，供「存量库只有尾页→顶部插入」升级路径的自动化复现与回归
            // 走查（真机触摸手感仍需真机复验——本探针只覆盖数据链与插入/恢复链）。触发
            // 时机与视图几何门不同（无 userDrivenScroll），插入与恢复管线完全同一份。
            if ProcessInfo.processInfo.environment["AGENTREINS_DEV_EARLIER"] == "1" {
                await devEarlierWalk(hub)
            }
        }
    }
}

/// Dev 观测文件日志（Documents/dev-earlier.log，容器直写通道，与 scrollbench-result 同模式）
/// ——无 GUI 的自动化环境 stdout 不可靠，容器文件是唯一稳定观测面。
private func devWalkFileLog(_ line: String) {
    print(line)
    guard let logPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first?.appendingPathComponent("dev-earlier.log") else { return }
    if let handle = try? FileHandle(forWritingTo: logPath) {
        handle.seekToEndOfFile()
        handle.write(Data((line + "\n").utf8))
        handle.closeFile()
    } else {
        try? (line + "\n").write(to: logPath, atomically: true, encoding: .utf8)
    }
}

/// Dev 历史回灌走查（AGENTREINS_DEV_EARLIER=1）：独立函数承载防 body 超时（body 体量已贴
/// type-check 上限）。逐 tick 空转等待会话打开，随后串行 requestEarlierAuto 走完翻页链。
private func devEarlierWalk(_ hub: HubStore) async {
    devWalkFileLog("walk start")
    for _ in 0..<200 {
        try? await Task.sleep(nanoseconds: 500_000_000)
        guard hub.isLoggedIn, hub.selected != nil else { continue }
        guard !hub.earlierExhausted else { break }
        let count = hub.messages.count
        let firstSeq = hub.messages.first?.seq ?? -1
        let lastSeq = hub.messages.last?.seq ?? -1
        devWalkFileLog("tick count=\(count) canLoad=\(hub.canLoadEarlier) loading=\(hub.loadingEarlier) firstSeq=\(firstSeq) lastSeq=\(lastSeq)")
        hub.requestEarlierAuto()
    }
    let count = hub.messages.count
    let firstSeq = hub.messages.first?.seq ?? -1
    devWalkFileLog("end exhausted=\(hub.earlierExhausted) count=\(count) firstSeq=\(firstSeq)")
    await devScrollProbe()
}

/// Dev 滚动锁死探针（随 DEV_EARLIER 走查执行）：走查结束后抓取聊天时间线 UIScrollView，
/// 逐帧向下驱动 contentOffset 并记录几何——「视口锁死」判定面：若 contentSize 不随物化
/// 增长、offset 钳在 maxOffset 且视口停在旧内容（无法下滚回最新），即锁死实锤。
private func devScrollProbe() async {
    try? await Task.sleep(nanoseconds: 2_000_000_000)
    var targets: [UIScrollView] = []
    let windows = UIApplication.shared.connectedScenes
        .compactMap { $0 as? UIWindowScene }
        .flatMap(\.windows)
    func walk(_ view: UIView) {
        if let sv = view as? UIScrollView, sv.window != nil, !sv.isHidden, sv.bounds.height > 200 {
            targets.append(sv)
        }
        for sub in view.subviews { walk(sub) }
    }
    for window in windows where !window.isHidden { walk(window) }
    guard let sv = targets.max(by: { $0.contentSize.height < $1.contentSize.height }) else {
        devWalkFileLog("[scrollprobe] no scrollview found")
        return
    }
    func geo(_ tag: String) {
        let minY = -sv.adjustedContentInset.top
        let maxY = max(minY, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
        devWalkFileLog(String(format: "[scrollprobe] %@ offset=%.0f contentH=%.0f maxOffset=%.0f clamped=%@", tag, sv.contentOffset.y, sv.contentSize.height, maxY, String(sv.contentOffset.y >= maxY - 1)))
    }
    geo("start")
    for step in 1...24 {
        let minY = -sv.adjustedContentInset.top
        let maxY = max(minY, sv.contentSize.height - sv.bounds.height + sv.adjustedContentInset.bottom)
        sv.contentOffset.y = min(maxY, sv.contentOffset.y + 600)
        try? await Task.sleep(nanoseconds: 250_000_000)
        geo("down\(step)")
        if sv.contentOffset.y >= maxY - 1, step > 6 { break }
    }
}

/// wp-10：连接状态提示条（iOS 顶层挂载，HubRootView 的 TabView 之上）。非 linked 相位常驻
/// 显示，追平（linked）自动消失——验收③「断网出现提示条、恢复后自动消失；410 重建期间显示
/// 重建态」。文案与桌面 SyncPhaseBanner 同族（两端体验 parity）：catching = 引导/追平在途
/// （灰面弱提示）；reconnecting / rebuilding / superseded = 连接降级（琥珀警示面）。
struct HubSyncPhaseBanner: View {
    let phase: String

    /// #7（2026-11-06 UX 轮）：非 linked 相位的同步 icon 持续旋转——用户可感知「在动 =
    /// 加载中」；wifi.slash/superseded 静态（语义是状态不是进度）。repeatForever 驱动，
    /// 横条随 linked 消失时动画随视图一并卸载。
    @State private var spinning = false

    var body: some View {
        HStack(spacing: 8) {
            if rotates {
                Image(systemName: iconName)
                    .rotationEffect(.degrees(spinning ? 360 : 0))
                    .animation(
                        .linear(duration: 1.2).repeatForever(autoreverses: false),
                        value: spinning,
                    )
                    .onAppear { spinning = true }
            } else {
                Image(systemName: iconName)
            }
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

    /// 进度语义相位（转圈）；状态语义相位（wifi.slash/superseded）不转。
    private var rotates: Bool {
        phase == "catching" || phase == "rebuilding"
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
                    Spacer(minLength: 0)
                    // #1（2026-11-08）：最近活跃时间（title 行右侧弱化展示——桌面
                    // ConversationCard preview 行 formatTs 同族 parity；解析失败省略不占位）
                    Text(formatMessageTimeISO(c.lastActiveAt))
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
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
    // v1.3 发送链：草稿文本与 pending 附件住在 HubStore 会话草稿层（可存活模型层，per
    // conversationId）——视图重建/切后台/滚动往返不丢（验收④），本视图只持 picker 选中态。
    @State private var renaming = false
    @State private var renameDraft = ""
    @State private var compacting = false
    /// wp-7①：底部锚定态（初始锚定；只被用户驱动的滚动翻转，见 setAtBottom）。
    /// Dev 走查豁免（AGENTREINS_DEV_ATBOTTOM=0）：初始即「用户已上滑离底」态——自动化
    /// 环境无触摸手势翻转不了本态，置 0 模拟真实用户翻历史时的锚定态（贴底跟随让路，
    /// restore 滚动成为唯一程序化滚动），复现「上滑翻页→插入→恢复视口」完整用户路径。
    @State private var atBottom = ProcessInfo.processInfo.environment["AGENTREINS_DEV_ATBOTTOM"] != "0"
    /// b8：底部哨兵视口位置（非观测盒，0.4.6 纪律：逐帧值不进 @State）——
    /// 贴底/按钮判定的唯一几何信号。offset 公式（contentHeight−containerSize）与真实
    /// 可滚上限差一个恒定底部安全区偏移（实测恒 ~117pt、containerSize 不含 safeAreaInset
    /// composer 区），offset 路线无法修准，弃用。
    @State private var sentinelBox = SentinelBox()
    /// 视口高（ScrollView 自身尺寸，与哨兵同一 named space 参照；仅布局变化时写入）。
    @State private var viewportHeight: CGFloat = 0
    /// wp-7①：最近一次滚动几何（非观测盒——逐帧更新不得触发 body 重渲）。
    @State private var probeBox = ScrollProbeBox()
    /// wp-7①：当前滚动相是否用户驱动（tracking/interacting/decelerating）。
    @State private var userDrivenScroll = false
    /// b8：手势前科——程序化滚动也会呈 decelerating 相，与用户惯性不可按相区分；
    /// 以「本轮滚动链是否出现过手势」判定。
    @State private var recentUserGesture = false
    /// #4（2026-11-07 UX 轮）：软键盘可见态（keyboardWillChangeFrame 驱动）——键盘弹出
    /// 改变滚动几何但不经用户手势（userDrivenScroll=false），配合贴底跟随补滚动（见 timeline）。
    @State private var keyboardVisible = false
    /// 钉底在途（统一机制）：进页锚定（b8）、键盘唤起瞬移（#4 二轮）与回底按钮点击共用——
    /// 置位后由 onScrollGeometryChange 逐帧 proxy.scrollTo("bottom") 重放收敛（SwiftUI 下
    /// 单次 scrollTo 落 LazyVStack 估算点且滚动中会被吞；逐帧重放每次都是全新命令，随物化
    /// 修正推进——ScrollPosition 系 API 在本视图实测整体失效，勿再迁移），落底（8pt 口径，
    /// b8）即清；用户重新抓取列表（tracking）取消。
    @State private var bottomJumpActive = false
    /// wp-12 翻页恢复重放（mini_x 升级路径修复轮）：翻页行顶部插入后，单次 scrollTo(anchor)
    /// 恒落 LazyVStack 估算点且物化修正后无校正（b8 同族教训，向上插入同理）——改为预算帧
    /// 逐帧重放 scrollTo(anchor, .top)，随物化修正推进直至预算耗尽。用户手指在场的帧跳过
    ///（不与手势争视口、预算不消耗），松手后的帧继续校正——修复拖动中 restore 被吞。
    @State private var restoreReplayId: String?
    @State private var restoreReplayFrames = 0
    /// #3（2026-11-07 UX 轮）：待删除确认的本地消息（长按菜单入口 → 会话页二次确认弹层）。
    @State private var pendingDeleteMessage: UiMessage?
    /// v1.3 发送链：PhotosPicker 绑定项（视图本地瞬态；onChange 消费 = 只增入列，草稿槽
    /// 撤除唯一通道是 ✕ 按钮——撤选同步见 consumePickedItems 注释）。pending 附件本体在
    /// HubStore 会话草稿层（可存活），本绑定只服务系统选择器的选中态回显。
    @State private var pickedItems: [PhotosPickerItem] = []
    /// #3'（2026-11-07 b8 口径返工）：回底按钮显隐唯一依据——距底 > 8pt 容差即显示（
    /// 「最后可见项不是底部哨兵」），≤ 容差即隐藏。b6 的 max(贴底容差, 末行实测高度)+迟滞
    /// 组合按用户口径整体退场（lastRowHeight 上报链同撤）。
    @State private var showJumpToBottom = false
    /// v1.4 查看器：全屏图片查看器载荷（页集 + 初始页）。三入口统一打开面：消息行附件、
    /// Markdown 图块、composer pending 缩略图（openPendingPreview）。瞬态 UI 挂视图层，
    /// 不进 store。
    @State private var imageViewer: ImageViewerPayload?
    @Environment(\.inkColors) private var ink
    let conv: UiConversation

    /// 距底容差（pt）：吸收 overscroll 回弹/行内边距；上翻超过该值即视为离底。
    private static let bottomSlack: CGFloat = 80
    /// 回底按钮判定容差（b8 口径）：距底 ≤ 此值 = 最后可见项是底部哨兵。
    private static let jumpShowTolerance: CGFloat = 8
    /// b8：滚动坐标系名（哨兵探针与视口测量的同一参照系）。
    private static let scrollSpace = "hub.chat.scroll"
    /// 上滑自动翻页触发距离（pt，2026-11-10 加载更早修复轮）：滚动位置距内容顶 < 此值即
    /// 触发 requestEarlierAuto（TG 式近顶回灌）；串行节流在 HubStore.loadEarlier 守卫内收敛
    ///（loadingEarlier 互斥 / exhausted 锁存 / autoEarlierBlocked 失败阻断）。mini_x 升级
    /// 路径修复轮起本值同时是链式续拉（HubStore.autoEarlierChainProbe）的近顶判据。
    private static let earlierAutoProximity: CGFloat = 400
    /// 翻页恢复重放预算（非用户驱动的几何帧数，mini_x 升级路径修复轮）：≈一次物化收敛
    /// 窗口；耗尽即停，下一页恢复重新武装，不相互串扰。
    private static let restoreReplayBudget = 16

    /// wp-8②：当前会话实时行——conv 是导航时刻快照，hasActiveRun/activeRunId 随 run.* 事件
    /// 后续翻转，Stop 按钮态一律从会话列表现值取，不得用快照（会话列表 Flow 持续推送）。
    private var liveConv: UiConversation? {
        hub.conversations.first { $0.conversationId == conv.conversationId }
    }

    /// wp-8②：活跃 run 态——grokbot 式 Stop 常驻 composer 的驱动源；列表未及推送时按无 run 处理（安全降级）。
    private var isRunActive: Bool { liveConv?.hasActiveRun == true }

    /// 交互卡数据面（2026-11-08 修复轮泛化）：`/model` → 模型目录卡（REST #22）；其余带
    /// 静态选项闭集的命令（Gateway catalog args 有界视图，如 /usage /exec）→ 通用选项卡。
    /// 点选项发送 `/命令 <值>`（值原样回填，ADR-14）。
    fileprivate enum InteractiveCardSpec {
        case models
        case options(command: SlashCommand)
    }

    /// 交互卡命中（2026-11-08 UX 轮扩展）：回复行 + 执行态。executedCommand 非 nil =
    /// 该交换已执行（回复行之后出现同命令带参用户行，如 `/model <id>`——点选项即发，
    /// ADR-14），卡面呈结果态（已发送命令 + 指向下方回复），不再渲染按钮。
    fileprivate struct InteractiveCardHit {
        let messageId: String
        let spec: InteractiveCardSpec
        let executedCommand: String?
    }

    /// 交互卡挂载判定（泛化自 modelsCardMessageId，规则不变）：最新一次裸交互命令交换的
    /// 回复行——找最后一条正文恰为 `/<交互命令名>` 的用户行，其后第一条 assistant final 行
    /// 即回复（两者间不得夹任何其他用户行/assistant 行——回复流式未落定期间不出卡），该行
    /// 就地渲染交互卡**替代原回复文本**。点选项后交换被带参用户行「执行」，卡面收敛为结果态；
    /// 再次裸发同名命令时卡面迁移到新交换，旧回复恢复文本渲染（同时只活一张卡）。
    private var interactiveCard: InteractiveCardHit? {
        let msgs = hub.messages
        guard let lastBareIdx = msgs.lastIndex(where: {
            guard $0.role == "user" else { return false }
            let t = $0.content.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            guard t.hasPrefix("/") else { return false }
            return interactiveSpec(forBareName: String(t.dropFirst())) != nil
        }) else { return nil }
        let bare = msgs[lastBareIdx].content.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard let spec = interactiveSpec(forBareName: String(bare.dropFirst())) else {
            return nil
        }
        for idx in (lastBareIdx + 1)..<msgs.count {
            let m = msgs[idx]
            if m.role == "user" { return nil } // 命令与回复间被其他用户行打断 = 交换不完整
            if m.role == "assistant" {
                guard m.state == "final", !m.content.isEmpty else { return nil } // 回复流式/未落定，不出卡
                // 执行判定（2026-11-08 UX 轮）：回复行之后同命令带参用户行 = 已从卡面执行
                //（含手打同形命令——交换语义等价）。取原文（保留大小写）作结果态展示。
                let bareName = bare.dropFirst() // 小写命令名（不含斜杠）
                let executed = msgs[(idx + 1)...].first(where: {
                    guard $0.role == "user" else { return false }
                    return $0.content.trimmingCharacters(in: .whitespacesAndNewlines)
                        .lowercased().hasPrefix("/" + bareName + " ")
                }).map { $0.content.trimmingCharacters(in: .whitespacesAndNewlines) }
                return InteractiveCardHit(messageId: m.messageId, spec: spec, executedCommand: executed)
            }
        }
        return nil
    }

    /// 裸命令名 → 交互卡种类（2026-11-08 自检轮泛化补齐）：model/models 固定走 REST #22
    /// 模型卡（models 为列表型指令，结构化数据面同源——点按钮仍发 `/model <id>` 完成切换）；
    /// think 走思考等级卡（动态闭集的值 = REST #22 当前模型 thinkingLevels，见
    /// thinkCommand）；其余查合并目录首个带 choices 的参数（Gateway catalog args 有界视图）。
    /// 无命中 = 非交互命令（普通文本渲染）。
    private func interactiveSpec(forBareName name: String) -> InteractiveCardSpec? {
        if name == "model" || name == "models" { return .models }
        if name == "think" {
            return Self.thinkCommand(modelsList: hub.modelsCache[conv.conversationId]).map { .options(command: $0) }
        }
        guard let cmd = slashCommands.first(where: { $0.name.dropFirst().lowercased() == name }),
              let choices = cmd.choices, !choices.isEmpty else { return nil }
        return .options(command: cmd)
    }

    /// /think 卡数据面（2026-11-08 自检轮）：Gateway /think.level 为函数型动态闭集（按当前
    /// 模型解析，commands.list 不下发值仅标 dynamic:true）——结构化数据出口 = REST #22
    /// 当前模型条目的 thinkingLevels；卡选项 = default 前置 + levels（剔除 value=="default"），
    /// 镜像 OpenClaw listThinkingLevelChoices 形状。当前模型缺失（非投影路径/缓存未到）或
    /// 无等级 → nil（无卡退化，不编造值集）。
    private static func thinkCommand(modelsList: ModelsList?) -> SlashCommand? {
        guard let current = modelsList?.models.first(where: { $0.current?.boolValue == true }),
              let levels = current.thinkingLevels, !levels.isEmpty else { return nil }
        var choices: [CommandChoice] = [CommandChoice(value: "default", label: "默认")]
        for level in levels where level.value != "default" { choices.append(level) }
        return SlashCommand(
            name: "/think",
            summary: "切换思考等级（当前模型：\(current.name)）",
            takesArgument: true,
            choices: choices,
            argName: "level")
    }

    /// wp-8②：Stop 可执行 = 活跃 run 且 activeRunId 已习得（wp-5 v4 投影）。映射未学习场景
    /// （activeRunId 为 null——DefaultEventProjector.setRun 在 resolveConversation 失败时直接
    /// return，不落库）置灰为「结束中」提示态，不假设 runId 恒有值、不误触。
    private var canCancelRun: Bool { !(liveConv?.activeRunId ?? "").isEmpty }

    /// #3（2026-11-08 UX 轮，用户改版）：标题栏进行中态——thinking 帧在途呈「思考中」，
    /// 其余活跃 run 呈「处理中」。数据链：workActivity 阶段帧（hub.isThinkingNow，wp-15
    /// 数据面）+ 会话实时行 hasActiveRun（wp-8②）。时间线空正文流式占位行随之撤显示
    ///（HubMessageRow 早退分支），进行中信号收敛到标题一处。
    private var agentBusy: Bool {
        hub.isThinkingNow || isRunActive
    }

    /// #4（2026-11-08 UX 轮，用户改版）：草稿有内容 = 发送键优先——run 活跃也可直接发
    /// 新消息（不打断进行中 run，排队语义，消息按 run 态落 queued 徽标）。
    /// v1.3 发送链：文本与 pending 附件（任意上传态）都计入草稿内容——纯图片消息允许空
    /// 文本（契约「message 与 attachments 至少一项非空」）；未就绪的附件由发送链补传
    ///（sendComposerDraft），不再是发送键的禁用条件。
    private var draftHasContent: Bool {
        hub.draftHasContent(conv.conversationId)
    }

    /// 草稿文本（store 可存活层读侧；写入走 draftBinding set 侧）。
    private var draftText: String {
        hub.draft(for: conv.conversationId).text
    }

    /// 草稿文本双向绑定（TextField ↔ store 会话草稿层）。
    private var draftBinding: Binding<String> {
        Binding(
            get: { hub.draft(for: conv.conversationId).text },
            set: { hub.setDraftText($0, conversationId: conv.conversationId) },
        )
    }

    /// wp-14：斜杠命令建议——draft 起手 "/" 且未进入参数书写段时给出目录前缀命中；普通消息、
    /// URL、多行草稿等其余输入零干扰不触发（筛选细则见 SlashCommandCatalog.suggestions）。
    /// #21（v1.1 additive 2026-11-07）：数据源 = 动态目录 ∪ 静态六合并（动态优先，见
    /// SlashCommandCatalog.merged）；动态列表取 HubStore 缓存，未拉取/未命中即纯静态六。
    private var slashSuggestions: [SlashCommand] {
        SlashCommandCatalog.suggestions(forDraft: draftText, from: slashCommands)
    }

    /// #21：合并命令目录——动态列表读 HubStore 缓存（fetchCommandsIfNeeded 在 composer
    /// onChange 触发拉取），无动态项时即静态六原序，行为与旧版零差异。
    private var slashCommands: [SlashCommand] {
        SlashCommandCatalog.merged(dynamic: hub.commandsCache[conv.conversationId]?.commands ?? [])
    }

    /// wp-14 + 2026-11-08 修复轮（用户拍板）：斜杠命令**点行 = 直接发送**——无论是否带参，
    /// 与发送键同一通道（hub.send → message.send 普通文本，Gateway 原生斜杠语义）；带参命令
    /// 裸发由 Gateway 回用法提示，需要写参数时走行尾 ✎ 预填路径（prefillSlashCommand）。
    private func selectSlashCommand(_ cmd: SlashCommand) {
        hub.send(cmd.name)
        hub.setDraftText("", conversationId: conv.conversationId)
    }

    /// 2026-11-08 修复轮：斜杠命令行尾 ✎ = 预填命令文本（含尾随空格）进输入框续写参数，
    /// 等待用户发送（不直接发送）。
    private func prefillSlashCommand(_ cmd: SlashCommand) {
        hub.setDraftText(cmd.name + " ", conversationId: conv.conversationId)
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

    /// #2'（2026-11-07 二轮，x总改版）：「工作过程」披露条整体撤显示——进行中信号收敛到回复
    /// 占位行（「正在思考…/xx 正在处理中…」动画文案，见 HubMessageRow）；workActivity 数据面
    /// 保留（isThinkingNow 判定依赖）。

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
    /// b8：程序化滚动保留 ScrollViewReader/proxy 路径（ScrollPosition 系 API 在本视图实测
    /// 整体失效，见 bottomJumpActive 注释）。
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
                        HubMessageRow(
                            m: m,
                            cardHit: interactiveCard?.messageId == m.messageId ? interactiveCard : nil,
                            onDelete: { pendingDeleteMessage = m },
                            onOpenImages: { pages, idx in openViewer(pages, index: idx) }
                        ).id(m.messageId)
                            // b8：末行视口位置探针（用户口径「last visible 是最底下的 item」——
                            // 判定基准=最后一条消息行，不是垫行）。全部行挂观察、仅末行写入
                            // （不做条件包装防结构翻转，b6 同款）；写入进非观测盒不触发重渲。
                            .onGeometryChange(for: CGFloat.self) { geo in
                                geo.frame(in: .named(HubChatView.scrollSpace)).maxY
                            } action: { y in
                                if m.messageId == hub.messages.last?.messageId {
                                    sentinelBox.viewportY = y
                                    let showJump = y > viewportHeight + Self.jumpShowTolerance
                                    if showJump != showJumpToBottom { showJumpToBottom = showJump }
                                }
                            }
                    }
                    // #6（2026-11-07 UX 轮）：底部贴距收窄——原 vertical 10 + 高度 4 垫行合计
                    // 视觉空隙 ~24pt 偏大；改 top 10 / bottom 0 + 垫行 2，尾行距输入区分隔线
                    // ≈12pt（靠近但不贴死）。
                    Color.clear.frame(height: 2).id("bottom")
                }
                .padding(.horizontal, 14).padding(.top, 10)
            }
            .coordinateSpace(name: Self.scrollSpace)
            // b8：视口高（同一参照系；仅键盘/旋转等布局变化时写入，非逐帧）。
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { h in
                if viewportHeight != h { viewportHeight = h }
            }
            .onScrollGeometryChange(for: ScrollProbe.self) { geo in
                ScrollProbe(offsetY: geo.contentOffset.y,
                            contentHeight: geo.contentSize.height,
                            containerHeight: geo.containerSize.height,
                            boundsHeight: geo.bounds.height,
                            insetTop: geo.contentInsets.top,
                            insetBottom: geo.contentInsets.bottom)
            } action: { _, new in
                probeBox.latest = new
                // 2026-11-10 加载更早修复轮：上滑近顶自动翻页（TG 式）——仅用户手势驱动的
                // 滚动帧触发（userDrivenScroll；程序化钉底/翻页恢复滚动不吃触发，防失控连翻），
                // 距顶 < earlierAutoProximity 即请求；幂等：串行节流全在 HubStore.loadEarlier
                // 守卫内收敛（loadingEarlier 互斥/exhausted 锁存/autoEarlierBlocked 失败阻断），
                // 逐帧重复触发为空转。翻页落地后视口锚回触发前最老行（新页在其上方），继续
                // 上滑越过的行数攒满一页距顶即再触发——连续回灌由用户滚动节奏驱动。
                if userDrivenScroll, new.distanceToTop < Self.earlierAutoProximity {
                    hub.requestEarlierAuto()
                }
                // 贴底/按钮判定统一走哨兵几何信号（sentinelBox.viewportY，见其声明注释）；
                // offset 公式因参照系偏移不可用，仅存档 probe 供键盘瞬移等消费。
                // 钉底在途：逐帧 scrollTo 重放收敛（进页锚定/键盘 resize 共用），哨兵
                // 进入视口底 8pt 内即清。
                if bottomJumpActive {
                    proxy.scrollTo("bottom", anchor: .bottom)
                    setAtBottom(sentinelY: sentinelBox.viewportY)
                    if sentinelBox.viewportY <= viewportHeight + Self.jumpShowTolerance {
                        bottomJumpActive = false
                    }
                    return
                }
                // 翻页恢复重放（restoreReplayId 注）：预算帧内逐帧 scrollTo(anchor, .top)
                // 随物化修正推进；用户手指在场的帧跳过（预算不消耗），松手后的帧继续校正。
                // 重放期间不吃贴底判定（视口在历史区，贴底语义让路）。
                if let anchorId = restoreReplayId {
                    if !userDrivenScroll {
                        proxy.scrollTo(anchorId, anchor: .top)
                        restoreReplayFrames -= 1
                        if restoreReplayFrames <= 0 { restoreReplayId = nil }
                    }
                    return
                }
                // 流式 append 与程序化跟随滚动（animating）不改锚定态：内容增长会把
                // 哨兵推到视口下方，若据此解锚，下一帧起跟随永久停摆。
                guard userDrivenScroll else { return }
                setAtBottom(sentinelY: sentinelBox.viewportY)
            }
            .onScrollPhaseChange { _, new in
                switch new {
                case .tracking, .interacting:
                    // 用户手指在场：开新手势链。
                    recentUserGesture = true
                    userDrivenScroll = true
                case .decelerating:
                    // 手势惯性 = 用户驱动；程序化对齐（defaultScrollAnchor/scrollTo 点定位）
                    // 也呈 decelerating——无手势前科不算用户驱动（b8：误算会让对齐中途的
                    // dist 偏大翻转锚定态、跟随停摆）。
                    userDrivenScroll = recentUserGesture
                case .idle:
                    // 用户手势链结束补判一次（惯性停在半途按最终几何定锚定态，80pt 容差）；
                    // 程序化滚动的 idle 不补判、且清除手势前科。
                    if recentUserGesture { setAtBottom(sentinelY: sentinelBox.viewportY) }
                    recentUserGesture = false
                    userDrivenScroll = false
                case .animating:
                    userDrivenScroll = false
                @unknown default:
                    userDrivenScroll = false
                }
                // 用户重新抓取列表 = 取消自动钉底（人意愿优先于瞬移目标）。
                if new == .tracking { bottomJumpActive = false }
            }
            .onChange(of: hub.messages.count) { _, _ in
                // wp-12：翻页行合入列表（顶部插入）后滚回触发前最老一行，保住阅读位置——
                // 不滚回则视口内容整体下移（读取位置丢失）。锚由 HubStore.loadEarlier 武装、
                // 仅在成功回执后消费（在途新尾消息到达不触发）。mini_x 升级路径修复轮：单次
                // scrollTo 换预算帧重放（restoreReplayId 注）——估算落点随物化逐帧校正。
                if let anchor = hub.takeRestoreScrollTarget() {
                    restoreReplayId = anchor
                    restoreReplayFrames = Self.restoreReplayBudget
                    debugLogRestoreLanding(anchor: anchor)
                }
            }
            .onChange(of: tailSignature) { _, _ in
                // wp-7①：跟随条件 = 尾部变化 且 用户在底部（上翻阅读时不拉回）。
                // 尾签覆盖行数、尾行身份/状态/内容量——比旧「只看 messages.count」
                // 多覆盖流式行原位增长与占位行收敛。
                // #5（2026-11-06 UX 轮）：自己发送例外——无论阅读位置强制贴底，保证
                // 刚发出的消息与随后的服务端回复立即可见（聊天产品的自我消息语义）。
                let ownSend = hub.consumeOwnSendScrollPending()
                guard atBottom || ownSend else { return }
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onChange(of: interactiveCard?.messageId) { _, newId in
                // 交互卡挂到回复行（内容替换渲染，行高变化）出现时用户在底部即贴底；上翻
                // 阅读中不打扰。
                guard newId != nil, atBottom else { return }
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            .onChange(of: hub.messages.last?.content ?? "") { _, _ in
                // choices/think 卡数据面鸡生蛋修复：用户可能不经 "/" 草稿直接发出交互命令
                // ——每条新消息落定幂等触发一次目录拉取（TTL/在途去重内空转零成本），目录
                // 到位（Flow 推送）后 interactiveCard 判定即出卡。think 卡数据面 = #22 当前
                // 模型 thinkingLevels，模型目录同口径补拉（2026-11-08 自检轮）。
                hub.ensureCommands(conversationId: conv.conversationId)
                hub.fetchModelsIfNeeded(conversationId: conv.conversationId)
            }
            .onAppear {
                // b8 进页锚定：置钉底在途逐帧重放收敛（单次 scrollTo 恒落 LazyVStack 估算
                // 点且物化修正后无校正——用户 b7 真机报「进入会话不能自动滑到底部」；重放
                // 每次都是全新命令、随物化修正推进，末行进入视口底 8pt 内即清）。
                // Dev 走查豁免（ATBOTTOM=0，见 atBottom 注）：跳过进页钉底，模拟用户上滑后
                // 的「历史阅读中」状态。
                if ProcessInfo.processInfo.environment["AGENTREINS_DEV_ATBOTTOM"] == "0" { return }
                bottomJumpActive = true
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            // 链式续拉近顶探针注入（HubStore.autoEarlierChainProbe 注）：读滚动探针盒判
            // 「视口仍近顶」；onDisappear 置 nil 即链收敛。盒为非观测类，闭包直读安全。
            .onAppear {
                hub.autoEarlierChainProbe = { [weak probeBox] in
                    guard let probe = probeBox?.latest else { return false }
                    return probe.distanceToTop < Self.earlierAutoProximity
                }
            }
            .onDisappear {
                hub.autoEarlierChainProbe = nil
            }
            // #4（2026-11-06 UX 轮）：键盘收起双通道——滑动列表渐进收起（interactively，
            // 系统键盘伴随）；点时间线空白处一次性收起（tap 不与长按菜单/按钮冲突）。
            .scrollDismissesKeyboard(.interactively)
            .onTapGesture { UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil) }
            // #4（2026-11-07 UX 轮）：键盘唤起 → 内容区随系统避让收缩（输入区顶起、时间线
            // 撑满剩余空间，默认避让已 resize）；此处补尾部跟随——键盘弹出不记为用户滚动，
            // 在底部时自动滚到最底，保证输入时看得见最新消息；上翻阅读中不拉回。
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { note in
                guard let f = (note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else { return }
                keyboardVisible = f.minY < UIScreen.main.bounds.height - 1
            }
            .onChange(of: keyboardVisible) { _, visible in
                // #4 二轮（用户复验反馈）：键盘唤起 → 聊天内容瞬移到底部——无条件（不等
                // atBottom）、无动画；键盘 resize 期间由滚动几何钉底收敛（见 action），
                // 收键盘清在途标记。
                guard visible else {
                    bottomJumpActive = false
                    return
                }
                bottomJumpActive = true
                proxy.scrollTo("bottom", anchor: .bottom)
            }
            // #3'（b8 口径）：回到底部悬浮按钮——点击中断惯性并动画滚到底部哨兵：先带动画
            // 滚动（静止态平滑），同时置钉底在途——动画被在途滚动吞掉/估算落点不足时由逐帧
            // 重放兜底必到底（b5 教训：滚动中单次 scrollTo 不可靠）。
            .overlay(alignment: .bottomTrailing) {
                if showJumpToBottom {
                    Button {
                        bottomJumpActive = true
                        withAnimation(.smooth(duration: 0.3)) {
                            proxy.scrollTo("bottom", anchor: .bottom)
                        }
                    } label: {
                        Image(systemName: "chevron.down.circle.fill")
                            .font(.title3)
                            .foregroundStyle(.secondary)
                            .padding(9)
                            .background(Circle().fill(.thinMaterial))
                            .overlay(Circle().stroke(ink.borderTertiary, lineWidth: 1))
                    }
                    .padding(.trailing, 12)
                    .padding(.bottom, 6)
                    .accessibilityLabel("回到底部")
                }
            }
            .animation(.easeInOut(duration: 0.15), value: showJumpToBottom)
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
            // wp-12：时间线空态占位（桌面 ConversationScreen「暂无消息」同型，空态纪律
            // 对齐会话列表空态）——空列表（打开瞬态/真空会话）整块替换时间线呈居中占位，
            // 首批快照落地即被时间线替换（ScrollView 重建，onAppear 锚底照走）。
            // 2026-11-10 加载更早修复轮「进会话首屏不空」：库内有缺口（canLoadEarlier）或
            // 回灌在途时，空态下挂回灌指示行（首帧由 HubStore.maybeBackfillEmptyTimeline
            // 自动翻一页，按钮兜底；回灌落地行合入即翻转为时间线并锚底）——防「快照尾页整带
            // 被显示过滤」时误导为真空会话且无入口；真空会话（lastSeq=0）保持纯空态。
            if hub.messages.isEmpty {
                VStack(spacing: 12) {
                    timelineEmptyState
                    if hub.loadingEarlier {
                        HStack(spacing: 6) {
                            ProgressView().controlSize(.small)
                            Text("正在回灌历史…").font(.caption).foregroundStyle(.secondary)
                        }
                    } else if hub.canLoadEarlier {
                        Button {
                            hub.loadEarlier()
                        } label: {
                            Label("加载更早", systemImage: "chevron.up")
                                .font(.caption.weight(.medium))
                        }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("加载更早")
                    } else if let err = hub.loadEarlierError {
                        Label { Text(err) } icon: { Image(systemName: "exclamationmark.triangle") }
                            .font(.caption)
                            .foregroundStyle(.red.opacity(0.85))
                    }
                }
            } else {
                timeline
            }
            // wp-14：斜杠命令建议面板——draft 由 "/" 起手时悬浮于 composer 上方，选中即动作；
            // 无命中/进入参数书写段自动收起，其余输入零干扰不触发。
            if !slashSuggestions.isEmpty {
                SlashCommandPanel(
                    suggestions: slashSuggestions,
                    onSelect: selectSlashCommand,
                    onPrefill: prefillSlashCommand,
                )
                .padding(.horizontal, 12).padding(.top, 6)
            }
            // v1.3 发送链：pending 附件预览区（TG 同款——选完立即呈现于输入框上方：上传在途
            // spinner / 就绪预览 / 失败重试态；点 ✕ 撤除并同步撤 picker 选中；点缩略图走
            // openPendingPreview 查看器接入点）。数据源 = HubStore 会话草稿层。
            let pendingDraft = hub.draft(for: conv.conversationId)
            if !pendingDraft.attachments.isEmpty {
                DraftAttachmentChipsRow(
                    drafts: pendingDraft.attachments,
                    onRemove: { id in
                        if let pid = hub.draft(for: conv.conversationId).attachments
                            .first(where: { $0.id == id })?.pickerItemIdentifier {
                            pickedItems.removeAll { $0.itemIdentifier == pid }
                        }
                        hub.removeDraftAttachment(conv.conversationId, slotId: id)
                    },
                    onRetry: { hub.retryDraftUpload(conv.conversationId, slotId: $0) },
                    onOpenPreview: openPendingPreview,
                )
                .padding(.horizontal, 12).padding(.top, 8)
            }
            // v1.3 发送链：发送失败显式降级（任一附件补传失败 = 整体不发送，pending 区保留）
            if let sendErr = hub.draftSendError[conv.conversationId] {
                Label {
                    Text(sendErr)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                }
                .font(.caption)
                .foregroundStyle(.red.opacity(0.9))
                .padding(.horizontal, 12).padding(.top, 4)
            }
            Divider()
            // #2'（2026-11-07 二轮）：原「运行中发送将排队」composer 提示移除——排队语义
            // 上移为发送消息自身的「排队中」状态徽标（信息在消息现场，不再占 composer 行）。
            HStack(spacing: 10) {
                // v1.3 发送链：图片选择入口（PHPicker 出程式面板，无需相册权限）；多选上限
                // 9 张（契约 v1.2.1 attachments maxItems）。上传/失败细节在 pending 预览区呈现。
                PhotosPicker(
                    selection: $pickedItems,
                    maxSelectionCount: HubStore.maxDraftAttachments,
                    matching: .images,
                ) {
                    Image(systemName: "photo")
                        .font(.title3)
                }
                .accessibilityLabel("添加图片（最多 \(HubStore.maxDraftAttachments) 张）")
                TextField("发消息…", text: draftBinding, axis: .vertical)
                    .lineLimit(1...4)
                    .padding(8)
                    .background(RoundedRectangle(cornerRadius: 18).fill(ink.surfaceTertiary))
                    // #21：草稿以 "/" 起手时按需拉取动态命令目录（TTL 5 分钟缓存 + 在途去重
                    // 在 HubStore.fetchCommandsIfNeeded；其余输入空转零网络）。观察源 = store
                    // 草稿文本（可存活层），行为与旧视图 @State 同型。
                    .onChange(of: draftText) { _, new in
                        hub.fetchCommandsIfNeeded(conversationId: conv.conversationId, draft: new)
                    }
                if isRunActive && canCancelRun && !draftHasContent {
                    // wp-8② + #4（2026-11-08 UX 轮改版）：Stop 按钮仅在「活跃 run 且草稿为空」
                    // 时占键位——草稿有内容（文本或 pending 附件）时发送键优先（发送新消息不打断
                    // 进行中 run，排队语义见消息 queued 徽标）；中止后部分输出保留由 Gateway
                    // 权威收敛（run.aborted 回流投影库，UI 只翻转回发送键）。终止随时可经斜杠
                    // /stop（静态目录在列）。
                    Button {
                        hub.stopRun()
                    } label: {
                        Image(systemName: "stop.circle.fill").font(.title2)
                    }
                    .accessibilityLabel("停止生成")
                } else {
                    // #3 修复轮（2026-11-08）：防死锁——run 活跃但 activeRunId 缺失（映射未学习/
                    // 服务端聚合分叉）时**不再让禁用态 Stop 独占键位**：发送键照常可用（发送照常
                    // 走排队语义），旁挂「结束中」弱提示说明 run 在途。此前禁用 Stop 独占 +
                    // 发送键被替换 = 服务端聚合一旦卡 true 用户既不能发也不能停（真机实测：
                    // roster 瞬态信号把会话活跃 run 卡死后 composer 整体锁死）。
                    // v1.3 发送链：发送 = HubStore.sendComposerDraft（逐槽补传 → 一条消息发出，
                    // 文本与全部 pending 图同发；纯文本走 hub.send v1.1 同形通道）。发送在途 =
                    // loading 态占键位 + 防重复点击（验收③）；成功回调清 picker 选中态，
                    // pending 区/文本清空由 store 承担。
                    if hub.sendingDraftFor.contains(conv.conversationId) {
                        ProgressView()
                            .frame(width: 28, height: 28)
                            .accessibilityLabel("发送中")
                    } else {
                        Button {
                            sendDraft()
                        } label: {
                            Image(systemName: "arrow.up.circle.fill").font(.title2)
                        }
                        .disabled(!draftHasContent)
                    }
                    if isRunActive && !canCancelRun {
                        Text("结束中")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 8)
            .onChange(of: pickedItems) { _, items in
                consumePickedItems(items)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        // #2（2026-11-07 UX 轮）：会话详情页隐藏底部 Tab 栏——IM 详情页惯例（聊天内容+输入区
        // 独占全屏），pop 回列表自动恢复；详情仍走 NavigationStack 推栈，导航纪律不变
        //（不必单开 WindowGroup/Scene：多 Scene 会引入窗口生命周期与状态同步复杂度，收益不抵）。
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            // #3（2026-11-08 UX 轮，用户改版）：进行中信号移至标题——标题旁小字 + 动画
            // 省略号（思考中/处理中，见 agentBusy）。principal 自定义标题项替代
            // navigationTitle（inline 模式下居中渲染，动画不劫持返回手势）。
            ToolbarItem(placement: .principal) {
                HStack(spacing: 5) {
                    Text(title(conv))
                        .fontWeight(.semibold)
                        .lineLimit(1)
                    if agentBusy {
                        Text(hub.isThinkingNow ? "思考中" : "处理中")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        AnimatedEllipsis()
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(agentBusy
                    ? "\(title(conv))，\(hub.isThinkingNow ? "正在思考" : "正在处理")"
                    : title(conv))
            }
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
        // #3（2026-11-07 UX 轮）：本地删除二次确认——仅清本机投影行（脏数据清理出口），
        // 不影响服务端历史；服务端数据经快照/校准回流可能重新出现（弹层文案明示）。
        .confirmationDialog(
            "删除这条本地消息？",
            isPresented: Binding(
                get: { pendingDeleteMessage != nil },
                set: { if !$0 { pendingDeleteMessage = nil } },
            ),
            titleVisibility: .visible,
        ) {
            Button("删除本地消息", role: .destructive) {
                if let m = pendingDeleteMessage { hub.deleteLocalMessage(m) }
                pendingDeleteMessage = nil
            }
            Button("取消", role: .cancel) { pendingDeleteMessage = nil }
        } message: {
            Text("仅从本机删除显示；服务端历史不受影响，重新同步后可能重新出现。")
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
        // v1.4 查看器：全屏图片查看器（黑底/捏合/平移/双击/下拉关闭/翻页，见 ImageViewer.swift）。
        // item 驱动：载荷 Identifiable，置 nil 即收（下拉关闭与右上按钮同走 openViewer 的 binding）。
        .fullScreenCover(item: $imageViewer) { payload in
            ImageViewerView(pages: payload.pages, initialIndex: payload.index) {
                imageViewer = nil
            }
        }
    }

    private func title(_ c: UiConversation) -> String {
        if !c.label.isEmpty { return c.label }
        return c.agentId.isEmpty ? "会话" : c.agentId
    }

    /// wp-7①：按哨兵几何重判底部锚定态（仅写状态于翻转时，逐帧空转零重渲）。
    /// b8：信号源从 offset 公式改为哨兵视口位置——offset 公式与真实可滚上限差恒定
    /// 底部安全区偏移（实测 ~117pt），贴底时仍报「离底」，判定永假。
    private func setAtBottom(sentinelY y: CGFloat) {
        let nowAtBottom = y <= viewportHeight + Self.bottomSlack
        if nowAtBottom != atBottom { atBottom = nowAtBottom }
    }

    /// 诊断：restore 落点几何（2026-11-11 mini_x 升级路径修复轮引入）。LazyVStack 估算误差
    /// 的观测面——restore scrollTo 触发瞬间的滚动探针快照（滚动未 settle 时 offset 为触发前
    /// 值，仅作参照）。独立函数承载防 builder 表达式超时（body 体量已贴 type-check 上限）。
    private func debugLogRestoreLanding(anchor: String) {
        #if DEBUG
        guard let probe = probeBox.latest else { return }
        let firstSeq = hub.messages.first?.seq ?? -1
        let count = hub.messages.count
        devWalkFileLog("[paging] restore anchor=\(anchor.prefix(12)) count=\(count) offset=\(probe.offsetY) contentH=\(probe.contentHeight) containerH=\(probe.containerHeight) firstSeq=\(firstSeq)")
        #endif
    }

    /// wp-7①：尾部签名——行数 + 尾行身份/状态/内容量与首尾切片，O(1) 构造
    /// （utf8.count / prefix / suffix 均不做全文遍历）。极小概率漏检「等长且首尾切片
    /// 相同的原地改写」，下一帧 append 自愈，不追。
    private var tailSignature: String {
        guard let last = hub.messages.last else { return "" }
        let c = last.content
        return "\(hub.messages.count)|\(last.messageId)|\(last.state)|\(c.utf8.count)|\(c.prefix(16))|\(c.suffix(64))"
    }

    // ── v1.3 发送链（TG 水准）：选图消费 + 发送 + 查看器接入点 ──

    /// 发送（composer 发送键动作）：链路在 HubStore.sendComposerDraft（补传 → 一条消息
    /// → loading/防重 → 成功清空 pending 区与文本）。成功后本视图只清 picker 选中态
    ///（草稿槽/文本清空由 store 承担）；失败保持一切现状，错误由 store 显式降级展示。
    private func sendDraft() {
        hub.sendComposerDraft(conv) { ok, _ in
            if ok { pickedItems = [] }
        }
    }

    /// PhotosPicker 选中变化消费：**只增入列**（同 identifier 去重，onChange 对同批选中重放）。
    /// 与旧版差异：不再按「选中集差集」撤槽——pending 槽本体在 store 可存活层，视图重建后
    /// pickedItems 归零，若按差集撤除会把未发送的 pending 图一并抹掉（验收④「pending 图与
    /// 文本不得丢失」）；槽位撤除唯一通道 = 预览区 ✕ 按钮（同步撤 picker 选中，见 body 内
    /// onRemove），系统选择器内的取消勾选不影响已入列槽位。
    @MainActor
    private func consumePickedItems(_ items: [PhotosPickerItem]) {
        let known = Set(hub.draft(for: conv.conversationId).attachments.compactMap(\.pickerItemIdentifier))
        let fresh = items.filter { item in
            guard let pid = item.itemIdentifier else { return false }
            return !known.contains(pid)
        }
        guard !fresh.isEmpty else { return }
        hub.addPickedImages(conversationId: conv.conversationId, items: fresh)
    }

    /// v1.4 查看器统一打开面：页集非空才弹（空集不动作——附件全为降级 chip/失败态时
    /// 无可看内容）；初始页钳界。
    private func openViewer(_ pages: [ImageViewerPage], index: Int) {
        guard !pages.isEmpty else { return }
        imageViewer = ImageViewerPayload(pages: pages, index: min(max(index, 0), pages.count - 1))
    }

    /// pending 附件查看器接入点（v1.3 发送轮暴露，本轮实装）：点缩略图回调，参数 = 草稿序
    /// index（数据面 = hub.draft(for:).attachments）。
    ///
    /// 页集口径：槽位已就绪（mediaId）→ hub 页（与消息发出后的查看口径一致，命中
    /// HubMediaLoader 缓存）；上传中未得 mediaId 但有 chip 预览 → 本地位图页（inline）。
    /// 失败态槽（点按已被 chip 拦为重试，正常不会进来）与两者皆缺的槽位不进页集；
    /// 被点槽序映射进过滤后页序，映射不到时退到首页。
    private func openPendingPreview(_ index: Int) {
        let slots = hub.draft(for: conv.conversationId).attachments
        var pages: [ImageViewerPage] = []
        var tapped = 0
        for (i, slot) in slots.enumerated() {
            let page: ImageViewerPage?
            if let upload = slot.upload, MediaIdCheck.isUlid(upload.mediaId) {
                page = .hub(upload.mediaId, fileName: slot.fileName)
            } else if let preview = slot.preview {
                page = .inline(preview, id: "draft:\(slot.id)")
            } else {
                page = nil
            }
            guard let page else { continue }
            if i == index { tapped = pages.count }
            pages.append(page)
        }
        openViewer(pages, index: tapped)
    }
}

/// pending 附件预览区（v1.3 发送链，TG 同款输入框上方缩略图行）：预览缩略图 + 状态角标
///（上传中 spinner / 失败 ⚠ 可点重试 / ✕ 撤除）。点缩略图（非失败态）= openPendingPreview
/// 查看器接入点（HubChatView TODO 标注）。草稿槽本体在 HubStore 会话草稿层，本行只读渲染。
private struct DraftAttachmentChipsRow: View {
    let drafts: [DraftAttachment]
    let onRemove: (UUID) -> Void
    let onRetry: (UUID) -> Void
    /// 点缩略图回调（参数 = 草稿序 index；查看器接线 TODO 在 HubChatView.openPendingPreview）。
    let onOpenPreview: (Int) -> Void
    @Environment(\.inkColors) private var ink

    var body: some View {
        HStack(spacing: 8) {
            ForEach(Array(drafts.enumerated()), id: \.element.id) { index, d in
                chip(d, index: index)
            }
            Spacer(minLength: 0)
        }
    }

    private func chip(_ d: DraftAttachment, index: Int) -> some View {
        ZStack(alignment: .topTrailing) {
            ZStack {
                RoundedRectangle(cornerRadius: 10).fill(ink.surfaceTertiary)
                if let preview = d.preview {
                    Image(uiImage: preview)
                        .resizable()
                        .scaledToFill()
                        .frame(width: 52, height: 52)
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                } else {
                    Image(systemName: "photo").foregroundStyle(.secondary)
                }
                if d.uploading {
                    ProgressView().controlSize(.small)
                }
                if d.error != nil {
                    // 失败态：整 chip 可点重试（显式降级，不静默——wp-9 纪律同源）
                    RoundedRectangle(cornerRadius: 10).fill(Color.red.opacity(0.18))
                    Image(systemName: "arrow.clockwise")
                        .font(.caption.bold())
                        .foregroundStyle(.red)
                }
            }
            .frame(width: 52, height: 52)
            .contentShape(Rectangle())
            .onTapGesture {
                if d.error != nil { onRetry(d.id) } else { onOpenPreview(index) }
            }
            .accessibilityLabel(d.error != nil
                ? "图片上传失败，点按重试"
                : (d.uploading ? "图片上传中" : "已选图片附件，点按查看"))
            // ✕ 撤除角标
            Button {
                onRemove(d.id)
            } label: {
                Image(systemName: "xmark.circle.fill")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .background(Circle().fill(.thinMaterial))
            }
            .offset(x: 6, y: -6)
            .accessibilityLabel("移除该图片")
        }
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
    /// #22 修复轮（2026-11-08）：首参静态选项闭集（Gateway catalog args 有界视图，交互卡
    /// 数据面）；nil = 无选项数据（面板仅普通行为）。model 命令不走此面（动态目录走 REST #22）。
    let choices: [CommandChoice]?
    /// 选项所属参数名（交互卡副标题展示）。
    let argName: String?

    var id: String { name }
}

/// wp-14：命令目录初版（客户端自建静态六）。scope 命名的 Gateway 原生斜杠六命令；steer/followup
/// 对应 chat.send queueMode 词汇族（protocol.schema.json ChatSendParams.queueMode，
/// research/01:113；research/01:236 注明 sessions.steer 为 queueMode:"interrupt" 废弃别名）。
/// #21（v1.1 additive 2026-11-07）：目录动态化已接 Gateway commands.list（REST #21 有界透传，
/// HubStore.fetchCommandsIfNeeded 拉取）——静态六降级为「失败回落/保底」目录，仍可增行扩展。
private enum SlashCommandCatalog {
    static let all: [SlashCommand] = [
        SlashCommand(name: "/new", summary: "开启全新会话上下文", takesArgument: false, choices: nil, argName: nil),
        SlashCommand(name: "/compact", summary: "压缩上下文", takesArgument: false, choices: nil, argName: nil),
        SlashCommand(name: "/stop", summary: "停止当前运行", takesArgument: false, choices: nil, argName: nil),
        SlashCommand(name: "/status", summary: "查看会话状态", takesArgument: false, choices: nil, argName: nil),
        SlashCommand(name: "/steer", summary: "运行中插入引导指令（可接文本）", takesArgument: true, choices: nil, argName: nil),
        SlashCommand(name: "/followup", summary: "追加队列消息，本轮结束后执行（可接文本）", takesArgument: true, choices: nil, argName: nil),
    ]

    /// 触发与筛选：draft 以 "/" 起手且尚未出现空白（用户仍在选命令、未写参数）时，按整段
    /// draft 对命令名做前缀匹配；draft 为单独 "/" 时展示全目录；无命中返回空 = 面板隐藏，
    /// 不拦自由输入（用户仍可发送任意文本，含未知斜杠命令——Gateway 侧原生裁决）。
    /// 数据源由调用方传入（merged 动态 ∪ 静态结果），本函数只做筛选不改目录。
    static func suggestions(forDraft draft: String, from all: [SlashCommand]) -> [SlashCommand] {
        guard draft.hasPrefix("/") else { return [] }
        guard !draft.contains(" "), !draft.contains("\t"), !draft.contains("\n") else { return [] }
        if draft == "/" { return all }
        return all.filter { $0.name.hasPrefix(draft) }
    }

    /// #21：动态 ∪ 静态六合并——归一键 = 去前导 "/"（动态 name 本无 "/"——Gateway mapCommand
    /// 已剥；静态 name 带 "/"；防御性再剥一次防上游形态变化），动态优先、静态保底（服务端
    /// 目录是权威，同名静态行让位），保持动态在前静态在后的稳定次序。动态项展示/发送文本 =
    /// textAliases 首选，防御性补前导 "/"（契约未钉死别名带前缀；裸别名会被面板
    /// hasPrefix("/") 筛选静默消失，对位桌面 SlashCommandRules.mergedSlashCommands），
    /// 缺省 "/" + name；Kotlin description 属性 ObjC 导出为 description_
    /// （与 NSObject.description 撞名自动加下划线），Kotlin 侧字段名不变。
    static func merged(dynamic: [CommandListItem]) -> [SlashCommand] {
        var merged: [SlashCommand] = []
        var seen: Set<String> = []
        seen.reserveCapacity(dynamic.count + all.count)
        for item in dynamic {
            let key = normalizedKey(item.name)
            guard !key.isEmpty, seen.insert(key).inserted else { continue }
            // v1.1 additive 2026-11-08 修复轮：首参选项闭集透传（交互卡数据面）——取第一个
            // 带 choices 的参数（args 有界视图白名单 name/required/choices）。
            let interactiveArg = item.args?.first { !($0.choices ?? []).isEmpty }
            merged.append(SlashCommand(
                name: item.textAliases?.first
                    .flatMap { $0.isEmpty ? nil : ($0.hasPrefix("/") ? $0 : "/" + $0) }
                    ?? "/" + item.name,
                summary: item.description_,
                takesArgument: item.acceptsArgs,
                choices: interactiveArg?.choices,
                argName: interactiveArg?.name))
        }
        for cmd in all where seen.insert(normalizedKey(cmd.name)).inserted {
            merged.append(cmd)
        }
        return merged
    }

    private static func normalizedKey(_ name: String) -> String {
        name.hasPrefix("/") ? String(name.dropFirst()) : name
    }
}

/// wp-14：斜杠命令建议面板——composer 上方轻量目录（输入 "/" 即出、选中即动作）。
/// ScrollView 承载溢出（目录超出可视行数时内滚）；纯本地视图零网络，不劫持键盘焦点。
private struct SlashCommandPanel: View {
    let suggestions: [SlashCommand]
    /// 点行 = 直接发送（2026-11-08 用户拍板）。
    let onSelect: (SlashCommand) -> Void
    /// 行尾 ✎ = 预填命令文本进输入框（带尾随空格）续写参数，等待用户发送。
    let onPrefill: (SlashCommand) -> Void
    @Environment(\.inkColors) private var ink

    /// 面板可视高度上限（≈4 行），其余行内滚。
    private static let panelMaxHeight: CGFloat = 148

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                ForEach(Array(suggestions.enumerated()), id: \.element.id) { index, cmd in
                    HStack(spacing: 0) {
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
                        .accessibilityLabel("\(cmd.name)：\(cmd.summary)（点按直接发送）")
                        // 2026-11-08 修复轮：行尾 ✎ = 预填续写参数（原「带参命令点行预填」
                        // 行为移到此处，点行语义让位给直接发送）。
                        Button {
                            onPrefill(cmd)
                        } label: {
                            Image(systemName: "square.and.pencil")
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .padding(.horizontal, 10).padding(.vertical, 8)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("把 \(cmd.name) 填入输入框续写参数")
                    }
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

/// #22（2026-11-08）：斜杠交互卡——模型目录卡（端点 #22 数据面）。/model 无参命令回复
/// 落定后出现于时间线尾部（interactiveCard 判定），分页网格展示模型按钮，点按钮
/// 发送 `/model <id>`（Gateway 原生模型标识原样回填，客户端不拼装不改写——ADR-14）。
/// 数据 = HubStore.modelsCache（fetchModelsIfNeeded TTL 缓存 + 在途去重，与命令目录同
/// 纪律）；拉取中呈进度态，空目录/截断如实标注；不可用模型按钮置灰（available=false，
/// 不编造原因）；当前模型（current=true）描边高亮。
/// #2（2026-11-08 UX 轮）：executedCommand 非 nil = 交换已执行——卡收敛为结果态
/// （已发送命令 + 指向下方回复），不再渲染按钮（防误触继续切换）；目录改分页网格限高。
/// 卡片纯本地视图零命令副作用，选择动作走 hub.send 既有通道。
private struct ModelsInteractiveCard: View {
    @State private var hub = HubStore.shared
    let conversationId: String
    /// 非 nil = 该交换已执行（用户已点选/手发同命令带参消息）——结果态。
    let executedCommand: String?
    let onSelect: (ModelOptionItem) -> Void
    @Environment(\.inkColors) private var ink

    var body: some View {
        let list = hub.modelsCache[conversationId]
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: executedCommand == nil ? "cpu" : "checkmark.circle.fill")
                    .font(.caption2)
                    .foregroundStyle(executedCommand == nil ? Color.secondary : Color.green)
                if let executed = executedCommand {
                    Text("已执行").font(.caption.weight(.semibold))
                    Text(executed)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text("结果见下方回复")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                } else {
                    Text("切换模型").font(.caption.weight(.semibold))
                    Text("点击发送 /model <模型>")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    if list?.truncated == true {
                        Text("已截断").font(.caption2).foregroundStyle(.tertiary)
                    }
                }
            }
            .foregroundStyle(.secondary)
            if executedCommand != nil {
                // 结果态：无按钮区（防误触继续切换）；切换结果 = 下方 assistant 回复行。
                EmptyView()
            } else if let list {
                if list.models.isEmpty {
                    Text("暂无可用模型")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    PagedChoiceGrid(items: list.models, id: \.id) { item in
                        modelButton(item)
                    }
                }
            } else {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("正在获取模型目录…").font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(ink.elevated))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(ink.borderTertiary, lineWidth: 1))
        .task { hub.fetchModelsIfNeeded(conversationId: conversationId) }
    }

    /// 模型按钮（#2 改版）：name + 「provider · id」副行（平铺分页后保留供应商语境）；
    /// available=false 置灰；current=true 描边高亮（切换后重发 /models 即见当前落点）。
    private func modelButton(_ item: ModelOptionItem) -> some View {
        let available = item.available?.boolValue ?? true
        let isCurrent = item.current?.boolValue == true
        let subtitle = item.provider.isEmpty ? item.id : "\(item.provider) · \(item.id)"
        return Button {
            onSelect(item)
        } label: {
            VStack(alignment: .leading, spacing: 1) {
                HStack(spacing: 3) {
                    Text(item.name)
                        .font(.footnote.weight(isCurrent ? .semibold : .medium))
                        .lineLimit(1)
                    if isCurrent {
                        Image(systemName: "checkmark")
                            .font(.caption2.weight(.bold))
                            .foregroundStyle(Color.accentColor)
                    }
                }
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 8).fill(ink.surfaceTertiary))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(isCurrent ? Color.accentColor : ink.borderTertiary,
                                                             lineWidth: isCurrent ? 1.2 : 0.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!available)
        .opacity(available ? 1 : 0.45)
        .accessibilityLabel("切换模型到 \(item.name)\(isCurrent ? "（当前）" : "")\(available ? "" : "（不可用）")")
    }
}

/// 通用选项卡（2026-11-08 修复轮，斜杠交互卡泛化）：挂最新一次裸命令交换的回复行，
/// 替代原回复文本——分页按钮网格展示 Gateway catalog 静态选项闭集（args 有界视图），
/// 点按钮发送 `/命令 <值>`（值原样回填）。
/// #2（2026-11-08 UX 轮）：executedCommand 非 nil = 交换已执行——卡收敛为结果态
///（已发送命令 + 指向下方回复），不再渲染按钮（防误触重复设置）。与 ModelsInteractiveCard
/// 同纪律：纯本地视图零命令副作用，数据 = 命令目录缓存（REST #21 args 面板同源），
/// 选择走 hub.send 通道。
private struct CommandOptionsCard: View {
    @State private var hub = HubStore.shared
    let command: SlashCommand
    /// 非 nil = 该交换已执行——结果态。
    let executedCommand: String?
    let onSelectValue: (String) -> Void
    @Environment(\.inkColors) private var ink

    var body: some View {
        let choices = command.choices ?? []
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: executedCommand == nil ? "slider.horizontal.3" : "checkmark.circle.fill")
                    .font(.caption2)
                    .foregroundStyle(executedCommand == nil ? Color.secondary : Color.green)
                if let executed = executedCommand {
                    Text("已执行").font(.caption.weight(.semibold))
                    Text(executed)
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text("结果见下方回复")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                } else {
                    Text(command.name).font(.caption.weight(.semibold))
                    if let arg = command.argName, !arg.isEmpty {
                        Text("/\(arg)").font(.caption2).foregroundStyle(.tertiary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Text("点击发送 /\(command.name.dropFirst()) <选项>")
                        .font(.caption2)
                        .foregroundStyle(.tertiary)
                        .lineLimit(1)
                }
            }
            .foregroundStyle(.secondary)
            if executedCommand != nil {
                // 结果态：无按钮区（防误触重复设置）；执行结果 = 下方 assistant 回复行。
                EmptyView()
            } else if choices.isEmpty {
                Text("暂无可用选项").font(.footnote).foregroundStyle(.secondary)
            } else {
                PagedChoiceGrid(items: choices, id: \.value) { choice in
                    choiceButton(choice)
                }
            }
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 12).fill(ink.elevated))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(ink.borderTertiary, lineWidth: 1))
    }

    private func choiceButton(_ choice: CommandChoice) -> some View {
        Button {
            onSelectValue(choice.value)
        } label: {
            VStack(alignment: .leading, spacing: 1) {
                Text(choice.label)
                    .font(.footnote.weight(.medium))
                    .lineLimit(1)
                Text(choice.value)
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 8).fill(ink.surfaceTertiary))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(ink.borderTertiary, lineWidth: 0.5))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(command.name) 设为 \(choice.label)")
    }
}

/// wp-7①：滚动几何探针（onScrollGeometryChange 变换产物），供锚定态判定。
/// b8：末行视口位置（named space，用户口径的「最底下的 item」底边）——0.4.6 纪律的
/// 滚动路径非观测盒，逐帧更新不触发 body 重渲。
private final class SentinelBox {
    var viewportY: CGFloat = .infinity
}

private struct ScrollProbe: Equatable {
    let offsetY: CGFloat
    let contentHeight: CGFloat
    let containerHeight: CGFloat
    let boundsHeight: CGFloat
    let insetTop: CGFloat
    let insetBottom: CGFloat

    /// b8 修正：可滚上限按 UIKit bounds（含 safeAreaInset composer 区）——SwiftUI 的
    /// containerSize 不含该区（实测 668 vs 785），旧公式恒虚报 ~117pt：按钮常显、
    /// 钉底收敛的 8pt 判定永不被满足（逐帧重放空转）。
    var maxOffset: CGFloat { contentHeight - boundsHeight + insetBottom }

    /// 距内容顶距离（pt，2026-11-10 加载更早修复轮）：offsetY 以 -insetTop 为顶（UIKit
    /// contentOffset 语义），归一后 0 = 贴顶。自动翻页触发的唯一几何判据。
    var distanceToTop: CGFloat { offsetY + insetTop }
}

/// wp-7①：滚动几何暂存盒（引用类型 + 非观测）——逐帧写入不触发 body 重渲，
/// 仅在手势结束补判时读取。
private final class ScrollProbeBox {
    var latest: ScrollProbe?
}

/// 交互卡命中透传（HubChatView.interactiveCard 判定产物；行内据此渲染对应卡替代回复文本；
/// executedCommand 非 nil = 已执行，卡呈结果态）。
 fileprivate struct HubMessageRow: View {
    let m: UiMessage
    /// 非 nil = 本行是最新一次裸交互命令交换的回复行——就地渲染对应交互卡**替代原回复
    /// 文本**（models → REST #22 模型卡；options → 通用选项卡）。
    var cardHit: HubChatView.InteractiveCardHit? = nil
    /// #3（2026-11-07 UX 轮）：删除入口回调——二次确认弹层挂在会话页（pendingDeleteMessage），
    /// 行内只负责发起，不自带弹层（LazyVStack 行级弹层生命周期不可靠）。
    let onDelete: () -> Void
    /// v1.4 查看器打开回调（会话页 imageViewer binding 承担）：页集 + 被点页序。入口 a/b
    /// 共用——附件图片块（含 Markdown 图块）点按上抛，查看器本体在 ImageViewer.swift。
    let onOpenImages: ([ImageViewerPage], Int) -> Void
    @State private var hub = HubStore.shared
    @Environment(\.inkColors) private var ink

    var body: some View {
        if m.role == "assistant" && m.state == "streaming" && m.content.isEmpty {
            // #3（2026-11-08 UX 轮，用户改版）：空正文流式占位行不再渲染——进行中信号移至
            // 标题栏（标题旁小字 + 动画省略号，见 HubChatView principal）。思考期时间线只留
            // 已送达的用户行（✓ 徽标语义不变）；正文返回即由 typewriter/Markdown 行承接
            //（dstream 占位行同此口径——首 delta 前隐藏）。
            Color.clear.frame(height: 0)
        } else if m.role == "" {
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
                        if m.role == "assistant" && cardHit != nil && m.state == "final" && !m.content.isEmpty {
                            // 交互卡替换回复文本（挂最新一次裸交互命令交换的回复行）：未执行 =
                            // 选项卡（点选项发 `/命令 <值>`）；已执行 = 结果态（见各卡 executedCommand）。
                            switch cardHit!.spec {
                            case .models:
                                ModelsInteractiveCard(
                                    conversationId: hub.selected?.conversationId ?? "",
                                    executedCommand: cardHit!.executedCommand
                                ) { item in
                                    hub.send("/model \(item.id)")
                                }
                            case let .options(command):
                                CommandOptionsCard(
                                    command: command,
                                    executedCommand: cardHit!.executedCommand
                                ) { value in
                                    hub.send("\(command.name) \(value)")
                                }
                            }
                        } else if m.role == "user" {
                            // wp-8③：空 SENDING 用户行维持发送中态——不渲染空气泡，占位点显示、
                            // 状态徽标照常翻转（sending→delivered）。
                            // v1.2.0 多模态：空正文 + 有附件（纯图消息）不再落「…」占位——媒体
                            // 由下方 attachmentsRow 渲染，文本段整段省略。
                            if !m.content.isEmpty {
                                Text(m.content)
                            } else if m.attachments.isEmpty {
                                Text("…")
                                    .foregroundStyle(Color.secondary)
                            }
                        } else if m.state == "streaming" {
                            // wp-7 可选增强：流式行 typewriter 渐进揭示——数据 2s 一块、渲染逐字放出
                            // v1.4 查看器：流式期已成块的 ![...](url) 图块同样可点开查看（入口 b）
                            StreamingTypewriter(
                                content: m.content,
                                onOpenImage: { urls, idx in
                                    onOpenImages(urls.map { ImageViewerPage.remote($0) }, idx)
                                },
                            )
                        } else if m.content.isEmpty && m.attachments.isEmpty {
                            // wp-8③：final/failed 空正文助手行渲染占位文案，不再出现空气泡
                            //（勘察确认投影层零过滤：空 content 行照常入库，防线只能在渲染侧）。
                            // v1.2.0 多模态：有附件（含生图外链）时空正文不再落占位——媒体即内容。
                            Text("（无文本内容）")
                                .font(.footnote)
                                .italic()
                                .foregroundStyle(.secondary)
                        } else {
                            // 助手内容走自研轻量 Markdown 渲染器（0.4.8 滑动性能专项资产，P4 接入 Hub 面）
                            // v1.4 查看器：Markdown 图片块点开查看（入口 b；页集 = 同消息全部 https 图块，可左右翻页）
                            MarkdownView(
                                content: m.content,
                                onOpenImage: { urls, idx in
                                    onOpenImages(urls.map { ImageViewerPage.remote($0) }, idx)
                                },
                            )
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(
                        Group {
                            if m.role == "assistant" && cardHit != nil && m.state == "final" && !m.content.isEmpty {
                                // 交互卡行：卡自带底色边框，不再套消息气泡底
                                Color.clear
                            } else if m.content.isEmpty && !m.attachments.isEmpty {
                                // v1.2.0 多模态：纯附件行（空正文 + 附件）——文本段为空，不套
                                // 空气泡壳（媒体由下方 attachmentsRow 直陈，防「空壳+图」双底）
                                Color.clear
                            } else {
                                RoundedRectangle(cornerRadius: 16)
                                    .fill(m.role == "user" ? Color.accentColor.opacity(0.24) : ink.surfaceTertiary)
                            }
                        }
                    )
                    // v1.2.0 多模态：行级媒体附件（user 行 = 上传引用回显，source=hub 走 #24
                    // Bearer 自定义 loader；assistant 行 = Bridge 规范化的外发产物引用，
                    // source=external）。纯图消息（空正文+附件）由此渲染，不再落占位文本。
                    // v1.4 查看器：图片块点开查看（入口 a；页集 = 可内联渲染的附件，可左右翻页）。
                    if !m.attachments.isEmpty {
                        let (viewerPages, pageIndexMap) =
                            ImageViewerPage.pagesAndIndexMap(forAttachments: m.attachments)
                        MessageAttachmentsRow(
                            attachments: m.attachments,
                            onOpen: { idx in
                                guard let pageIdx = pageIndexMap[idx] else { return }
                                onOpenImages(viewerPages, pageIdx)
                            },
                        )
                        .padding(.horizontal, 6)
                    }
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
        // #3（2026-11-07 UX 轮）：本地删除入口（长按菜单）——二次确认在会话页弹层，
        // 仅清本机投影行（脏数据清理出口），不影响服务端历史。
        Button(role: .destructive) {
            onDelete()
        } label: {
            Label("删除本地消息", systemImage: "trash")
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
            if ui == "queued" {
                // #3（2026-11-08）：排队中 = 消息级落库状态（发送时 run 活跃），run 终态由
                // 投影层统一释放翻已送达——替代旧会话级水位判定（旧口径把发消息自己触发的
                // run 也染上排队，且水位=0 时波及全会话历史行）。
                Image(systemName: "hourglass").font(.caption2)
                Text("排队中").font(.caption2)
            } else if m.state == "streaming" {
                Image(systemName: "waveform").font(.caption2)
            } else if ui == "sending" {
                Image(systemName: "clock").font(.caption2)
                Text("发送中").font(.caption2)
            } else if ui == "failed" {
                Image(systemName: "exclamationmark.triangle.fill").font(.caption2).foregroundStyle(.red)
                Text("发送失败").font(.caption2).foregroundStyle(.red)
            } else if ui == "delivered" {
                // 已送达 = 唯一的 ✓ 形态：出现即代表排队已释放/网关已确认，不再有
                // 「打勾后又排队」的二义序列（#3）。
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

// ── wp-15：工作过程披露条已撤显示（2026-11-07 二轮 x总改版：进行中信号收敛到回复占位行
//    「正在思考…/xx 正在处理中…」；workActivity 数据面在 HubStore 保留，isThinkingNow 消费） ──

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

/// #3（2026-11-08 UX 轮）：标题栏进行中态的动画省略号——"." / ".." / "..." 循环步进。
/// TimelineView 定步驱动（与 TypingIndicator 同纪律，重渲不丢相位）；minWidth 锁宽
/// 防步进抖动把标题文本推来推去。
private struct AnimatedEllipsis: View {
    var color: Color = .secondary

    /// 单步时长：三点循环一圈 ≈1.2s。
    private static let step: Double = 0.4

    var body: some View {
        TimelineView(.periodic(from: .now, by: Self.step)) { context in
            let n = Int(context.date.timeIntervalSinceReferenceDate / Self.step) % 3 + 1
            Text(String(repeating: ".", count: n))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(color)
                .frame(minWidth: 14, alignment: .leading)
        }
    }
}

/// #2（2026-11-08 UX 轮）：交互卡分页按钮网格——模型/选项多时限制单页容量（2 列 ×
/// rowsPerPage 行），TabView 横滑翻页（自带页点），卡片不再随目录长度无限增高。
/// 单页时不显示页点、高度收敛到内容。页面下标 @State：行随 LazyVStack 物化/去物化
/// 重置回首页（可接受——目录顺序稳定，首页即最常用项）。id 走 keyPath（KMP DTO
/// 类非 Identifiable，与既有 ForEach(id:) 同口径）。
private struct PagedChoiceGrid<Item, ID: Hashable, Card: View>: View {
    let items: [Item]
    let id: KeyPath<Item, ID>
    /// 每页行数（列数固定 2，模型名/选项名两行文本按钮的实测行高 ~44pt）。
    var rowsPerPage: Int = 3
    @ViewBuilder let card: (Item) -> Card

    @State private var page: Int = 0

    private var perPage: Int { rowsPerPage * 2 }
    private var pageCount: Int { max(1, (items.count + perPage - 1) / perPage) }

    /// 页高 = 行高 44 × 行数 + 行距 6 × (行数-1)；多页时底部加页点净空（页点 overlay
    /// 在 TabView 底缘内，不占独立高度，须预留防与末行按钮重叠）。
    private var pageHeight: CGFloat {
        let grid = CGFloat(rowsPerPage) * 44 + CGFloat(rowsPerPage - 1) * 6
        return pageCount > 1 ? grid + 16 : grid + 2
    }

    var body: some View {
        TabView(selection: $page) {
            ForEach(0..<pageCount, id: \.self) { p in
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 6), GridItem(.flexible(), spacing: 6)],
                          spacing: 6,
                          content: {
                    ForEach(Array(items.dropFirst(p * perPage).prefix(perPage)), id: id) { item in
                        card(item)
                    }
                })
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .padding(.horizontal, 2)
                .tag(p)
            }
        }
        .tabViewStyle(.page(indexDisplayMode: pageCount > 1 ? .always : .never))
        .frame(height: pageHeight)
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
    /// v1.4 查看器（入口 b）回调穿线：流式期已成块的 https 图块点按上抛（nil = 不接）。
    var onOpenImage: (([URL], Int) -> Void)? = nil
    /// 已揭示字符数；nil = 未初始化（挂载即贴齐现内容）。
    @State private var revealedCount: Int? = nil
    /// 上一帧内容（区分 append 前缀增长与改写跳变）。
    @State private var lastSeen = ""

    private static let tickNanos: UInt64 = 16_000_000

    var body: some View {
        // streaming=true：typewriter 逐字前缀行跳过 JSON 卡嗅探（仅 final 嗅探，对位桌面
        // jsonCardPayloadOrNull 的 streaming 闸——[]/{} 这类瞬时合法前缀不闪卡）。
        MarkdownView(content: String(content.prefix(revealedCount ?? content.count)),
                     streaming: true, onOpenImage: onOpenImage)
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
