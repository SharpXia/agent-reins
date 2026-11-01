package dev.sharpxia.agentreins

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.sharpxia.agentreins.hub.HubConfig
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.HubAuthStack
import kotlinx.coroutines.runBlocking

/**
 * 桌面壳。默认空模型（用户手动连接）。
 * Dev 直连（桌面端同界面联调取证用）：设 AGENTREINS_DEV_URL（+可选 AGENTREINS_DEV_KEY、
 * AGENTREINS_DEV_SESSION）启动即连接并自动打开指定会话；AGENTREINS_DEV_SHOT=<png路径>
 * 则 20s 后 Robot 自截全屏。凭据与路径全部来自环境变量，不落代码。
 *
 * `hub-login` 参数：不开窗口，跑 Hub 认证链路验收通道（P1c）后退出，见 [hubLoginProbe]。
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "hub-login") {
        hubLoginProbe()
        return
    }
    val devUrl = System.getenv("AGENTREINS_DEV_URL")
    val devKey = System.getenv("AGENTREINS_DEV_KEY")
    val prebuilt = devUrl?.let {
        AppModel(initialUrl = it, initialApiKey = devKey.orEmpty()).apply {
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

/**
 * Hub 认证链路验收通道（方案 §6-P1 验收判据）。HUB_BASE / HUB_USER / HUB_PASS 来自环境变量，不落代码。
 * 凭据存储用进程内存桩（不碰 ~/.agentreins），测试设备跑完即撤销，不在 Hub 留垃圾设备。
 */
private fun hubLoginProbe() = runBlocking {
    val base = System.getenv("HUB_BASE") ?: error("HUB_BASE 未设（如 http://<HUB-IP>:8080/v1）")
    val user = System.getenv("HUB_USER") ?: error("HUB_USER 未设")
    val pass = System.getenv("HUB_PASS") ?: error("HUB_PASS 未设")
    val mem = object : CredentialStore {
        var v: String? = null
        override suspend fun saveRefreshToken(token: String) { v = token }
        override suspend fun loadRefreshToken(): String? = v
        override suspend fun clear() { v = null }
    }
    val ws = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
    val stack = HubAuthStack(HubConfig(base, ws), credentialStore = mem)

    println("① 登录（会话 Token）…")
    val ok = stack.auth.login(user, pass, null)
    println("   ok user=${ok.user.displayName} tokenType=${ok.tokenType} expiresIn=${ok.expiresIn} mfaRequired=${ok.mfaRequired}")

    println("② AUTH-006 预检：未绑定会话 Token 调 GET /v1/devices…")
    val e6 = runCatching { stack.api.listDevices() }.exceptionOrNull()
    check(e6 is HubException && e6.payload.code == "HUB-AUTH-006") {
        "期待 403 HUB-AUTH-006，实得 ${e6?.message ?: "成功响应（契约破坏）"}"
    }
    println("   ok http=${e6.httpStatus} ${e6.payload.code} x-request-id=${e6.requestId}")

    println("③ 设备注册（会话 Token 升级为绑定 Token，ADR-15）…")
    val reg = stack.auth.registerCurrentDevice("AgentReins P1 probe", "desktop", null, "p1-probe")
    println("   ok deviceId=${reg.deviceId} refreshExpiresIn=${reg.refreshExpiresIn} refreshToken 已入存储=${mem.v != null}")

    println("④ 同一 accessToken 现在可调设备清单（无需重新登录）…")
    val devices = stack.auth.withBearer { stack.api.listDevices() }
    val me = devices.devices.firstOrNull { it.deviceId == reg.deviceId } ?: error("设备清单不含刚注册的设备")
    println("   ok 共 ${devices.devices.size} 台，本机 current=${me.current} platform=${me.platform}")

    println("⑤ 新实例仅凭安全存储的 refreshToken 静默恢复（refresh 一次一用轮换）…")
    val before = mem.v
    val stack2 = HubAuthStack(HubConfig(base, ws), credentialStore = mem)
    check(stack2.auth.restoreFromStoredRefreshToken()) { "静默恢复失败" }
    check(stack2.auth.currentDeviceId == reg.deviceId) { "deviceId 未延续" }
    check(mem.v != null && mem.v != before) { "refreshToken 未轮换" }
    println("   ok deviceId 延续=${stack2.auth.currentDeviceId}，refreshToken 已轮换")

    println("⑥ 旧 refreshToken 重放（超 60s 宽限前立即重放应幂等返回同一新对；此处验证服务端不报 5xx）…")
    val replay = runCatching { stack2.api.refresh(dev.sharpxia.agentreins.hub.auth.RefreshRequest(before!!)) }
    println("   ${replay.fold({ "幂等返回 deviceId=${it.deviceId}" }, { "拒绝：${(it as? HubException)?.payload?.code ?: it.message}" })}")

    println("⑦ 清理：撤销测试设备（DELETE /v1/devices/{id} → 204）…")
    stack2.auth.withBearer { stack2.api.deleteDevice(reg.deviceId) }
    println("   ok")

    println("⑧ 撤销后在途请求应 403 HUB-AUTH-004…")
    val e4 = runCatching { stack2.api.listDevices() }.exceptionOrNull()
    println("   ${(e4 as? HubException)?.let { "http=${it.httpStatus} ${it.payload.code}" } ?: (e4?.message ?: "成功响应（异常）")}")

    println("PASS: Hub 认证两步流 + AUTH-006 + 设备绑定 + 静默恢复轮换 + 撤销 全链路通过")
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
