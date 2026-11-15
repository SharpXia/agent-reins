package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.DefaultTokenManager
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshOk
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.command.ConversationCreateEnvelope
import dev.sharpxia.agentreins.hub.command.DefaultCommandClient
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #16 conversation.create 线格式与入队用例（v1.4.0 多主会话轮 2026-11-16）：
 * ①–③ CreateConversationRequest 序列化口径（HubJson：encodeDefaults=false + explicitNulls=false）
 *   ——v1.1 mainSession 主线锚定与缺省不编码的既有线格式；
 * ④ newMainSession 线格式与互斥体（v1.4.0 additive）：true = 新开主线
 *   agent:<agentId>:main-<idempotencyKey>（Hub 以请求幂等键确定性派生，客户端永不拼键，ADR-14），
 *   客户端构造恒 mainSession=!newMainSession 不双真（服务端同真 400 HUB-BIZ-004）；
 * ⑤ createConversation 入队（DefaultCommandClient 真跑）：客户端生成幂等键、conversationId=null、
 *   payloadJson 存路由信封 ConversationCreateEnvelope、REST 实收请求与信封 request 同键同载荷
 *   （评审 H4/H5：重试载荷逐字节同 → 服务端确定性派生指纹不漂移）；
 * ⑥ retryUnfinished 对 conversation.create 信封重放：同键同载荷重建请求；解码失败（存量/异形
 *   行）按既有纪律跳过不重放。
 */
class ConversationCreateMainSessionTest {

    // ── ① 显式 true（旧入口缺省语义）必编码 ──

    @Test
    fun mainSessionTrueIsEncoded() {
        assertEquals(
            """{"mainSession":true}""",
            HubJson.encodeToString(CreateConversationRequest.serializer(), CreateConversationRequest(mainSession = true)),
        )
    }

    @Test
    fun labelAndMainSessionEncodeTogether() {
        assertEquals(
            """{"label":"新会话","mainSession":true}""",
            HubJson.encodeToString(
                CreateConversationRequest.serializer(),
                CreateConversationRequest(label = "新会话", mainSession = true),
            ),
        )
    }

    @Test
    fun mainSessionAbsentByDefault() {
        // 缺省（不带字段）= null 不编码：与既有 0.5.x 线格式完全一致（契约 additive）
        assertEquals("{}", HubJson.encodeToString(CreateConversationRequest.serializer(), CreateConversationRequest()))
        assertEquals(
            """{"label":"重构讨论"}""",
            HubJson.encodeToString(
                CreateConversationRequest.serializer(),
                CreateConversationRequest(label = "重构讨论"),
            ),
        )
    }

    // ── ④ v1.4.0：newMainSession 线格式与互斥体 ──

    @Test
    fun newMainSessionWireShapeAndMutex() {
        // 客户端构造恒 mainSession=!newMainSession：true 分支 = mainSession 显式 false（契约明文
        // 「含显式 false」= 缺省语义，不撞服务端互斥）+ newMainSession=true（新主线旗标）
        assertEquals(
            """{"mainSession":false,"newMainSession":true}""",
            HubJson.encodeToString(
                CreateConversationRequest.serializer(),
                CreateConversationRequest(mainSession = false, newMainSession = true),
            ),
        )
        // 互斥体线格式参考形状（服务端同真 400 HUB-BIZ-004 闭集；DefaultCommandClient 构造器
        // mainSession=!newMainSession 结构上保证客户端永不产生双真）
        assertEquals(
            """{"mainSession":true,"newMainSession":true}""",
            HubJson.encodeToString(
                CreateConversationRequest.serializer(),
                CreateConversationRequest(mainSession = true, newMainSession = true),
            ),
        )
    }

    // ── ⑤ v1.4.0：createConversation 入队（路由信封 + 客户端幂等键 + 同键同载荷） ──

