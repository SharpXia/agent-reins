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

/**
 * 2026-11-13 假未读修复回归：message.created 增量路径旧为盲目 +1（bumpUnread），不带
 * countUnread 的闭值过滤——长任务空转录行/心跳「无回复」行被时间线过滤不可见，
 * maxRenderedSeq 不推进 → 无 read.updated 回声 → 无重算自愈，假未读挂到重进会话才消
 * （resetPagingState 重发同值游标 → 服务端无条件回声 → 重算清零）。修复后增量=countUnread
 * 重算单源：闭值行落库但不计未读；真回复照常 +1；read.updated 自愈路径不受影响。
 */
class UnreadIncrementClosedValueTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun ev(id: String, data: kotlinx.serialization.json.JsonObject) =
        EventEnvelope(
            id = id, seq = 1L, type = EventEnvelope.MESSAGE_CREATED, gatewayId = GW,
            sessionKey = SK, runId = null, ts = T1, data = data,
        )

    private fun createdData(messageId: String, role: String, content: String, seq: Long) =
        buildJsonObject {
            put("messageId", messageId)
            put("role", role)
            put("content", content)
            put("sourceMessageSeq", seq)
        }

    private fun readUpdatedEv(id: String, readUpTo: Long) = EventEnvelope(
        id = id, seq = 1L, type = EventEnvelope.READ_UPDATED, gatewayId = GW,
        sessionKey = SK, runId = null, ts = T1,
        data = buildJsonObject {
            put("conversationId", CONV)
            put("reader", "device-a")
            put("readUpTo", readUpTo)
        },
    )

    private fun unread(db: HubDatabase) =
        db.hubQueries.selectConversation(CONV).executeAsOne().unreadCount

    /** 真回复：seq>游标 → 计 1（增量路径本职不回归）。 */
    @Test
    fun realReplyStillCounts() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        p.applyEvent(ev(E1, createdData("m-1", "assistant", "真实回复", seq = 2L)))

        assertEquals(1L, unread(db))
    }

    /** 闭值行（空正文/「无回复」）：落库但不计未读——修复目标本体。 */
    @Test
    fun closedValueRowsLandButDoNotCount() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        p.applyEvent(ev(E1, createdData("m-1", "assistant", "", seq = 2L)))       // 长任务空转录行
        p.applyEvent(ev(E2, createdData("m-2", "assistant", "无回复", seq = 3L))) // 心跳协议答复
        p.applyEvent(ev(E3, createdData("m-3", "assistant", "   ", seq = 4L)))    // 空白行

        // 行照常落库（快照/翻页 seq 数学不受影响），仅时间线与未读面过滤（Hub.sq 同注释）
        for (id in listOf("m-1", "m-2", "m-3")) {
            assertEquals(id, db.hubQueries.selectMessage(id).executeAsOneOrNull()?.messageId, "$id 应落库")
        }
        assertEquals(0, db.hubQueries.selectMessages(CONV).executeAsList().size, "时间线不呈现闭值行")
        assertEquals(0L, unread(db), "闭值行不得推高未读（时间线不可见行无自愈路径）")
    }

    /** 闭值行后再来真回复：重算口径下真回复计 1，闭值行不累积虚高。 */
    @Test
    fun realReplyAfterNoiseCountsExactlyOnce() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        p.applyEvent(ev(E1, createdData("m-1", "assistant", "", seq = 2L)))
        p.applyEvent(ev(E2, createdData("m-2", "assistant", "无回复", seq = 3L)))
        p.applyEvent(ev(E3, createdData("m-3", "assistant", "真实回复", seq = 4L)))

        assertEquals(1L, unread(db))
    }

    /** 真回复已读自愈链不受影响：read.updated 回声 → 重算清零。 */
    @Test
    fun readUpdatedEchoStillHeals() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        p.applyEvent(ev(E1, createdData("m-1", "assistant", "真实回复", seq = 2L)))
        assertEquals(1L, unread(db))

        p.applyEvent(readUpdatedEv(E2, readUpTo = 2L))
        assertEquals(0L, unread(db))
    }

    private fun fixtureSnapshot() = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = listOf(GatewaySummary(gatewayId = GW, name = "mini", online = false, agents = listOf(AgentRef("main", "Main")))),
        conversations = listOf(
            ConversationRow(
                conversationId = CONV, gatewayId = GW, label = "主线", agentId = "main",
                lastMessagePreview = "", lastSeq = 1L, lastActiveAt = T0,
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
        const val T0 = "2026-11-13T08:00:00.000Z"
        const val T1 = "2026-11-13T08:01:00.000Z"
        const val E1 = "01JFIX00000000000000000001"
        const val E2 = "01JFIX00000000000000000002"
        const val E3 = "01JFIX00000000000000000003"
    }
}
