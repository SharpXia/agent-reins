package dev.sharpxia.agentreins.hub.auth

/**
 * 认证两步流 + 静默刷新（08 §2.2–2.4、ADR-15；P1 实现）。
 *
 * - 第①步 login → 会话级 accessToken（2h，未绑定设备）；第②步 registerCurrentDevice → 绑定 Token + 设备 refreshToken
 * - 已有持久 refreshToken 的设备跳过登录，[restoreFromStoredRefreshToken] 静默刷新起步
 * - 静默刷新必须单飞（single-flight）：同一设备同一时刻至多一个在途 refresh——refreshToken 一次一用轮换 +
 *   轮换后仅 60s 幂等宽限，不收敛的并发刷新会撞 HUB-AUTH-003 导致整台设备被无故登出（08 §2.2 要点 4）
 */
interface TokenManager {
    suspend fun login(username: String, password: String, mfaCode: String? = null): LoginOk

    suspend fun registerCurrentDevice(
        displayName: String,
        platform: String,
        model: String? = null,
        appVersion: String? = null,
    ): DeviceRegistrationOk

    /** 从安全存储恢复 refreshToken 并静默刷新；无凭据或 HUB-AUTH-003 时返回 false（调用方引导重新登录）。 */
    suspend fun restoreFromStoredRefreshToken(): Boolean

    /**
     * 携 Bearer 执行 [block]；撞 HUB-AUTH-002 时单飞刷新后重试原请求**一次**。
     * HUB-AUTH-003 → 清凭证抛出；HUB-AUTH-004（设备已撤销）→ 清全部本地数据、停止自动重试后抛出。
     */
    suspend fun <T> withBearer(block: suspend (accessToken: String) -> T): T

    fun currentAccessToken(): String?

    /** 清内存 accessToken 与安全存储 refreshToken（本地登出；设备撤销走 api.deleteDevice）。 */
    suspend fun clearCredentials()
}
