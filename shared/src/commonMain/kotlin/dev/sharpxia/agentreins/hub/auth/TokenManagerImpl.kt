package dev.sharpxia.agentreins.hub.auth

import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.HubErrorCode
import dev.sharpxia.agentreins.hub.HubException
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [TokenManager] 实现（P1）。accessToken 只在内存；refreshToken 只经 [CredentialStore]。
 *
 * 单飞（single-flight）落法：并发撞 HUB-AUTH-002 的请求都带着各自失败时的 stale token 进
 * [refreshSingleFlight]，互斥区内先比对——内存 token 已不等于 stale 说明别的航班刚刷过，直接复用，
 * 不再发第二次 refresh（否则旧 refreshToken 已轮换失效，第二次必撞 AUTH-003 把整台设备登出）。
 */
class DefaultTokenManager(
    private val api: HubApi,
    private val store: CredentialStore,
    /** HUB-AUTH-004 设备已撤销：契约要求清全部本地数据并停止自动重试——数据层清理由调用方注入（P2 接 DB）。 */
    private val onDeviceRevoked: suspend () -> Unit = {},
) : TokenManager {

    private val refreshMutex = Mutex()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var deviceId: String? = null

    /** 当前绑定的 deviceId（登录未注册设备时为 null）。 */
    val currentDeviceId: String? get() = deviceId

    override suspend fun login(username: String, password: String, mfaCode: String?): LoginOk {
        val ok = api.login(LoginRequest(username = username, password = password, mfaCode = mfaCode))
        accessToken = ok.accessToken
        return ok
    }

    override suspend fun registerCurrentDevice(
        displayName: String,
        platform: String,
        model: String?,
        appVersion: String?,
    ): DeviceRegistrationOk {
        val ok = withBearer {
            api.registerDevice(DeviceRegistrationRequest(displayName, platform, model, appVersion))
        }
        deviceId = ok.deviceId
        // refreshToken 仅此一次下发，立即落安全存储；会话 accessToken 原值已升级为绑定 Token（ADR-15），不换
        store.saveRefreshToken(ok.refreshToken)
        return ok
    }

    override suspend fun restoreFromStoredRefreshToken(): Boolean {
        if (store.loadRefreshToken() == null) return false
        return try {
            refreshSingleFlight(stale = accessToken)
            true
        } catch (e: HubException) {
            // AUTH-003/004 已在 refreshSingleFlight 内清凭证；对调用方表现为「需重新登录」
            if (e.code == HubErrorCode.AUTH_003 || e.code == HubErrorCode.AUTH_004) false else throw e
        }
    }

    override suspend fun <T> withBearer(block: suspend (accessToken: String) -> T): T {
        val token = accessToken ?: run {
            if (!restoreFromStoredRefreshToken()) throw HubException.notLoggedIn()
            checkNotNull(accessToken)
        }
        return try {
            block(token)
        } catch (e: HubException) {
            when (e.code) {
                // 过期可刷新：单飞刷新后重试原请求一次（08 §7）
                HubErrorCode.AUTH_002 -> block(refreshSingleFlight(stale = token))
                HubErrorCode.AUTH_004 -> {
                    clearCredentials()
                    onDeviceRevoked()
                    throw e
                }
                else -> throw e
            }
        }
    }

    override fun currentAccessToken(): String? = accessToken

    override suspend fun clearCredentials() {
        accessToken = null
        deviceId = null
        store.clear()
    }

    /** 互斥刷新；返回可用 accessToken。[stale] = 调用方失败时手里的 token（null = 没有）。 */
    private suspend fun refreshSingleFlight(stale: String?): String = refreshMutex.withLock {
        val current = accessToken
        if (current != null && current != stale) return@withLock current
        val refreshToken = store.loadRefreshToken() ?: throw HubException.notLoggedIn()
        try {
            val ok = api.refresh(RefreshRequest(refreshToken))
            // 先落新 refreshToken（旧值已被服务端轮换失效），再切内存 accessToken
            store.saveRefreshToken(ok.refreshToken)
            deviceId = ok.deviceId
            accessToken = ok.accessToken
            ok.accessToken
        } catch (e: HubException) {
            when (e.code) {
                HubErrorCode.AUTH_003 -> {
                    clearCredentials()
                    throw e
                }
                HubErrorCode.AUTH_004 -> {
                    clearCredentials()
                    onDeviceRevoked()
                    throw e
                }
                else -> throw e
            }
        }
    }
}
