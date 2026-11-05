import Foundation
import UIKit
import UserNotifications

/// wp-13：iOS 本地通知协调器——UNUserNotificationCenter 的唯一出入口。
///
/// 职责边界：授权请求（登录态建立时一次）、通知派发（内容最小化：只有 kind 会话名，
/// 不携带审批 payload 与消息正文）、前台横幅呈现（验收要求前台也收到审批与回复通知）、
/// 点按回调（userInfo.conversationId → HubStore 跳会话）。触发判定（非当前打开/静音/
/// 去重）全部在 HubStore 差异面，本类不做业务判定。wp-17 增补：远程通知注册入口
/// （token 取得与上报在 AppDelegate，见 App.swift）。
///
/// 静默降级纪律（验收硬要求）：权限拒绝（denied）/未决定（notDetermined）时派发静默
/// no-op；`UNUserNotificationCenter.add` 失败只吞错误，任何路径不抛不崩。macOS 通知面
/// 在 wp-18，不在本类范围。
final class NotificationCoordinator: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationCoordinator()

    /// 点按通知跳会话（HubStore 接线；参数 = userInfo.conversationId）。回调可能在非主线程
    /// （UNUserNotificationCenter 回调队列未承诺主线程），消费方须自行回主队列再动 UI 态。
    var onTapConversation: ((String) -> Void)?

    private override init() {
        super.init()
        UNUserNotificationCenter.current().delegate = self
    }

    /// 登录态建立时请求一次授权。系统只在首次未决定时弹窗；已拒绝后再调用即时返回、不弹窗
    /// （无重复骚扰）。结果不落地本类状态——派发前实时查 getNotificationSettings（用户可随时
    /// 在系统设置里翻转，实时查是唯一可靠口径）。
    func requestAuthorizationIfNeeded() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    /// wp-17：注册远程通知（登录态建立时与 [requestAuthorizationIfNeeded] 同一时机调用）。
    /// 注册结果不经本类：成功/失败由系统回调 AppDelegate 的
    /// didRegisterForRemoteNotificationsWithDeviceToken / didFailToRegister…（App.swift），
    /// token 取得后经 HubStore → 桥上报（REST #20 POST /v1/devices/apns-token）。未勾选
    /// aps-environment entitlement 时系统走失败回调静默降级，本调用本身无前置校验。
    func registerForRemoteNotifications() {
        UIApplication.shared.registerForRemoteNotifications()
    }

    // ---------- 派发（通知内容最小化：标题 + 会话名，零正文/payload 摘要） ----------

    /// 审批请求（approval.requested）：不携带 toolName/payloadSummary（消息体不透明）。
    func approvalRequested(conversationId: String?, label: String?) {
        let body = label.map { "「\($0)」有新的审批请求" } ?? "有新的审批请求"
        post(kind: "approval", conversationId: conversationId, title: "有待审批", body: body)
    }

    /// 新回复（assistant final 且非当前打开）：不携带消息正文。
    func replyArrived(conversationId: String, label: String) {
        post(kind: "reply", conversationId: conversationId, title: "有新回复", body: "「\(label)」有新的回复")
    }

    /// run 完成（run.completed）。
    func runCompleted(conversationId: String, label: String) {
        post(kind: "run", conversationId: conversationId, title: "运行完成", body: "「\(label)」的任务已运行完成")
    }

    /// run 失败（run.failed）。
    func runFailed(conversationId: String, label: String) {
        post(kind: "run", conversationId: conversationId, title: "运行失败", body: "「\(label)」的任务运行失败")
    }

    /// 派发前实时查授权态；未授权/未决定直接丢弃（静默降级，验收硬要求）。
    /// trigger nil = 立即呈现；identifier 用 UUID（触发判定与去重在上游 HubStore 完成）。
    private func post(kind: String, conversationId: String?, title: String, body: String) {
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral: break
            default: return // denied / notDetermined：静默降级，不重试不提示
            }
            let content = UNMutableNotificationContent()
            content.title = title
            content.body = body
            content.sound = .default
            var info: [String: String] = ["kind": kind]
            if let conversationId, !conversationId.isEmpty { info["conversationId"] = conversationId }
            content.userInfo = info
            let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            center.add(request) { _ in } // 失败静默（沙盒态/超限等），不抛不崩
        }
    }

    // ---------- UNUserNotificationCenterDelegate ----------

    /// 前台也呈现横幅 + 声音（验收「前台收到审批与回复通知」；系统默认前台不呈现，必须显式放行）。
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    /// 点按通知（含冷启动）：会话归属在 userInfo 时回调 onTapConversation，由 HubStore 跳转；
    /// 无归属（未映射会话未派发过，理论上不会出现）只打开 App 不跳转。
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        if let conversationId = info["conversationId"] as? String, !conversationId.isEmpty {
            onTapConversation?(conversationId)
        }
        completionHandler()
    }
}
