package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.AgentClient
import dev.sharpxia.agentreins.GatewayConfig
import dev.sharpxia.agentreins.crypto.secureRandomBytes
import dev.sharpxia.agentreins.db.CacheStore
import dev.sharpxia.agentreins.db.NoopCacheStore
import dev.sharpxia.agentreins.db.SqlCacheStore
import dev.sharpxia.agentreins.model.ApprovalRequest
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ThinkingEntry
import dev.sharpxia.agentreins.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 网关 RPC 失败（res.ok=false）。[hint] 携带协议约定的补救提示；[requestId] 为待批准配对请求；
 * [retryable]/[retryAfterMs] 为服务器错误帧的结构化重试指令（A11，驱动重连退避）。
 */
class GatewayRpcException(
    val code: String,
    message: String,
    val hint: String? = null,
    val requestId: String? = null,
    val retryable: Boolean = false,
    val retryAfterMs: Long? = null,
) : Exception("[$code] $message" + (hint?.let { " — $it" } ?: ""))

/** connect 帧 auth 段的三种凭证；v3 签名 token 槽位与服务器 resolveSignatureToken 对齐。 */
private sealed class ConnectAuth(val authKey: String, val value: String) {
    data class SharedToken(val token: String) : ConnectAuth("token", token)
    data class Bootstrap(val token: String) : ConnectAuth("bootstrapToken", token)
    data class Device(val token: String) : ConnectAuth("deviceToken", token)
}

/** sessions.search 命中行（M3.6 服务端搜索）：消息级结果，UI 按 sessionKey 归组。 */
data class SearchHit(
    val sessionKey: String,
    val messageId: String,
    val role: String,
    val timestampMs: Long,
    val snippet: String,
    val score: Double,
)

/**
 * OpenClaw 网关客户端（WS 根路径，protocol 4）。
 *
 * 实现依据 M1 spike 实测（docs/protocol-notes.md）：
 * - 鉴权：device pairing——connect 帧 auth + device v3 签名（Ed25519）；
 *   纯 token 的非 cli 客户端 scope 会被清空，故必须带设备身份
 * - 扫码配对：auth.bootstrapToken（openclaw qr），node-first 两腿——node 腿静默配对
 *   并取回 operator deviceToken，再以 deviceToken 重连为 operator（详见 §3.5）
 * - client.id 用平台封闭枚举（openclaw-ios / openclaw-macos）
 * - 帧信封 req/res/event，res 按 id 关联；agent 事件按 sessionKey 路由（全量广播分流），
 *   经 AgentEventParser 归并为各会话 ConversationState（M2：消息流 + 工具调用卡片）
 * - sessions.send 两阶段：受理回 runId+started，回复经 agent 事件流（snapshot 全量）；
 *   run 终局后自动 chat.history 回填，补齐事件流没有的工具参数/结果
 *
 * M2.5 数据基座集成：
 * - 会话列表全量分页（hasMore/nextOffset 循环）+ sessions.changed 增量刷新 + 本地缓存
 * - 消息缓存：会话流首建即播种本地缓存（重启秒开），history 拉取成功后落库
 * - 发送队列 outbox（A4 简化版）：断线排队、重连自动补发（同幂等键）、history 出现才退休
 * - 连接韧性：断线指数退避重连（尊重 retryable/retryAfterMs 与 pauseReconnect）；
 *   agent 事件 seq 缺口 → history 回填
 * - 缓存不可用时全部能力静默降级为无缓存行为（缓存非真相源，丢失不伤在线功能）
 */
