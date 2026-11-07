package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.GatewaysPage
import dev.sharpxia.agentreins.hub.projection.GatewaySummary
import dev.sharpxia.agentreins.hub.projection.MessageRow
import dev.sharpxia.agentreins.hub.projection.MessagesPage
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wp-12 历史翻页 fixture 测试：REST #8 `?before=<seq>` 向上翻页接入面。
 * ① 请求参数与游标链（before/limit 按契约下发、hasMore/oldestSeq 消费）；
 * ② 行经 upsertMessage 合并——messageId 主键天然去重、seq 升序全序列；
 * ③ 事件流行合并去重——REST 翻页行与事件流（created/delta）同库同键互不冲突（wp-1 无交互）；
 * ④ 化身围栏——取数途中 lifecycleRevision 变化整页丢弃。
 */
class HubMessagesPagingTest {
    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun newClient(db: HubDatabase): DefaultHubClient =
        DefaultHubClient(CoroutineScope(Dispatchers.Unconfined), HubRuntime(CoroutineScope(Dispatchers.Unconfined), MemStore()), db)

    // ── ①② 翻页请求参数 / 游标链 / upsert 合并升序去重 ─────────────────────────

    @Test
    fun loadEarlierPagesAndMergesAscending() = runTest {
        val db = newDb()
        val client = newClient(db)
        val stub = PagingStubApi()
        client.messagesApiOverride = stub
        DefaultEventProjector(db).applySnapshot(fixtureSnapshot()) // 尾页 seq 51..60，conv.lastSeq=60

        // 第 1 翻：before=51（当前最老已载 seq），契约 limit 下发 50
        val page1 = client.loadEarlierMessages(CONV, before = 51L)
        assertNotNull(page1)
        assertEquals(51L, stub.lastBefore)
        assertEquals(50, stub.lastLimit)
        assertEquals(CONV, stub.lastConversationId)
        assertTrue(page1.hasMore)
        assertEquals(41L, page1.oldestSeq)
        assertEquals(50L, page1.newestSeq)
        assertEquals(10, page1.count)
        // 合并后 seq 升序全序列、无重复（41..60 共 20 行）
        val rows1 = db.hubQueries.selectMessages(CONV).executeAsList()
        assertEquals(20, rows1.size)
        assertEquals((41L..60L).toList(), rows1.map { it.seq })
        assertEquals(rows1.map { it.seq }.distinct().size, rows1.size)
        // 翻页不推进会话口径：lastSeq 不被历史行污染（占位行 seq=lastSeq+1 的口径保持）
        assertEquals(60L, db.hubQueries.selectConversation(CONV).executeAsOne().lastSeq)
        // DB 侧窗口翻页原语（selectMessagesBefore）：before=51 下方最新 10 行、DESC 呈现
        val window = db.hubQueries.selectMessagesBefore(CONV, seq = 51L, limit = 50).executeAsList()
        assertEquals((41L..50L).reversed().toList(), window.map { it.seq })

        // 第 2 翻：游标按服务端 oldestSeq 推进（41）→ 取到头，hasMore=false
        stub.nextPage = { MessagesPage(CONV, rows(1L, 40L), hasMore = false, oldestSeq = 1L, newestSeq = 40L) }
        val page2 = client.loadEarlierMessages(CONV, before = page1.oldestSeq!!)
        assertNotNull(page2)
        assertEquals(41L, stub.lastBefore)
        assertFalse(page2.hasMore)
        assertEquals(1L, page2.oldestSeq)
        assertEquals(60, db.hubQueries.selectMessages(CONV).executeAsList().size)

        // 第 3 翻：同窗口重复翻页 → 同 messageId 再合并，upsert 主键天然去重（60 行不增）
        val page3 = client.loadEarlierMessages(CONV, before = 41L)
        assertNotNull(page3)
        assertFalse(page3.hasMore)
        assertEquals(60, db.hubQueries.selectMessages(CONV).executeAsList().size)
    }

    // ── ③ 事件流行合并去重（REST 翻页行 × 事件流同库同键；wp-1 delta 只作用既有行） ──