    @Test
    fun createConversationEnqueuesRouteEnvelope() = runTest {
        val db = newDb()
        val api = ConversationCreateStubApi()
        val tm = DefaultTokenManager(api, ConversationCreateMemStore())
        assertTrue(tm.restoreFromStoredRefreshToken()) // 登录态起步（withBearer 就绪）
        val commands = DefaultCommandClient(api, tm, db)

        // 默认主线（旧入口语义）：newMainSession 缺省 false → mainSession=true
        val receipt = commands.createConversation(GW, AGENT, "新主线")
        assertNotNull(receipt) // 提交成功取得 PERSISTED 回执（契约智能转换）

        val cmd = db.hubQueries.selectCommandByKey(receipt.idempotencyKey).executeAsOne()
        assertEquals("conversation.create", cmd.type)
        assertEquals("PERSISTED", cmd.state)
        assertNull(cmd.conversationId) // conversationId=null（重放路由参数唯一来源 = 信封）
        assertEquals(receipt.idempotencyKey, cmd.idempotencyKey) // 客户端生成幂等键（队列 PK）

        // 路由信封解码：gatewayId/agentId + request（含客户端幂等键）
        val env = HubJson.decodeFromString(ConversationCreateEnvelope.serializer(), cmd.payloadJson)
        assertEquals(GW, env.gatewayId)
        assertEquals(AGENT, env.agentId)
        assertEquals(receipt.idempotencyKey, env.request.idempotencyKey) // 信封 request 与队列条目同键
        assertEquals(true, env.request.mainSession)
        // newMainSession=newMainSession 直传（任务书公式）：Boolean?=false 非默认 → 信封编码显式
        // false——契约明文「缺省（不带字段，含显式 false）」同语义；重放解码/重编码字节一致
        assertEquals(false, env.request.newMainSession)

        // 同键同载荷（H4/H5）：REST 实收请求与信封 request 逐字段同（data class 全等）
        assertEquals(GW, api.createdGatewayIds.single())
        assertEquals(AGENT, api.createdAgentIds.single())
        assertEquals(env.request, api.createdRequests.single())
        // 字节级同形（重新编码比对，encodeDefaults=false 口径下逐字节确定）
        assertEquals(
            HubJson.encodeToString(CreateConversationRequest.serializer(), env.request),
            HubJson.encodeToString(CreateConversationRequest.serializer(), api.createdRequests.single()),
        )

        // 新主线分支：newMainSession=true → mainSession=false（显式 false = 缺省语义，不双真）
        val receipt2 = commands.createConversation(GW, AGENT, null, newMainSession = true)
        assertNotNull(receipt2)
        val env2 = HubJson.decodeFromString(
            ConversationCreateEnvelope.serializer(),
            db.hubQueries.selectCommandByKey(receipt2.idempotencyKey).executeAsOne().payloadJson,
        )
        assertEquals(false, env2.request.mainSession)
        assertEquals(true, env2.request.newMainSession)
        assertEquals(receipt2.idempotencyKey, env2.request.idempotencyKey)
        assertEquals(env2.request, api.createdRequests[1])
    }

    // ── ⑥ v1.4.0：retryUnfinished 对 conversation.create 信封重放 ──

    @Test
    fun retryUnfinishedReplaysConversationCreateEnvelope() = runTest {
        val db = newDb()
        val api = ConversationCreateStubApi()
        val tm = DefaultTokenManager(api, ConversationCreateMemStore())
        assertTrue(tm.restoreFromStoredRefreshToken())
        val commands = DefaultCommandClient(api, tm, db)

        // 直接造 PENDING_LOCAL 队列行（模拟提交前断线残留）：payloadJson = 路由信封
        val envelope = ConversationCreateEnvelope(
            gatewayId = GW,
            agentId = AGENT,
            request = CreateConversationRequest(idempotencyKey = KEY, label = "断线主线", mainSession = true),
        )
        db.hubQueries.insertCommand(
            idempotencyKey = KEY, commandId = null, conversationId = null,
            runId = null, approvalId = null, type = "conversation.create",
            payloadJson = HubJson.encodeToString(ConversationCreateEnvelope.serializer(), envelope),
            state = "PENDING_LOCAL", errorCode = null, createdAt = T0, updatedAt = T0,
        )

        commands.retryUnfinished()

        // 重放 = 同键同载荷：REST 收到 (gatewayId, agentId, 与信封 request 全等的请求体)
        assertEquals(GW, api.createdGatewayIds.single())
        assertEquals(AGENT, api.createdAgentIds.single())
        assertEquals(KEY, api.createdRequests.single().idempotencyKey)
        assertEquals(envelope.request, api.createdRequests.single())
        // 字节级同载荷：重放重建的请求与信封 request 重新编码逐字节同 → 服务端确定性派生
        // 同指纹 → 202 + 原 commandId（H4/H5 重试安全闭环）
        assertEquals(
            HubJson.encodeToString(CreateConversationRequest.serializer(), envelope.request),
            HubJson.encodeToString(CreateConversationRequest.serializer(), api.createdRequests.single()),
        )
        assertEquals("PERSISTED", db.hubQueries.selectCommandByKey(KEY).executeAsOne().state)
    }

