package dev.sharpxia.agentreins

import androidx.compose.ui.window.ComposeUIViewController
import dev.sharpxia.agentreins.ui.StatusBarTapBus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.CoreGraphics.CGRectMake
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
    val controller = ComposeUIViewController { App() }
    attachStatusBarTapDetector(controller)
    return controller
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
