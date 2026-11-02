package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.GatewaySummary
import dev.sharpxia.agentreins.hub.projection.MessageRow
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.sync.DbEventCursorStore
import dev.sharpxia.agentreins.hub.sync.DefaultSyncEngine
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P2 同步层 fixture 测试：①四帧判别解码 ②快照覆盖投影 ③21 类事件逐类投影 ④入口幂等/水位。 */
class HubSyncFixtureTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    // ── ① WSS 四帧判别解码 ──

    @Test
    fun wsFrameDecoding() {
        val ws = DefaultHubWsClient(
            config = HubConfig("http://x/v1", "ws://x/v1/ws"),
            accessTokenProvider = { null },
            scope = kotlinx.coroutines.MainScope(),
       )
        val ready = ws.decodeFrame("""{"type":"sync.ready","watermark":"01JWM000000000000000000000","pollIntervalMs":30000}""")
        assertTrue(ready is HubServerFrame.Ready && ready.watermark == "01JWM000000000000000000000" && ready.pollIntervalMs == 30000L)
        val event = ws.decodeFrame(
            """{"type":"event","event":{"id":"01JEV000000000000000000000","seq":7,"type":"message.delta",""" +
                """"gatewayId":"01JGW000000000000000000000","sessionKey":"sk-main","ts":"2026-11-02T08:00:00.000Z",""" +
                """"data":{"messageId":"01JMSG00000000000000000000","deltaText":"hi"}}}""",
        )
        assertTrue(event is HubServerFrame.Event && event.event.type == "message.delta" && event.event.seq == 7L)
        val err = ws.decodeFrame("""{"type":"error","code":"HUB-SYNC-001","message":"cursor expired","retryable":false}""")
        assertTrue(err is HubServerFrame.Error && err.code == "HUB-SYNC-001" && err.retryable == false)
        assertNull(ws.decodeFrame("""{"type":"future.frame","x":1}"""))
        assertNull(ws.decodeFrame("not-json"))
    }

    // ── ② 快照覆盖投影 ──

    @Test
    fun snapshotOverwritesProjection() {
        val db = newDb()
        DefaultEventProjector(db).applySnapshot(fixtureSnapshot())
        val conv = db.hubQueries.selectConversation(GW, SK).executeAsOneOrNull()
        assertNotNull(conv)
        assertEquals("01JCONV00000000000000000000", conv.conversationId)
        assertEquals(2, conv.unreadCount)
        assertEquals(2, db.hubQueries.selectMessages(GW, SK).executeAsList().size)
        assertEquals(1L, db.hubQueries.selectReadCursor(GW, SK).executeAsOneOrNull())
    }

    // ── ③ 21 类事件投影 ──

    @Test
    fun eventFamilyProjection() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        var n = 100
        fun nextId(): String = "01JAAA00000000000000000${(++n).toString().padStart(4, '0')}"

        // message.delta：追加 → replace 权威替换
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000001"); put("deltaText", " world")
        }))
        assertEquals("hi world", db.hubQueries.selectMessage("01JMSG00000000000000000001").executeAsOne().content)
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000001"); put("deltaText", "hi there"); put("replace", true)
        }))
        assertEquals("hi there", db.hubQueries.selectMessage("01JMSG00000000000000000001").executeAsOne().content)

        // message.created：assistant 行 → 未读+1、preview 推进
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000009")
            put("role", "assistant"); put("content", "answer"); put("openclawId", "oc-1")
            put("sessionId", "s-1"); put("sourceMessageSeq", 3L)
        }))
        var conv = db.hubQueries.selectConversation(GW, SK).executeAsOne()
        assertEquals(3, conv.unreadCount)
        assertEquals("answer", conv.lastMessagePreview)

        // message.created：用户行回执 → 乐观行翻 delivered
        val cmdIdem = "0f9c6a2e-1111-4222-8333-444455556666"
        db.hubQueries.upsertMessage(
            messageId = "01JMSG00000000000000000008", gatewayId = GW, sessionKey = SK, seq = 2L,
            role = "user", content = "sending...", state = "final", uiState = "sending",
            openclawId = null, commandId = "01JCMD00000000000000000000", idempotencyKey = cmdIdem, ts = null,
        )
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000007")
            put("role", "user"); put("content", "hello"); put("openclawId", "oc-2")
            put("sessionId", "s-1"); put("sourceMessageSeq", 4L)
            put("commandId", "01JCMD00000000000000000000"); put("idempotencyKey", cmdIdem)
        }))
        assertEquals("delivered", db.hubQueries.selectMessages(GW, SK).executeAsList()
            .first { it.idempotencyKey == cmdIdem }.uiState)

        // message.replaced / message.failed
        val replacedId = "01JMSG00000000000000000001"
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_REPLACED, data = buildJsonObject {
            put("messageId", replacedId); put("content", "authoritative"); put("openclawId", "oc-1")
        }))
        assertEquals("authoritative", db.hubQueries.selectMessage(replacedId).executeAsOne().content)
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_FAILED, data = buildJsonObject {
            put("messageId", replacedId); put("reason", "timeout")
        }))
        assertEquals("failed", db.hubQueries.selectMessage(replacedId).executeAsOne().state)

        // run.started/completed → hasActiveRun 翻转
        p.applyEvent(ev(nextId(), EventEnvelope.RUN_STARTED, data = buildJsonObject {
            put("runId", "r1"); put("agentId", "main")
        }, runId = "r1"))
        assertEquals(1L, db.hubQueries.selectConversation(GW, SK).executeAsOne().hasActiveRun)
        p.applyEvent(ev(nextId(), EventEnvelope.RUN_COMPLETED, data = buildJsonObject {
            put("runId", "r1"); put("stopReason", "end_turn")
        }, runId = "r1"))
        assertEquals(0L, db.hubQueries.selectConversation(GW, SK).executeAsOne().hasActiveRun)

        // session.updated 化身更替 → 旧代次消息清空、未读清零、readCursor 重置
        p.applyEvent(ev(nextId(), EventEnvelope.SESSION_UPDATED, data = buildJsonObject {
            put("sessionKey", SK); put("agentId", "main"); put("label", "v2")
            put("sessionId", "s-2"); put("lifecycleRevision", 2L); put("hasActiveRun", false)
        }))
        conv = db.hubQueries.selectConversation(GW, SK).executeAsOne()
        assertEquals(2L, conv.lifecycleRevision)
        assertEquals("s-2", conv.sessionId)
        assertEquals(0, db.hubQueries.selectMessages(GW, SK).executeAsList().size)
        assertEquals(0L, conv.unreadCount)
        assertEquals(0L, db.hubQueries.selectReadCursor(GW, SK).executeAsOneOrNull())

        // session.created：未知会话占位行（conversationId NULL、reconciling=1）
        val newSk = "sk-new-session"
        p.applyEvent(ev(nextId(), EventEnvelope.SESSION_CREATED, sk = newSk, data = buildJsonObject {
            put("sessionKey", newSk); put("agentId", "main"); put("label", "new"); put("hasActiveRun", false)
        }))
        val placeholder = db.hubQueries.selectConversation(GW, newSk).executeAsOne()
        assertNull(placeholder.conversationId)
        assertEquals(1L, placeholder.reconciling)

        // gateway.online/offline
        p.applyEvent(ev(nextId(), EventEnvelope.GATEWAY_ONLINE, sk = null, data = buildJsonObject {
            put("gatewayId", GW); put("generation", 3L)
        }))
        assertEquals(1L, db.hubQueries.selectGateways().executeAsOne().online)
        p.applyEvent(ev(nextId(), EventEnvelope.GATEWAY_OFFLINE, sk = null, data = buildJsonObject {
            put("gatewayId", GW); put("generation", 3L)
        }))
        assertEquals(0L, db.hubQueries.selectGateways().executeAsOne().online)

        // approval.requested/resolved
        p.applyEvent(ev(nextId(), EventEnvelope.APPROVAL_REQUESTED, data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-1"); put("toolName", "bash"); put("payloadSummary", "ls -la")
        }, runId = "r2"))
        assertEquals("pending", db.hubQueries.selectPendingApprovals().executeAsList().single().status)
        p.applyEvent(ev(nextId(), EventEnvelope.APPROVAL_RESOLVED, data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-1"); put("decision", "approved"); put("decidedBy", "app")
        }, runId = "r2"))
        assertEquals(0, db.hubQueries.selectPendingApprovals().executeAsList().size)

        // command.acked/failed
        db.hubQueries.insertCommand(
            idempotencyKey = "k-1", commandId = null, conversationId = "01JCONV00000000000000000000",
            sessionKey = SK, runId = null, approvalId = null, type = "message.send",
            payloadJson = "{}", state = "PENDING_LOCAL", errorCode = null, createdAt = T0, updatedAt = T0,
        )
        p.applyEvent(ev(nextId(), EventEnvelope.COMMAND_ACKED, sk = null, data = buildJsonObject {
            put("commandId", "01JC000000000000000000000000"); put("idempotencyKey", "k-1")
            put("commandType", "message.send"); put("status", "PERSISTED"); put("previousStatus", "PENDING_LOCAL")
        }))
        assertEquals("PERSISTED", db.hubQueries.selectCommandByKey("k-1").executeAsOne().state)
        p.applyEvent(ev(nextId(), EventEnvelope.COMMAND_FAILED, sk = null, data = buildJsonObject {
            put("commandId", "01JC000000000000000000000000"); put("idempotencyKey", "k-1")
            put("commandType", "message.send"); put("status", "FAILED"); put("previousStatus", "PERSISTED")
            put("errorCode", "HUB-GW-004")
        }))
        assertEquals("FAILED", db.hubQueries.selectCommandByKey("k-1").executeAsOne().state)

        // read.updated：句柄反查 + 未读重算
        p.applyEvent(ev(nextId(), EventEnvelope.READ_UPDATED, sk = null, data = buildJsonObject {
            put("conversationId", "01JCONV00000000000000000000"); put("reader", "dev-1"); put("readUpTo", 99L)
        }))
        assertEquals(99L, db.hubQueries.selectReadCursor(GW, SK).executeAsOneOrNull())
        assertEquals(0L, db.hubQueries.selectConversation(GW, SK).executeAsOne().unreadCount)

        // tool.* 与未注册类型：忽略内容、不报错
        p.applyEvent(ev(nextId(), EventEnvelope.TOOL_STARTED, data = buildJsonObject {
            put("runId", "r3"); put("toolName", "web"); put("toolCallKey", "t1"); put("inputSummary", "q")
        }, runId = "r3"))
        p.applyEvent(ev(nextId(), "future.unknown.type", data = buildJsonObject { put("x", 1) }))
        assertEquals(0, p.stats.eventsSkipped)
    }

    // ── ④ 事件入口幂等/水位裁决 ──

    @Test
    fun engineDedupAndWatermark() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        val cursors = DbEventCursorStore(db)
        cursors.updateEventCursor(WM) // 模拟 bootstrap 落定（快照覆盖由 projector.applySnapshot 承担）
        val engine = DefaultSyncEngine(StubHubApi(), StubAuth(), p, cursors)

        // 水位之前：丢弃
        engine.onEventArrived(ev("01AAAA00000000000000000000", EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000001"); put("deltaText", "x")
        }))
        assertEquals("hi", db.hubQueries.selectMessage("01JMSG00000000000000000001").executeAsOne().content)
        assertEquals(WM, cursors.eventCursor())

        // 水位之后：应用 + 推进游标；同 id 重复跳过（delta 不重复 append）
        val after = "01JAAA00000000000000000001"
        engine.onEventArrived(ev(after, EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000001"); put("deltaText", "!")
        }))
        engine.onEventArrived(ev(after, EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000001"); put("deltaText", "!")
        }))
        assertEquals("hi!", db.hubQueries.selectMessage("01JMSG00000000000000000001").executeAsOne().content)
        assertEquals(after, cursors.eventCursor())
    }

    // ── fixture 工厂 ──

    private fun ev(
        id: String,
        type: String,
        sk: String? = SK,
        data: kotlinx.serialization.json.JsonObject,
        runId: String? = null,
    ) = EventEnvelope(id = id, seq = 1L, type = type, gatewayId = GW, sessionKey = sk, runId = runId, ts = T0, data = data)

    private fun fixtureSnapshot() = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = listOf(GatewaySummary(gatewayId = GW, name = "mini", online = false, agents = listOf(AgentRef("main", "Main")))),
        conversations = listOf(
            ConversationRow(
                conversationId = "01JCONV00000000000000000000", gatewayId = GW, sessionKey = SK,
                sessionId = "s-1", lifecycleRevision = 1L, label = "主线", agentId = "main",
                lastMessagePreview = "hi", lastSeq = 2L, lastActiveAt = T0,
                unreadCount = 2, hasActiveRun = false, reconciling = false,
            ),
        ),
        messages = mapOf(
            "01JCONV00000000000000000000" to listOf(
                MessageRow(messageId = "01JMSG00000000000000000001", seq = 1L, role = "assistant", content = "hi", state = "final"),
                MessageRow(messageId = "01JMSG00000000000000000002", seq = 2L, role = "user", content = "hello", state = "final"),
            ),
        ),
        readCursors = mapOf("01JCONV00000000000000000000" to 1L),
    )

    companion object {
        const val GW = "01JGW000000000000000000000"
        const val SK = "sk-main"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-02T08:00:00.000Z"
    }
}

