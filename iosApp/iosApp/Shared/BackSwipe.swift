import UIKit

/// 恢复系统侧滑返回：SwiftUI 隐藏导航栏 + 自绘返回键后，UINavigationController 的
/// interactivePopGestureRecognizer 会被停用（真机反馈：侧滑返回没了）。
/// 做法：遍历场景树找到导航控制器，把 pop 手势 delegate 换成自有守卫
/// （仅栈深 > 1 放行，根页不弹）。SwiftUI 可能重设 delegate——启动后若干时点
/// 与每次回前台重新接线（幂等）。
enum BackSwipe {
    static func scheduleInstall() {
        for delay in [0.6, 2.0, 5.0] {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { attach() }
        }
    }

    static func attach() {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for scene in scenes {
            attach(in: scene.keyWindow?.rootViewController)
        }
    }

    private static func attach(in vc: UIViewController?) {
        guard let vc else { return }
        if let nav = vc as? UINavigationController,
           !(nav.interactivePopGestureRecognizer?.delegate is PopGestureDelegate) {
            nav.interactivePopGestureRecognizer?.delegate = PopGestureDelegate.shared
        }
        vc.children.forEach { attach(in: $0) }
        if let presented = vc.presentedViewController {
            attach(in: presented)
        }
    }
}

private final class PopGestureDelegate: NSObject, UIGestureRecognizerDelegate {
    static let shared = PopGestureDelegate()

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let nav = gestureRecognizer.view as? UINavigationController else { return true }
        return nav.viewControllers.count > 1
    }
}
