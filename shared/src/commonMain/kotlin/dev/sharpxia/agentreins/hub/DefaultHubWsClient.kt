package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.takeFrom
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 客户端唯一上行帧（schema SyncResumeFrame：required type/protocolVersion/cursor）。 */
@Serializable
private data class SyncResumeFrame(
    val type: String = "sync.resume",
    val protocolVersion: Int = 1,
    val cursor: String,
)

/** 服务端 event 帧包裹（schema EventFrame：required type/event）。 */
@Serializable
private data class EventFrameDto(val type: String, val event: EventEnvelope)

/** 服务端 ready 帧（schema SyncReadyFrame：required type/watermark/pollIntervalMs——后者注册值 30000，advisory）。 */
@Serializable
private data class ReadyFrameDto(
    val type: String,
    val watermark: String,
    val pollIntervalMs: Long? = null,
)

/** 服务端 error 帧（schema ErrorFrame：required type/code/message）。 */
@Serializable
private data class ErrorFrameDto(
    val type: String,
    val code: String,
    val message: String,
    val details: JsonObject? = null,
    val retryable: Boolean? = null,
    val retryAfterMs: Long? = null,
)

/**
 * [HubWsClient] 的 Ktor 实现。WS 走独立 HttpClient（装 WebSockets 插件；库级 ping 25s——
 * Darwin 引擎支持性未证实（R6），不生效时由上层 90s 无帧看门狗兜底，协议正确性不依赖心跳）。
 * 接收循环把 Text 帧按 type 判别为四帧之三；Binary/Ping/Pong 帧不承载同步语义，忽略不失败。
 */
class DefaultHubWsClient(
    private val config: HubConfig,
    private val accessTokenProvider: () -> String?,
    private val scope: CoroutineScope,
    http: HttpClient? = null,
) : HubWsClient {

    private val json: Json = HubJson

    private val wsHttp: HttpClient = (http ?: hubHttpClient(HubJson)).config {
        install(WebSockets) {
            // 08 §5.4：WS 协议层 ping/pong；25s 落在 25–30s 建议带内。引擎不支持时静默忽略。
            pingIntervalMillis = 25_000
        }
    }

    private val _frames = MutableSharedFlow<HubServerFrame>(extraBufferCapacity = 512)
    private val _closed = MutableStateFlow<HubWsClosed?>(null)
    private var session: DefaultClientWebSocketSession? = null
    private var receiver: Job? = null

    override val closed: StateFlow<HubWsClosed?> = _closed

    override fun frames(): Flow<HubServerFrame> = _frames

    override suspend fun connect(cursor: String) {
        accessTokenProvider() ?: throw HubException.notLoggedIn()
        close()
        _closed.value = null
        val s = wsHttp.webSocketSession {
            url { takeFrom(config.wsUrl) }
            header(HttpHeaders.Authorization, "Bearer ${accessTokenProvider()}")
        }
        session = s
        // 首帧 sync.resume（服务端校验：过期 HUB-SYNC-001 / 非法 HUB-SYNC-002 → error 帧 + close）
        s.send(Frame.Text(json.encodeToString(SyncResumeFrame.serializer(), SyncResumeFrame(cursor = cursor))))
        receiver = scope.launch {
            var lastClose: HubWsClosed? = null
            try {
                loop@ for (frame in s.incoming) {
                    when (frame) {
                        is Frame.Text -> decodeFrame(frame.data.decodeToString())?.let { _frames.emit(it) }
                        else -> Unit // Binary/Ping/Pong/Close：close 原因统一在 closeReason 兜底读取
                    }
                }
            } finally {
                // closeReason 在 incoming 关闭后才可读
                runCatching { s.closeReason.await() }.getOrNull()?.let {
                    lastClose = HubWsClosed(it.code, it.message)
                }
                _closed.value = lastClose ?: HubWsClosed(null, null)
            }
        }
    }

    override suspend fun close() {
        receiver?.cancel()
        receiver = null
        runCatching { session?.close() }
        session = null
    }

    internal fun decodeFrame(text: String): HubServerFrame? {
        val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (type) {
            "sync.ready" -> json.decodeSafe(ReadyFrameDto.serializer(), obj)?.let {
                HubServerFrame.Ready(it.watermark, it.pollIntervalMs)
            }
            "event" -> json.decodeSafe(EventFrameDto.serializer(), obj)?.let { HubServerFrame.Event(it.event) }
            "error" -> json.decodeSafe(ErrorFrameDto.serializer(), obj)?.let {
                HubServerFrame.Error(it.code, it.message, it.retryable, it.retryAfterMs)
            }
            else -> null // 未知帧类型：忽略内容不失败（宽松解析义务，08 §1.1）
        }
    }
}

private fun <T> Json.decodeSafe(serializer: KSerializer<T>, element: JsonElement): T? =
    runCatching { decodeFromJsonElement(serializer, element) }.getOrNull()
