package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.avatar.parseAvatarUpdatedFrame
import dev.sharpxia.agentreins.hub.avatar.parseConversationAvatarUpdatedFrame
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 客户端唯一上行帧（schema SyncResumeFrame：required type/protocolVersion/cursor）——显式构造，见 connect 注释。 */
private fun buildResumeFrame(cursor: String): String =
    buildJsonObject {
        put("type", "sync.resume")
        put("protocolVersion", 1)
        put("cursor", cursor)
    }.toString()

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
 * Darwin 引擎支持性未证实（R6），不生效时由上层 90s 无传输活性看门狗兜底，协议正确性不依赖
 * 心跳）。接收循环把 Text 帧按 type 判别为四帧之三；Binary/Ping/Pong 帧不承载同步语义、不进
 * [frames]。PING/PONG 栈面事实（2026-11-22 FLOW-5 取证，完整版见 pingIntervalMillis 注）：
 * Ktor DefaultWebSocketSession 包装在到达本循环前即拦截 Ping（自动 pong）/Pong（路由内部
 * pinger），循环实际只见数据帧——[transportActivity] 计数就位但当前栈下与数据帧活性同源，
 * 静默期误重连的根治（启用 pinger）defer 待活 Hub 复验。
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
            // R6 实测（desktop CIO）：pingIntervalMillis>0 时 ready 帧永不到达（pinger 与
            // webSocketSession() 用法冲突），先禁用——活性由上层 90s 无传输活性看门狗兜底。
            //
            // 2026-11-22 FLOW-5 层级取证（避免未来轮次重新推导）：
            // - 服务端 30s 协议层 ping 存在（clientaccess/wss.go 心跳泵 WriteControl(PingMessage)），
            //   但到不了本接收循环：webSocketSession() 经客户端插件 convertSessionToDefault 把
            //   引擎 raw 会话包装成 DefaultWebSocketSession，其 incoming 处理器拦截控制帧——
            //   Ping 自动 pong（ponger 通道）、Pong 路由给内部 pinger（未启用即丢弃），仅
            //   Text/Binary 进用户 incoming（ktor 3.6.0 字节码 + 上游同版源码双证）。
            // - raw 属性 private、客户端插件无公开 API 旁路 Default 包装——「接收循环对 Ping/Pong
            //   刷活性戳」在当前栈是空操作（transportActivity 机制仍就位，见 HubWsClient.transportActivity）。
            // - 真死判据（静默期误重连根因的根治）= 启用本 pinger 由 Ktor pong 超时关会话，
            //   defer 待活 Hub 复验 R6 的 CIO 复现（当前轮禁碰活 Hub，不许盲开）。
            pingIntervalMillis = 0
        }
    }

    // replay 64：connect 与下游订阅之间存在天然间隙（建连即发 resume、ready 可能先到），
    // 无 replay 的 SharedFlow 会在无订阅者时丢帧——重放旧帧无害（事件应用按 id 幂等去重）
    private val _frames = MutableSharedFlow<HubServerFrame>(replay = 64, extraBufferCapacity = 512)
    private val _closed = MutableStateFlow<HubWsClosed?>(null)
    // 传输层活性计数（任意到达帧 +1，跨连接单调不归零——消费方按快照比对判变化，连接切换
    // 由上层在 connect 后重取基线）
    private val _transportActivity = MutableStateFlow(0L)
    private var session: DefaultClientWebSocketSession? = null
    private var receiver: Job? = null

    override val closed: StateFlow<HubWsClosed?> = _closed

    override val transportActivity: StateFlow<Long> = _transportActivity

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
        // 首帧 sync.resume（服务端校验：过期 HUB-SYNC-001 / 非法 HUB-SYNC-002 → error 帧 + close）。
        // 显式构造而非 data class 序列化：HubJson.encodeDefaults=false 会把默认值字段
        // （type/protocolVersion）整个省掉，服务端判「首帧须为 sync.resume」（HUB-SYNC-002 实证）
        s.send(Frame.Text(buildResumeFrame(cursor)))
        receiver = scope.launch {
            var lastClose: HubWsClosed? = null
            try {
                loop@ for (frame in s.incoming) {
                    // 任意到达帧先刷传输活性（FLOW-5；当前 Ktor 栈下实际只有 Text/Binary 到达
                    // 此处——Ping/Pong 被 DefaultWebSocketSession 拦截，见 pingIntervalMillis 注）
                    _transportActivity.value += 1
                    when (frame) {
                        is Frame.Text -> decodeFrame(frame.data.decodeToString())?.let { _frames.emit(it) }
                        else -> Unit // Binary/Ping/Pong/Close：不承载同步语义；close 原因统一在 closeReason 兜底读取
                    }
                }
            } catch (e: CancellationException) {
                throw e // 主动取消（close/reconnect）：正常控制流透传
            } catch (_: Exception) {
                // 挂机崩溃修复（2026-11-08，0.5.4 真机 9 连 SIGABRT 同族）：网络错误终结
                //（didCompleteWithError → DarwinHttpRequestException 传导 incoming）会让
                // 迭代以异常退出——0.5.5 修的 close 侧竞态不覆盖这条路径：前台闲置网络掉线
                // 时异常逃出接收协程 → 无处理器 → Kotlin/Native abort。错误终结统一按
                // 「连接已断」收尾（_closed 置位驱动上层重连），异常不再外逃。
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
        val s = session
        session = null
        if (s != null) {
            runCatching { s.close() }
            // 崩溃防护（0.5.4 真机锁屏解锁 SIGABRT 根因）：等会话真正收尾再返回——
            // Ktor Darwin 引擎的 didComplete(error) 回调在 URLSession 线程同步构造异常并
            // completeExceptionally；若此时会话尚未终结（父调用上下文已退出/取消），该异常
            // 无人消费 → Kotlin/Native terminateWithUnhandledException → 进程 abort。
            // 会话先收尾，迟到的 didComplete 落在已终结 job 上只被静默丢弃。
            withTimeoutOrNull(2_000) { runCatching { s.closeReason.await() } }
        }
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
            // v1.5.0：avatar.updated 控制帧（非事件域）——载荷抽取宽松（缺 data.profile → null 静默忽略）
            "avatar.updated" -> parseAvatarUpdatedFrame(obj)?.let { HubServerFrame.AvatarUpdated(it) }
            // v1.5.2：conversation.avatar.updated 控制帧（非事件域）——profile=null 是合法清除帧
            // （非形状漂移）；缺 data/conversationId/profile 键或档形状漂移才静默忽略
            "conversation.avatar.updated" -> parseConversationAvatarUpdatedFrame(obj)?.let {
                HubServerFrame.ConversationAvatarUpdated(it.conversationId, it.profile)
            }
            else -> null // 未知帧类型：忽略内容不失败（宽松解析义务，08 §1.1）
        }
    }
}

private fun <T> Json.decodeSafe(serializer: KSerializer<T>, element: JsonElement): T? =
    runCatching { decodeFromJsonElement(serializer, element) }.getOrNull()
