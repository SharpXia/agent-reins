package dev.sharpxia.agentreins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.openclaw.ConnectionStore
import dev.sharpxia.agentreins.openclaw.GatewayRpcException
import dev.sharpxia.agentreins.openclaw.OpenClawClient
import dev.sharpxia.agentreins.openclaw.StoredConnection
import dev.sharpxia.agentreins.openclaw.parsePairingSetupCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 连接配置 + 网关客户端生命周期 + 会话/对话流状态。
 * M2：对话流为单一数据源（ConversationState），发送即乐观回显、流式归并、终局回填历史。
 */
class AppModel(
    /** Dev 直连预填（desktop 壳的 AGENTREINS_DEV_* 入口）；普通构造留空，走连接页。 */
    url: String = "",
    apiKey: String = "",
) {
    var url by mutableStateOf(url)
    var apiKey by mutableStateOf(apiKey)
    /** Dev 模式：构造后立即连接（desktop 壳）。 */
    var devAutoConnect = false
    /** Dev 模式：会话列表就绪后自动打开该会话。 */
    var devAutoSelectKey: String? = null
    /** 扫码/粘贴得到的 setup code（oc-pair://…）；非空时连接走扫码配对流程。 */
    var setupCode by mutableStateOf("")
    /** 备用网关地址（QR urls 字段 / 上次连接持久化），多地址 failover 候选（remote-access-design.md §6）。 */
    var alternateUrls by mutableStateOf<List<String>>(emptyList())
    /** 实际连上的地址（可能与主地址不同——failover 命中备用时），连接页显示来源标签用。 */
    var connectedUrl by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<GatewayStatus>(GatewayStatus.Disconnected)
        private set
    var sessions by mutableStateOf<List<Session>>(emptyList())
        private set
    var selectedSession by mutableStateOf<String?>(null)
        private set
    /** 当前选中会话的对话流状态（聊天页唯一数据源）。 */
    var conversation by mutableStateOf<ConversationState?>(null)
        private set
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
    /** 本机已有配对设备凭证（deviceToken），API Key 可留空直连。 */
    var hasPairedCredential by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: OpenClawClient? = null
    private var deviceToken: String? = null
    private var statusJob: Job? = null
    private var sessionsJob: Job? = null
    private var conversationJob: Job? = null
    /** 用户主动断开时置位，回前台不自动重连；connect() 成功路径清零。 */
    private var explicitDisconnect = false
    /** 向上翻页加载中去重（防 scroll 事件连发重复请求）。 */
    private var loadingOlder = false

    init {
        // 记住上次连接：预填地址与 Key；deviceToken 是扫码配对成功后的免 token 凭证
        runCatching { ConnectionStore.load() }.getOrNull()?.let { stored ->
            if (this.url.isBlank()) this.url = stored.url
            if (this.apiKey.isBlank()) this.apiKey = stored.apiKey
            alternateUrls = stored.urls
            deviceToken = stored.deviceToken
            hasPairedCredential = stored.deviceToken != null
        }
        // iOS 进后台 WS 必死：回前台自动用已存配置重连（显式断开除外）
        scope.launch {
            appForegroundEvents.collect {
                delay(800) // 等 resume 后 WS 错误态落地再判断
                if (!explicitDisconnect && !busy) {
                    val st = status
                    val connectedLike = st is GatewayStatus.Connected ||
                        st is GatewayStatus.Connecting || st is GatewayStatus.PairingPending
                    if (!connectedLike && url.isNotBlank() && (deviceToken != null || apiKey.isNotBlank())) {
                        connect()
                    }
                }
            }
        }
        // 已有可用配置（deviceToken 免扫码 / 共享 Key）则启动即连——不再要求每次手动点连接
        if (url.isNotBlank() && (deviceToken != null || apiKey.isNotBlank())) connect()
        if (devAutoConnect) connect() // dev 通道重复调用被 busy 守卫挡掉
    }

    /** 向上翻到顶时懒加载更早一页历史（hasMoreHistory / 加载中去重）。 */
    fun loadOlderMessages() {
        val key = selectedSession ?: return
        if (loadingOlder) return
        val c = client ?: return
        val st = conversation ?: return
        if (st.entries.isEmpty() || !st.hasMoreHistory) return
        loadingOlder = true
        scope.launch {
            try {
                c.loadHistoryOlder(key)
            } catch (e: Exception) {
                lastError = e.message
            } finally {
                loadingOlder = false
            }
        }
    }

    fun connect() {
        if (busy) return
        val code = setupCode.trim()
        // 多地址 failover（remote-access-design.md §6）：候选按序 [主地址, *备用地址]，
        // 传输层失败（超时/DNS/TLS）才轮换下一候选；RPC 拒绝说明地址可达，不轮换。
        val candidates: List<String>
        val apiKeyForConfig: String
        val bootstrapToken: String?
        when {
            code.isNotEmpty() -> try {
                val setup = parsePairingSetupCode(code)
                url = setup.url
                alternateUrls = setup.urls
                candidates = (listOf(setup.url) + setup.urls).distinct()
                bootstrapToken = setup.bootstrapToken
                apiKeyForConfig = ""
            } catch (e: IllegalArgumentException) {
                lastError = e.message
                return
            }
            url.isNotBlank() && apiKey.isNotBlank() -> {
                candidates = (listOf(url.trim()) + alternateUrls).distinct()
                bootstrapToken = null
                apiKeyForConfig = apiKey.trim()
            }
            url.isNotBlank() && deviceToken != null -> {
                candidates = (listOf(url.trim()) + alternateUrls).distinct()
                bootstrapToken = null
                apiKeyForConfig = ""
            }
            else -> {
                lastError = "填写网关地址与 API Key，或扫码配对"
                return
            }
        }
        busy = true
        lastError = null
        explicitDisconnect = false
        connectedUrl = null
        // 旧客户端先收尾（重连场景）：WS 任务与 HttpClient 不能泄漏
        val old = client
        if (old != null) scope.launch { runCatching { old.disconnect() } }
        val c = OpenClawClient(scope)
        client = c
        // collector 必须在 connect() 前挂上：PairingPending 等中间状态发生在 connect() 返回之前
        statusJob?.cancel()
        sessionsJob?.cancel()
        statusJob = scope.launch { c.status.collect { status = it } }
        sessionsJob = scope.launch {
            c.sessions.collect { list ->
                sessions = list
                // Dev 直连：列表就绪后自动打开目标会话
                val auto = devAutoSelectKey
                if (auto != null && selectedSession == null) {
                    list.firstOrNull { it.id == auto }?.let { select(it) }
                }
            }
        }
        scope.launch {
            try {
                var lastTransportError: Exception? = null
                var successUrl: String? = null
                for ((index, candidate) in candidates.withIndex()) {
                    val config = GatewayConfig(
                        baseUrl = candidate,
                        apiKey = apiKeyForConfig,
                        bootstrapToken = bootstrapToken,
                        deviceToken = if (bootstrapToken == null) deviceToken else null,
                    )
                    try {
                        // connect() 入口自清理，同一 client 逐候选重试安全
                        c.connect(config)
                        successUrl = candidate
                        break
                    } catch (e: GatewayRpcException) {
                        throw e // 配对/鉴权等协议拒绝：地址已可达，换地址无意义
                    } catch (e: Exception) {
                        lastTransportError = e
                        if (index < candidates.lastIndex) {
                            lastError = "地址 ${candidate} 连接失败（${e.message ?: e::class.simpleName}），尝试备用地址…"
                        }
                    }
                }
                val connected = successUrl
                    ?: throw (lastTransportError ?: IllegalStateException("connect failed"))
                connectedUrl = connected
                // 扫码/连接成功即记住本次配置与设备凭证（M1 存 Documents，M3 迁 Keychain）
                // 主地址保持用户输入/QR 主地址（到家优先 LAN），备用列表原样保存
                deviceToken = c.deviceToken ?: deviceToken
                hasPairedCredential = deviceToken != null
                runCatching {
                    ConnectionStore.save(
                        StoredConnection(
                            url = url.trim(),
                            apiKey = apiKey.trim(),
                            deviceToken = deviceToken,
                            urls = alternateUrls,
                        ),
                    )
                }
                setupCode = ""
                // 恢复上次浏览的会话（重连场景）
                selectedSession?.let { openConversation(c, it) }
            } catch (e: GatewayRpcException) {
                lastError = e.message
                status = GatewayStatus.Error(e.message)
            } catch (e: Exception) {
                lastError = e.message
                status = GatewayStatus.Error(e.message)
            } finally {
                busy = false
            }
        }
    }

    /** 扫码结果入口：拿到 setup code 即自动发起配对连接。 */
    fun onQrScan(payload: String?) {
        if (payload.isNullOrBlank()) return
        setupCode = payload
        connect()
    }

    fun select(session: Session?) {
        selectedSession = session?.id
        conversation = null
        conversationJob?.cancel()
        conversationJob = null
        val c = client ?: return
        session ?: return
        openConversation(c, session.id)
    }

    private fun openConversation(c: OpenClawClient, sessionKey: String) {
        conversationJob?.cancel()
        conversationJob = scope.launch {
            val flow = c.conversation(sessionKey)
            runCatching { c.loadHistory(sessionKey) }
                .onFailure { lastError = it.message }
            flow.collect { conversation = it }
        }
    }

    fun send() {
        val key = selectedSession ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        val c = client ?: return
        draft = ""
        scope.launch {
            try {
                c.send(key, text)
            } catch (e: Exception) {
                lastError = e.message
            }
        }
    }

    fun disconnect() {
        explicitDisconnect = true
        connectedUrl = null
        scope.launch { client?.disconnect() }
        selectedSession = null
        conversation = null
        conversationJob?.cancel()
        conversationJob = null
        sessions = emptyList()
    }
}
