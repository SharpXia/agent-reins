package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.GatewaySummary
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 2026-11-06 真机修复回归：message.created 载荷 sourceMessageSeq 缺失（bridge 修复前的
 * eventlog 存量行形状，hub-wss-frames-v1 DataMessageCreated required 但历史入库不带）曾令
 * 必填解码整帧失败 → 消息族静默 skip，除冷启动快照外任何消息进不了详情页。修复后：
 * ① 缺 sourceMessageSeq（=0）→ 解码放行，seq 按当前权威 lastSeq+1 兜底落库（delta 占位同款）；
 * ② 带 sourceMessageSeq → 载荷 seq 权威（契约路径行为不变）；
 * ③ 用户回显不带幂等键 → 权威行落库 + 同会话已 DELIVERED 乐观行清除（防双气泡）；
 * ④ 用户回显带幂等键 → 乐观行整体提升（既有行为，seq 走兜底口径）。
 */
class MessageCreatedSeqFallbackTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun rows(db: HubDatabase) =
        db.hubQueries.selectMessages(CONV).executeAsList().sortedBy { it.seq }

    private fun ev(id: String, data: kotlinx.serialization.json.JsonObject, sk: String = SK) =
        EventEnvelope(
            id = id, seq = 1L, type = EventEnvelope.MESSAGE_CREATED, gatewayId = GW,
            sessionKey = sk, runId = null, ts = T1, data = data,
        )

    private fun createdData(
        messageId: String,
        role: String,
        content: String,
        seq: Long? = null,
        idempotencyKey: String? = null,
    ): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("messageId", messageId)
        put("role", role)
        put("content", content)
        if (seq != null) put("sourceMessageSeq", seq)
        if (idempotencyKey != null) put("idempotencyKey", idempotencyKey)
    }

    private fun seedConvKey(db: HubDatabase) {
        // 自学习映射直连（对位 command.acked 通道②学习成果）
        db.hubQueries.upsertConvKey(GW, SK, CONV)
    }

    /** ① 存量形状（无 sourceMessageSeq）→ 兜底 seq=lastSeq+1 落库，不再整帧 skip。 */
    @Test
    fun legacyEventWithoutSeqStillLandsWithFallbackSeq() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot(lastSeq = 3L))
        seedConvKey(db)

        p.applyEvent(ev(E1, createdData("msg-a", "assistant", "回复甲")))

        val list = rows(db)
        assertEquals(1, list.size)
        assertEquals(4L, list.single().seq) // 3 + 1
        assertEquals("assistant", list.single().role)
        assertEquals("final", list.single().state)
    }

    /** ② 契约路径（带 sourceMessageSeq）→ 载荷 seq 权威；连续缺省行按 lastSeq+1 顺延不互撞。 */
    @Test
    fun contractSeqWinsAndFallbackRowsAdvanceWithoutCollision() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot(lastSeq = 0L))
        seedConvKey(db)

        p.applyEvent(ev(E1, createdData("msg-a", "assistant", "甲", seq = 7L)))
        p.applyEvent(ev(E2, createdData("msg-b", "assistant", "乙"))) // 缺省 → 7+1
        p.applyEvent(ev(E3, createdData("msg-c", "assistant", "丙"))) // 缺省 → 8+1

        val seqs = rows(db).map { it.seq to it.messageId }
        assertEquals(listOf(7L to "msg-a", 8L to "msg-b", 9L to "msg-c"), seqs)
    }

    /** ③ 回显不带幂等键：权威行落库 + 同会话已 DELIVERED 乐观行清除（防双气泡）；SENDING 行不动。 */
    @Test
    fun echoWithoutIdemKeyClearsDeliveredOptimisticOnly() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot(lastSeq = 0L))
        seedConvKey(db)
        // 在途两行：已 DELIVERED（等回显收敛）+ 仍 SENDING（后发未回显）
        db.hubQueries.upsertMessage(
            messageId = "opti-delivered", conversationId = CONV, seq = Long.MAX_VALUE,
            role = "user", content = "先发", state = "final", reason = null,
            uiState = "delivered", commandId = null, idempotencyKey = "idem-1", ts = T0,
        )
        db.hubQueries.upsertMessage(
            messageId = "opti-sending", conversationId = CONV, seq = Long.MAX_VALUE,
            role = "user", content = "后发", state = "final", reason = null,
            uiState = "sending", commandId = null, idempotencyKey = "idem-2", ts = T0,
        )

        p.applyEvent(ev(E1, createdData("srv-echo-1", "user", "先发")))

        val ids = rows(db).map { it.messageId }
        assertTrue("srv-echo-1" in ids, "权威回显行必须落库")
        assertTrue("opti-sending" in ids, "仍在途 SENDING 乐观行不得误清")
        assertTrue("opti-delivered" !in ids, "已 DELIVERED 乐观行须被权威行取代")
    }

    /** ④ 回显带幂等键：乐观行整体提升（既有行为），seq 走兜底口径（快照 lastSeq+1）。 */
    @Test
    fun echoWithIdemKeyPromotesOptimisticRowWithFallbackSeq() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot(lastSeq = 2L))
        seedConvKey(db)
        db.hubQueries.upsertMessage(
            messageId = "opti-1", conversationId = CONV, seq = Long.MAX_VALUE,
            role = "user", content = "测试", state = "final", reason = null,
            uiState = "sending", commandId = null, idempotencyKey = "idem-1", ts = T0,
        )

        p.applyEvent(ev(E1, createdData("srv-echo-1", "user", "测试", idempotencyKey = "idem-1")))

        val list = rows(db)
        assertEquals(1, list.size, "提升路径不得产生双行")
        assertEquals("srv-echo-1", list.single().messageId)
        assertEquals(3L, list.single().seq)
        assertEquals("delivered", list.single().uiState)
        assertEquals("idem-1", list.single().idempotencyKey) // promoteLocalEcho 不清幂等键列（对账留痕）
    }

    private fun fixtureSnapshot(lastSeq: Long) = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = listOf(GatewaySummary(gatewayId = GW, name = "mini", online = false, agents = listOf(AgentRef("main", "Main")))),
        conversations = listOf(
            ConversationRow(
                conversationId = CONV, gatewayId = GW, label = "主线", agentId = "main",
                lastMessagePreview = "", lastSeq = lastSeq, lastActiveAt = T0,
                unreadCount = 0, hasActiveRun = false, reconciling = false,
            ),
        ),
        messages = emptyMap(),
        readCursors = emptyMap(),
    )

    companion object {
        const val GW = "01JGW000000000000000000000"
        const val CONV = "01JCONV00000000000000000000"
        const val SK = "agent:main:main"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-06T08:00:00.000Z"
        const val T1 = "2026-11-06T08:01:00.000Z"
        const val E1 = "01JFIX00000000000000000001"
        const val E2 = "01JFIX00000000000000000002"
        const val E3 = "01JFIX00000000000000000003"
    }
}
