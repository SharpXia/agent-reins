package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationOk
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.DevicesPage
import dev.sharpxia.agentreins.hub.auth.LoginOk
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshOk
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.AvatarProfileRequest
import dev.sharpxia.agentreins.hub.avatar.ConversationAvatarProfileEntry
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.CommandsList
import dev.sharpxia.agentreins.hub.projection.ModelsList
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.GatewaysPage
import dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse
import dev.sharpxia.agentreins.hub.projection.MediaUpload
import dev.sharpxia.agentreins.hub.projection.MessagesPage
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SessionsResponse
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
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
    private val http: HttpClient = hubHttpClient(HubJson),
) : HubApi {

    override suspend fun login(req: LoginRequest): LoginOk = post("/auth/login", req, auth = false)

    override suspend fun refresh(req: RefreshRequest): RefreshOk = post("/auth/refresh", req, auth = false)

    override suspend fun registerDevice(req: DeviceRegistrationRequest): DeviceRegistrationOk = post("/devices", req)

    override suspend fun listDevices(): DevicesPage = get("/devices")

    override suspend fun deleteDevice(deviceId: String) {
        val resp = http.delete(config.baseUrl + "/devices/" + deviceId) { bearer() }
        ensureSuccess(resp)
    }

    /** #20（v1.1 additive）：204 无响应体（ApnsTokenUpdated）——与 deleteDevice 同款，不走 decode()。 */
    override suspend fun registerApnsToken(req: ApnsTokenRequest) {
        val resp = http.post(config.baseUrl + "/devices/apns-token") {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        ensureSuccess(resp)
    }

    override suspend fun gateways(): GatewaysPage = get("/gateways")

    // ── v1.3.0 网关供给面（#25/#26/#27，多网关轮；bearerDevice 链与 #6 同款 bearer()） ──

    /**
     * #25（v1.3.0 additive，多网关轮）：注册网关——201 GatewayCreatedResponse，一次性明文
     * bridgeToken 仅本响应下发一次（服务端只存 SHA-256 摘要）；客户端不持久化（会话内存
     * 纪律由上层门面承担，本层无状态）。名称不判重无幂等键，重试安全由调用方按钮防重
     * 保证（契约 #25 description）。
     */
    override suspend fun createGateway(req: CreateGatewayRequest): GatewayCreatedResponse =
        post("/gateways", req)

    /**
     * #26（v1.3.0 additive，多网关轮）：轮换 bridgeToken——200 BridgeTokenIssuedResponse，
     * 旧 Token 进 24h 宽限（新旧双活）；并发轮换 last-write-wins。路径参数 gatewayId 为
     * Hub 签发 ULID（非用户自由文本），直拼与既有路径样板同口径安全。
     */
    override suspend fun rotateBridgeToken(gatewayId: String): BridgeTokenIssuedResponse =
        post("/gateways/$gatewayId/bridge-token", Unit)

    /**
     * #27（v1.3.0 additive，多网关轮）：撤销网关——204 无响应体（GatewayRevoked），与
     * deleteDevice 同款不走 decode()；撤销是终态且对属主幂等（重复 DELETE 204）。
     */
    override suspend fun deleteGateway(gatewayId: String) {
        val resp = http.delete(config.baseUrl + "/gateways/" + gatewayId) { bearer() }
        ensureSuccess(resp)
    }

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

    /**
     * #23（v1.2.0 additive）：multipart 上传（MediaUploadBody：file 必填二进制 + fileName/
     * width/height 可选表单字段）。[mime] 只作 file part 的 Content-Type 声明——服务端以魔数
     * 字节判定白名单类型、不信任本头（契约端点 #23 description）；返回 mimeType 为服务端嗅探值。
     * 路径参数 conversationId 为 Hub ULID（服务端签发句柄），直拼与既有样板同口径安全。
     */
    override suspend fun uploadMedia(
        conversationId: String,
        bytes: ByteArray,
        mime: String,
        fileName: String?,
        width: Int?,
        height: Int?,
    ): MediaUpload {
        // 部件表单以第二位置参数显式传入——submitFormWithBinaryData 的表单参数带缺省（空表单），
        // 缺位会静默上送空 multipart，必须始终传值（Ktor 3.6 该参数在 common 源码中不叫
        // formDataBinaryContent，具名不可靠，位置参数 + 始终传值是防呆必需）。
        val resp = http.submitFormWithBinaryData(
            config.baseUrl + "/conversations/$conversationId/media",
            formData {
                append(
                    "file", bytes,
                    Headers.build { append(HttpHeaders.ContentType, mime) },
                )
                fileName?.let { append("fileName", it) }
                width?.let { append("width", it.toString()) }
                height?.let { append("height", it.toString()) }
            },
        ) {
            bearer()
        }
        return decode(resp)
    }

    /**
     * #24（v1.2.0 additive）：媒体内容拉取（GET /media/{mediaId}/content，Bearer 分支）。
     * 响应为原始字节——不走 JSON decode 管线（ContentNegotiation 只作用于 JSON 壳），
     * ensureSuccess 后直读 ByteArray body。[mediaId] 为 Hub ULID（服务端签发句柄），
     * 直拼与既有路径样板同口径安全；契约 404 HUB-BIZ-002 防枚举由服务端裁决。
     */
    override suspend fun fetchMediaContent(mediaId: String): ByteArray {
        val resp = http.get(config.baseUrl + "/media/$mediaId/content") { bearer() }
        ensureSuccess(resp)
        return resp.body()
    }

    override suspend fun markRead(conversationId: String, req: ReadCursorRequest): CommandReceipt =
        post("/conversations/$conversationId/read-cursor", req)

    /**
     * #29（v1.4.0 additive）：会话删除——204 无响应体（ConversationDeleted），与 deleteGateway
     * 同款 ensureSuccess 不走 decode()；幂等终态（重复 DELETE 204），「不存在/无权」统一 404
     * HUB-BIZ-002 防枚举。路径参数 conversationId 为 Hub ULID（服务端签发句柄），直拼与既有
     * 路径样板同口径安全。
     */
    override suspend fun deleteConversation(conversationId: String) {
        val resp = http.delete(config.baseUrl + "/conversations/" + conversationId) { bearer() }
        ensureSuccess(resp)
    }

    // ── v1.1 新增（#16–#19） ────────────────────────────────────────────

    override suspend fun createConversation(
        gatewayId: String,
        agentId: String,
        req: CreateConversationRequest,
    ): CommandReceipt = post("/gateways/$gatewayId/agents/$agentId/conversations", req)

    override suspend fun compactConversation(conversationId: String): CommandReceipt =
        post("/conversations/$conversationId/compact", Unit)

    override suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse =
        get("/gateways/$gatewayId/agents/$agentId/status")

    /** #28（v1.4.0 additive）：按 Agent 列会话（管理面清单）；路径参数为 Hub ULID，直拼安全（既有 get 样板）。 */
    override suspend fun listAgentSessions(gatewayId: String, agentId: String): SessionsResponse =
        get("/gateways/$gatewayId/agents/$agentId/sessions")

    /** #21（v1.1 additive）：动态斜杠命令目录；路径参数不含用户自由文本，直拼安全（既有 get 样板）。 */
    override suspend fun commands(conversationId: String): CommandsList =
        get("/conversations/$conversationId/commands")

    /** #22（v1.1 additive）：会话模型目录；路径参数不含用户自由文本，直拼安全（既有 get 样板）。 */
    override suspend fun models(conversationId: String): ModelsList =
        get("/conversations/$conversationId/models")

    override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest): CommandReceipt =
        patch("/conversations/$conversationId", req)

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

    // ── v1.5.0 用户形象档（#30/#31；路径无参数直拼 /v1/me/avatar-profile，无用户自由文本） ──

    /**
     * #30（v1.5.0 additive）：形象档读取——纯投影读，200 回 [AvatarProfile]（未设置过 =
     * 默认档 + epoch 哨兵，无 404 语义）；get JSON decode 样板与 get gateways 同式。
     * 收侧宽松解析（未知枚举回落/越界回退正圆）在 [AvatarProfile] 序列化器内完成。
     */
    override suspend fun getAvatarProfile(): AvatarProfile =
        get("/me/avatar-profile")

    /**
     * #31（v1.5.0 additive）：形象档保存——幂等覆盖写，**200 回服务端权威档**（updatedAt
     * 直接回填，客户端无二程 GET；与 apns-token 204 形不同、幂等语义同源）。请求体
     * [AvatarProfileRequest] 三可编辑字段全量覆盖（updatedAt 不入请求）；http.put 直拼 +
     * ensureSuccess，与 deleteConversation 直拼形同源（Ktor HttpMethod.Put 现成）。
     */
    override suspend fun putAvatarProfile(req: AvatarProfileRequest): AvatarProfile {
        val resp = http.put(config.baseUrl + "/me/avatar-profile") {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        return decode(resp)
    }

    // ── v1.5.2 会话形象覆盖档（#32/#33/#34；路径参数 conversationId 为 Hub ULID（服务端签发
    //    句柄），直拼与既有路径样板同口径安全——deleteConversation #29 同款） ──

    /**
     * #32（v1.5.2 additive）：会话覆盖档保存——幂等覆盖写，**200 回服务端权威档**（updatedAt
     * 直接回填）；请求体与 #31 同款三可编辑字段全量覆盖。http.put 直拼 + decode，与
     * putAvatarProfile 同式仅多路径参数。
     */
    override suspend fun putConversationAvatarProfile(conversationId: String, req: AvatarProfileRequest): AvatarProfile {
        val resp = http.put(config.baseUrl + "/conversations/" + conversationId + "/avatar-profile") {
            bearer()
            contentType(ContentType.Application.Json)
            setBody(req)
        }
        return decode(resp)
    }

    /**
     * #33（v1.5.2 additive）：会话覆盖档清除——204 无响应体（ConversationAvatarProfileCleared），
     * 与 deleteConversation 同款 ensureSuccess 不走 decode()；幂等终态（无覆盖重复 DELETE 亦 204），
     * 「不存在/无权」统一 404 HUB-BIZ-002 防枚举。
     */
    override suspend fun deleteConversationAvatarProfile(conversationId: String) {
        val resp = http.delete(config.baseUrl + "/conversations/" + conversationId + "/avatar-profile") { bearer() }
        ensureSuccess(resp)
    }

    /**
     * #34（v1.5.2 additive）：会话覆盖档列表（水合面）——conversationId ULID 升序，无覆盖 =
     * 空数组（非 null）；get JSON decode 样板（reified List），get gateways 同式。
     */
    override suspend fun listConversationAvatarProfiles(): List<ConversationAvatarProfileEntry> =
        get("/me/conversation-avatar-profiles")

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

    private suspend inline fun <reified B : Any, reified T> patch(path: String, body: B): T {
        val resp = http.patch(config.baseUrl + path) {
            bearer()
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