    @Test
    fun pagedRowsDedupAgainstEventRows() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV) // 事件路由先决
        val client = newClient(db)
        val stub = PagingStubApi()
        client.messagesApiOverride = stub

        // 事件流先落一行（对账/迟到重放路径）：message.created seq=45
        val m45 = row(45L).messageId
        var n = 400
        fun nextId(): String = "01JAAA00000000000000000${(++n).toString().padStart(4, '0')}"
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", m45); put("role", "assistant"); put("content", "event-version"); put("sourceMessageSeq", 45L)
        }))
        assertEquals("event-version", db.hubQueries.selectMessage(m45).executeAsOne().content)

        // 同一 messageId 也出现在 REST 翻页行（服务端权威内容不同）→ upsert 合并成单行
        stub.nextPage = {
            MessagesPage(CONV, rows(41L, 44L) + row(45L, content = "rest-version") + rows(46L, 50L),
                hasMore = true, oldestSeq = 41L, newestSeq = 50L)
        }
        val page = client.loadEarlierMessages(CONV, before = 51L)
        assertNotNull(page)
        val m45Rows = db.hubQueries.selectMessages(CONV).executeAsList().filter { it.seq == 45L }
        assertEquals(1, m45Rows.size) // 无双行
        assertEquals("rest-version", m45Rows.single().content) // 服务端权威覆盖
        assertEquals(20, db.hubQueries.selectMessages(CONV).executeAsList().size) // 41..50 共 10 行 + 快照 51..60 共 10 行

        // wp-1 事件流作用既有行：锚定 delta 追加 → 只改本行内容，行数不变
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", m45); put("deltaText", "!")
        }))
        assertEquals("rest-version!", db.hubQueries.selectMessage(m45).executeAsOne().content)
        assertEquals(20, db.hubQueries.selectMessages(CONV).executeAsList().size)
        // created 权威终态同键覆盖收敛 → final
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", m45); put("role", "assistant"); put("content", "authoritative"); put("sourceMessageSeq", 45L)
        }))
        val finalRow = db.hubQueries.selectMessage(m45).executeAsOne()
        assertEquals("final", finalRow.state)
        assertEquals("authoritative", finalRow.content)
        assertEquals(1, db.hubQueries.selectMessages(CONV).executeAsList().count { it.seq == 45L })
    }

    // ── ④ 化身围栏：取数途中 lifecycleRevision 变化 → 整页丢弃（防旧化身行复活） ──

    @Test
    fun incarnationFlipDiscardsPage() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)
        val client = newClient(db)
        // 取数途中事件流推进化身基准（快照行 -1 未知 → session.updated 只记录 revision）：
        // 前后两次读 lifecycleRevision 不一致 → 门卫拦截
        val stub = PagingStubApi(onFetch = {
            p.applyEvent(ev("01JAAA00000000000000000999", EventEnvelope.SESSION_UPDATED, data = buildJsonObject {
                put("sessionKey", SK); put("agentId", "main"); put("label", "v2")
                put("lifecycleRevision", 5L); put("hasActiveRun", false)
            }))
        })
        client.messagesApiOverride = stub
        val page = client.loadEarlierMessages(CONV, before = 51L)
        assertNull(page)
        assertEquals(0, db.hubQueries.selectMessages(CONV).executeAsList().count { it.seq < 51L }) // 页未合入
        // 对照：无化身变化时同页正常合入
        val ok = client.loadEarlierMessages(CONV, before = 51L)
        assertNotNull(ok)
        assertEquals(20, db.hubQueries.selectMessages(CONV).executeAsList().size)
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    /** 快照：长会话尾页（seq 51..60 共 10 行，lastSeq=60）——>50 行会话的翻页前置形态。 */
    private fun fixtureSnapshot() = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = listOf(GatewaySummary(gatewayId = GW, name = "mini", online = false, agents = listOf(AgentRef("main", "Main")))),
        conversations = listOf(
            ConversationRow(
                conversationId = CONV, gatewayId = GW, label = "主线", agentId = "main",
                lastMessagePreview = "msg-60", lastSeq = 60L, lastActiveAt = T0,
                unreadCount = 0, hasActiveRun = false, reconciling = false,
            ),
        ),
        messages = mapOf(CONV to rows(51L, 60L)),
        readCursors = mapOf(CONV to 55L),
    )

    private fun ev(id: String, type: String, data: kotlinx.serialization.json.JsonObject, runId: String? = null) =
        EventEnvelope(id = id, seq = 1L, type = type, gatewayId = GW, sessionKey = SK, runId = runId, ts = T0, data = data)

    /** 只实现 messages 的 REST 桩：记录请求参数、按页返回。 */
    private class PagingStubApi(private val onFetch: suspend () -> Unit = {}) : HubApi {
        var lastConversationId: String? = null
        var lastBefore: Long? = null
        var lastLimit: Int? = null
        var nextPage: () -> MessagesPage = {
            MessagesPage(HubMessagesPagingTest.CONV, rows(41L, 50L), hasMore = true, oldestSeq = 41L, newestSeq = 50L)
        }

        override suspend fun messages(conversationId: String, before: Long?, limit: Int?): MessagesPage {
            lastConversationId = conversationId
            lastBefore = before
            lastLimit = limit
            onFetch()
            return nextPage()
        }

        override suspend fun login(req: dev.sharpxia.agentreins.hub.auth.LoginRequest) = TODO()
        override suspend fun refresh(req: dev.sharpxia.agentreins.hub.auth.RefreshRequest) = TODO()
        override suspend fun registerDevice(req: dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest) = TODO()
        override suspend fun listDevices() = TODO()
        override suspend fun deleteDevice(deviceId: String) = TODO()
        override suspend fun registerApnsToken(req: dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest) = TODO()
        override suspend fun gateways() = TODO()
        override suspend fun conversations(limit: Int?, cursor: String?) = TODO()
        override suspend fun sendMessage(conversationId: String, req: SendMessageRequest) = TODO()
        override suspend fun markRead(conversationId: String, req: ReadCursorRequest) = TODO()
        override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest) = TODO()
        override suspend fun compactConversation(conversationId: String) = TODO()
        override suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse = TODO()
        override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest) = TODO()
        override suspend fun cancelRun(runId: String, req: CancelRunRequest) = TODO()
        override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest) = TODO()
        override suspend fun snapshot() = TODO()
        override suspend fun events(cursor: String, limit: Int?) = TODO()
    }

    private class MemStore : CredentialStore {
        override suspend fun saveRefreshToken(token: String) {}
        override suspend fun loadRefreshToken(): String? = null
        override suspend fun clear() {}
    }

    companion object {
        const val GW = "01JGW000000000000000000000"
        const val CONV = "01JCONV00000000000000000000"
        const val SK = "sk-main"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-03T08:00:00.000Z"
    }
}

// ── 行工厂（类内用例与嵌套 REST 桩共用；messageId 以 seq 编码便于断言） ─────────────

private fun row(seq: Long, content: String = "msg-$seq") = MessageRow(
    messageId = "01JMSG0000000000000000" + seq.toString().padStart(5, '0'),
    seq = seq, role = "assistant", content = content, state = "final",
)

private fun rows(from: Long, to: Long) = (from..to).map { row(it) }
