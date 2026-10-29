package dev.sharpxia.agentreins

import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.UIKit.UIViewController

/** Dev 自动发消息 prompt（AGENTREINS_DEV_AUTOPROMPT；AgentBridge 启动时消费一次）。 */
internal object AutoPromptHolder {
    val value: String? = (NSProcessInfo.processInfo.environment["AGENTREINS_DEV_AUTOPROMPT"] as? String)?.takeIf { it.isNotBlank() }
}

/**
 * SwiftUI 壳入口（feature/ios-swiftui 起 iOS 前端原生重写）：构造 AppModel（沿用 dev
 * 通道语义）并返回 [AgentBridge]。生命周期/网络监听在此安装——Swift 侧不接触这些。
 * Dev 通道与 [MainViewController] 完全同源：AGENTREINS_DEV_SETUPCODE/URL/KEY/SESSION +
 * Documents/dev.json，供模拟器/自动化闭环验证。
 */
fun AgentReinsBridge(): AgentBridge {
    installAppForegroundObserver()
    installNetworkMonitor()
    return AgentBridge(devPrebuilt() ?: AppModel())
}

/** iOS 入口：iosApp 壳工程（SwiftUI）经此桥接 Compose UI。 */
fun MainViewController(): UIViewController {
    installAppForegroundObserver() // 回前台自动重连（AppModel 经 appForegroundEvents 消费）
    installNetworkMonitor() // 网络路径监听（AppModel 经 networkAvailable 响应式重连）
    // dev 模型必须在组合外构造一次：App(prebuilt=…) 的参数表达式每次重组都会重求值，
    // 在 lambda 里构造 AppModel 会产生多实例 + connect 风暴（模拟器实测触发网关限流）
    val devPrebuilt = devPrebuilt()
    // 状态栏回顶 Phase-1 诊断开关（AGENTREINS_DEV_SCROLLPROBE=1）：会话页顶栏出 scrollToItem(0) 测试按钮
    if (NSProcessInfo.processInfo.environment["AGENTREINS_DEV_SCROLLPROBE"] == "1") {
        devPrebuilt?.devScrollProbe = true
    }
    return ComposeUIViewController { App(prebuilt = devPrebuilt) }
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
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun readDevJson(): String? {
    val path = NSHomeDirectory() + "/Documents/dev.json"
    return NSString.stringWithContentsOfFile(
        path,
        encoding = NSUTF8StringEncoding,
        error = null,
    ) as? String
}
