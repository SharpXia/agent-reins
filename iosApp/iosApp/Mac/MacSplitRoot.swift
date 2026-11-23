#if targetEnvironment(macCatalyst)
import SwiftUI
import composeApp

/// Mac 大屏分栏根（feature/mac-bigscreen-20261118）：真 NavigationSplitView 侧栏 + 内容列。
/// 整文件文件级门控（同 MacCommands.swift 先例），iOS/iPad 树上不存在本类型——共享代码
/// 只在 HubRootView 的 TabView 段留一个 `#if targetEnvironment(macCatalyst) MacSplitRoot()
/// #else TabView 原文 #endif` 分支。
///
/// 选中态完全复用 store 既有 `selectedTab`/`selected`（wp-13 已在 store）：行点选直接走
/// hub.open / selectedTab——菜单栏 ⌘,/⌘N（MacCommands）、通知点按（HubStore.handleNotificationTap）、
/// dev 探针（HubRootView.task / DEV_TAB）、sidebar 点选四路入口共用同一状态面，零额外同步逻辑。
///
/// **侧栏选中面 = 自绘单层，不用系统选中面**（2026-11-23 Mac 焦点侧栏终版修复轮，用户反馈
/// beta-0.5.28「切换会话时选中行闪烁」）：此前走 `List(selection:)` + 行级 `listRowBackground`
/// 不透明实底，两层叠加——系统 sidebar 胶囊半透明、其出现时机（UIKit 选中变更）比 SwiftUI
/// 行底晚/早至少一帧、且随键盘焦点在侧栏/输入框之间迁移在强调/非强调两相间变色；再叠加
/// 行内文字随选中翻色（tertiary↔实色令牌），切会话时行面必然发生多次可见变化。现改为
/// `MacSidebarRow`（见文件尾）：每行是一个 Button，选中态与按压态**同一枚**不透明
/// selectionFill 圆角面、无系统高亮、无显式动画，行内文字选中未选中同值——按下即亮、松手
/// 即选中，切换全程行面颜色不变。
///
/// **取舍：侧栏不再提供 ↑/↓ 方向键移选**（原 List(selection:) 自带）。恢复它需要让侧栏
/// 重新成为 SwiftUI 焦点项（.focusable + onKeyPress），而「焦点项把键盘焦点从 composer
/// 手里拽回侧栏」正是本轮焦点回归的同族风险——本轮焦点修复的全部价值在于让 SwiftUI 焦点
/// 引擎彻底不参与 composer，故不引入新的焦点项。键盘用户仍可经全键盘访问（Tab 聚焦行
/// Button + 空格激活）切换会话；如需恢复方向键移选，走 UIKit 层按键捕获（同
/// ComposerTextViewBase 的 pressesBegan 机制，不进焦点引擎），须在解锁屏幕上实测后落地。
///
/// 数据面接线（最易漏的静默故障，落档 §8-2 同族）：`watchConversations()/watchGateways()`
/// 全仓唯一挂点是 HubHomeView.task（HubViews.swift:323）——Mac 树不挂 HubHomeView，故本
/// 视图自带同款 `.task` 镜像该行；`watchApprovals` 在 store 内 loggedIn 时自挂（HubStore
/// 内部），不在迁移面。NewConversationSheet 挂载点随 HubHomeView 退役一并迁到本视图
/// （同 $hub.showNewConversation 一态一挂）。
struct MacSplitRoot: View {
    @State private var hub = HubStore.shared
    /// 选中面/行内文字令牌（selectionFill / sidebarText / sidebarTextMuted，见 InkTheme）。
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

    /// 行选中判据（设置 tab 优先——通知点跳/⌘, 都以 selectedTab 为准）。会话以 conversationId
    /// 字符串比对（KMP 类不可作 value-based 导航的 Hashable 值，HubRootView 导航纪律）。
    private func isConversationSelected(_ c: UiConversation) -> Bool {
        hub.selectedTab != 1 && hub.selected?.conversationId == c.conversationId
    }

    /// 行点选（原 sidebarSelection Binding 的 set 侧语义）：hub.open 原子置 selected（无 nil
    /// 闪烁帧）+ selectedTab=0。切 A→B 时旧 HubChatView 的 onDisappear guard（selected 已翻
    /// 新值）自然短路，不误触 closeConversation。
    ///
    /// **重复点击已选中的行 = no-op**（守卫）：原 List(selection:) 下选中值不变时 Binding
    /// set 根本不会被调，重复点击天然无事；改自绘行后每次点击都会走到这里，若放行则对当前
    /// 会话再次 hub.open——它会清空 messages 后重订阅（消息区先闪空白态再回填）并重置翻页
    /// 与已读水位，纯属无谓重载。守卫把语义钉回与原实现一致。
    private func openConversation(_ c: UiConversation) {
        guard !isConversationSelected(c) else { return }
        hub.open(c)
        hub.selectedTab = 0
    }

