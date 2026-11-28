package dev.sharpxia.agentreins.hub.auth

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

actual fun hubDevicePlatform(): String = "desktop"

actual fun hubDeviceModel(): String? = null // 桌面 displayName 为用户自填；机型面 JVM 无直取 API，保持未上报（读面 omitempty）

actual fun platformCredentialStore(): CredentialStore = FileCredentialStore()

/**
 * Desktop 凭据存储：~/.agentreins/hub-refresh-token 文件，POSIX 权限 600（沿用仓库既有模式）。
 * v1.6.0 安装标识（2026-11-28）：~/.agentreins/hub-install-id 独立文件（非机密，权限同目录
 * 既有口径）；[clear]（登出）只删凭据文件，安装标识跨登出/重登/升级存活。
 */
private class FileCredentialStore : CredentialStore {
    private val dir: File = File(System.getProperty("user.home"), ".agentreins").apply { mkdirs() }
    private val file: File = File(dir, "hub-refresh-token")
    private val installIdFile: File = File(dir, "hub-install-id")

    override suspend fun saveRefreshToken(token: String) {
        file.writeText(token)
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    override suspend fun loadRefreshToken(): String? =
        file.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    override suspend fun loadInstallId(): String? =
        installIdFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    override suspend fun saveInstallId(id: String) {
        installIdFile.writeText(id)
    }

    override suspend fun clear() {
        // 登出只清凭据，不清安装标识（同安装重登复用同一服务端设备行）。
        file.delete()
    }
}
