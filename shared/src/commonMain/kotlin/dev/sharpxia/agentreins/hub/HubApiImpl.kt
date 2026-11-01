package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationOk
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.DevicesPage
import dev.sharpxia.agentreins.hub.auth.LoginOk
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshOk
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.GatewaysPage
import dev.sharpxia.agentreins.hub.projection.MessagesPage
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Hub JSON 口径：忽略未知字段、宽松解析（08 §6.1 客户端义务）；null 可选字段不编码（如 mfaCode）。 */
val HubJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    encodeDefaults = false
}

/**
 * [HubApi] 的 Ktor 实现（P1）。引擎按平台自动发现（android=OkHttp / desktop=CIO / ios=Darwin，
 * 依赖已在 shared/build.gradle.kts 各端挂好）。鉴权 Token 由 [accessTokenProvider] 现取——
 * 本类不持有、不刷新凭据，401 的刷新与重试归 TokenManager.withBearer。
 */
class DefaultHubApi(
    private val config: HubConfig,
    private val accessTokenProvider: () -> String?,
    private val http: HttpClient = HttpClient {
        install(ContentNegotiation) { json(HubJson) }
        expectSuccess = false
    },
) : HubApi {

    override suspend fun login(req: LoginRequest): LoginOk = post("/auth/login", req, auth = false)

    override suspend fun refresh(req: RefreshRequest): RefreshOk = post("/auth/refresh", req, auth = false)

    override suspend fun registerDevice(req: DeviceRegistrationRequest): DeviceRegistrationOk = post("/devices", req)

    override suspend fun listDevices(): DevicesPage = get("/devices")

    override suspend fun deleteDevice(deviceId: String) {
        val resp = http.delete(config.baseUrl + "/devices/" + deviceId) { bearer() }
        ensureSuccess(resp)
    }

    override suspend fun gateways(): GatewaysPage = get("/gateways")

    override suspend fun conversations(limit: Int?, cursor: String?): ConversationsPage =
        get("/conversations") {
            parameter("limit", limit)
            parameter("cursor", cursor)
        }

    override suspend fun messages(conversationId: String, before: Long?, limit: Int?): MessagesPage =
        get("/conversations/$conversationId/messages") {
            parameter("before", before)
            parameter("limit", limit)
        }

    override suspend fun sendMessage(conversationId: String, req: SendMessageRequest): CommandReceipt =
        post("/conversations/$conversationId/messages", req)

    override suspend fun markRead(conversationId: String, req: ReadCursorRequest): CommandReceipt =
        post("/conversations/$conversationId/read-cursor", req)

    override suspend fun cancelRun(runId: String, req: CancelRunRequest): CommandReceipt =
        post("/runs/$runId/cancel", req)

    override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest): CommandReceipt =
        post("/approvals/$approvalId/decision", req)

    override suspend fun snapshot(): SnapshotResponse = get("/sync/snapshot")

    override suspend fun events(cursor: String, limit: Int?): EventsPage =
        get("/sync/events") {
            parameter("cursor", cursor)
            parameter("limit", limit)
        }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private fun HttpRequestBuilder.bearer() {
        val token = accessTokenProvider() ?: throw HubException.notLoggedIn()
        header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend inline fun <reified B : Any, reified T> post(path: String, body: B, auth: Boolean = true): T {
        val resp = http.post(config.baseUrl + path) {
            if (auth) bearer()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return decode(resp)
    }

    private suspend inline fun <reified T> get(path: String, crossinline configure: HttpRequestBuilder.() -> Unit = {}): T {
        val resp = http.get(config.baseUrl + path) {
            bearer()
            configure()
        }
        return decode(resp)
    }

    private suspend inline fun <reified T> decode(resp: HttpResponse): T {
        ensureSuccess(resp)
        return resp.body()
    }

    /** 非 2xx → [HubException]：载荷按契约错误形状解析；解析不动（非 JSON / 网关层错误页）则用裸 HTTP 状态码兜底。 */
    private suspend fun ensureSuccess(resp: HttpResponse) {
        if (resp.status.isSuccess()) return
        val requestId = resp.headers["x-request-id"]
        val text = resp.bodyAsText()
        val payload = runCatching { HubJson.decodeFromString(HubErrorPayload.serializer(), text) }
            .getOrElse { HubErrorPayload(code = "HTTP-${resp.status.value}", message = text.take(200)) }
        throw HubException(payload, resp.status.value, requestId)
    }
}
