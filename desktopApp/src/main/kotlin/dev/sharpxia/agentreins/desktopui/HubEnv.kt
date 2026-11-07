package dev.sharpxia.agentreins.desktopui

import java.io.File

/**
 * 桌面端 Hub 入口地址的来源与记忆（wp-6）：
 * - 优先环境变量 HUB_BASE（沿用三条验收通道的既有约定），其次上次登录成功记住的地址
 * - 记忆文件落 ~/.agentreins/hub-base-url（与 shared 的 FileCredentialStore/投影库同目录，
 *   沿用仓库既有模式；只存非敏感的入口 URL，不含任何凭据）
 */
internal object HubEnv {
    private val dir: File get() = File(System.getProperty("user.home"), ".agentreins")
    private val file: File get() = File(dir, "hub-base-url")

    /** 登录/恢复用入口地址：环境变量优先（方便探针同机复用），其次上次登录记忆。 */
    fun resolveBaseUrl(): String? = System.getenv("HUB_BASE")?.takeIf { it.isNotBlank() } ?: storedBaseUrl()

    fun storedBaseUrl(): String? =
        runCatching { file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()

    /** 登录提交时记忆入口地址（无论成败——重试时免重输）。 */
    fun rememberBaseUrl(url: String) {
        runCatching {
            dir.mkdirs()
            file.writeText(url.trim())
        }
    }
}
