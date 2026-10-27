package dev.sharpxia.agentreins

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.sharpxia.agentreins.db.CacheStore
import dev.sharpxia.agentreins.db.SqlCacheStore
import dev.sharpxia.agentreins.db.createAppDriver
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.openclaw.ConnectionStore
import dev.sharpxia.agentreins.openclaw.GatewayRpcException
import dev.sharpxia.agentreins.openclaw.OpenClawClient
import dev.sharpxia.agentreins.openclaw.Outbox
import dev.sharpxia.agentreins.openclaw.SessionOverlay
import dev.sharpxia.agentreins.openclaw.StoredConnection
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.ThemeMode
import dev.sharpxia.agentreins.crypto.secureRandomBytes
import dev.sharpxia.agentreins.openclaw.epochMs
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
 * M2.5：冷启动先读缓存（断网可见上次列表）、草稿持久化（kv）、未读计数联动、
 *       发送走 outbox 队列（离线排队、重连自动补发）。
 */
class AppModel(
    /** Dev 直连预填（desktop 壳的 AGENTREINS_DEV_* 入口）；普通构造留空，走连接页。
     *  命名注意：不能叫 url/apiKey——无 val/var 的构造参数会在 init 块与属性初始化器
     *  作用域内遮蔽同名属性，init 的自动连接判据将永远读到空串（冷启动失联的根因）。 */
    initialUrl: String = "",
    initialApiKey: String = "",
) {
    var url by mutableStateOf(initialUrl)
    var apiKey by mutableStateOf(initialApiKey)
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
    /** 当前会话发送队列待补发条数，聊天页显示排队提示。 */
    var queuedCount by mutableStateOf(0)
        private set
    /** 当前会话的 PENDING 待发命令（聊天页「未发出」气泡 + 重试/删除 UI）。 */
    var pendingOutbox by mutableStateOf<List<Outbox.Command>>(emptyList())
        private set
    var selectedSession by mutableStateOf<String?>(null)
        private set
    /** 当前选中会话的对话流状态（聊天页唯一数据源）。 */
    var conversation by mutableStateOf<ConversationState?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
    /** 本机已有配对设备凭证（deviceToken），API Key 可留空直连。 */
    var hasPairedCredential by mutableStateOf(false)
        private set

    // ---- M3.1：Agent 本地注册表（名称/身份色，kv 持久化；协议无 agent 写入面） ----
    var agentProfiles by mutableStateOf<Map<String, AgentProfile>>(emptyMap())
        private set

    // ---- M3.1：外观偏好（跟随系统/亮/暗，kv 持久化） ----
    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set

    /** 草稿（per-session 持久化到 kv，500ms 去抖）。 */
    private val draftState = mutableStateOf("")
    var draft: String
        get() = draftState.value
        set(value) {
            draftState.value = value
            scheduleDraftPersist()
        }

    /** 本地缓存（M2.5 数据基座）。driver 不可用时 Noop 降级，全部能力退化为无缓存。 */
    private val cache: CacheStore = SqlCacheStore.open(createAppDriver())
    private val gatewayId = SqlCacheStore.DEFAULT_GATEWAY_ID

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var client: OpenClawClient? = null
    private var deviceToken: String? = null
    private var statusJob: Job? = null
    private var sessionsJob: Job? = null
    private var activityJob: Job? = null
    private var outboxJob: Job? = null
    private var conversationJob: Job? = null
    private var draftPersistJob: Job? = null
    /** 用户主动断开时置位，回前台不自动重连；connect() 成功路径清零。 */
    private var explicitDisconnect = false
    /** 向上翻页加载中去重（防 scroll 事件连发重复请求）。 */
    private var loadingOlder = false

    // ---- 会话列表本地 overlay（M2.6：pinned/archived 本地显式状态，网关同步留 M3+） ----
    // mutableStateOf：collector 在 Default 线程写、UI 线程读写，走 snapshot 保证可见性
    /** 网关/缓存视图（未叠 overlay 的原始列表）。 */
    private var rawSessions by mutableStateOf<List<Session>>(emptyList())
    /** 本地覆盖层（kv 持久化，跨重启）。 */
    private var sessionOverlay by mutableStateOf(emptyMap<String, SessionOverlay.Entry>())

    /** overlay 叠加后发布到 UI 状态（网关刷新与本地操作共用同一出口）。 */
    private fun publishSessions() {
        sessions = SessionOverlay.apply(rawSessions, sessionOverlay)
    }

    /** 归档/置顶（本地即时生效 + kv 持久化；离线可用）。 */
    fun setSessionArchived(session: Session, archived: Boolean) {
        sessionOverlay = SessionOverlay.toggled(sessionOverlay, session.id, archived = archived)
        scheduleOverlayPersist()
        publishSessions()
    }

    fun setSessionPinned(session: Session, pinned: Boolean) {
        sessionOverlay = SessionOverlay.toggled(sessionOverlay, session.id, pinned = pinned)
        scheduleOverlayPersist()
        publishSessions()
    }

    /** 隐藏/取消隐藏（M3.2 隐私分区；纯本地，归档视图可恢复）。 */
    fun setSessionHidden(session: Session, hidden: Boolean) {
        sessionOverlay = SessionOverlay.toggled(sessionOverlay, session.id, hidden = hidden)
        scheduleOverlayPersist()
        publishSessions()
    }

    /** Agent 显示名（空串回落 agentId）。 */
    fun setAgentName(agentId: String, name: String) =
        upsertAgent(agentId) { it.copy(name = name.trim().ifBlank { agentId }) }

    /** Agent 身份色。 */
    fun setAgentTint(agentId: String, tint: AgentTintName) =
        upsertAgent(agentId) { it.copy(tint = tint) }

    fun applyThemeMode(mode: ThemeMode) {
        themeMode = mode
        runCatching { cache.kvPut(gatewayId, THEME_KEY, mode.name) }
    }

    private fun upsertAgent(agentId: String, transform: (AgentProfile) -> AgentProfile) {
        val current = agentProfiles[agentId] ?: AgentProfile.default(agentId)
        agentProfiles = agentProfiles + (agentId to transform(current))
        runCatching { cache.kvPut(gatewayId, AgentRegistry.KV_KEY, AgentRegistry.serialize(agentProfiles)) }
    }

    /** overlay 持久化：300ms 去抖（滑动连做合并写；单 job 串行天然防乱序）。 */
    private var overlayPersistJob: Job? = null

    private fun scheduleOverlayPersist() {
        val snapshot = sessionOverlay
        overlayPersistJob?.cancel()
        overlayPersistJob = scope.launch {
            delay(300)
            runCatching { cache.kvPut(gatewayId, OVERLAY_KEY, SessionOverlay.serialize(snapshot)) }
        }
    }

    init {
        // 记住上次连接：预填地址与 Key；deviceToken 是扫码配对成功后的免 token 凭证
        runCatching { ConnectionStore.load() }.getOrNull()?.let {
            if (this.url.isBlank()) this.url = it.url
            if (this.apiKey.isBlank()) this.apiKey = it.apiKey
            alternateUrls = it.urls
            deviceToken = it.deviceToken
            hasPairedCredential = it.deviceToken != null
        }
        // 冷启动先读缓存（M2.5 验收项：断网冷启动可见上次列表；连接成功后被网关权威覆盖）
        runCatching { cache.loadSessions(gatewayId) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { rawSessions = it }
        // overlay（pinned/archived 本地状态）随后叠加并发布
        sessionOverlay = SessionOverlay.parse(runCatching { cache.kvGet(gatewayId, OVERLAY_KEY) }.getOrNull())
        publishSessions()
        agentProfiles = AgentRegistry.parse(runCatching { cache.kvGet(gatewayId, AgentRegistry.KV_KEY) }.getOrNull())
        themeMode = runCatching { cache.kvGet(gatewayId, THEME_KEY) }.getOrNull()
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
        // iOS 进后台 WS 必死：回前台自动用已存配置重连（显式断开除外）
        scope.launch {
            appForegroundEvents.collect {
                delay(800) // 等 resume 后 WS 错误态落地再判断
                if (!explicitDisconnect && !busy) {
                    val st = status
                    val connectedLike = st is GatewayStatus.Connected ||
                        st is GatewayStatus.Connecting || st is GatewayStatus.PairingPending
                    if (!connectedLike && url.isNotBlank() && (deviceToken != null || apiKey.isNotBlank())) {
                        resetAutoRetry() // 前台事件 = 网络环境可能变化，退避计数清零重新起算
                        connect()
                    }
                }
            }
        }
        // 响应式重连（真机反馈：应「条件满足再连」而非定时盲试）：网络路径恢复即连。
        // StateFlow 回放当前值——冷启动即做一次判定；此后 Wi-Fi/VPN 路径每次变化再触发。
        scope.launch {
            networkAvailable.collect { available ->
                if (!available || explicitDisconnect || busy) return@collect
                val st = status
                val connectedLike = st is GatewayStatus.Connected ||
                    st is GatewayStatus.Connecting || st is GatewayStatus.PairingPending
                if (!connectedLike && url.isNotBlank() && (deviceToken != null || apiKey.isNotBlank())) {
                    resetAutoRetry()
                    connect()
                }
            }
        }
        // 已有可用配置（deviceToken 免扫码 / 共享 Key）则启动即连——不再要求每次手动点连接
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
        autoRetryJob?.cancel() // 手动/重入连接优先，别让在途退避重试插进来
        // 旧客户端先收尾（重连场景）：WS 任务与 HttpClient 不能泄漏
        val old = client
        if (old != null) scope.launch { runCatching { old.disconnect() } }
        val c = OpenClawClient(scope, cache)
        client = c
        // collector 必须在 connect() 前挂上：PairingPending 等中间状态发生在 connect() 返回之前
        statusJob?.cancel()
        sessionsJob?.cancel()
        activityJob?.cancel()
        outboxJob?.cancel()
        statusJob = scope.launch { c.status.collect { status = it } }
        sessionsJob = scope.launch {
            c.sessions.collect { list ->
                rawSessions = list
                publishSessions()
                // Dev 直连：列表就绪后自动打开目标会话
                val auto = devAutoSelectKey
                if (auto != null && selectedSession == null) {
                    list.firstOrNull { it.id == auto }?.let { select(it) }
                }
            }
        }
        // 非当前会话的 agent 事件 → 未读 +1（A8 叠加）；当前会话不计数
        activityJob = scope.launch {
            c.sessionActivity.collect { key ->
                if (key != selectedSession) c.incrementUnread(key)
            }
        }
        outboxJob = scope.launch {
            // 变更序号驱动（而非条数）：PENDING→SENT 不改条数，靠条数会漏刷新
            // （「未发出」气泡不消失/同屏与回显重复，review P2-1）
            c.outboxChanges.collect {
                val key = selectedSession
                queuedCount = if (key == null) 0 else runCatching {
                    cache.loadOutbox(gatewayId).count { it.sessionKey == key }
                }.getOrDefault(0)
                refreshPendingOutbox()
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
                resetAutoRetry() // 连上了，退避重试收队
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
                // 自动连接失败（冷启动时网络/VPN 路由常未就绪）：有界退避重试，
                // 覆盖「启动后几十秒内链路才通」的时序；成功/前台事件/手动操作会重置
                if (status is GatewayStatus.Error) scheduleAutoRetry()
            }
        }
    }

    // ---- 自动连接退避重试（真机反馈：冷启动无法连接，网络/VPN 路由未就绪的时序问题） ----
    private var autoRetryJob: Job? = null
    private var autoRetryAttempts = 0

    private fun scheduleAutoRetry() {
        autoRetryJob?.cancel()
        if (explicitDisconnect) return
        if (deviceToken == null && apiKey.isBlank()) return // 没有可用凭据不空转
        if (autoRetryAttempts >= AUTO_RETRY_MAX_ATTEMPTS) return
        val delayMs = 2_000L shl autoRetryAttempts.coerceAtMost(4) // 2s→4s→8s→16s→32s
        autoRetryAttempts++
        autoRetryJob = scope.launch {
            delay(delayMs)
            val st = status
            val retryable = st is GatewayStatus.Disconnected || st is GatewayStatus.Error
            if (!explicitDisconnect && retryable) {
                connect()
            }
        }
    }

    /** 成功/回前台时重置退避计数，取消在途重试。 */
    private fun resetAutoRetry() {
        autoRetryAttempts = 0
        autoRetryJob?.cancel()
        autoRetryJob = null
    }

    /** 扫码结果入口：拿到 setup code 即自动发起配对连接。 */
    fun onQrScan(payload: String?) {
        if (payload.isNullOrBlank()) return
        setupCode = payload
        connect()
    }

    fun select(session: Session?) {
        // 切走前把旧会话在途草稿立即落库（P1：500ms 去抖窗口内切会话不能串草稿/丢草稿）。
        // P3 修复：无条件写入（含空值）——只写非空会让「已发送清空的草稿」写不进去，
        // 旧槽位残留已发送文本，切回时复活。
        selectedSession?.let { old ->
            draftPersistJob?.cancel()
            runCatching { cache.kvPut(gatewayId, draftKey(old), draftState.value) }
        }
        selectedSession = session?.id
        conversation = null
        conversationJob?.cancel()
        conversationJob = null
        session ?: return
        // 切会话即清未读 + 恢复该会话草稿与排队计数（重启/切换均生效）
        queuedCount = runCatching {
            cache.loadOutbox(gatewayId).count { it.sessionKey == session.id }
        }.getOrDefault(0)
        refreshPendingOutbox()
        draftState.value = runCatching { cache.kvGet(gatewayId, draftKey(session.id)) }.getOrNull() ?: ""
        val c = client ?: return
        c.clearUnread(session.id)
        openConversation(c, session.id)
    }

    /** 当前会话 PENDING 待发命令快照（聊天页「未发出」气泡的数据源）。 */
    private fun refreshPendingOutbox() {
        val key = selectedSession ?: run { pendingOutbox = emptyList(); return }
        pendingOutbox = runCatching {
            cache.loadOutbox(gatewayId).filter {
                it.sessionKey == key && it.status == Outbox.Command.Status.PENDING
            }
        }.getOrDefault(emptyList())
    }

    /** 删除一条待发消息（用户放弃发送）。删除即从队列消失，不再补发。 */
    fun deleteQueued(cmd: Outbox.Command) {
        runCatching { cache.deleteOutbox(cmd.idempotencyKey, gatewayId) }
        queuedCount = runCatching {
            cache.loadOutbox(gatewayId).count { it.sessionKey == selectedSession }
        }.getOrDefault(0)
        refreshPendingOutbox()
    }

    /** 立即重试待发队列（在线时即刻补发；离线由重连自动补发兜底）。 */
    fun retryQueuedNow() {
        client?.retryPendingNow()
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

    /**
     * 发送（M2.5 outbox 语义）：client 在线时立即发；失败/离线进队列（消息不静默丢失），
     * 重连后由 client.flushOutbox 自动补发（同幂等键）。
     */
    fun send() {
        val key = selectedSession ?: return
        val text = draft.trim()
        if (text.isEmpty()) return
        val quoted = quotedText
        draft = ""
        clearQuote()
        // 引用以 Markdown blockquote 头部拼接（M3.4 QuoteBar；截前三行防引用淹没正文）
        val composed = if (quoted != null) {
            val lines = quoted.lines().filter { it.isNotBlank() }.take(3)
            val ellipsis = if (quoted.lines().count { it.isNotBlank() } > 3) "\n> …" else ""
            (lines.joinToString("\n> ").let { "> $it" } + ellipsis + "\n\n" + text)
        } else {
            text
        }
        sendText(key, composed)
    }

    /** 消息长按「重发」：把一段文本再次走发送管线（不经草稿，M2.6）。 */
    fun resendText(text: String) {
        val key = selectedSession ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        sendText(key, t)
    }

    private fun sendText(sessionKey: String, text: String) {
        val c = client
        if (c == null) {
            // 尚无 client（冷启动离线）：直接入缓存队列，连接成功后由 flush 补发
            enqueueOffline(sessionKey, text)
            return
        }
        scope.launch {
            try {
                c.send(sessionKey, text)
            } catch (e: GatewayRpcException) {
                lastError = when (e.code) {
                    "SEND_QUEUED" -> "已进入发送队列，重连后自动补发"
                    else -> e.message
                }
            } catch (e: Exception) {
                lastError = e.message
            }
        }
    }

    /** 无 client 时的离线入队（与 client.send 的限额/键规则一致）。 */
    private fun enqueueOffline(sessionKey: String, text: String) {
        if (!cache.available) {
            // 存储不可用 = 没有队列：诚实报错，不谎报"已排队"（消息不静默丢失原则）
            lastError = "当前离线且本地存储不可用，消息未发出，请连接后重试"
            draft = text
            return
        }
        val now = epochMs()
        val existing = runCatching { cache.loadOutbox(gatewayId) }.getOrDefault(emptyList())
        Outbox.checkLimit(existing, now)?.let {
            lastError = it
            draft = text
            return
        }
        runCatching {
            cache.upsertOutbox(
                Outbox.Command(
                    idempotencyKey = Outbox.newKey(now, randomSuffix()),
                    sessionKey = sessionKey,
                    text = text,
                    createdAtMs = now,
                ),
                gatewayId,
            )
            queuedCount = cache.loadOutbox(gatewayId).count { it.sessionKey == sessionKey }
            refreshPendingOutbox()
            lastError = "当前离线，消息已排队（连接后自动补发）"
        }
    }

    /** idempotencyKey 随机后缀（hexString 是 shared internal，这里本地实现同语义）。 */
    private fun randomSuffix(): String = secureRandomBytes(4)
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    // ---- M3.4：停止 / 分支 / 从头开始 / 引用 ----

    /** Composer 停止态：中止当前 run（sessions.abort）。 */
    fun stopRun() {
        val key = selectedSession ?: return
        scope.launch { client?.abort(key) }
    }

    /** 引用消息（MessageActionsSheet「引用」）：Composer 上方 QuoteBar 展示，随下一条消息发出。 */
    var quotedText by mutableStateOf<String?>(null)
        private set

    fun quoteMessage(text: String) {
        quotedText = text
    }

    fun clearQuote() {
        quotedText = null
    }

    /**
     * 从某条消息分支出新会话（sessions.fork，M3.0 探针证实普通 scope 可用）。
     * 成功后刷新列表并切进新会话；失败透传 lastError。
     */
    fun forkFrom(message: Message) {
        val c = client ?: run { lastError = "未连接网关，无法分支"; return }
        val source = selectedSession ?: return
        val anchorId = message.entryId
            ?: message.id.takeIf { !it.startsWith("user-") }
            ?: run { lastError = "该消息不支持分支（缺 transcript 锚点）"; return }
        scope.launch {
            try {
                val newKey = c.fork(source, anchorId)
                clearQuote()
                c.refreshSessions()
                sessions.firstOrNull { it.id == newKey }?.let { select(it) }
                    ?: run { // 列表未及时含新键：直接选（conversation 按键惰性建流）
                        selectedSession = newKey
                        openConversation(c, newKey)
                    }
            } catch (e: Exception) {
                lastError = "分支失败：${e.message}"
            }
        }
    }

    /**
     * 从头开始（重置语义，Grok Bot 对齐）：会话历史留在原会话，同一 agent 开一条全新
     * surface 键（sessions.create）。agent 状态（指令/记忆在网关侧）保留。
     */
    fun startFresh() {
        val c = client ?: run { lastError = "未连接网关"; return }
        val key = selectedSession ?: return
        val agentId = key.removePrefix("agent:").substringBefore(':')
        val newKey = "agent:$agentId:ar-${epochMs().toString(36)}"
        scope.launch {
            try {
                c.createSession(newKey, label = "新会话")
                clearQuote()
                c.refreshSessions()
                sessions.firstOrNull { it.id == newKey }?.let { select(it) }
                    ?: run {
                        selectedSession = newKey
                        openConversation(c, newKey)
                    }
            } catch (e: Exception) {
                lastError = "新建会话失败：${e.message}"
            }
        }
    }

    fun disconnect() {
        explicitDisconnect = true
        connectedUrl = null
        resetAutoRetry() // 用户主动断开不自动重连
        scope.launch { client?.disconnect() }
        selectedSession = null
        conversation = null
        conversationJob?.cancel()
        conversationJob = null
        // sessions 保留缓存列表（M2.5：断开后仍可离线浏览上次列表）
    }

    /** 草稿持久化：500ms 去抖写 kv。**调度时捕获值快照**——job 触发时才读状态会把
     *  切换后的新会话草稿写进旧会话槽位（P1 竞态）；配合 select() 的切换前冲刷双保险。 */
    private fun scheduleDraftPersist() {
        val key = selectedSession ?: return
        val snapshot = draftState.value
        draftPersistJob?.cancel()
        draftPersistJob = scope.launch {
            delay(500)
            runCatching { cache.kvPut(gatewayId, draftKey(key), snapshot) }
        }
    }

    private fun draftKey(sessionKey: String) = "draft:$sessionKey"

    private companion object {
        /** session overlay（pinned/archived 本地状态）的 kv 槽位（M2.6）。 */
        const val OVERLAY_KEY = "sessionOverlay:v1"

        /** 外观偏好 kv 槽位（M3.1）。 */
        const val THEME_KEY = "appearance:v1"

        /** 自动连接退避重试上限（2s→4s→8s→16s→32s，约 1 分钟窗口）。 */
        const val AUTO_RETRY_MAX_ATTEMPTS = 5
    }
}
