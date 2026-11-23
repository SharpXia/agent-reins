#if targetEnvironment(macCatalyst)
import SwiftUI
import composeApp

/// Mac 大屏分栏根（feature/mac-bigscreen-20261118）：真 NavigationSplitView 侧栏 + 内容列。
/// 整文件文件级门控（同 MacCommands.swift 先例），iOS/iPad 树上不存在本类型——共享代码
/// 只在 HubRootView 的 TabView 段留一个 `#if targetEnvironment(macCatalyst) MacSplitRoot()
/// #else TabView 原文 #endif` 分支。
///
/// 选中态完全复用 store 既有 `selectedTab`/`selected`（wp-13 已在 store）：Binding get 侧
/// 读两值映射侧栏高亮，set 侧走 hub.open / selectedTab——菜单栏 ⌘,/⌘N（MacCommands）、
/// 通知点按（HubStore.handleNotificationTap）、dev 探针（HubRootView.task / DEV_TAB）、
/// sidebar 点选四路入口共用同一状态面，零额外同步逻辑。
///
/// 数据面接线（最易漏的静默故障，落档 §8-2 同族）：`watchConversations()/watchGateways()`
/// 全仓唯一挂点是 HubHomeView.task（HubViews.swift:323）——Mac 树不挂 HubHomeView，故本
/// 视图自带同款 `.task` 镜像该行；`watchApprovals` 在 store 内 loggedIn 时自挂（HubStore
/// 内部），不在迁移面。NewConversationSheet 挂载点随 HubHomeView 退役一并迁到本视图
/// （同 $hub.showNewConversation 一态一挂）。
struct MacSplitRoot: View {
    @State private var hub = HubStore.shared
    /// 选中面/行内文字令牌（2026-11-21 观感整治轮：List 作用域 .tint 钉 selectionFill +
    /// 选中行翻色，见 sidebarList 注；2026-11-23 起 selectionFill=近页面克制灰，
    /// 选中态只由底色微差表达）。
    @Environment(\.inkColors) private var ink
    /// 用户现值字号档（2026-11-22 反向钳制修复轮）：侧栏字号环境按下限 .large 取
    /// max(用户现值, .large)——原绝对值 `.environment(\.dynamicTypeSize, .large)` 注释
    /// 称「放大一级」实为钳制，系统设了更大字号（.xLarge 起/AX 档）的用户在侧栏被反向
    /// 压回默认档；本值在 body 内与下限合成（环境自身值不受下游 .environment 覆写）。
    @Environment(\.dynamicTypeSize) private var incomingTypeSize
    /// 侧栏行删除确认目标（本地瞬态；终动作全走 hub.deleteConversation 同 store 入口，
    /// 同 HubHomeView 的 deleteTarget 口径与文案——已知跨面复制，B3 既有先例）。
    @State private var deleteTarget: UiConversation?
    /// v1.5.2 会话换形象目标（侧栏行右键「换形象」入口；nil=关。UiConversation 非
    /// Identifiable，同 deleteTarget 走 isPresented Binding 口径）。
    @State private var avatarTarget: UiConversation?