    @Test
    fun retryUnfinishedSkipsUndecodableConversationCreate() = runTest {
        val db = newDb()
        val api = ConversationCreateStubApi()
        val tm = DefaultTokenManager(api, ConversationCreateMemStore())
        assertTrue(tm.restoreFromStoredRefreshToken())
        val commands = DefaultCommandClient(api, tm, db)

        // 异形行（存量/坏数据）：payloadJson 解码失败按既有纪律跳过不重放
        db.hubQueries.insertCommand(
            idempotencyKey = KEY, commandId = null, conversationId = null,
            runId = null, approvalId = null, type = "conversation.create",
            payloadJson = "{not-a-json",
            state = "PENDING_LOCAL", errorCode = null, createdAt = T0, updatedAt = T0,
        )

        commands.retryUnfinished()

        assertTrue(api.createdRequests.isEmpty()) // 未触达 REST
        assertEquals("PENDING_LOCAL", db.hubQueries.selectCommandByKey(KEY).executeAsOne().state) // 行保留待人工/清理
    }

    // ── fixture ──

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    companion object {
        const val GW = "01JGW000000000000000000000"
        const val AGENT = "main"
        const val KEY = "11111111-2222-4333-8444-555555555555"
        const val T0 = "2026-11-16T08:00:00.000Z"
    }
}

/** 内存凭据仓（独立命名：同包顶层 private 类跨文件重声明冲突，见 GatewaySurfaceTest 同注）。 */
private class ConversationCreateMemStore : CredentialStore {
    private var stored: String? = Companion.SEED

    override suspend fun saveRefreshToken(token: String) { stored = token }
    override suspend fun loadRefreshToken(): String? = stored
    override suspend fun clear() { stored = null }

    private companion object {
        /** 测试占位种子（桩内无真实凭据；refresh 桩自请求参数派生后续值）。 */
        const val SEED = "stub-refresh-seed"
    }
}

/** 记录 conversation.create 提交与 refresh 编排的 REST 桩（HubApnsTokenTest.RecordingApi 同款口径）。
 *  refresh 返回值从请求参数派生（测试桩无凭据字面量）。 */
private class ConversationCreateStubApi : HubApi {
    var refreshCalls = 0
    val createdGatewayIds = mutableListOf<String>()
    val createdAgentIds = mutableListOf<String>()
    val createdRequests = mutableListOf<CreateConversationRequest>()

    override suspend fun refresh(req: RefreshRequest): RefreshOk {
        refreshCalls++
        return RefreshOk(
            deviceId = "dev-1",
            accessToken = req.refreshToken + "-access-" + refreshCalls,
            expiresIn = 7200,
            refreshToken = req.refreshToken + "-rotated-" + refreshCalls,
            refreshExpiresIn = 2592000,
        )
    }

    override suspend fun createConversation(
        gatewayId: String,
        agentId: String,
        req: CreateConversationRequest,
    ): CommandReceipt {
        createdGatewayIds += gatewayId
        createdAgentIds += agentId
        createdRequests += req
        return CommandReceipt(
            commandId = "cmd-stub",
            idempotencyKey = req.idempotencyKey ?: "",
            state = "PERSISTED",
            acceptedAt = "2026-11-16T08:00:00.000Z",
        )
    }

    override suspend fun login(req: LoginRequest) = TODO()
    override suspend fun registerDevice(req: DeviceRegistrationRequest) = TODO()
    override suspend fun listDevices() = TODO()
    override suspend fun deleteDevice(deviceId: String) = TODO()
    override suspend fun registerApnsToken(req: ApnsTokenRequest) = TODO()
    override suspend fun gateways() = TODO()
    override suspend fun createGateway(req: CreateGatewayRequest) = TODO()
    override suspend fun rotateBridgeToken(gatewayId: String) = TODO()
    override suspend fun deleteGateway(gatewayId: String) = TODO()
    override suspend fun conversations(limit: Int?, cursor: String?) = TODO()
    override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = TODO()
    override suspend fun sendMessage(conversationId: String, req: SendMessageRequest) = TODO()
    override suspend fun uploadMedia(
        conversationId: String, bytes: ByteArray, mime: String,
        fileName: String?, width: Int?, height: Int?,
    ) = TODO()
    override suspend fun fetchMediaContent(mediaId: String) = TODO()
    override suspend fun markRead(conversationId: String, req: ReadCursorRequest) = TODO()
    override suspend fun compactConversation(conversationId: String) = TODO()
    override suspend fun agentStatus(gatewayId: String, agentId: String) = TODO()
    override suspend fun commands(conversationId: String) = TODO()
    override suspend fun models(conversationId: String) = TODO()
    override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest) = TODO()
    override suspend fun cancelRun(runId: String, req: CancelRunRequest) = TODO()
    override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest) = TODO()
    override suspend fun snapshot() = TODO()
    override suspend fun events(cursor: String, limit: Int?) = TODO()
}