package dev.sharpxia.agentreins

import androidx.compose.ui.window.ComposeUIViewController
import dev.sharpxia.agentreins.ui.StatusBarTapBus
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.darwin.NSObject
import platform.UIKit.NSLayoutConstraint
import platform.UIKit.UIColor
import platform.UIKit.UIScrollView
import platform.UIKit.UIScrollViewDelegateProtocol
import platform.UIKit.UIView
import platform.UIKit.UIViewController
import platform.UIKit.UITapGestureRecognizer
import platform.objc.sel_registerName
import kotlinx.cinterop.useContents

/** iOS 入口：iosApp 壳工程（SwiftUI）经此桥接 Compose UI。 */
fun MainViewController(): UIViewController {
    installAppForegroundObserver() // 回前台自动重连（AppModel 经 appForegroundEvents 消费）
    installNetworkMonitor() // 网络路径监听（AppModel 经 networkAvailable 响应式重连）
    // dev 模型必须在组合外构造一次：App(prebuilt=…) 的参数表达式每次重组都会重求值，
    // 在 lambda 里构造 AppModel 会产生多实例 + connect 风暴（模拟器实测触发网关限流）
    val devPrebuilt = devPrebuilt()
    val controller = ComposeUIViewController { App(prebuilt = devPrebuilt) }
    attachStatusBarTapDetector(controller)
    return controller
}

/**
 * 桌面壳同款 dev 直连入口（desktopApp/main.kt 镜像）：AGENTREINS_DEV_SETUPCODE（配对路径，openclaw qr --setup-code-only 产物）或
 * AGENTREINS_DEV_URL（+可选 KEY/SESSION，需设备已配对）。模拟器/自动化验证用：
 * `SIMCTL_CHILD_* xcrun simctl launch` 注入，不出任何 UI 面。
 */
private fun devPrebuilt(): AppModel? {
    val env = NSProcessInfo.processInfo.environment
    val session = env["AGENTREINS_DEV_SESSION"] as? String
    // 通道 1：SIMCTL_CHILD_* 环境变量（真机/CI）
    (env["AGENTREINS_DEV_SETUPCODE"] as? String)?.takeIf { it.isNotBlank() }?.let { code ->
        return devModel(setupCode = code, session = session)
    }
    (env["AGENTREINS_DEV_URL"] as? String)?.takeIf { it.isNotBlank() }?.let { url ->
        return devModel(url = url, key = env["AGENTREINS_DEV_KEY"] as? String ?: "", session = session)
    }
    // 通道 2：沙盒 Documents/dev.json（模拟器验证：宿主机直接写容器，env 注入不通时的兜底）
    val raw = readDevJson()
    raw?.let { json ->
        val obj = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return@let
        fun str(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
        str("setupCode")?.let { return devModel(setupCode = it, session = str("session")) }
        str("url")?.let { return devModel(url = it, key = str("key") ?: "", session = str("session")) }
    }
    return null
}

private fun devModel(setupCode: String? = null, url: String? = null, key: String = "", session: String?): AppModel =
    if (setupCode != null) {
        AppModel().apply { this.setupCode = setupCode }
    } else {
        AppModel(initialUrl = url ?: "", initialApiKey = key)
    }.apply {
        devAutoConnect = true
        devAutoSelectKey = session
    }.also { model ->
        // AppModel 的 init 在构造期已跑完，此时才设 devAutoConnect 不会触发 init 里的
        // 自动连接（setupCode 是构造后才写入的 var）——显式补一发，busy 守卫防重入
        model.connect()
    }

/** 读 Documents/dev.json（不存在返回 null）。 */
@OptIn(ExperimentalForeignApi::class)
private fun readDevJson(): String? {
    val path = NSHomeDirectory() + "/Documents/dev.json"
    return NSString.stringWithContentsOfFile(
        path,
        encoding = NSUTF8StringEncoding,
        error = null,
    ) as? String
}

/**
 * 状态栏点击探测（CMP 未内置，YouTrack CMP-4187）。双通道（真机反馈⑤-2）：
 * ① 系统 scrollsToTop 通道（主通道）——系统状态栏层会吞掉原始触摸，自绘 overlay
 *    永远收不到点按；系统改为对「窗口内唯一 scrollsToTop 的 UIScrollView」执行
 *    scroll-to-top。铺一个 1pt 高的隐藏 UIScrollView（初始 offset=1），系统回顶时
 *    offset 动画 1→0 触发 delegate → 总线。userInteractionEnabled=false 不挡任何触摸。
 * ② 自绘 overlay + 手势（历史通道，模拟器/系统不消费区域的环境兜底）。
 */
@OptIn(ExperimentalForeignApi::class)
private fun attachStatusBarTapDetector(controller: UIViewController) {
    val container = controller.view

    // 通道①：系统 scroll-to-top 探针
    val statusScroll = UIScrollView(
        frame = CGRectMake(0.0, 0.0, container.bounds.useContents { size.width }, 1.0),
    ).apply {
        translatesAutoresizingMaskIntoConstraints = false
        scrollsToTop = true
        showsVerticalScrollIndicator = false
        showsHorizontalScrollIndicator = false
        userInteractionEnabled = false
        backgroundColor = UIColor.clearColor
        setContentSize(CGSizeMake(0.0, 2.0))
        setContentOffset(CGPointMake(0.0, 1.0))
    }
    statusScroll.setDelegate(statusTapDelegate)
    container.addSubview(statusScroll)
    NSLayoutConstraint.activateConstraints(
        listOf(
            statusScroll.topAnchor.constraintEqualToAnchor(container.topAnchor),
            statusScroll.leadingAnchor.constraintEqualToAnchor(container.leadingAnchor),
            statusScroll.trailingAnchor.constraintEqualToAnchor(container.trailingAnchor),
            statusScroll.heightAnchor.constraintEqualToConstant(1.0),
        ),
    )

    // 通道②：自绘 overlay（兜底）
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

/** 系统 scroll-to-top → 总线（offset 动画 1→0 触发；随即复位到 1 备下次）。
 *  顶层强引用（review R1-P0）：UIKit delegate 是弱引用，临时对象秒被释放、回调永不触发。 */
@OptIn(ExperimentalForeignApi::class)
private class StatusTapScrollDelegate : NSObject(), UIScrollViewDelegateProtocol {
    override fun scrollViewDidScroll(scrollView: UIScrollView) {
        val y = scrollView.contentOffset.useContents { this.y } ?: 0.0
        if (y < 0.9) {
            platform.Foundation.NSLog("AgentReins: status-bar scroll-to-top (system) -> bus")
            StatusBarTapBus.taps.tryEmit(Unit)
            scrollView.setContentOffset(CGPointMake(0.0, 1.0))
        }
    }
}

// delegate 弱引用存储：必须在 Kotlin 侧持有强引用，否则语句结束即 dealloc（R1-P0）
private val statusTapDelegate = StatusTapScrollDelegate()

private class TapForwarder : NSObject() {
    @ObjCAction
    fun onTap(sender: UITapGestureRecognizer) {
        platform.Foundation.NSLog("AgentReins: status-bar tap detected -> bus")
        StatusBarTapBus.taps.tryEmit(Unit)
    }
}
