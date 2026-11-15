package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.GatewaySummary
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SessionSummary
import dev.sharpxia.agentreins.hub.projection.SessionsResponse
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * wp-11 会话管理入口与 gatewayId/agents 透出（desktopTest 验收项）：
 * ① 快照/校准两路径把 ConversationRow.gatewayId 落库（v5 hubConversations.gatewayId 列），
 *   UiConversation 透出不再投影即丢——#16 新建会话入口 (gatewayId, agentId) 主线卡判据的数据基础；
 * ② observeGateways Flow 消费 hubGateways（wp-2 快照填充 + agentsJson 结构化解析为 UiAgentRef）；
 * ③ agentsJson 损坏容错：单行降级空清单、网关本体不丢；
 * ④ gateway.online/offline 事件翻转 online/lastSeenAt 时点（UiGateway.online）。
 */
class GatewaySurfaceTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    /** 未登录客户端：observe 是纯投影库面，认证栈不参与（与 ApprovalProjectionTest 同模式）。 */
    private fun client(db: HubDatabase, scope: CoroutineScope) =
        DefaultHubClient(scope, HubRuntime(scope, GatewaySurfaceMemoryStore()), db)

    // ── ① gatewayId 快照/校准双路径落库并透出 ──

    @Test
    fun snapshotAndCalibrationCarryGatewayId() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())

        // 快照路径：列落库 + UiConversation 透出（wp-11：此前 required 字段在映射处丢弃）
        val convRow = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals(GW, convRow.gatewayId)
        val ui = client(db, backgroundScope).observeConversations().first().single()
        assertEquals(CONV, ui.conversationId)
        assertEquals(GW, ui.gatewayId)
        assertEquals("main", ui.agentId)

        // 校准路径（applyConversations）：同一字段照常透出，且同主键只合并字段
        p.applyConversations(
            listOf(
                ConversationRow(
                    conversationId = CONV, gatewayId = GW, label = "主线改", agentId = "main",
                    lastMessagePreview = "hi", lastSeq = 3L, lastActiveAt = T1,
                    unreadCount = 0, hasActiveRun = false, reconciling = false,
                ),
            ),
        )
        val recalibrated = client(db, backgroundScope).observeConversations().first().single()
        assertEquals(GW, recalibrated.gatewayId)
        assertEquals("主线改", recalibrated.label)
    }

    // ── ② observeGateways：快照 gateways + agentsJson 结构化透出 ──

    @Test
    fun observeGatewaysSurfacesAgentsJson() = runTest {
        val db = newDb()
        DefaultEventProjector(db).applySnapshot(fixtureSnapshot())
        val gateways = client(db, backgroundScope).observeGateways().first()
        assertEquals(1, gateways.size)
        val gw = gateways.single()
        assertEquals(GW, gw.gatewayId)
        assertEquals("mini", gw.name)
        assertFalse(gw.online)
        assertEquals(listOf(UiAgentRef("main", "Main")), gw.agents)
    }

    // ── ③ agentsJson 损坏容错（网关本体不丢，agents 降级空清单） ──

    @Test
    fun observeGatewaysToleratesCorruptedAgentsJson() = runTest {
        val db = newDb()
        DefaultEventProjector(db).applySnapshot(fixtureSnapshot())
        // 直接覆写损坏 JSON（模拟异常写入/历史脏数据）
        db.hubQueries.insertGateway(GW, "mini", 0L, T0, "{not-a-json", provider = "openclaw")
        val gw = client(db, backgroundScope).observeGateways().first().single()
        assertEquals(GW, gw.gatewayId)
        assertEquals("mini", gw.name)
        assertTrue(gw.agents.isEmpty())
    }

    // ── ④ gateway.online/offline 事件翻转 online/lastSeenAt；快照覆盖回基线（多网关互不串线） ──

    @Test
    fun gatewayEventsFlipOnlineAndSnapshotsReset() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        p.applyEvent(
            EventEnvelope(
                id = "01JAAA0000000000000000000A1", seq = 1L, type = EventEnvelope.GATEWAY_ONLINE,
                gatewayId = GW, sessionKey = null, runId = null, ts = T1,
                data = buildJsonObject { put("gatewayId", GW) },
            ),
        )
        // 事件翻转：online 翻真 + lastSeenAt 推进（UiGateway.online 的时点语义）
        val flipped = client(db, backgroundScope).observeGateways().first().single()
        assertTrue(flipped.online)
        assertEquals(T1, flipped.lastSeenAt)

        // 快照整体覆盖（HUB-SYNC-001 重建路径）：gateways 表清空重灌，事件翻转被基线覆盖
        p.applySnapshot(fixtureSnapshot(gw2 = true))
        val gateways = client(db, backgroundScope).observeGateways().first()
        assertEquals(2, gateways.size)
        val mini = gateways.first { it.gatewayId == GW }
        assertFalse(mini.online)
        assertEquals(listOf(UiAgentRef("main", "Main")), mini.agents)
        val render = gateways.first { it.gatewayId == GW2 }
        assertTrue(render.online)
        assertEquals(listOf(UiAgentRef("render", "Render")), render.agents)
    }

    // ── ⑤ v1.4.0：provider 落库读出（快照路径透传 + 回拉 INSERT OR REPLACE 覆盖语义） ──

    @Test
    fun providerPersistsFromSnapshotAndUpsert() = runTest {
        val db = newDb()
        DefaultEventProjector(db).applySnapshot(fixtureSnapshot(gw2 = true))
        val rows = db.hubQueries.selectGateways().executeAsList()
        assertEquals("openclaw", rows.single { it.gatewayId == GW }.provider) // 快照带 provider 透传落库
        assertEquals("", rows.single { it.gatewayId == GW2 }.provider) // 未知省略行落 ''（NOT NULL 缺省）

        // 回拉路径（pullGatewaysIntoProjection 同构调用）：INSERT OR REPLACE 全列覆盖更新 provider
        db.hubQueries.insertGateway(GW, "mini", 1L, T1, "[]", provider = "hermes")
        assertEquals(
            "hermes",
            db.hubQueries.selectGateways().executeAsList().single { it.gatewayId == GW }.provider,
        )
    }

    // ── ⑥ v1.4.0：deleteConversation/listAgentSessions 门面（REST 直调 + 204 后行删 / 响应透出） ──

    @Test
    fun deleteConversationFacadeCallsRestAndRemovesCardRow() = runTest {
        val db = newDb()
        DefaultEventProjector(db).applySnapshot(fixtureSnapshot())
        val api = SessionAdminStubApi()
        val client = client(db, backgroundScope)
        client.sessionAdminApiOverride = api

        client.deleteConversation(CONV)
        assertEquals(listOf(CONV), api.deleted) // REST 面收到会话句柄（tombstone 请求原样）
        assertEquals(
            0,
            db.hubQueries.selectConversations().executeAsList().size, // 204 后行删（乐观收敛）
        )
    }

    @Test
    fun listAgentSessionsFacadeReturnsResponse() = runTest {
        val db = newDb()
        val api = SessionAdminStubApi(
            sessions = SessionsResponse(
                listOf(
                    SessionSummary(conversationId = CONV, label = "主线", lastActivityAt = T0, isMainSession = true),
                    SessionSummary(
                        conversationId = "01JTASK0000000000000000000", label = "任务",
                        lastActivityAt = T1, isMainSession = false,
                    ),
                ),
            ),
        )
        val client = client(db, backgroundScope)
        client.sessionAdminApiOverride = api

        val resp = client.listAgentSessions(GW, "main")
        assertEquals(2, resp.sessions.size)
        assertEquals(true, resp.sessions[0].isMainSession) // 主线族谓词原样透出（rename 门判定依据）
        assertEquals(false, resp.sessions[1].isMainSession)
        assertEquals(listOf(GW to "main"), api.listed) // 路由参数原样送达
    }

    @Test
    fun deleteAndListFacadesWithoutLoginThrow() = runTest {
        val db = newDb()
        val client = client(db, backgroundScope) // 未登录：authStack null 且无 override → 栈查找降级
        assertFailsWith<IllegalStateException> { client.deleteConversation(CONV) }
        assertFailsWith<IllegalStateException> { client.listAgentSessions(GW, "main") }
    }

    // ── fixture ──

    private fun fixtureSnapshot(gw2: Boolean = false) = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = buildList {
            add(
                GatewaySummary(
                    gatewayId = GW, name = "mini", online = false,
                    agents = listOf(AgentRef("main", "Main")),
                    provider = "openclaw", // v10：快照行 provider 落库
                ),
            )
            if (gw2) {
                // GW2 不带 provider（未知省略口径）：落库为 ''（省略 ≠ 空串的第二形态只作用于
                // 服务端读面，客户端列 NOT NULL 缺省 ''）
                add(GatewaySummary(gatewayId = GW2, name = "render", online = true, agents = listOf(AgentRef("render", "Render"))))
            }
        },
        conversations = listOf(
            ConversationRow(
                conversationId = CONV, gatewayId = GW, label = "主线", agentId = "main",
                lastMessagePreview = "", lastSeq = 0L, lastActiveAt = T0,
                unreadCount = 0, hasActiveRun = false, reconciling = false,
            ),
        ),
        messages = emptyMap(),
        readCursors = emptyMap(),
    )

    companion object {
        const val GW = "01JGW000000000000000000000"
        const val GW2 = "01JGW1000000000000000000000"
        const val CONV = "01JCONV00000000000000000000"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-05T08:00:00.000Z"
        const val T1 = "2026-11-05T08:05:00.000Z"
    }
}

