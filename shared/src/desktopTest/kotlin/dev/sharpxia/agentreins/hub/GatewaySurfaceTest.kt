package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.GatewaySummary
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
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
        db.hubQueries.insertGateway(GW, "mini", 0L, T0, "{not-a-json")
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

    // ── fixture ──

    private fun fixtureSnapshot(gw2: Boolean = false) = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = buildList {
            add(
                GatewaySummary(
                    gatewayId = GW, name = "mini", online = false,
                    agents = listOf(AgentRef("main", "Main")),
                ),
            )
            if (gw2) {
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