    var body: some View {
        NavigationSplitView(columnVisibility: sidebarVisibility) {
            sidebarList
                .navigationTitle("AgentReins")
                .navigationSplitViewColumnWidth(min: 220, ideal: 260, max: 400)
        } detail: {
            detailColumn
        }
        // 新建会话 sheet（挂载点自 HubHomeView 迁入，Mac 树上一态一挂；⌘N 与工具栏 +
        // 同写 hub.showNewConversation）。
        .sheet(isPresented: $hub.showNewConversation) {
            NewConversationSheet()
        }
        // v1.5.2 会话换形象 sheet（侧栏行右键「换形象」入口；同 deleteTarget 走 isPresented
        // Binding——UiConversation 非 Identifiable 不可走 item:）。
        .sheet(isPresented: Binding(
            get: { avatarTarget != nil },
            set: { if !$0 { avatarTarget = nil } }
        )) {
            if let t = avatarTarget {
                ConversationAvatarSheet(conversation: t)
            }
        }
        // #29 会话删除确认（侧栏行右键入口）：与 HubHomeView 同款终态确认文案。
        .confirmationDialog(
            "删除该会话？",
            isPresented: Binding(
                get: { deleteTarget != nil },
                set: { if !$0 { deleteTarget = nil } }
            ),
            titleVisibility: .visible
        ) {
            Button("删除", role: .destructive) {
                if let t = deleteTarget { hub.deleteConversation(t) }
                deleteTarget = nil
            }
            Button("取消", role: .cancel) { deleteTarget = nil }
        } message: {
            Text("删除为终态：会话消息与后台任务一并删除，不可恢复。")
        }
        // 侧栏删除失败显式呈现（2026-11-18 low 债清账②）：sessionDeleteError 原全仓唯一
        // 呈现点在 SessionManagerSheet（Mac 主路径不经过）——行乐观消失后失败静默、由下次
        // 重拉悄然复活。macOS 惯例 alert 呈现 + 明确可重试文案；关弹层即清
        // （clearSessionDeleteError），下次删除入口重走同链路。
        .alert(
            "删除会话失败",
            isPresented: Binding(
                get: { hub.sessionDeleteError != nil },
                set: { if !$0 { hub.clearSessionDeleteError() } }
            )
        ) {
            Button("好", role: .cancel) {}
        } message: {
            Text(hub.sessionDeleteError ?? "删除未送达或被服务端拒绝（可重试）。")
        }
        // 数据订阅（镜像 HubHomeView.task：watchConversations + watchGateways）——漏挂则
        // 侧栏永久空（数据面无订阅、无报错）。
        .task { hub.watchConversations(); hub.watchGateways() }
    }

    // MARK: - 侧栏列

    /// 侧栏选中值：List(selection:) 需 Hashable 同型 tag。会话以 conversationId 字符串
    /// 标识（KMP 类不可作 value-based 导航的 Hashable 值，HubRootView 导航纪律），设置
    /// 是单值。tag 与泛型不同型是静默无高亮（编译器不报）——改动须两处同型。
    private enum MacSidebarEntry: Hashable {
        case conversation(String)
        case settings
    }

    /// 选中绑定：get 读 store 双值（设置 tab 优先——通知点跳/⌘, 都以 selectedTab 为准）；
    /// set 会话 → hub.open + selectedTab=0（open 原子置 selected，无 nil 闪烁帧），设置 →
    /// selectedTab=1。切 A→B 时旧 HubChatView 的 onDisappear guard（selected 已翻新值）
    /// 自然短路，不误触 closeConversation。
    private var sidebarSelection: Binding<MacSidebarEntry?> {
        Binding(
            get: {
                if hub.selectedTab == 1 { return .settings }
                if let c = hub.selected { return .conversation(c.conversationId) }
                return nil
            },
            set: { entry in
                switch entry {
                case .settings:
                    hub.selectedTab = 1
                case .conversation(let id):
                    guard let c = hub.conversations.first(where: { $0.conversationId == id }) else { return }
                    hub.open(c)
                    hub.selectedTab = 0
                case nil:
                    break
                }
            }
        )
    }