/** 内存凭据仓（测试用，避免 desktop 平台实现触碰真实文件系统；与 ApprovalProjectionTest 的
 *  同名私有类重名——本文件用独立命名避免同包顶层类重声明冲突）。 */
private class GatewaySurfaceMemoryStore : CredentialStore {
    private var token: String? = null
    override suspend fun saveRefreshToken(token: String) { this.token = token }
    override suspend fun loadRefreshToken(): String? = token
    override suspend fun clear() { token = null }
}

/** v1.4.0 会话管理直调通道 stub（sessionAdminApiOverride 注入面）：记录 delete/list 调用、
 *  list 返回预设响应；其余端点恒不触达（TODO 与 RecordingApi 同款口径）。 */
private class SessionAdminStubApi(
    val sessions: SessionsResponse = SessionsResponse(emptyList()),
) : HubApi {
    val deleted = mutableListOf<String>()
    val listed = mutableListOf<Pair<String, String>>()

    override suspend fun deleteConversation(conversationId: String) {
        deleted += conversationId
    }

    override suspend fun listAgentSessions(gatewayId: String, agentId: String): SessionsResponse {
        listed += gatewayId to agentId
        return sessions
    }

    override suspend fun login(req: LoginRequest) = TODO()
    override suspend fun refresh(req: RefreshRequest) = TODO()
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
    override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest) = TODO()
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
