package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.HubAuthStack
import dev.sharpxia.agentreins.hub.auth.hubDevicePlatform
import dev.sharpxia.agentreins.hub.auth.platformCredentialStore
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Hub 账号态（UI 呈现用；kind 字符串经桥扁平化给 Swift）。 */
sealed interface HubAuthState {
    /** 未登录/未开始。 */
    data object Idle : HubAuthState

    /** 在途（登录/注册/恢复/登出中）。 */
    data object Working : HubAuthState

    /** 需要登录（首启、AUTH-003 清凭证后、主动登出、登录失败带 reason）。 */
    data class NeedsLogin(val reason: String?) : HubAuthState

    /** 已登录（P1：仅认证面；数据面随 P2 接同步引擎）。 */
    data class LoggedIn(val userDisplay: String?, val deviceId: String?) : HubAuthState
}

/**
 * Hub 登录编排（P1 面向 iOS 桥与 desktop 验收通道；不依赖 Compose/AppModel，P2/P3 并入 HubClient 门面）。
 * baseUrl 由调用方传入（iOS 存 UserDefaults，desktop 走环境变量），不落代码与打包配置。
 */
class HubRuntime(
    private val scope: CoroutineScope,
    private val credentialStore: CredentialStore = platformCredentialStore(),
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow<HubAuthState>(HubAuthState.Idle)
    val state: StateFlow<HubAuthState> = _state.asStateFlow()

    @Volatile
    private var stack: HubAuthStack? = null

    /** 当前生效的 baseUrl（restore/login 时记录）。 */
    @Volatile
    var baseUrl: String? = null
        private set

    /**
     * 最近一次登录时的 AUTH-006 契约预检判定（验收取证用，不阻断登录）：
     * 期望 = 未绑定的会话 Token 调 GET /v1/devices 得 403 HUB-AUTH-006（hub-api-v1.yaml:110-111 ADR-15）。
     */
    @Volatile
    var auth006Verdict: String? = null
        private set

    /** 已组装的认证栈（登录/恢复成功后非空），供上层直接调 api/auth。 */
    val authStack: HubAuthStack? get() = stack

    /** 启动恢复：安全存储有 refreshToken → 静默刷新 → LoggedIn；否则 NeedsLogin。 */
    fun restore(baseUrl: String) {
        this.baseUrl = baseUrl
        _state.value = HubAuthState.Working
        scope.launch {
            try {
                mutex.withLock {
                    val s = buildStack(baseUrl)
                    _state.value = if (s.auth.restoreFromStoredRefreshToken()) {
                        HubAuthState.LoggedIn(userDisplay = null, deviceId = s.auth.currentDeviceId)
                    } else {
                        HubAuthState.NeedsLogin(reason = null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = HubAuthState.NeedsLogin(reason = e.message ?: e::class.simpleName)
            }
        }
    }

    /**
     * 登录两步流：login（会话 Token）→ AUTH-006 预检（记录判定）→ registerDevice（绑定 + refreshToken 落安全存储）→ LoggedIn。
     * 失败经 [state] 呈现 NeedsLogin(reason)，不抛出。
     */
    fun login(baseUrl: String, username: String, password: String, deviceName: String) {
        this.baseUrl = baseUrl
        _state.value = HubAuthState.Working
        scope.launch {
            try {
                mutex.withLock {
                    val s = buildStack(baseUrl)
                    val ok = s.auth.login(username = username, password = password, mfaCode = null)
                    auth006Verdict = probeAuth006(s)
                    val reg = s.auth.registerCurrentDevice(
                        displayName = deviceName.ifBlank { "AgentReins " + hubDevicePlatform() },
                        platform = hubDevicePlatform(),
                        model = null,
                        appVersion = null,
                    )
                    _state.value = HubAuthState.LoggedIn(userDisplay = ok.user.displayName, deviceId = reg.deviceId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = HubAuthState.NeedsLogin(reason = e.message ?: e::class.simpleName)
            }
        }
    }

    /** 登出 = 撤销当前设备（Token 即时失效、服务端关 WSS）+ 清全部本地凭据（08 §2.6）。 */
    fun logout() {
        _state.value = HubAuthState.Working
        scope.launch {
            val s = stack
            try {
                s?.auth?.currentDeviceId?.let { id -> runCatching { s.api.deleteDevice(id) } }
            } finally {
                s?.auth?.clearCredentials()
                stack = null
                _state.value = HubAuthState.NeedsLogin(reason = null)
            }
        }
    }

    /**
     * AUTH-006 契约预检（登录后、注册前的判定记录，不阻断登录流）。实测服务端：
     * devices 清单挂 bearer 放行，AUTH-006 只拦设备必需端点（gateways/conversations/sync/命令类），
     * 故预检端点取 GET /v1/gateways。
     */
    private suspend fun probeAuth006(s: HubAuthStack): String {
        val error = runCatching { s.api.gateways() }.exceptionOrNull()
        return when {
            error == null -> "unexpected: 未绑定会话 Token 竟能调 GET /v1/gateways（期待 403 HUB-AUTH-006）"
            error is HubException && error.payload.code == "HUB-AUTH-006" ->
                "ok: 403 HUB-AUTH-006 (x-request-id=${error.requestId})"
            else -> "unexpected: ${error.message}"
        }
    }

    private fun buildStack(baseUrl: String): HubAuthStack {
        stack?.let { return it }
        val normalized = normalizeBaseUrl(baseUrl)
        return HubAuthStack(
            config = HubConfig(baseUrl = normalized, wsUrl = wsUrlOf(normalized)),
            credentialStore = credentialStore,
        ).also { stack = it }
    }

    private fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().removeSuffix("/")
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "Hub 地址须以 http:// 或 https:// 开头：$raw"
        }
        return trimmed
    }

    private fun wsUrlOf(baseUrl: String): String =
        baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
}