    /// 侧栏列（分栏自动提供系统折叠钮 → 与工具栏/标题区融合；折叠态经 columnVisibility
    /// 落 hub.sidebarVisible，供备选菜单项共用）。行内全语义字体（HubConversationRow
    /// .title3/.caption2/.footnote），List 级环境以 .large 为下限适配 Mac 观感（2026-11-19
    /// 窗口锁死修复轮用户反馈「侧栏字体偏小」；2026-11-22 反向钳制修复轮改 max(用户现值,
    /// .large)，不再把系统更大字号压回；只挂本列，非 Mac 树无此视图零影响）。
    private var sidebarList: some View {
        List(selection: sidebarSelection) {
            Section("会话") {
                if hub.conversations.isEmpty {
                    // 空态同 HubHomeView「暂无会话」文案；行内呈现以保住下方设置入口
                    //（空态整列替换会吞掉 List(selection:) 机制）。
                    ContentUnavailableView("暂无会话", systemImage: "bubble.left",
                                           description: Text("网关接入后会话将自动出现，也可从右上角新建"))
                } else {
                    ForEach(hub.conversations, id: \.conversationId) { c in
                        HubConversationRow(
                            c: c,
                            // 选中翻色（2026-11-21 观感整治轮）：由 sidebarSelection 派生——
                            // 胶囊（selectionFill）与行内文字（onSelection 系）同令牌域定色。
                            selected: sidebarSelection.wrappedValue == .conversation(c.conversationId)
                        )
                            .tag(MacSidebarEntry.conversation(c.conversationId))
                            .contextMenu {
                                // 与 HubHomeView 行右键同动作同文案（parity，不单独门控）。
                                Button {
                                    hub.beginRename(c)
                                } label: {
                                    Label("重命名", systemImage: "pencil")
                                }
                                Button {
                                    hub.setMuted(!hub.mutedConversations.contains(c.conversationId),
                                                 conversationId: c.conversationId)
                                } label: {
                                    Label(hub.mutedConversations.contains(c.conversationId) ? "取消静音" : "静音此会话",
                                          systemImage: hub.mutedConversations.contains(c.conversationId) ? "bell.fill" : "bell.slash")
                                }
                                // v1.5.2 会话换形象（会话级覆盖档；HubHomeView 行同款 parity）。
                                Button {
                                    avatarTarget = c
                                } label: {
                                    Label("换形象", systemImage: "face.smiling")
                                }
                                Divider()
                                Button(role: .destructive) {
                                    deleteTarget = c
                                } label: {
                                    Label("删除会话…", systemImage: "trash")
                                }
                            }
                    }
                }
            }
            Section("系统") {
                // 系统侧栏行的 Label/图标默认吃 accent（本 app 被 .tint(colors.ink) 劫持为
                // 近白/近黑单色，InkTheme.swift:277）；选中时同 HubConversationRow 翻
                // onSelection 令牌，未选中显式钉 .primary（系统 Label 无处传参，与
                // HubConversationRow 的选中通道对齐——beta-0.5.22「设置行白字压近白胶囊」
                // 同款 bug 的预防收口）。
                Label("设置", systemImage: "gearshape.fill")
                    .foregroundStyle(sidebarSelection.wrappedValue == .settings
                                     ? AnyShapeStyle(ink.onSelection) : AnyShapeStyle(.primary))
                    .tag(MacSidebarEntry.settings)
            }
        }
        // 字号环境（见 sidebarList 注与 incomingTypeSize 注，2026-11-22 反向钳制修复轮）：
        // 以 .large 为下限取 max——默认档用户观感不变（.large 即系统默认），更大字号随
        // 用户不再被压回；语义字体随环境缩放，Section 头/系统行同效。
        .environment(\.dynamicTypeSize, max(incomingTypeSize, .large))
        // 2026-11-21 观感整治轮（铁律②）：选中胶囊作用域钉住 selectionFill 令牌，脱离全局
        // tint 的隐式来源（防未来全局 tint 漂移时选中面随行漂移；2026-11-23 起该令牌=
        // 近页面克制灰，与全局 tint 分值，本行真正生效不再恒 no-op）。
        .tint(ink.selectionFill)
        // 2026-11-20 Mac 观感轮：sidebar 样式——plain 样式的选中高亮是近白实底
        //（暗色模式下白底压白字，用户截图实证「选中行内容看不清」）；sidebar 样式
        // 选中=系统 accent 半透明胶囊+primary 文字。
        //（2026-11-22 对比度收口轮：半透明胶囊的落屏 α 不受代码控制——α 合成中间调后
        // 对比坍缩。会话行选中底已由 HubConversationRow 的 listRowBackground 显式
        // 不透明 selectionFill 实底接管（本 .tint 钉保留，胶囊被行底覆盖后无副作用）；
        //「设置」系统行无行级通道，选中底由 .tint 钉色承载——2026-11-23 起该色=近页面
        // 克制灰，行内文字 onSelection 深色系直接可读，此前「文字翻色扛对比」的知情
        // 边界随令牌改值自然收敛。）
        .listStyle(.sidebar)
        // 「新建」钮已迁 detail 列（补完项③）：原挂本列 .toolbar，侧栏折叠（detailOnly）
        // 后按钮随列隐藏，折叠态动作面只剩菜单栏 ⌘N——现挂 detailColumn 的 NavigationStack，
        // 折叠态常驻窗口工具栏（见 detailColumn 注）。
    }