class OpenClawClient(
    private val scope: CoroutineScope,
    private val cache: CacheStore = NoopCacheStore,
    private val gatewayId: String = SqlCacheStore.DEFAULT_GATEWAY_ID,
) : AgentClient {

    private val json = Json { ignoreUnknownKeys = true }

    private var http: HttpClient? = null
    private var wsJob: Job? = null
    private val outbound = Channel<String>(Channel.UNLIMITED)

    private val _status = MutableStateFlow<GatewayStatus>(GatewayStatus.Disconnected)
    override val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    override val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val mutex = Mutex()
    private val pending = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val runsByKey = mutableMapOf<String, String>()
    private val conversations = mutableMapOf<String, MutableStateFlow<ConversationState>>()
    /** 每会话已加载的历史条数（从最新往前），offset 分页游标。 */
    private val historyOffsets = mutableMapOf<String, Int>()

    private var nextId = 0

    // ---- M2.5：连接韧性与发送队列状态 ----

    /** 用户/上层显式断开：置位后重连 supervisor 退出；connect() 入口清零。 */
    private var explicitClose = false
    /** 网关维护标志（hello/shutdown 携带 pauseReconnect）：为 true 停止自动重连。 */
    private var pauseReconnect = false
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var pauseReevalJob: Job? = null
    /** 上次成功连接的配置（内部重连目标）；hello 成功后以 deviceToken 更新。 */
    private var lastConfig: GatewayConfig? = null
    /** 服务器错误帧给出的重试指令（下次退避优先采用）。 */
    private var lastRetryAfterMs: Long? = null
    /** per-session history 回填限频（seq 缺口连发时不打爆网关）。 */
    private val lastBackfillAtMs = mutableMapOf<String, Long>()
    private val seqTracker = SeqTracker()

    /** agent 事件活动流（sessionKey）：供 UI 做非当前会话的未读计数（A8）。 */
    private val _sessionActivity = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val sessionActivity: SharedFlow<String> = _sessionActivity.asSharedFlow()

    /** 待补发队列条数（PENDING+SENT 未退休）。 */
    private val _outboxPendingCount = MutableStateFlow(0)
    val outboxPendingCount: StateFlow<Int> = _outboxPendingCount.asStateFlow()

    /**
     * outbox 变更序号（M2.6）：每次 outbox 落库变化 +1。PENDING→SENT 迁移不改变
     * 条数，只靠条数 StateFlow 会因等值去重漏发射（「未发出」气泡不消失）——
     * UI 以此为准刷新待发列表，条数仅作横幅计数。
     */
    private val _outboxChanges = MutableStateFlow(0L)
    val outboxChanges: StateFlow<Long> = _outboxChanges.asStateFlow()

    /** 客户端侧未读计数（per-device UI 态，网关值只在冷启动播种）。 */
    private val localUnread = mutableMapOf<String, Int>()

    /** 最近一次 operator hello-ok 下发的设备凭证（配对成功后可免共享 token 直连）。 */
    var deviceToken: String? = null
        private set

    private val _approvals = MutableSharedFlow<ApprovalRequest>(extraBufferCapacity = 16)
    override val approvals: SharedFlow<ApprovalRequest> = _approvals.asSharedFlow()

    init {
        // 冷启动播种缓存列表：collector 挂上（StateFlow 立即回放）即见上次列表，不闪空
        if (cache.available) {
            runCatching { cache.loadSessions(gatewayId) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { _sessions.value = it }
        }
    }

    override suspend fun conversation(sessionKey: String): StateFlow<ConversationState> =
        mutex.withLock { flowForUnlocked(sessionKey) }.asStateFlow()

    /**
     * 回填会话历史（进入会话页时调用，重置分页）：offset=0 取最新 [limit] 条；
     * 与事件态合并（参数/结果来自 transcript）。offset 语义已实测：从最新往前的偏移。
     * 拉取失败时静默保留会话流现有内容（本地缓存播种，离线可读），重连周期自动刷新。
     */
    override suspend fun loadHistory(sessionKey: String, limit: Int) {
        val res = try {
            rpc(
                "chat.history",
                buildJsonObject {
                    put("sessionKey", sessionKey); put("limit", limit); put("offset", 0)
                },
            )
        } catch (e: Exception) {
            // 失败终态（R2-P1-2）：拉取失败不能让 toolbar「加载中」永挂——缓存播种的
            // 内容按已加载对待（M2.5 断网可读语义），重连周期会自动刷新覆盖
            val flow = mutex.withLock { flowForUnlocked(sessionKey) }
            flow.value = flow.value.copy(historyLoaded = true)
            return
        }
        val flow = mutex.withLock { flowForUnlocked(sessionKey) }
        flow.value = AgentEventParser.mergeHistory(flow.value, AgentEventParser.mapHistory(sessionKey, res))
        mutex.withLock {
            historyOffsets[sessionKey] = if (res.bool("hasMore") == true) limit else 0
        }
        afterHistoryFetched(sessionKey, res)
    }

    /** 向上翻页懒加载：按已加载数 offset 取更早一页，前插去重。hasMore 见 [ConversationState]. */
    override suspend fun loadHistoryOlder(sessionKey: String, limit: Int) {
        val offset = mutex.withLock { historyOffsets[sessionKey] } ?: return // 未做过初始加载不翻页
        if (offset <= 0) return
        val res = rpc(
            "chat.history",
            buildJsonObject {
                put("sessionKey", sessionKey); put("limit", limit); put("offset", offset)
            },
        )
        val flow = mutex.withLock { flowForUnlocked(sessionKey) }
        flow.value = AgentEventParser.mergeHistoryOlder(flow.value, AgentEventParser.mapHistory(sessionKey, res))
        mutex.withLock { historyOffsets[sessionKey] = offset + limit }
        afterHistoryFetched(sessionKey, res)
    }

    /**
     * run 终局的全量刷新：一次取「已加载页数 + 一页」，replace 式合并——既覆盖新消息，
     * 也保留此前向上翻到的旧页（旧区域 transcript 不变，合并按 id 去重不重排）。
     */
    private suspend fun reloadHistoryPreservingPages(sessionKey: String, limit: Int) {
        val loaded = mutex.withLock { historyOffsets[sessionKey] } ?: 0
        val fetchLimit = maxOf(limit, loaded)
        val res = rpc(
            "chat.history",
            buildJsonObject {
                put("sessionKey", sessionKey); put("limit", fetchLimit); put("offset", 0)
            },
        )
        val flow = mutex.withLock { flowForUnlocked(sessionKey) }
        flow.value = AgentEventParser.mergeHistory(flow.value, AgentEventParser.mapHistory(sessionKey, res))
        mutex.withLock {
            historyOffsets[sessionKey] = if (res.bool("hasMore") == true) fetchLimit else 0
        }
        afterHistoryFetched(sessionKey, res)
    }

    /** history 拉取成功的公共后置：消息落库 + outbox 退休对账（该 turn 进 transcript 才退休）。 */
    private fun afterHistoryFetched(sessionKey: String, res: JsonObject) {
        if (cache.available) {
            scope.launch { persistConversation(sessionKey) }
        }
        retireFromHistory(sessionKey, res)
    }

    private suspend fun persistConversation(sessionKey: String) {
        val flow = mutex.withLock { flowForUnlocked(sessionKey) }
        // 瞬态思考条目不落库（review R1-P2-1）：saveMessages 只 upsert 不删除，
        // 写入占位行会在 run 终局后永久残留为死行
        val persistable = flow.value.entries.filterNot { it is ThinkingEntry }
        runCatching { cache.saveMessages(gatewayId, sessionKey, persistable) }
            .onFailure { /* 缓存层故障不伤在线功能 */ }
    }

    /** outbox 对账：history 里 user 消息的 idempotencyKey 命中 → 退休（删除）。 */
    private fun retireFromHistory(sessionKey: String, res: JsonObject) {
        if (!cache.available) return
        val keys = res["messages"]?.jsonArray
            ?.mapNotNull { m -> (m as? JsonObject)?.takeIf { it.str("role") == "user" }?.str("idempotencyKey") }
            .orEmpty()
        if (keys.isEmpty()) return
        val retired = cache.loadOutbox(gatewayId).filter {
            it.sessionKey == sessionKey && Outbox.shouldRetire(it, keys)
        }
        retired.forEach { cache.deleteOutbox(it.idempotencyKey, gatewayId) }
        if (retired.isNotEmpty()) refreshOutboxCount()
    }

    override suspend fun connect(config: GatewayConfig) {
        // 旧连接收尾：先停重连 supervisor 与维护复评，再拆传输层；explicitClose 全程置位防误触发
        explicitClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        pauseReevalJob?.cancel()
        pauseReevalJob = null
        teardownTransport()
        explicitClose = false
        pauseReconnect = false
        reconnectAttempt = ReconnectPolicy.RESET_ATTEMPT
        seqTracker.reset()
        lastRetryAfterMs = null
        _status.value = GatewayStatus.Connecting
        connectOnce(config)
        lastConfig = config.copy(
            deviceToken = deviceToken ?: config.deviceToken,
            bootstrapToken = if (deviceToken != null) null else config.bootstrapToken,
        )
        // 连接成功后常驻重连 supervisor：观察 wsJob 结束 → 指数退避重试
        reconnectJob = scope.launch { reconnectLoop() }
    }

    /**
     * 单次连接（含扫码配对两腿）：hello 成功即拉会话列表并补发队列。
     * 抛错由调用方处理（外层 connect 抛给上层；重连循环吞掉继续退避）。
     */
    private suspend fun connectOnce(config: GatewayConfig) {
        teardownTransport()
        _status.value = GatewayStatus.Connecting

        val client = HttpClient { install(WebSockets) }
        http = client
        val hello = CompletableDeferred<JsonObject>()
        val identity = DeviceIdentity.loadOrCreate()

        wsJob = scope.launch {
            try {
                when {
                    config.bootstrapToken != null ->
                        bootstrapConnect(client, config, identity, hello, config.bootstrapToken)
                    config.deviceToken != null -> openSession(
                        client, config.baseUrl, identity, hello,
                        role = "operator", clientMode = "ui", scopes = REQUIRED_SCOPES + config.extraScopes,
                        auth = ConnectAuth.Device(config.deviceToken), holdOpen = true,
                    )
                    else -> openSession(
                        client, config.baseUrl, identity, hello,
                        role = "operator", clientMode = "ui", scopes = REQUIRED_SCOPES + config.extraScopes,
                        auth = ConnectAuth.SharedToken(config.apiKey), holdOpen = true,
                    )
                }
            } catch (e: Exception) {
                if (!hello.isCompleted) hello.completeExceptionally(e)
                if (_status.value !is GatewayStatus.Disconnected) {
                    _status.value = GatewayStatus.Error(e.message)
                }
            }
        }

        try {
            withTimeout(if (config.bootstrapToken != null) 160_000L else CONNECT_TIMEOUT_MS) { hello.await() }
        } catch (e: Exception) {
            if (e is GatewayRpcException) {
                if (e.retryable) lastRetryAfterMs = e.retryAfterMs
            }
            _status.value = GatewayStatus.Error(e.message)
            throw e
        }
        lastRetryAfterMs = null
        refreshSessions()
        scheduleFlush()
    }

    /**
     * 断线自动重连循环（A11）：仅对「成功连过之后」的意外断线生效（explicitClose /
     * 从未连接成功则直接退出）。指数退避 + 服务器 retryAfterMs 指令优先 + 尊重
     * pauseReconnect；重连目标恒为上次成功配置（多地址 failover 是上层 connect() 职责）。
     */
    private suspend fun reconnectLoop() {
        while (!explicitClose) {
            val job = wsJob ?: return
            job.join()
            if (explicitClose) return
            if (_status.value is GatewayStatus.Connected) return
            val cfg = lastConfig ?: return
            reconnectAttempt += 1
            val decision = ReconnectPolicy.decisionFor(reconnectAttempt, lastRetryAfterMs, pauseReconnect)
            if (decision.stop || decision.delayMs == null) {
                val paused = pauseReconnect
                _status.value = GatewayStatus.Error(
                    if (paused) "网关维护中（pauseReconnect），自动重连已暂停" else "连接已断开，自动重连已停止",
                )
                if (paused) {
                    // 维护窗口兜底（P2）：60s 后复评一次——网关重启完成即可自动恢复；
                    // 若仍处于维护（重连 hello 又置位）则继续按 pauseReconnect 停止。
                    // job 存字段并在 connect/disconnect 一并取消（P3-b：防脱管 supervisor 双开互拆）
                    pauseReevalJob?.cancel()
                    pauseReevalJob = scope.launch {
                        delay(PAUSE_REEVALUATE_DELAY_MS)
                        if (!explicitClose && _status.value !is GatewayStatus.Connected) {
                            pauseReconnect = false
                            reconnectAttempt = ReconnectPolicy.RESET_ATTEMPT
                            reconnectJob = scope.launch { reconnectLoop() }
                        }
                    }
                }
                return
            }
            delay(decision.delayMs)
            if (explicitClose) return
            try {
                connectOnce(cfg)
                reconnectAttempt = ReconnectPolicy.RESET_ATTEMPT
                seqTracker.reset() // seq 空间随新连接归零；活跃会话随后逐个回填补帧
                scope.launch {
                    mutex.withLock { conversations.keys.toList() }.forEach { key ->
                        runCatching { reloadHistoryPreservingPages(key, HISTORY_PAGE_LIMIT) }
                    }
                }
            } catch (e: GatewayRpcException) {
                if (e.retryable) lastRetryAfterMs = e.retryAfterMs
                if (wsJob == null) return // connectOnce 已把传输层拆干净，重连无目标
            } catch (e: Exception) {
                if (wsJob == null) return
            }
        }
    }

    /** 拆传输层（不置 explicitClose、不动 status）：ws/http/pending/outboard 缓冲全清。 */
    private suspend fun teardownTransport() {
        wsJob?.cancel()
        wsJob = null
        http?.close()
        http = null
        // 断线期间排队的陈旧 req 不得重放到新连接（重放会以新 id 关联旧请求）
        while (outbound.tryReceive().isSuccess) Unit
        mutex.withLock {
            pending.values.forEach { it.cancel() }
            pending.clear()
            runsByKey.clear()
            // conversations 保留：重连后同 key 的 StateFlow 继续复用，UI 无需重新订阅
        }
    }

    override suspend fun disconnect() {
        explicitClose = true
        reconnectJob?.cancel()
        reconnectJob = null
        pauseReevalJob?.cancel()
        pauseReevalJob = null
        teardownTransport()
        _status.value = GatewayStatus.Disconnected
    }

    private suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        withTimeout(RPC_TIMEOUT_MS) {
            val id = "ar-${nextId()}"
            val deferred = CompletableDeferred<JsonObject>()
            mutex.withLock { pending[id] = deferred }
            try {
                outbound.send(
                    buildJsonObject {
                        put("type", "req"); put("id", id); put("method", method)
                        put("params", params)
                    }.toString(),
                )
            } catch (e: Throwable) {
                mutex.withLock { pending.remove(id) } // 断线期间发送失败：不留悬挂 pending
                throw e
            }
            try {
                deferred.await()
            } catch (e: Throwable) {
                mutex.withLock { pending.remove(id) } // 超时/取消：条目不可复用，防陈旧响应误配
                throw e
            }
        }

    // ---- 发送队列（A4 文本简化版） ----

    /**
     * 发送（M2.5 outbox 语义）：入队 + 立即尝试。在线且成功 → SENT + 乐观回显（M2 同款
     * runId 回显语义）；离线/失败/超时 → 保持 PENDING（消息不静默丢失），重连后自动补发
     * （同幂等键）。存储不可用（Noop）时退回 M2 直发语义——没有队列就不谎报"已排队"，
     * 失败透传真实错误（P1：静默丢失比诚实失败更糟）。
     * @throws GatewayRpcException code=OUTBOX_LIMIT（限额拒绝）或 SEND_QUEUED（已排队待补发）
     */
    override suspend fun send(sessionId: String, content: String) {
        if (!cache.available) {
            sendDirect(sessionId, content)
            return
        }
        val now = epochMs()
        val existing = cache.loadOutbox(gatewayId)
        Outbox.checkLimit(existing, now)?.let { throw GatewayRpcException("OUTBOX_LIMIT", it) }
        val cmd = Outbox.Command(
            idempotencyKey = Outbox.newKey(now, secureRandomBytes(4).hexString()),
            sessionKey = sessionId,
            text = content,
            createdAtMs = now,
        )
        cache.upsertOutbox(cmd, gatewayId)
        refreshOutboxCount()
        val connected = _status.value is GatewayStatus.Connected
        val accepted = connected && sendOutboxCommand(cmd)
        if (!accepted) {
            // 已连接但本轮失败（如 RPC 超时）：安排延迟补发，不必等下次重连
            if (_status.value is GatewayStatus.Connected) scheduleFlush(SEND_RETRY_DELAY_MS)
            throw GatewayRpcException("SEND_QUEUED", "发送失败，已进入发送队列（重连后自动补发）")
        }
    }

    /** M2 直发语义（缓存不可用时的降级路径）：成功乐观回显，失败抛真实错误。 */
    private suspend fun sendDirect(sessionId: String, content: String) {
        val res = rpc(
            "sessions.send",
            buildJsonObject {
                put("key", sessionId)
                put("message", content)
                put("timeoutMs", 60_000)
            },
        )
        val runId = res.str("runId") ?: throw GatewayRpcException("PROTOCOL", "sessions.send: no runId")
        mutex.withLock { runsByKey[runId] = sessionId }
        val flow = mutex.withLock { flowForUnlocked(sessionId) }
        flow.value = flow.value.copy(
            entries = flow.value.entries + Message(
                id = "${AgentEventParser.LOCAL_ECHO_PREFIX}$runId",
                sessionId = sessionId,
                role = Message.Role.USER,
                content = content,
            ),
        )
    }

    /** 单条补发：成功置 SENT 并乐观回显；结果不明/失败保持 PENDING、重试计数+1。
     *  写回前检查条目仍在队列——in-flight 期间用户「放弃发送」是终态，回写会让
     *  已删除的消息以 SENT/PENDING 形态复活（P2 修复）。 */
    private suspend fun sendOutboxCommand(cmd: Outbox.Command): Boolean {
        return try {
            val res = rpc(
                "sessions.send",
                buildJsonObject {
                    put("key", cmd.sessionKey)
                    put("message", cmd.text)
                    put("timeoutMs", 60_000)
                    put("idempotencyKey", cmd.idempotencyKey)
                },
            )
            val runId = res.str("runId") ?: throw GatewayRpcException("PROTOCOL", "sessions.send: no runId")
            mutex.withLock { runsByKey[runId] = cmd.sessionKey }
            if (!cacheHasCommand(cmd.idempotencyKey)) {
                refreshOutboxCount()
                return true // 已被用户删除：不回写、不回显（消息可能已送达，history 回填后可见）
            }
            cache.upsertOutbox(Outbox.afterSendAttempt(cmd, ok = true, error = null), gatewayId)
            // 乐观回显（M2 语义）：id 带 runId，历史回填时由 mergeHistory 对账去重
            val flow = mutex.withLock { flowForUnlocked(cmd.sessionKey) }
            flow.value = flow.value.copy(
                entries = flow.value.entries + Message(
                    id = "${AgentEventParser.LOCAL_ECHO_PREFIX}$runId",
                    sessionId = cmd.sessionKey,
                    role = Message.Role.USER,
                    content = cmd.text,
                ),
            )
            refreshOutboxCount()
            true
        } catch (e: TimeoutCancellationException) {
            // 回执超时 ≠ 发送失败（真机反馈 bug①）：agent 忙时网关排队应答，15s 内无回执
            // 但消息可能已受理。按 PENDING 保留 + 延迟补发核对（同幂等键，网关幂等不重复
            // 投递）；错误串带「结果未确认」标记，UI 走中性「核对中」不走红字「未发出」。
            if (cacheHasCommand(cmd.idempotencyKey)) {
                cache.upsertOutbox(
                    Outbox.afterSendAttempt(cmd, ok = false, error = "回执超时（结果未确认）"),
                    gatewayId,
                )
            }
            refreshOutboxCount()
            scheduleFlush(SEND_RETRY_DELAY_MS)
            throw GatewayRpcException("SEND_TIMEOUT", "发送结果未确认，稍后自动核对（不会重复发送）")
        } catch (e: CancellationException) {
            throw e // 取消（retryPendingNow 重排/断连收尾）不算发送失败，剩余条目留给下一轮
        } catch (e: Exception) {
            if (cacheHasCommand(cmd.idempotencyKey)) {
                cache.upsertOutbox(Outbox.afterSendAttempt(cmd, ok = false, error = e.message), gatewayId)
            }
            refreshOutboxCount()
            false
        }
    }

    /** 该命令是否仍在队列（未被用户删除）。 */
    private fun cacheHasCommand(idempotencyKey: String): Boolean =
        cache.loadOutbox(gatewayId).any { it.idempotencyKey == idempotencyKey }

    /**
     * 重连后补发：PENDING 优先于 SENT，FIFO；断连即停（剩余下轮再补）。
     * P3 修复（flush 尾随丢失窗口）：flush 进行中新入队的命令不在第一轮快照里，
     * 循环结束后只对**本轮未尝试过**的条目补一轮——已失败的条目不原地自旋
     * （持续失败由下次 send/reconnect 触发新一轮 flush 兜底）。
     */
    private suspend fun flushOutbox() {
        var needsRecheck = false
        try {
            val attempted = mutableSetOf<String>()
            while (true) {
                val cmds = Outbox.pendingForRound(cache.loadOutbox(gatewayId), attempted)
                if (cmds.isEmpty()) {
                    if (needsRecheck) scheduleFlush(SEND_RETRY_DELAY_MS) // bug① 修复：本轮超时条目延迟再核对（review R3-P1-1，scheduleFlush 单飞守卫在本协程存活期间恒 no-op，只能在循环尾补排）
                    return
                }
                for (cmd in cmds) {
                    if (_status.value !is GatewayStatus.Connected || explicitClose) return
                    attempted += cmd.idempotencyKey
                    try {
                        sendOutboxCommand(cmd)
                    } catch (e: GatewayRpcException) {
                        if (e.code == "SEND_TIMEOUT") {
                            // 回执超时：条目保持 PENDING，本轮不中止、记一轮延迟核对
                            // （review R3-P0-1：GatewayRpcException 穿出 launch 即未处理异常，Native 直接终止进程）
                            needsRecheck = true
                        } else {
                            throw e
                        }
                    }
                }
            }
        } finally {
            refreshOutboxCount()
        }
    }

    /**
     * 补发排程（单飞）：连接成功与发送失败共用，防双 flush 并发重复回显。
     * 失败重试用退避间隔，连发合并为一次。
     */
    private var flushJob: Job? = null

    private fun scheduleFlush(delayMs: Long = 0) {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            flushOutbox()
        }
    }

    private fun refreshOutboxCount() {
        _outboxPendingCount.value = if (cache.available) cache.loadOutbox(gatewayId).size else 0
        // PENDING→SENT 等迁移不改条数，变更序号保证 UI 每次落库后都能收到刷新信号
        _outboxChanges.value += 1
    }

    /** UI「立即重试」入口：在途的延迟 flush 直接取消改排即刻轮（退避窗口内点重试不该静默 no-op）。 */
    fun retryPendingNow() {
        flushJob?.cancel()
        scheduleFlush(0)
    }

    override suspend fun respondApproval(requestId: String, approved: Boolean) {
        // M3 接入 exec.approval.* 方法族；M1 仅保留接缝
        throw GatewayRpcException("UNSUPPORTED", "approval 流 M3 实现")
    }

    // ---- M3.4：分支 / 停止 / 从头开始（探针 §7.3 实测的普通 scope 能力） ----

    /** 中止当前 run（sessions.abort {sessionKey}）。空闲会话返回语义错误，由调用方容忍。 */
    suspend fun abort(sessionKey: String) {
        runCatching {
            rpc(
                "sessions.abort",
                buildJsonObject { put("sessionKey", sessionKey) },
            )
        }
    }

    /**
     * 从某条消息分支出新会话（sessions.fork {sessionKey, entryId}）。
     * entryId = chat.history 消息的 `__openclaw.id`（即 Message.id；branches.list 的
     * leafEntryId 同域）。返回新会话 key；解析不到时抛 PROTOCOL。
     */
    suspend fun fork(sessionKey: String, entryId: String): String {
        val res = rpc(
            "sessions.fork",
            buildJsonObject {
                put("sessionKey", sessionKey); put("entryId", entryId)
            },
        )
        val newKey = res.str("key")
            ?: res.str("sessionKey")
            ?: (res["entry"] as? JsonObject)?.str("key")
            ?: throw GatewayRpcException("PROTOCOL", "sessions.fork: no key in response: ${res.toString().take(200)}")
        return newKey
    }

    /**
     * 审批应答（approval.resolve）。⚠️ 参数形态是骨架假设——网关未启用审批时无从实测
     * （exec.approval.* 全族要 operator.approvals scope，M3.0 探针 §7.3）；开启审批
     * 面板后按真实报文校准。
     */
    suspend fun resolveApproval(requestId: String, approved: Boolean) {
        rpc(
            "approval.resolve",
            buildJsonObject {
                put("requestId", requestId)
                put("decision", if (approved) "approve" else "deny")
            },
        )
    }

    /** 新建会话（sessions.create {key, label?}）——「从头开始」：同 agent 新 surface 键。 */
    suspend fun createSession(key: String, label: String? = null): String {
        val res = rpc(
            "sessions.create",
            buildJsonObject {
                put("key", key)
                if (label != null) put("label", label)
            },
        )
        return res.str("key") ?: key
    }

    // ---- M3.6：服务端全文搜索（探针实锤：{query, limit?} → {results:[…]}） ----

    /**
     * sessions.search 消息级全文搜索，返回按 score 降序的命中行（带摘要）。
     * UI 以 sessionKey 归组展示；空 query/无网关由调用方先拦。
     */
    suspend fun searchSessions(query: String, limit: Int = 20): List<SearchHit> {
        val res = rpc(
            "sessions.search",
            buildJsonObject {
                put("query", query)
                put("limit", limit)
            },
        )
        val arr = res["results"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o.str("sessionKey") ?: return@mapNotNull null
            SearchHit(
                sessionKey = key,
                messageId = o.str("messageId") ?: "",
                role = o.str("role") ?: "",
                timestampMs = o.long("timestamp") ?: 0L,
                snippet = o.str("snippet") ?: "",
                score = o.str("score")?.toDoubleOrNull() ?: 0.0,
            )
        }
    }

    /** 仅供已持有 [mutex] 的调用方使用——kotlinx Mutex 不可重入，嵌套 withLock 自死锁。
     *  首建时播种本地缓存（重启秒开；缓存非真相源，随后的 history 拉取权威覆盖）。 */
    private fun flowForUnlocked(sessionKey: String): MutableStateFlow<ConversationState> =
        conversations.getOrPut(sessionKey) {
            val cached = if (cache.available) {
                runCatching { cache.loadMessages(gatewayId, sessionKey) }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            MutableStateFlow(
                ConversationState(
                    sessionKey,
                    entries = cached,
                    hasMoreHistory = cached.size >= HISTORY_PAGE_LIMIT,
                ),
            )
        }

    // ---- 会话列表：全量分页 + 增量 + 缓存 ----

    /**
     * sessions.list 全量分页拉取（A6）：hasMore/nextOffset 循环到尽。
     * 成功后合并本地未读计数、写缓存；失败静默保留现有列表（缓存/上次值）。
     */
    suspend fun refreshSessions() {
        val all = mutableListOf<Session>()
        var offset = 0L
        while (true) {
            val res = try {
                rpc(
                    "sessions.list",
                    buildJsonObject {
                        put("sortBy", "activity")
                        put("limit", SessionListMapper.PAGE_SIZE)
                        put("offset", offset)
                    },
                )
            } catch (e: Exception) {
                return
            }
            val page = SessionListMapper.parsePage(res)
            all += page.sessions
            if (!page.hasMore) break
            val next = page.nextOffset ?: break // hasMore 但无游标：防协议异常死循环
            if (next <= offset) break
            offset = next
            if (all.size > MAX_SESSIONS) break // 硬护栏
        }
        _sessions.value = SessionListMapper.merge(all, emptyList(), keepMissing = false)
            .map { s -> localUnread[s.id]?.let { s.copy(unread = it) } ?: s }
        if (cache.available) runCatching { cache.saveSessions(gatewayId, _sessions.value) }
    }

    /** sessions.changed 增量（A6）：800ms 去抖合并连发事件，整表重取 + 缓存合并。 */
    private fun scheduleSessionsRefresh() {
        if (sessionsRefreshJob?.isActive == true) return
        sessionsRefreshJob = scope.launch {
            delay(SESSIONS_REFRESH_DEBOUNCE_MS)
            runCatching { refreshSessions() }
        }
    }

    private var sessionsRefreshJob: Job? = null

    /** UI 选中会话即清未读（本地计数归零 + 列表更新 + 缓存同步）。 */
    fun clearUnread(sessionKey: String) {
        localUnread.remove(sessionKey)
        mutateSession(sessionKey) { it.copy(unread = 0) }
    }

    /** 非当前会话收到 agent 事件 → 未读 +1（A8 叠加）。由 AppModel 判定「非当前」后调用。 */
    fun incrementUnread(sessionKey: String) {
        val current = _sessions.value.firstOrNull { it.id == sessionKey }?.unread ?: 0
        localUnread[sessionKey] = (localUnread[sessionKey] ?: current) + 1
        mutateSession(sessionKey) { it.copy(unread = localUnread[sessionKey] ?: 0) }
    }

    private fun mutateSession(sessionKey: String, transform: (Session) -> Session) {
        _sessions.value = _sessions.value.map { if (it.id == sessionKey) transform(it) else it }
        schedulePersistSessions() // 未读随 agent 事件高频变化：列表落库去抖，防每帧全表重写
    }

    private var sessionsPersistJob: Job? = null

    private fun schedulePersistSessions() {
        if (sessionsPersistJob?.isActive == true) return
        sessionsPersistJob = scope.launch {
            delay(SESSIONS_PERSIST_DEBOUNCE_MS)
            if (cache.available) runCatching { cache.saveSessions(gatewayId, _sessions.value) }
        }
    }

    // ---- 事件路由 ----

    private suspend fun route(obj: JsonObject) {
        when (obj.str("type")) {
            "res" -> {
                val id = obj.str("id") ?: return
                val deferred = mutex.withLock { pending.remove(id) } ?: return
                if (obj["ok"]?.jsonPrimitive?.contentOrNull == "true") {
                    deferred.complete(obj["payload"]?.jsonObject ?: JsonObject(emptyMap()))
                } else {
                    val err = obj["error"]?.jsonObject
                    val details = err?.get("details")?.jsonObject
                    deferred.completeExceptionally(
                        GatewayRpcException(
                            err?.str("code") ?: "UNKNOWN",
                            err?.str("message") ?: "rpc failed",
                            hint = details?.str("requestId"),
                            retryable = err?.bool("retryable") ?: false,
                            retryAfterMs = err?.long("retryAfterMs") ?: details?.long("retryAfterMs"),
                        ),
                    )
                }
            }
            "event" -> {
                // seq 缺口检测（A10 简化）：帧级 seq 是连接级全局序号（M1 录制实锤，非
                // per-session），且对**全部事件帧**连续计数——只在 agent 分支观察会把
                // sessions.changed 等穿插帧误判成缺口，故在路由层统一喂入。
                // 缺口 = 丢帧 → 全部活跃会话 history 回填（限频防打爆）。
                val gap = seqTracker.observe(FRAME_SEQ_TRACKER_KEY, obj.long("seq"))
                if (gap > 0) {
                    mutex.withLock { conversations.keys.toList() }.forEach { stale ->
                        scheduleBackfill(stale, BACKFILL_RATE_LIMIT_MS)
                    }
                }
                handleEvent(obj.str("event") ?: return, obj["payload"]?.jsonObject ?: JsonObject(emptyMap()), obj)
            }
        }
    }

    private suspend fun handleEvent(event: String, payload: JsonObject, frame: JsonObject) {
        when (event) {
            "agent" -> handleAgentEvent(payload)
            "sessions.changed" -> scheduleSessionsRefresh()
            "shutdown" -> {
                // 网关维护/重启：暂停自动重连（上层 connect() 或回前台可恢复）
                pauseReconnect = payload.bool("pauseReconnect") ?: true
            }
            "session.approval" -> {
                // M3.5 审批事件（includeApprovals 订阅/广播）：id+摘要透传 UI，detail 全量存 JSON
                val req = ApprovalRequest(
                    id = payload.str("requestId") ?: payload.str("id") ?: return@handleEvent,
                    sessionId = payload.str("sessionKey") ?: payload.str("session"),
                    summary = payload.str("summary")
                        ?: payload.str("toolName")
                        ?: payload.str("command")
                        ?: "请求审批",
                    detailJson = payload.toString(),
                    requestedAtMs = payload.long("ts") ?: payload.long("timestamp"),
                )
                _approvals.tryEmit(req)
            }
            else -> Unit
        }
    }

    private suspend fun handleAgentEvent(payload: JsonObject) {
        val decoded = AgentEventParser.decode(payload) ?: return
        // operator 连接收到全量广播（含活跃 subagent），按 sessionKey 分流到各自会话状态
        val key = payload.str("sessionKey") ?: mutex.withLock { runsByKey[payload.str("runId")] } ?: return
        val flow = mutex.withLock { flowForUnlocked(key) }
        flow.value = AgentEventParser.apply(flow.value, decoded)
        _sessionActivity.emit(key)
        // run 终局后回填 transcript：工具参数/结果只在 chat.history 里，事件态仅含耗时
        if (decoded is AgentEventParser.AgentStreamEvent.Lifecycle && decoded.phase == "end") {
            scheduleBackfill(key, HISTORY_RELOAD_DELAY)
        }
    }

    /** history 回填排程：限频窗口内合并连发请求；[delayMs] 覆盖限频（终局回填必须执行）。 */
    private fun scheduleBackfill(sessionKey: String, delayMs: Long) {
        scope.launch {
            delay(delayMs)
            val now = epochMs()
            val last = lastBackfillAtMs[sessionKey] ?: 0L
            if (now - last < BACKFILL_RATE_LIMIT_MS) return@launch
            lastBackfillAtMs[sessionKey] = now
            runCatching { reloadHistoryPreservingPages(sessionKey, HISTORY_PAGE_LIMIT) }
        }
    }

    /**
     * 打开一条 WS 会话走完 challenge → connect → hello-ok，返回 hello payload。
     * [holdOpen]=true 时完成 hello 后进入常驻收发循环（最终连接）；false 时拿到
     * hello 即返回，会话随即关闭（配对腿）。
     */
    private suspend fun openSession(
        client: HttpClient,
        url: String,
        identity: DeviceIdentity,
        hello: CompletableDeferred<JsonObject>,
        role: String,
        clientMode: String,
        scopes: List<String>,
        auth: ConnectAuth,
        holdOpen: Boolean,
    ): JsonObject {
        var helloPayload: JsonObject? = null
        client.webSocket(urlString = url) {
            // 1) 等服务器 connect.challenge（nonce/ts，签名原料）
            var challenge: JsonObject? = null
            while (challenge == null) {
                val frame = incoming.receive()
                if (frame !is Frame.Text) continue
                val obj = json.parseToJsonElement(frame.readText()).jsonObject
                if (obj.str("event") == "connect.challenge") {
                    challenge = obj["payload"]?.jsonObject
                }
            }
            val ch = challenge!!

            // 2) 发 connect（设备签名）
            val connId = "ar-${nextId()}"
            send(Frame.Text(connectFrame(connId, identity, ch, role, clientMode, scopes, auth).toString()))

            // 3) 等 hello-ok
            while (helloPayload == null) {
                val frame = incoming.receive()
                if (frame !is Frame.Text) continue
                val obj = json.parseToJsonElement(frame.readText()).jsonObject
                if (obj.str("type") != "res" || obj.str("id") != connId) continue
                if (obj["ok"]?.jsonPrimitive?.contentOrNull == "true") {
                    helloPayload = obj["payload"]?.jsonObject
                } else {
                    val err = obj["error"]?.jsonObject
                    val details = err?.get("details")?.jsonObject
                    val message = err?.str("message") ?: "connect rejected"
                    val requestId = details?.str("requestId")
                    throw GatewayRpcException(
                        err?.str("code") ?: "UNKNOWN",
                        message,
                        hint = when {
                            requestId != null ->
                                "待批准设备 requestId=$requestId，owner 执行 openclaw devices approve"
                            message.contains("setup code") ->
                                "setup code 已失效或已使用，请重新执行 openclaw qr 生成"
                            else -> null
                        },
                        requestId = requestId,
                        retryable = err?.bool("retryable") ?: false,
                        retryAfterMs = err?.long("retryAfterMs") ?: details?.long("retryAfterMs"),
                    )
                }
            }
            val payload = helloPayload!!

            if (role == "operator") {
                val granted = payload["auth"]?.jsonObject
                    ?.get("scopes")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
                if (!granted.containsAll(REQUIRED_SCOPES)) {
                    throw GatewayRpcException(
                        "FORBIDDEN",
                        "granted scopes insufficient: $granted（纯 token 连接会被清空 scope，需设备配对）",
                    )
                }
                payload["auth"]?.jsonObject?.str("deviceToken")?.let { deviceToken = it }
                // 网关维护标志：hello 明示 pauseReconnect 时停自动重连（A11）
                if (payload.bool("pauseReconnect") == true) pauseReconnect = true
            }
            if (!holdOpen) return@webSocket

            hello.complete(payload)
            _status.value = GatewayStatus.Connected(payload["server"]?.jsonObject?.str("version"))

            launch {
                while (true) {
                    val text = outbound.receive()
                    send(Frame.Text(text))
                }
            }

            // 4) 接收主循环
            while (true) {
                val frame = incoming.receive()
                when (frame) {
                    is Frame.Ping -> send(Frame.Pong(frame.data))
                    is Frame.Text -> route(json.parseToJsonElement(frame.readText()).jsonObject)
                    else -> Unit
                }
            }
        }
        return helloPayload ?: error("session closed before hello")
    }

    /**
     * 扫码配对（auth.bootstrapToken），固定 node-first 两腿（实测见 protocol-notes §3.5）：
     * ① role=node 的 bootstrap 连接——全新设备由网关按 setup profile 静默批准并写入
     *    node+operator 双角色，hello-ok 同时下发两个角色的 deviceToken；bootstrap token
     *    与首个请求的 (role, scopes) 绑定，operator 先行会毒化 token，故必须 node 先行。
     * ② 断开后用 operator deviceToken 重连为 operator（免共享 token）。
     * 腿 ① 返回 NOT_PAIRED（如仅 operator 角色的旧设备需要 role-upgrade 批准、或
     * desktop 客户端元数据不在静默配对白名单）时进入 PairingPending 自动重试——
     * owner 执行 openclaw devices approve 后无需操作自动续连。
     */
    private suspend fun bootstrapConnect(
        client: HttpClient,
        config: GatewayConfig,
        identity: DeviceIdentity,
        hello: CompletableDeferred<JsonObject>,
        bootstrapToken: String,
    ) {
        val start = TimeSource.Monotonic.markNow()
        var nodeHello: JsonObject? = null
        var lastFailure: GatewayRpcException? = null
        while (start.elapsedNow() < PAIRING_WAIT_TIMEOUT) {
            try {
                nodeHello = openSession(
                    client, config.baseUrl, identity, hello,
                    role = "node", clientMode = "node", scopes = emptyList(),
                    auth = ConnectAuth.Bootstrap(bootstrapToken), holdOpen = false,
                )
                break
            } catch (e: GatewayRpcException) {
                if (e.code != "NOT_PAIRED" && e.code != "PAIRING_REQUIRED") throw e
                lastFailure = e
                _status.value = GatewayStatus.PairingPending(e.requestId)
                delay(PAIRING_RETRY_INTERVAL)
            }
        }
        val leg1 = nodeHello ?: throw lastFailure ?: GatewayRpcException("NOT_PAIRED", "pairing not approved in time")
        val nodeAuth = leg1["auth"]?.jsonObject
        val operatorToken = nodeAuth?.get("deviceTokens")?.jsonArray
            ?.mapNotNull { it.jsonObject }
            ?.firstOrNull { it.str("role") == "operator" }
            ?.str("deviceToken")
            ?: nodeAuth?.str("deviceToken")
            ?: throw GatewayRpcException("PROTOCOL", "bootstrap hello: no operator deviceToken")
        openSession(
            client, config.baseUrl, identity, hello,
            role = "operator", clientMode = "ui", scopes = REQUIRED_SCOPES,
            auth = ConnectAuth.Device(operatorToken), holdOpen = true,
        )
    }

    /**
     * connect 帧（device pairing 版）。v3 签名 payload（platform/deviceFamily 小写，与服务器
     * normalizeDeviceMetadataForAuth 一致）；signedAt/nonce 取自 connect.challenge；
     * 签名 token 槽位按服务器 resolveSignatureToken 取当前 auth 凭证。
     */
    private fun connectFrame(
        id: String,
        identity: DeviceIdentity,
        challenge: JsonObject,
        role: String,
        clientMode: String,
        scopes: List<String>,
        auth: ConnectAuth,
    ): JsonObject {
        val platform = gatewayPlatform().lowercase()
        val family = gatewayDeviceFamily().lowercase()
        val signedAt = challenge["ts"]?.jsonPrimitive?.longOrNull ?: 0L
        val nonce = challenge.str("nonce").orEmpty()
        val clientId = gatewayClientId()
        val payloadV3 = listOf(
            "v3", identity.deviceIdHex, clientId, clientMode, role,
            scopes.joinToString(","), signedAt.toString(), auth.value, nonce, platform, family,
        ).joinToString("|")
        val signature = identity.sign(payloadV3.encodeToByteArray()).base64Url()

        return buildJsonObject {
            put("type", "req"); put("id", id); put("method", "connect")
            put(
                "params",
                buildJsonObject {
                    put("minProtocol", 4); put("maxProtocol", 4)
                    put(
                        "client",
                        buildJsonObject {
                            put("id", clientId)
                            put("version", "0.1.0")
                            put("platform", gatewayPlatform())
                            put("deviceFamily", gatewayDeviceFamily())
                            put("mode", clientMode)
                            put("instanceId", secureRandomBytes(16).hexString())
                        },
                    )
                    put("caps", buildJsonArray {})
                    put("role", role)
                    put(
                        "scopes",
                        buildJsonArray {
                            scopes.forEach { add(it) }
                        },
                    )
                    put("auth", buildJsonObject { put(auth.authKey, auth.value) })
                    put(
                        "device",
                        buildJsonObject {
                            put("id", identity.deviceIdHex)
                            put("publicKey", identity.publicKeyRaw.base64Url())
                            put("signature", signature)
                            put("signedAt", signedAt)
                            put("nonce", nonce)
                        },
                    )
                },
            )
        }
    }

    private fun nextId(): Int = nextId++

    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull

    private companion object {
        val REQUIRED_SCOPES = listOf("operator.read", "operator.write")
        const val RPC_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        /** run 终局后等 transcript 落库再回填历史。 */
        const val HISTORY_RELOAD_DELAY = 2_000L
        /** 单页历史条数（进入会话初始加载与翻页共用）。 */
        const val HISTORY_PAGE_LIMIT = 50
        /** seq 缺口回填限频窗口（缺口连发时不打爆网关；误限只损失即时性，终局回填兜底）。 */
        const val BACKFILL_RATE_LIMIT_MS = 3_000L
        /** 帧级 seq 全局跟踪键（连接级序号，见 handleAgentEvent 注释）。 */
        const val FRAME_SEQ_TRACKER_KEY = "__frame_seq__"
        /** sessions.changed 去抖窗口。 */
        const val SESSIONS_REFRESH_DEBOUNCE_MS = 800L
        /** 会话列表落库去抖（未读高频变化的写放大防护）。 */
        const val SESSIONS_PERSIST_DEBOUNCE_MS = 1_000L
        /** 已连接但发送失败后的补发延迟。 */
        const val SEND_RETRY_DELAY_MS = 5_000L
        /** pauseReconnect 维护窗口复评延迟。 */
        const val PAUSE_REEVALUATE_DELAY_MS = 60_000L
        /** 全量分页硬护栏（异常协议下防失控）。 */
        const val MAX_SESSIONS = 5_000
        /** 扫码配对含 owner 手动 approve 的等待窗口，外层 hello 超时随之一并放宽。 */
        val PAIRING_WAIT_TIMEOUT = 150.seconds
        val PAIRING_RETRY_INTERVAL = 3.seconds
    }
}
