#if targetEnvironment(macCatalyst)
import SwiftUI
import composeApp

/// 菜单栏 Commands（UxPlan item2）：新建会话 ⌘N / 设置 ⌘, / 会话菜单（静音·重命名·压缩·
/// 回到底部 ⌘J·删除 ⌘⇧⌫）。状态全部取自 HubStore 单例：
/// - showNewConversation / selectedTab：store 发布属性（本项提升自视图本地态，iOS 行为不变）；
/// - 回底走自增 token requestJumpToBottom（评审修正⑥：own-send 信号随下一条消息变化才消费、
///   Bool 信号重复触发不翻变——回底必须即时且可重复，HubChatView onChange 立即消费）；
/// - 重命名走 beginRename（alert 挂 HubRootView，列表/详情两入口共用一套文案）；
/// - 删除走 menuDeleteRequested（复用 HubChatView 既有终态确认弹层与 dismiss 关页链，
///   确认语义留在视图层，store 侧不直接删）。
/// hub 用 plain let 引用单例：@Observable 属性读取发生在 Commands body 求值期（受跟踪），
/// 菜单启停/文案随状态翻转；HubStore.shared 为单例，无重建丢态问题。
struct MacHubCommands: Commands {

    private let hub = HubStore.shared

    var body: some Commands {
        // App 菜单：设置 ⌘,（切换到设置 tab；无独立 Settings scene——0.5.x 单窗裁决）。
        CommandGroup(replacing: .appSettings) {
            Button("设置") {
                hub.selectedTab = 1
            }
            .keyboardShortcut(",", modifiers: .command)
            .disabled(!hub.isLoggedIn)
        }
        // File 菜单：新建会话 ⌘N（替代默认 newItem 占位；单 Scene 无「新建窗口」项）。
        CommandGroup(replacing: .newItem) {
            Button("新建会话") {
                hub.selectedTab = 0
                hub.showNewConversation = true
            }
            .keyboardShortcut("n", modifiers: .command)
            .disabled(!hub.isLoggedIn)
        }
        // View 菜单：切换边栏 ⌃⌘S（feature/mac-bigscreen-20261118 补完项①）——**替换**
        // 系统 Toggle Sidebar 项（.sidebar 位即系统该项所在，替换防双项/⌃⌘S 键位冲突），
        // 翻转 hub.sidebarVisible：与 MacSplitRoot 的 columnVisibility 绑定、侧栏折叠钮
        // 三方同一 store 入口，无第二事实源；持久化在 store didSet。文案随状态翻转
        // 「隐藏边栏/显示边栏」（2026-11-18 low 债清账①：系统 Toggle Sidebar 即两态翻转
        // 标签，原固定文案与系统口径不符；手法同下方 muteLabel）。
        CommandGroup(replacing: .sidebar) {
            Button(sidebarLabel) {
                hub.sidebarVisible.toggle()
            }
            .keyboardShortcut("s", modifiers: [.control, .command])
            .disabled(!hub.isLoggedIn)
        }
        // 会话菜单：作用于当前打开会话（hub.selected 非空 = 会话页在场）。
        CommandMenu("会话") {
            Button(muteLabel) {
                if let c = hub.selected {
                    hub.setMuted(!hub.mutedConversations.contains(c.conversationId),
                                 conversationId: c.conversationId)
                }
            }
            .disabled(!hub.isLoggedIn || hub.selected == nil)

            Button("重命名…") {
                if let c = hub.selected { hub.beginRename(c) }
            }
            .keyboardShortcut("r", modifiers: [.command, .shift])
            .disabled(!hub.isLoggedIn || hub.selected == nil)

            Button("压缩上下文") {
                if let c = hub.liveSelected { hub.compact(c) }
            }
            .disabled(!hub.isLoggedIn || hub.selected == nil
                      || hub.liveSelected?.hasActiveRun == true)

            Divider()

            Button("回到底部") {
                hub.requestJumpToBottom()
            }
            // UX 复审意见②：⌘↓ 在聚焦的多行 composer 里是「光标移到文末」编辑惯例，而
            // Catalyst 菜单键等价物先于响应链拦截按键（有劫持风险）——换 ⌘J 零冲突，不占
            // 文本编辑键位。
            .keyboardShortcut("j", modifiers: .command)
            .disabled(!hub.isLoggedIn || hub.selected == nil)

            Divider()

            Button("删除会话…", role: .destructive) {
                if hub.selected != nil { hub.menuDeleteRequested = true }
            }
            // 2026-11-23 Mac 键位修复轮（用户反馈）：原 ⌘⌫ 与 macOS 文本编辑惯例「删除到
            // 行首」撞车——Catalyst 菜单键等价物先于 composer 文本视图拦截按键，输入框里
            // 按 ⌘⌫ 弹出的是删除会话确认而非删行首。换 ⌘⇧⌫（破坏性操作配重键位，与上方
            // 重命名 ⌘⇧R 同族；⌘↓→⌘J 前例同理）；⌘⌫ 回归 ComposerTextViewBase 的
            // deleteToBeginningOfLine（keyCommands 通道）。
            .keyboardShortcut(.delete, modifiers: [.command, .shift])
            .disabled(!hub.isLoggedIn || hub.selected == nil)
        }
    }

    /// 静音项文案随状态实时翻转（@Observable 跟踪）。
    private var muteLabel: String {
        guard let c = hub.selected else { return "静音此会话" }
        return hub.mutedConversations.contains(c.conversationId) ? "取消静音" : "静音此会话"
    }

    /// 边栏项文案随状态翻转（2026-11-18 low 债清账①）：可见态=「隐藏边栏」、折叠态=
    /// 「显示边栏」，同系统 Toggle Sidebar 惯例与 muteLabel 手法（@Observable 跟踪）。
    private var sidebarLabel: String {
        hub.sidebarVisible ? "隐藏边栏" : "显示边栏"
    }
}

#endif
