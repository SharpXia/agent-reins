package dev.sharpxia.agentreins.hub.auth

import kotlin.random.Random

/**
 * refreshToken 安全存储（P1 落三端实现并 expect/actual 化：iOS=Keychain、Android=Keystore、
 * Desktop=~/.agentreins 文件 chmod 600）。accessToken 永不经此存储——仅内存（08 §1.1/§2.1）。
 *
 * v1.6.0 设备身份（2026-11-28）：另承载**安装标识**（installId）——一安装一标识、跨登出/
 * 重登/升级持久（[clear] 只清凭据不清它：登出后重登同一安装须复用同一服务端设备行，
 * 设备清单不再随重登无限增长）。
 */
interface CredentialStore {
    suspend fun saveRefreshToken(token: String)

    suspend fun loadRefreshToken(): String?

    suspend fun clear()

    /**
     * 安装标识读取（v1.6.0）：未生成过 = null。缺省实现不持久（每次进程内现生成）——测试
     * 替身/内存实现零改动；三平台实现须覆盖为持久化，否则同一安装每次冷启动登录都会在
     * 服务端新建设备行。
     */
    suspend fun loadInstallId(): String? = null

    /** 安装标识持久化（首次生成时写一次；幂等覆盖写）。 */
    suspend fun saveInstallId(id: String) {}
}

/**
 * 安装标识（128 位随机 hex，32 字符——契约 installId 形状 `[A-Za-z0-9_-]{16,64}`）：无则
 * 生成并持久。非机密（只作等值匹配，服务端落摘要），用非加密 PRNG 即可——碰撞概率 2^-128。
 */
internal suspend fun CredentialStore.installId(): String {
    loadInstallId()?.takeIf { it.isNotBlank() }?.let { return it }
    val id = generateInstallId()
    saveInstallId(id)
    return id
}

internal fun generateInstallId(): String =
    Random.nextBytes(ByteArray(16)).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
