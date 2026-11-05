import SwiftUI
import composeApp

@main
struct AgentReinsApp: App {
    /// wp-17：APNs token 上报闭环——注册回调（didRegisterForRemoteNotificationsWithDeviceToken /
    /// didFailToRegister…）由系统派发到 AppDelegate，不挂则回调没有归宿。
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

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

/// wp-17：APNs 设备 token 上报闭环（客户端一半；服务端 = REST #20 POST /v1/devices/apns-token）。
///
/// 注册时机：HubStore 在登录态建立时（与 wp-13 通知授权同一时机）经
/// NotificationCoordinator.registerForRemoteNotifications 发起；注册结果由系统回调本类。
///
/// 退订取舍：登出**不**上报空串清除 token——登出闭环即撤销当前设备（bridge.hubLogout →
/// Hub DELETE /devices/{id}），撤销设备拒绝上报（403 HUB-AUTH-004，契约端点 #20 描述），
/// 报了只是制造一条注定失败的请求；服务端推送目标按 devices.status=active 筛选，撤销即时
/// 停止推送，客户端退订是多余的。
class AppDelegate: NSObject, UIApplicationDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool { true }

    /// 注册成功：deviceToken 转 hex（64 字节 = 128 hex 字符，即契约 token 的实际形态）经桥上报。
    /// 上报失败静默（未登录/网络不可达/设备被撤销 403 都只吞错）——token 上报是尽力而为的
    /// 增强面，任何失败不得影响登录与同步主路径；APNs 在系统恢复/App 更新后可能换发 token，
    /// 换发即再次注册重报，服务端幂等覆盖写。aps-environment entitlement 未勾选时走下面的
    /// 失败回调，不会到达这里。
    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        let hex = deviceToken.map { String(format: "%02x", $0) }.joined()
        // 必须走 HubStore.shared 自持的 bridge：另起 AgentBridge 实例没有登录栈，上报会被静默丢弃
        HubStore.shared.reportApnsToken(hex)
    }

    /// 注册失败（无 aps-environment entitlement/装置不支持/沙盒限制）：记日志静默降级，不崩溃。
    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        NSLog("[APNs] register failed: \(error.localizedDescription)")
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