private open class StubHubApi : HubApi {
    override suspend fun login(req: dev.sharpxia.agentreins.hub.auth.LoginRequest) = TODO()
    override suspend fun refresh(req: dev.sharpxia.agentreins.hub.auth.RefreshRequest) = TODO()
    override suspend fun registerDevice(req: dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest) = TODO()
    override suspend fun listDevices() = TODO()
    override suspend fun deleteDevice(deviceId: String) = TODO()
    override suspend fun gateways() = TODO()
    override suspend fun conversations(limit: Int?, cursor: String?) = TODO()
    override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = TODO()
    override suspend fun sendMessage(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.SendMessageRequest) = TODO()
    override suspend fun markRead(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.ReadCursorRequest) = TODO()
    override suspend fun cancelRun(runId: String, req: dev.sharpxia.agentreins.hub.projection.CancelRunRequest) = TODO()
    override suspend fun decideApproval(approvalId: String, req: dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest) = TODO()
    override suspend fun snapshot() = TODO()
    override suspend fun events(cursor: String, limit: Int?) = TODO()
}

private open class StubAuth : TokenManager {
    override suspend fun login(username: String, password: String, mfaCode: String?) = TODO()
    override suspend fun registerCurrentDevice(displayName: String, platform: String, model: String?, appVersion: String?) = TODO()
    override suspend fun restoreFromStoredRefreshToken() = false
    override suspend fun <T> withBearer(block: suspend (String) -> T): T = TODO()
    override fun currentAccessToken(): String? = null
    override suspend fun clearCredentials() {}
}
