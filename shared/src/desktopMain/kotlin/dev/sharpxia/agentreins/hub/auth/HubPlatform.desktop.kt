package dev.sharpxia.agentreins.hub.auth

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

actual fun hubDevicePlatform(): String = "desktop"

actual fun hubDeviceModel(): String? = null // 桌面 displayName 为用户自填；机型面 JVM 无直取 API，保持未上报（读面 omitempty）

actual fun platformCredentialStore(): CredentialStore = FileCredentialStore()

/** Desktop 凭据存储：~/.agentreins/hub-refresh-token 文件，POSIX 权限 600（沿用仓库既有模式）。 */
private class FileCredentialStore : CredentialStore {
    private val file: File =
        File(File(System.getProperty("user.home"), ".agentreins").apply { mkdirs() }, "hub-refresh-token")

    override suspend fun saveRefreshToken(token: String) {
        file.writeText(token)
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    override suspend fun loadRefreshToken(): String? =
        file.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

    override suspend fun clear() {
        file.delete()
    }
}
