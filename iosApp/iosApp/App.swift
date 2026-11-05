import SwiftUI
import composeApp

@main
struct AgentReinsApp: App {
    init() {
        BackSwipe.scheduleInstall()
        // wp-13：进程启动即挂 UNUserNotificationCenter 代理（NotificationCoordinator.shared
        // 首次访问时安装）——冷启动点通知的 didReceive 路径也必须在首帧渲染前就绪。
        _ = NotificationCoordinator.shared
    }

    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// 根视图（Hub 化 P4 定稿）：账号态三分支——已登录进 Hub 主界面 / 恢复中 / 登录页。
///
/// 网关直连面（AppStore 五路快照、连接/配对路由、审批弹层、P1 调试状态条）已整体退役。
/// 主题跟随系统（直连时代的主题覆盖设置随旧设置页退役，待 Hub 设置页重建）；
/// 审批卡待 Hub 审批事件面接入（投影库 hubApprovals 已有表，UI 未接）。
struct RootView: View {
    @State private var hub = HubStore.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if hub.isLoggedIn {
                HubRootView()
            } else if hub.isRestoring {
                ProgressView("正在恢复 Hub 登录…")
            } else {
                LoginView()
            }
        }
        .inkTheme()
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { BackSwipe.attach() }
        }
    }
}