    // MARK: - 内容列

    /// 内容列：设置 tab（⌘,/DEV_TAB/侧栏「设置」行）→ HubSettingsView；其余 → 当前打开
    /// 会话（无则未选择占位）。.id 强制每会话全新视图身份——切会话不留旧滚动 @State
    /// （atBottom/sentinelBox/probeBox），保住 b8 进页锚底链（onAppear 重新 hub.open）。
    /// 勿在此补 navigationDestination(isPresented: $hub.chatPush)：chatPush 在 Mac 树无
    /// 消费者、恒 true 是死标志，误补会二次推栈。
    ///
    /// 「新建」钮挂 NavigationStack 本体（补完项③，分支外）：原挂 sidebar 列 .toolbar，
    /// 侧栏折叠（detailOnly）后随列消失、折叠态只剩菜单栏 ⌘N——挂 detail 列后折叠态常驻；
    /// 设置/会话/占位三态切换不重建该项。Catalyst 统一提升进窗口工具栏（与 HubChatView 的
    /// principal 标题、ellipsis 菜单同一条 toolbar），⌘N 同写 hub.showNewConversation。
    @ViewBuilder
    private var detailColumn: some View {
        NavigationStack {
            if hub.selectedTab == 1 {
                HubSettingsView()
            } else if let c = hub.selected {
                HubChatView(conv: c)
                    .id(c.conversationId)
            } else {
                ContentUnavailableView("未选择会话", systemImage: "bubble.left.and.bubble.right",
                                       description: Text(emptyStateHint))
            }
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    hub.showNewConversation = true
                } label: {
                    Image(systemName: "plus.message")
                }
                .accessibilityLabel("新建会话")
            }
        }
    }

    /// columnVisibility ↔ hub.sidebarVisible 双向映射（.all/.detailOnly 两态口径；
    /// .doubleColumn/.automatic 归入可见侧）。
    private var sidebarVisibility: Binding<NavigationSplitViewVisibility> {
        Binding(
            get: { hub.sidebarVisible ? .all : .detailOnly },
            set: { hub.sidebarVisible = ($0 != .detailOnly) }
        )
    }

    /// 未选择占位指路文案随侧栏可见态分支（2026-11-18 low 债清账④）：折叠态「左侧边栏」
    /// 不可见，原指路失效——可见时才点名左侧边栏。
    private var emptyStateHint: String {
        hub.sidebarVisible ? "从左侧边栏选择一个会话，或点右上角新建"
                           : "选择一个会话，或点右上角新建"
    }
}

// MARK: - 内容列居中帽（item2）

/// 内容列居中 + 合理 max-width：双 frame 先帽后撑满——内层 maxWidth 668 收口，外层
/// infinity 占满剩余宽并把收口列居中。668 = 气泡帽 640（HubMessageRow，HubViews.swift
/// :2101）+ 时间线水平 padding 14×2（:878）：消息文本测度与 composer 分隔线对齐同一
/// measure，宽窗下气泡顶满 640、列帽在测度边界不再额外收紧（帽保留 = 双保险，防列宽
/// 调整后失效）。滚动锚定链零影响：列帽只收宽度，viewportHeight/sentinelBox/restore
/// 重放几何全是纵向量。
private struct MacContentColumnModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .frame(maxWidth: 668)
            .frame(maxWidth: .infinity)
    }
}

extension View {
    /// Catalyst-only：内容列居中帽（见 MacContentColumnModifier 注；镜像
    /// MacWindowFrameSaver/macWindowFrameSaver() 的扩展手法）。
    func macContentColumn() -> some View {
        modifier(MacContentColumnModifier())
    }
}

#endif
