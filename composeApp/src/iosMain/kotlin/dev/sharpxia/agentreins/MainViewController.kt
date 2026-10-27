package dev.sharpxia.agentreins

import androidx.compose.ui.window.ComposeUIViewController
import dev.sharpxia.agentreins.ui.StatusBarTapBus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSProcessInfo
import platform.darwin.NSObject
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UITapGestureRecognizer
import platform.objc.sel_registerName

/** iOS 入口：iosApp 壳工程（SwiftUI）经此桥接 Compose UI。 */
fun MainViewController(): UIViewController {
    installAppForegroundObserver() // 回前台自动重连（AppModel 经 appForegroundEvents 消费）
    installNetworkMonitor() // 网络路径监听（AppModel 经 networkAvailable 响应式重连）
    val controller = ComposeUIViewController { App(prebuilt = devPrebuilt()) }
    attachStatusBarTapDetector(controller)
    return controller
}

/**
 * 桌面壳同款 dev 直连入口（desktopApp/main.kt 镜像）：AGENTREINS_DEV_URL（必）+
 * AGENTREINS_DEV_KEY / AGENTREINS_DEV_SESSION（可选）。模拟器/自动化验证用：
 * `SIMCTL_CHILD_AGENTREINS_DEV_URL=… xcrun simctl launch …` 注入，不出任何 UI 面。
 */
private fun devPrebuilt(): AppModel? {
    val env = NSProcessInfo.processInfo.environment
    val devUrl = env["AGENTREINS_DEV_URL"] as? String ?: return null
    return AppModel(url = devUrl, apiKey = env["AGENTREINS_DEV_KEY"] as? String ?: "").apply {
        devAutoConnect = true
        devAutoSelectKey = env["AGENTREINS_DEV_SESSION"] as? String
    }
}

/**
 * 状态栏点击探测（CMP 未内置，YouTrack CMP-4187）：在 Compose 视图顶部与
 * 安全区上沿之间铺一层透明 UIView 收点击，经 [StatusBarTapBus] 交给
 * commonMain 的 StatusBarTapEffect。底部锚定 safeAreaLayoutGuide 上沿，
 * 刘海/灵动岛高度变化时约束自动跟随。
 */
@OptIn(ExperimentalForeignApi::class)
private fun attachStatusBarTapDetector(controller: UIViewController) {
    val container = controller.view
    val overlay = UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)).apply {
        translatesAutoresizingMaskIntoConstraints = false
        backgroundColor = UIColor.clearColor
    }
    container.addSubview(overlay)
    NSLayoutConstraint.activateConstraints(
        listOf(
            overlay.topAnchor.constraintEqualToAnchor(container.topAnchor),
            overlay.leadingAnchor.constraintEqualToAnchor(container.leadingAnchor),
            overlay.trailingAnchor.constraintEqualToAnchor(container.trailingAnchor),
            overlay.bottomAnchor.constraintEqualToAnchor(container.safeAreaLayoutGuide.topAnchor),
        ),
    )
    overlay.addGestureRecognizer(
        UITapGestureRecognizer(target = TapForwarder(), action = sel_registerName("onTap:")),
    )
}

private class TapForwarder : NSObject() {
    @ObjCAction
    fun onTap(sender: UITapGestureRecognizer) {
        StatusBarTapBus.taps.tryEmit(Unit)
    }
}
