package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.AgentClient
import dev.sharpxia.agentreins.GatewayConfig
import dev.sharpxia.agentreins.crypto.secureRandomBytes
import dev.sharpxia.agentreins.model.ApprovalRequest
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 网关 RPC 失败（res.ok=false）。[hint] 携带协议约定的补救提示；[requestId] 为待批准配对请求。 */
class GatewayRpcException(
    val code: String,
    message: String,
    val hint: String? = null,
    val requestId: String? = null,
) : Exception("[$code] $message" + (hint?.let { " — $it" } ?: ""))

/** connect 帧 auth 段的三种凭证；v3 签名 token 槽位与服务器 resolveSignatureToken 对齐。 */
private sealed class ConnectAuth(val authKey: String, val value: String) {
    data class SharedToken(val token: String) : ConnectAuth("token", token)
    data class Bootstrap(val token: String) : ConnectAuth("bootstrapToken", token)
    data class Device(val token: String) : ConnectAuth("deviceToken", token)
}

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
 */
class OpenClawClient(private val scope: CoroutineScope) : AgentClient {

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

    /** 最近一次 operator hello-ok 下发的设备凭证（配对成功后可免共享 token 直连）。 */
    var deviceToken: String? = null
        private set

    private val _approvals = MutableSharedFlow<ApprovalRequest>(extraBufferCapacity = 16)
    override val approvals: SharedFlow<ApprovalRequest> = _approvals.asSharedFlow()

    override suspend fun conversation(sessionKey: String): StateFlow<ConversationState> =
        mutex.withLock { flowForUnlocked(sessionKey) }.asStateFlow()

    /**
     * 回填会话历史（进入会话页时调用，重置分页）：offset=0 取最新 [limit] 条；
     * 与事件态合并（参数/结果来自 transcript）。offset 语义已实测：从最新往前的偏移。
     */
    override suspend fun loadHistory(sessionKey: String, limit: Int) {
        val res = rpc(
            "chat.history",
            buildJsonObject {
                put("sessionKey", sessionKey); put("limit", limit); put("offset", 0)
            },
        )
        val flow = mutex.withLock { flowForUnlocked(sessionKey) }
        flow.value = AgentEventParser.mergeHistory(flow.value, AgentEventParser.mapHistory(sessionKey, res))
        mutex.withLock {
            historyOffsets[sessionKey] = if (res.bool("hasMore") == true) limit else 0
        }
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
    }

    override suspend fun connect(config: GatewayConfig) {
        disconnect()
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
                        role = "operator", clientMode = "ui", scopes = REQUIRED_SCOPES,
                        auth = ConnectAuth.Device(config.deviceToken), holdOpen = true,
                    )
                    else -> openSession(
                        client, config.baseUrl, identity, hello,
                        role = "operator", clientMode = "ui", scopes = REQUIRED_SCOPES,
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
            refreshSessions()
        } catch (e: Exception) {
            _status.value = GatewayStatus.Error(e.message)
            throw e
        }
        // 远端关闭时把 Connected 态翻成 Error
        scope.launch {
            wsJob?.join()
            if (_status.value is GatewayStatus.Connected) {
                _status.value = GatewayStatus.Error("connection closed by gateway")
            }
        }
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
                    val message = err?.str("message") ?: "connect rejected"
                    val requestId = err?.get("details")?.jsonObject?.str("requestId")
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

    override suspend fun disconnect() {
        wsJob?.cancel()
        wsJob = null
        http?.close()
        http = null
        mutex.withLock {
            pending.values.forEach { it.cancel() }
            pending.clear()
            runsByKey.clear()
            // conversations 保留：重连后同 key 的 StateFlow 继续复用，UI 无需重新订阅
        }
        _status.value = GatewayStatus.Disconnected
    }

