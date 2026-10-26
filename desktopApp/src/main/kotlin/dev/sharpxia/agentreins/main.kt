package dev.sharpxia.agentreins

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * 桌面壳。默认空模型（用户手动连接）。
 * Dev 直连（桌面端同界面联调取证用）：设 AGENTREINS_DEV_URL（+可选 AGENTREINS_DEV_KEY、
 * AGENTREINS_DEV_SESSION）启动即连接并自动打开指定会话；AGENTREINS_DEV_SHOT=<png路径>
 * 则 20s 后 Robot 自截全屏。凭据与路径全部来自环境变量，不落代码。
 */
fun main() {
    val devUrl = System.getenv("AGENTREINS_DEV_URL")
    val devKey = System.getenv("AGENTREINS_DEV_KEY")
    val prebuilt = devUrl?.let {
        AppModel(url = it, apiKey = devKey.orEmpty()).apply {
            devAutoConnect = true
            devAutoSelectKey = System.getenv("AGENTREINS_DEV_SESSION") ?: "agent:main:main"
        }
    }
    devScreenshot() // application{} 阻塞至退出，取证线程必须先起
    application {
        Window(
            title = "AgentReins",
            onCloseRequest = ::exitApplication,
        ) {
            App(prebuilt)
        }
    }
}

/** 沙箱/CI 无屏幕录制权限时失败并写 <path>.status.txt（stdout 可能被缓冲，用文件做真值）。 */
private fun devScreenshot() {
    val path = System.getenv("AGENTREINS_DEV_SHOT") ?: return
    Thread {
        Thread.sleep(20_000)
        try {
            val size = java.awt.Toolkit.getDefaultToolkit().screenSize
            val img = java.awt.Robot().createScreenCapture(java.awt.Rectangle(size))
            val ok = javax.imageio.ImageIO.write(img, "png", java.io.File(path))
            java.io.File("$path.status.txt").writeText(
                if (ok) "written ${img.width}x${img.height}" else "ImageIO.write returned false",
            )
        } catch (t: Throwable) {
            runCatching {
                java.io.File("$path.status.txt").writeText("failed: ${t::class.simpleName}: ${t.message}")
            }
        }
    }.apply { isDaemon = true }.start()
}