    /// 侧栏列（分栏自动提供系统折叠钮 → 与工具栏/标题区融合；折叠态经 columnVisibility
    /// 落 hub.sidebarVisible，供备选菜单项共用）。行内全语义字体（HubConversationRow
    /// .title3/.caption2/.footnote），List 级环境以 .large 为下限适配 Mac 观感（2026-11-19
    /// 窗口锁死修复轮用户反馈「侧栏字体偏小」；2026-11-22 反向钳制修复轮改 max(用户现值,
    /// .large)，不再把系统更大字号压回；只挂本列，非 Mac 树无此视图零影响）。
    ///
    /// 选中面走 MacSidebarRow 自绘（见文件头注），List 本身**不挂 selection 绑定**——系统
    /// 选中胶囊/按压高亮全程不参与，切换会话行面无相位差可闪。
    private var sidebarList: some View {
        List {
            Section("会话") {
                if hub.conversations.isEmpty {
                    // 空态同 HubHomeView「暂无会话」文案；行内呈现以保住下方设置入口。
                    ContentUnavailableView("暂无会话", systemImage: "bubble.left",
                                           description: Text("网关接入后会话将自动出现，也可从右上角新建"))
                } else {
                    ForEach(hub.conversations, id: \.conversationId) { c in
                        MacSidebarRow(selected: isConversationSelected(c)) {
                            openConversation(c)
                        } label: {
                            HubConversationRow(c: c)
                        }
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
                // 行内文字显式钉 sidebarText（Label/图标默认吃 accent——本 app 全局
                // .tint(colors.ink) 劫持为近白/近黑单色，InkTheme 铁律①）；labelStyle 显式
                // 钉 titleAndIcon——Label 进了 Button label 后容器默认解析为 titleOnly，图标不渲染
                //（进程内快照实证）。
                MacSidebarRow(selected: hub.selectedTab == 1) {
                    hub.selectedTab = 1
                } label: {
                    Label("设置", systemImage: "gearshape.fill")
                        .labelStyle(.titleAndIcon)
                        .foregroundStyle(ink.sidebarText)
                }
            }
        }
        // 字号环境（见 sidebarList 注与 incomingTypeSize 注，2026-11-22 反向钳制修复轮）：
        // 以 .large 为下限取 max——默认档用户观感不变（.large 即系统默认），更大字号随
        // 用户不再被压回；语义字体随环境缩放，Section 头/系统行同效。
        .environment(\.dynamicTypeSize, max(incomingTypeSize, .large))
        // 注意：这里刻意不挂 .focusable()/onKeyPress——侧栏不做 SwiftUI 焦点项（取舍见文件
        // 头注），避免与 composer 的键盘焦点产生任何焦点引擎层面的交互。
        // sidebar 样式保侧栏材质/节头/行距观感（2026-11-20 Mac 观感轮引入；现仅取材质
        // 与排版，选中面已不由样式承载）。
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

// MARK: - 侧栏行（选中/按压面单一视觉来源）

/// Mac 侧栏行（2026-11-23 Mac 焦点侧栏终版修复轮）：每行一个 Button，选中态与按压态由
/// **同一枚**不透明 selectionFill 圆角面承载（MacSidebarRowButtonStyle）——按下即亮、松手
/// 即选中，全程行面颜色不变；系统 sidebar 选中胶囊与按压高亮全程不参与（List 无 selection
/// 绑定）。未选中行 hover 走极淡主色罩（值同 Components.swift HoverFillModifier 的 0.07，
/// 按压/选中态下罩归零保证按压==选中的同色不变式）。行内边距归零、留白在本面内自管，
/// 命中区与底面同范围。选中语义经 accessibilityAddTraits(.isSelected) 暴露（原
/// List(selection:) 的读屏选中态由此等价承担）。
private struct MacSidebarRow<Label: View>: View {
    let selected: Bool
    let action: () -> Void
    @ViewBuilder let label: () -> Label
    @State private var hovered = false
    @Environment(\.inkColors) private var ink

    var body: some View {
        Button(action: action) {
            label()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .contentShape(Rectangle())
        }
        .buttonStyle(MacSidebarRowButtonStyle(selected: selected, hovered: hovered, fill: ink.selectionFill))
        .listRowInsets(EdgeInsets())
        .listRowBackground(Color.clear)
        .onHover { hovered = $0 }
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// 侧栏行面：选中或按压 = selectionFill 实底；否则透明 +（hover 且未选中未按压时）淡罩。
/// 面挂在 ButtonStyle 内——按压态（configuration.isPressed）只在样式层可得，这是
/// 「按下即亮」与「按压==选中同色」两个不变式的唯一落点。
private struct MacSidebarRowButtonStyle: ButtonStyle {
    let selected: Bool
    let hovered: Bool
    let fill: Color

    func makeBody(configuration: Configuration) -> some View {
        let face = selected || configuration.isPressed
        configuration.label
            .background(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(face ? fill : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8, style: .continuous)
                    .fill(Color.primary.opacity(hovered && !face ? 0.07 : 0))
                    .allowsHitTesting(false)
            )
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