    private suspend fun rpc(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject =
        withTimeout(RPC_TIMEOUT_MS) {
            val id = "ar-${nextId()}"
            val deferred = CompletableDeferred<JsonObject>()
            mutex.withLock { pending[id] = deferred }
            outbound.send(
                buildJsonObject {
                    put("type", "req"); put("id", id); put("method", method)
                    put("params", params)
                }.toString(),
            )
            deferred.await()
        }

    override suspend fun send(sessionId: String, content: String) {
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
        // 乐观回显：id 带 runId，历史回填时由 mergeHistory 对账去重
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

    override suspend fun respondApproval(requestId: String, approved: Boolean) {
        // M3 接入 exec.approval.* 方法族；M1 仅保留接缝
        throw GatewayRpcException("UNSUPPORTED", "approval 流 M3 实现")
    }

    private suspend fun flowFor(sessionKey: String): MutableStateFlow<ConversationState> =
        mutex.withLock { flowForUnlocked(sessionKey) }

    /** 仅供已持有 [mutex] 的调用方使用——kotlinx Mutex 不可重入，嵌套 withLock 自死锁。 */
    private fun flowForUnlocked(sessionKey: String): MutableStateFlow<ConversationState> =
        conversations.getOrPut(sessionKey) { MutableStateFlow(ConversationState(sessionKey)) }

    suspend fun refreshSessions() {
        val res = rpc("sessions.list", buildJsonObject { put("sortBy", "activity") })
        val rows = res["sessions"]?.jsonArray ?: return
        _sessions.value = rows.mapNotNull { row ->
            val o = row.jsonObject
            val key = o.str("key") ?: return@mapNotNull null
            Session(
                id = key,
                title = o.str("label") ?: o.str("displayName") ?: key.substringAfterLast(':'),
                preview = null,
                lastActiveAtMs = o["updatedAt"]?.jsonPrimitive?.longOrNull,
            )
        }
    }

    private suspend fun route(obj: JsonObject) {
        when (obj.str("type")) {
            "res" -> {
                val id = obj.str("id") ?: return
                val deferred = mutex.withLock { pending.remove(id) } ?: return
                if (obj["ok"]?.jsonPrimitive?.contentOrNull == "true") {
                    deferred.complete(obj["payload"]?.jsonObject ?: JsonObject(emptyMap()))
                } else {
                    val err = obj["error"]?.jsonObject
                    deferred.completeExceptionally(
                        GatewayRpcException(
                            err?.str("code") ?: "UNKNOWN",
                            err?.str("message") ?: "rpc failed",
                            hint = err?.get("details")?.jsonObject?.str("requestId"),
                        ),
                    )
                }
            }
            "event" -> handleEvent(obj.str("event") ?: return, obj["payload"]?.jsonObject ?: return)
        }
    }

    private suspend fun handleEvent(event: String, payload: JsonObject) {
        if (event != "agent") return // M3 扩展 session.approval / sessions.changed
        val decoded = AgentEventParser.decode(payload) ?: return
        // operator 连接收到全量广播（含活跃 subagent），按 sessionKey 分流到各自会话状态
        val key = payload.str("sessionKey") ?: mutex.withLock { runsByKey[payload.str("runId")] } ?: return
        val flow = mutex.withLock { flowForUnlocked(key) }
        flow.value = AgentEventParser.apply(flow.value, decoded)
        // run 终局后回填 transcript：工具参数/结果只在 chat.history 里，事件态仅含耗时
        if (decoded is AgentEventParser.AgentStreamEvent.Lifecycle && decoded.phase == "end") {
            scope.launch {
                delay(HISTORY_RELOAD_DELAY)
                runCatching { reloadHistoryPreservingPages(key, HISTORY_PAGE_LIMIT) }
            }
        }
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

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private companion object {
        val REQUIRED_SCOPES = listOf("operator.read", "operator.write")
        const val RPC_TIMEOUT_MS = 15_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        /** run 终局后等 transcript 落库再回填历史。 */
        const val HISTORY_RELOAD_DELAY = 2_000L
        /** 单页历史条数（进入会话初始加载与翻页共用）。 */
        const val HISTORY_PAGE_LIMIT = 50
        /** 扫码配对含 owner 手动 approve 的等待窗口，外层 hello 超时随之一并放宽。 */
        val PAIRING_WAIT_TIMEOUT = 150.seconds
        val PAIRING_RETRY_INTERVAL = 3.seconds
    }
}
