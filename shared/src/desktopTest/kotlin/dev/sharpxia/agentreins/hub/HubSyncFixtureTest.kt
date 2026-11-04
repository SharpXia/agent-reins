package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.GatewaySummary
import dev.sharpxia.agentreins.hub.projection.MessageRow
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
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

/** P2 同步层 fixture 测试（契约 v1.1）：①四帧判别解码 ②快照覆盖投影 ③事件投影（conversationId 路由 + 映射学习）④入口幂等/水位。 */
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
                """"gatewayId":"01JGW000000000000000000000","sessionKey":"sk-main","ts":"2026-11-03T08:00:00.000Z",""" +
                """"data":{"messageId":"01JMSG00000000000000000000","deltaText":"hi"}}}""",
        )
        assertTrue(event is HubServerFrame.Event && event.event.type == "message.delta" && event.event.seq == 7L)
        val err = ws.decodeFrame("""{"type":"error","code":"HUB-SYNC-001","message":"cursor expired","retryable":false}""")
        assertTrue(err is HubServerFrame.Error && err.code == "HUB-SYNC-001" && err.retryable == false)
        assertNull(ws.decodeFrame("""{"type":"future.frame","x":1}"""))
        assertNull(ws.decodeFrame("not-json"))
    }

    // ── ② 快照覆盖投影（v1.1：卡片键 = conversationId；映射跨重建保留、孤儿清理） ──

    @Test
    fun snapshotOverwritesProjection() {
        val db = newDb()
        // 预置两条映射：一条指向即将落卡的会话（保留），一条孤儿（清掉）
        db.hubQueries.upsertConvKey(GW, SK, CONV)
        db.hubQueries.upsertConvKey(GW, "sk-orphan", "01JCONV00000000000000000099")
        DefaultEventProjector(db).applySnapshot(fixtureSnapshot())
        val conv = db.hubQueries.selectConversation(CONV).executeAsOneOrNull()
        assertNotNull(conv)
        assertEquals(2, conv.unreadCount)
        assertEquals(-1L, conv.lifecycleRevision) // 快照行：化身基准未知
        assertNull(conv.sessionKey)               // v1.1 快照无 sessionKey
        assertEquals(2, db.hubQueries.selectMessages(CONV).executeAsList().size)
        assertEquals(1L, db.hubQueries.selectReadCursor(CONV).executeAsOneOrNull())
        // 映射：保留有效项、清理孤儿
        assertNotNull(db.hubQueries.selectConversationByKey(GW, SK).executeAsOneOrNull())
        assertNull(db.hubQueries.selectConversationByKey(GW, "sk-orphan").executeAsOneOrNull())
    }

    // ── ③ 事件投影（v1.1 路由：先决 = 映射命中或自发送回执链学习） ──

    @Test
    fun eventFamilyProjection() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        // 预置映射：事件路由先决（快照不带 sessionKey，映射由自发送链/命令 ack 学习或此处显式预置）
        db.hubQueries.upsertConvKey(GW, SK, CONV)
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

        // message.created：assistant 行 → 未读+1、preview 推进（v1.1 载荷无 openclawId/sessionId）
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000009")
            put("role", "assistant"); put("content", "answer"); put("sourceMessageSeq", 3L)
        }))
        var conv = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals(3, conv.unreadCount)
        assertEquals("answer", conv.lastMessagePreview)

        // message.created：role="" 合成行 → 不计未读
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000010")
            put("role", ""); put("content", "上下文已压缩"); put("sourceMessageSeq", 4L)
        }))
        assertEquals(3, db.hubQueries.selectConversation(CONV).executeAsOne().unreadCount)

        // message.created：用户行回执 → 乐观行翻 delivered + **映射学习**（未知 sessionKey 经
        // idempotencyKey → 队列条目 conversationId 学到）
        val cmdIdem = "0f9c6a2e-1111-4222-8333-444455556666"
        db.hubQueries.upsertMessage(
            messageId = "01JMSG00000000000000000008", conversationId = CONV, seq = 2L,
            role = "user", content = "sending...", state = "final", reason = null, uiState = "sending",
            commandId = "01JCMD00000000000000000000", idempotencyKey = cmdIdem, ts = null,
        )
        val newSk = "sk-learned-via-echo"
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, sk = newSk, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000007")
            put("role", "user"); put("content", "hello"); put("sourceMessageSeq", 5L)
            put("commandId", "01JCMD00000000000000000000"); put("idempotencyKey", cmdIdem)
        }))
        assertEquals("delivered", db.hubQueries.selectMessages(CONV).executeAsList()
            .first { it.idempotencyKey == cmdIdem }.uiState)
        assertEquals(CONV, db.hubQueries.selectConversationByKey(GW, newSk).executeAsOneOrNull()?.conversationId)

        // message.replaced / message.failed
        val replacedId = "01JMSG00000000000000000001"
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_REPLACED, data = buildJsonObject {
            put("messageId", replacedId); put("content", "authoritative")
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
        assertEquals(1L, db.hubQueries.selectConversation(CONV).executeAsOne().hasActiveRun)
        p.applyEvent(ev(nextId(), EventEnvelope.RUN_COMPLETED, data = buildJsonObject {
            put("runId", "r1"); put("stopReason", "end_turn")
        }, runId = "r1"))
        assertEquals(0L, db.hubQueries.selectConversation(CONV).executeAsOne().hasActiveRun)

        // session.updated ×2：第一段建立化身基准（快照行 -1 未知 → 只记录不重置），第二段变化 → 更替
        p.applyEvent(ev(nextId(), EventEnvelope.SESSION_UPDATED, data = buildJsonObject {
            put("sessionKey", SK); put("agentId", "main"); put("label", "v2")
            put("lifecycleRevision", 2L); put("hasActiveRun", false)
        }))
        conv = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals(2L, conv.lifecycleRevision)
        assertEquals("v2", conv.label)
        assertEquals(5, db.hubQueries.selectMessages(CONV).executeAsList().size) // 基准段不清消息（快照2+3条事件行）
        p.applyEvent(ev(nextId(), EventEnvelope.SESSION_UPDATED, data = buildJsonObject {
            put("sessionKey", SK); put("agentId", "main"); put("label", "v3")
            put("lifecycleRevision", 3L); put("hasActiveRun", false)
        }))
        conv = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals(3L, conv.lifecycleRevision)
        assertEquals(0, db.hubQueries.selectMessages(CONV).executeAsList().size) // 更替：旧代次封存
        assertEquals(0L, conv.unreadCount)
        assertNull(db.hubQueries.selectReadCursor(CONV).executeAsOneOrNull())

        // session.created：不建卡（v1.1：卡片唯一来源 = 快照/校准；新会话 conversationId 未知）
        val brandSk = "sk-brand-new"
        p.applyEvent(ev(nextId(), EventEnvelope.SESSION_CREATED, sk = brandSk, data = buildJsonObject {
            put("sessionKey", brandSk); put("agentId", "main"); put("label", "new"); put("hasActiveRun", false)
        }))
        assertEquals(1, db.hubQueries.selectConversations().executeAsList().size) // 不产生第二张卡

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
        assertEquals(CONV, db.hubQueries.selectPendingApprovals().executeAsList().single().conversationId)
        p.applyEvent(ev(nextId(), EventEnvelope.APPROVAL_RESOLVED, data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-1"); put("decision", "approved"); put("decidedBy", "app")
        }, runId = "r2"))
        assertEquals(0, db.hubQueries.selectPendingApprovals().executeAsList().size)

        // command.acked/failed：队列推进 + **映射学习**（信封 sessionKey + 队列条目）
        db.hubQueries.insertCommand(
            idempotencyKey = "k-1", commandId = null, conversationId = CONV,
            runId = null, approvalId = null, type = "message.send",
            payloadJson = "{}", state = "PENDING_LOCAL", errorCode = null, createdAt = T0, updatedAt = T0,
        )
        val ackSk = "sk-learned-via-ack"
        p.applyEvent(ev(nextId(), EventEnvelope.COMMAND_ACKED, sk = ackSk, data = buildJsonObject {
            put("commandId", "01JC000000000000000000000000"); put("idempotencyKey", "k-1")
            put("commandType", "message.send"); put("status", "PERSISTED"); put("previousStatus", "PENDING_LOCAL")
        }))
        assertEquals("PERSISTED", db.hubQueries.selectCommandByKey("k-1").executeAsOne().state)
        assertEquals(CONV, db.hubQueries.selectConversationByKey(GW, ackSk).executeAsOneOrNull()?.conversationId)
        p.applyEvent(ev(nextId(), EventEnvelope.COMMAND_FAILED, sk = null, data = buildJsonObject {
            put("commandId", "01JC000000000000000000000000"); put("idempotencyKey", "k-1")
            put("commandType", "message.send"); put("status", "FAILED"); put("previousStatus", "PERSISTED")
            put("errorCode", "HUB-GW-004")
        }))
        assertEquals("FAILED", db.hubQueries.selectCommandByKey("k-1").executeAsOne().state)

        // read.updated：conversationId 直达（无需映射）+ 未读重算
        db.hubQueries.setReadCursorValue(CONV, 0L) // 更替后已删行，重置为 0 便于断言
        p.applyEvent(ev(nextId(), EventEnvelope.READ_UPDATED, sk = null, data = buildJsonObject {
            put("conversationId", CONV); put("reader", "dev-1"); put("readUpTo", 99L)
        }))
        assertEquals(99L, db.hubQueries.selectReadCursor(CONV).executeAsOneOrNull())

        // session.removed：映射命中 → 卡/消息/cursor/映射全清
        p.applyEvent(ev(nextId(), EventEnvelope.SESSION_REMOVED, data = buildJsonObject {
            put("sessionKey", SK)
        }))
        assertNull(db.hubQueries.selectConversation(CONV).executeAsOneOrNull())
        assertNull(db.hubQueries.selectConversationByKey(GW, SK).executeAsOneOrNull())

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

    // ── ⑤ v4 wp-5①：缺行 delta 建 streaming 占位行 + 占位行收敛双分支（探针定档：无锚=异锚为主） ──
    //
    // wp-1 探针结论（AgentReinService bridge/src/normalize/index.ts delta 分支 + relay/delta-coalesce.ts）：
    // 本宿主 Gateway chat.delta 恒无锚，合并产物 messageId = dstream:<runId>（run 域合成身份），
    // 与 created 行 messageId（displayRowId 26hex）**异锚** → 异锚清除为主用例；同锚覆盖为
    // 防御性保留用例（未来 Gateway 回锚）。

    @Test
    fun streamingPlaceholderAndConvergence() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)
        var n = 200
        fun nextId(): String = "01JAAA00000000000000000${(++n).toString().padStart(4, '0')}"

        val dstreamId = "dstream:run-a"
        val deltaData: (String, Long) -> kotlinx.serialization.json.JsonObject = { text, seq ->
            buildJsonObject {
                put("messageId", dstreamId); put("deltaText", text); put("seq", seq); put("runScoped", true)
            }
        }

        // 门卫：无活跃 run 的缺行 delta = 迟到重放噪声（合并器 final 后抑制驱逐兜底放行），skip 不建行
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = deltaData("Hel", 1L)))
        assertEquals(1, p.stats.eventsSkipped)
        assertNull(db.hubQueries.selectMessage(dstreamId).executeAsOneOrNull())

        // run.started：hasActiveRun=1 + activeRunId 捕获（wp-5③，wp-8 Stop 取值）
        p.applyEvent(ev(nextId(), EventEnvelope.RUN_STARTED, data = buildJsonObject {
            put("runId", "run-a"); put("agentId", "main")
        }, runId = "run-a"))
        var conv = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals(1L, conv.hasActiveRun)
        assertEquals("run-a", conv.activeRunId)

        // 缺行 delta → 建 streaming 占位行（异锚主路径），后续 delta 追加
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = deltaData("Hel", 1L)))
        val ph = db.hubQueries.selectMessage(dstreamId).executeAsOneOrNull()
        assertNotNull(ph)
        assertEquals(CONV, ph.conversationId)
        assertEquals("assistant", ph.role)
        assertEquals("streaming", ph.state)
        assertEquals("Hel", ph.content)
        assertEquals(3L, ph.seq) // 快照 lastSeq=2 → 占位行 seq=3 压尾显示；不推进 lastSeq（服务端 seq 口径不污染）
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = deltaData("lo", 2L)))
        assertEquals("Hello", db.hubQueries.selectMessage(dstreamId).executeAsOne().content)
        assertEquals(2L, db.hubQueries.selectConversation(CONV).executeAsOne().lastSeq)

        // 异锚收敛：created 权威行（26hex）落定 → 占位行清除、单行 final（无幽灵气泡+无重复气泡）
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000009")
            put("role", "assistant"); put("content", "Hello"); put("sourceMessageSeq", 3L)
        }))
        assertNull(db.hubQueries.selectMessage(dstreamId).executeAsOneOrNull())
        assertEquals("final", db.hubQueries.selectMessage("01JMSG00000000000000000009").executeAsOne().state)
        assertEquals(
            1,
            db.hubQueries.selectMessages(CONV).executeAsList().count { it.role == "assistant" && it.seq == 3L },
        )

        // 同锚分支（防御性保留）：delta messageId == created messageId，占位行经 created 同键
        // INSERT OR REPLACE 覆盖收敛（清除规则以 messageId=本行排除，不误删在途行）
        val anchoredId = "01JMSG00000000000000000011"
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", anchoredId); put("deltaText", "partial"); put("seq", 3L)
        }))
        assertEquals("streaming", db.hubQueries.selectMessage(anchoredId).executeAsOne().state)
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_CREATED, data = buildJsonObject {
            put("messageId", anchoredId)
            put("role", "assistant"); put("content", "authoritative"); put("sourceMessageSeq", 4L)
        }))
        val same = db.hubQueries.selectMessage(anchoredId).executeAsOne()
        assertEquals("final", same.state)
        assertEquals("authoritative", same.content)
        assertEquals(1, db.hubQueries.selectMessages(CONV).executeAsList().count { it.messageId == anchoredId })

        // run.completed：hasActiveRun 翻转 + activeRunId 清空 + 流式行兜底清除（无残留）
        p.applyEvent(ev(nextId(), EventEnvelope.RUN_COMPLETED, data = buildJsonObject {
            put("runId", "run-a"); put("stopReason", "end_turn")
        }, runId = "run-a"))
        conv = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals(0L, conv.hasActiveRun)
        assertNull(conv.activeRunId)
        assertEquals(0, db.hubQueries.selectMessages(CONV).executeAsList().count { it.state == "streaming" })

        // run.failed 终态清除：失败 run 的占位行无 message 终态事件可收敛，不得残留幽灵气泡
        p.applyEvent(ev(nextId(), EventEnvelope.RUN_STARTED, data = buildJsonObject {
            put("runId", "run-b"); put("agentId", "main")
        }, runId = "run-b"))
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_DELTA, data = buildJsonObject {
            put("messageId", "dstream:run-b"); put("deltaText", "x"); put("seq", 1L); put("runScoped", true)
        }))
        assertNotNull(db.hubQueries.selectMessage("dstream:run-b").executeAsOneOrNull())
        p.applyEvent(ev(nextId(), EventEnvelope.RUN_FAILED, data = buildJsonObject { put("runId", "run-b") }, runId = "run-b"))
        assertNull(db.hubQueries.selectMessage("dstream:run-b").executeAsOneOrNull())
        conv = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals(0L, conv.hasActiveRun)
        assertNull(conv.activeRunId)
    }

    // ── ⑥ v4 wp-5②：事件路径 message.failed 的 reason 落库（此前只翻 state 丢 reason） ──

    @Test
    fun failedReasonPersists() = runTest {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)
        var n = 300
        fun nextId(): String = "01JAAA00000000000000000${(++n).toString().padStart(4, '0')}"

        // 载荷带 reason（脱敏 errorKind 闭集）→ 写入 reason 列（与快照路径共用）
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_FAILED, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000001"); put("reason", "timeout")
        }))
        val classified = db.hubQueries.selectMessage("01JMSG00000000000000000001").executeAsOne()
        assertEquals("failed", classified.state)
        assertEquals("timeout", classified.reason)

        // 未分类失败（载荷缺 reason——normalize chat.error 仅在携带 errorKind 时透传）：
        // state 照翻、reason 置 NULL 不虚构，事件不因缺字段整帧丢弃
        p.applyEvent(ev(nextId(), EventEnvelope.MESSAGE_FAILED, data = buildJsonObject {
            put("messageId", "01JMSG00000000000000000002")
        }))
        val unclassified = db.hubQueries.selectMessage("01JMSG00000000000000000002").executeAsOne()
        assertEquals("failed", unclassified.state)
        assertNull(unclassified.reason)
        assertEquals(0, p.stats.eventsSkipped)
    }

    // ── ⑦ v4 wp-5④：快照路径 MessageRow.errorKind → hubMessages.reason（事件/快照共用同一列） ──

    @Test
    fun snapshotErrorKindMapsToReason() {
        val db = newDb()
        // 宽解析容忍缺省：无 errorKind 字段的历史快照行照常解码（additive 老客户端兼容语义）
        val lenient = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val parsed = lenient.decodeFromString(
            MessageRow.serializer(),
            """{"messageId":"01JMSG00000000000000000009","seq":9,"role":"assistant","content":"legacy","state":"failed"}""",
        )
        assertNull(parsed.errorKind)

        DefaultEventProjector(db).applySnapshot(
            SnapshotResponse(
                snapshotWatermark = WM,
                generatedAt = T0,
                gateways = listOf(GatewaySummary(gatewayId = GW, name = "mini", online = false)),
                conversations = listOf(
                    ConversationRow(
                        conversationId = CONV, gatewayId = GW, label = "主线", agentId = "main",
                        lastMessagePreview = "", lastSeq = 3L, lastActiveAt = T0,
                        unreadCount = 0, hasActiveRun = false, reconciling = false,
                    ),
                ),
                messages = mapOf(
                    CONV to listOf(
                        // wp-4 additive：仅 failed 行携带 errorKind（六值闭集），快照重建的失败行由此获得原因
                        MessageRow(
                            messageId = "01JMSG00000000000000000001", seq = 1L, role = "assistant",
                            content = "boom", state = "failed", errorKind = "refusal",
                        ),
                        MessageRow(
                            messageId = "01JMSG00000000000000000002", seq = 2L, role = "assistant",
                            content = "unclassified", state = "failed",
                        ),
                        MessageRow(
                            messageId = "01JMSG00000000000000000003", seq = 3L, role = "user",
                            content = "hi", state = "final",
                        ),
                    ),
                ),
                readCursors = emptyMap(),
            ),
        )
        assertEquals("refusal", db.hubQueries.selectMessage("01JMSG00000000000000000001").executeAsOne().reason)
        assertNull(db.hubQueries.selectMessage("01JMSG00000000000000000002").executeAsOne().reason)
        assertNull(db.hubQueries.selectMessage("01JMSG00000000000000000003").executeAsOne().reason)
    }

    // ── fixture 工厂 ──

    private fun ev(
        id: String,
        type: String,
        sk: String? = SK,
        data: kotlinx.serialization.json.JsonObject,
        runId: String? = null,
    ) = EventEnvelope(id = id, seq = 1L, type = type, gatewayId = GW, sessionKey = sk, runId = runId, ts = T0, data = data)

    /** v1.1 快照行：10 字段（无 sessionKey/sessionId/lifecycleRevision）。 */
    private fun fixtureSnapshot() = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = listOf(GatewaySummary(gatewayId = GW, name = "mini", online = false, agents = listOf(AgentRef("main", "Main")))),
        conversations = listOf(
            ConversationRow(
                conversationId = CONV, gatewayId = GW, label = "主线", agentId = "main",
                lastMessagePreview = "hi", lastSeq = 2L, lastActiveAt = T0,
                unreadCount = 2, hasActiveRun = false, reconciling = false,
            ),
        ),
        messages = mapOf(
            CONV to listOf(
                MessageRow(messageId = "01JMSG00000000000000000001", seq = 1L, role = "assistant", content = "hi", state = "final"),
                MessageRow(messageId = "01JMSG00000000000000000002", seq = 2L, role = "user", content = "hello", state = "final"),
            ),
        ),
        readCursors = mapOf(CONV to 1L),
    )

    companion object {
        const val GW = "01JGW000000000000000000000"
        const val CONV = "01JCONV00000000000000000000"
        const val SK = "sk-main"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-03T08:00:00.000Z"
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
    override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest) = TODO()
    override suspend fun compactConversation(conversationId: String) = TODO()
    override suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse = TODO()
    override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest) = TODO()
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
