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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * wp-15 工具活动捕获投影用例（desktopTest 验收项）：
 * ① tool.started/completed/failed → hubToolEvents 三态串联（调用关联键 upsert，计数可导出）；
 * ② 双形状宽松解码：契约注册形状（toolName/toolCallKey/inputSummary）与 Bridge 实际透传形状
 *    （name/toolCallId/args，normalize/index.ts normalizeToolEvent）并存均解析；
 * ③ 终态先于 started（丢失面 06 §10.2）落终态行、名称缺省 ''（不虚构）；
 * ④ run.status（wp-15 可选项 additive）→ hubRunStatus 每 run 最新阶段帧 + run 终态清行；
 * ⑤ observeWorkActivity Flow 镜像（UiWorkActivity 两表合流）；
 * ⑥ 累积上限裁剪（每会话 200 行）与会话删除/化身更替清空。
 */
class ToolActivityProjectionTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    // ── ① 三态串联与计数 ──

    @Test
    fun toolLifecycleLinksByCallKeyWithCounts() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // started：running 行 + 归属 + 有界入参摘要
        p.applyEvent(toolEv("01JTL00000000000000000001", EventEnvelope.TOOL_STARTED, runId = "r1", data = buildJsonObject {
            put("toolName", "shell.exec"); put("toolCallKey", "tk-1"); put("inputSummary", "ls -la /tmp")
        }))
        val started = db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().single()
        assertEquals("shell.exec", started.toolName)
        assertEquals("tk-1", started.toolCallKey)
        assertEquals("running", started.status)
        assertEquals("ls -la /tmp", started.summary)
        assertEquals(CONV, started.conversationId)
        assertEquals("r1", started.runId)

        // completed：同键翻转终态，started 的名称/摘要保留（终态载荷无名称字段不覆盖）
        p.applyEvent(toolEv("01JTL00000000000000000002", EventEnvelope.TOOL_COMPLETED, runId = "r1", data = buildJsonObject {
            put("toolCallKey", "tk-1")
        }))
        val done = db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().single()
        assertEquals("completed", done.status)
        assertEquals("shell.exec", done.toolName)
        assertEquals("ls -la /tmp", done.summary)

        // 第二个调用失败 → 计数 = 1 完成 / 1 失败（验收「工具名与完成/失败计数」的数据基础）
        p.applyEvent(toolEv("01JTL00000000000000000003", EventEnvelope.TOOL_STARTED, runId = "r1", data = buildJsonObject {
            put("toolName", "web.fetch"); put("toolCallKey", "tk-2")
        }))
        p.applyEvent(toolEv("01JTL00000000000000000004", EventEnvelope.TOOL_FAILED, runId = "r1", data = buildJsonObject {
            put("toolCallKey", "tk-2")
        }))
        val rows = db.hubQueries.selectToolEventsByConversation(CONV).executeAsList()
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.status == "completed" })
        assertEquals(1, rows.count { it.status == "failed" })
        assertEquals("web.fetch", rows.first { it.status == "failed" }.toolName)
        assertEquals(0, p.stats.eventsSkipped)
    }

    // ── ② 双形状宽松解码（契约注册形状 vs Bridge 实际透传形状） ──

    @Test
    fun dualWireShapesDecodeTolerantly() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // Bridge 实际形状（normalize/index.ts normalizeToolEvent）：{name, toolCallId, args}
        p.applyEvent(toolEv("01JTL00000000000000000011", EventEnvelope.TOOL_STARTED, runId = "r1", data = buildJsonObject {
            put("name", "system.run"); put("toolCallId", "call-abc")
            put("args", buildJsonObject {
                put("cmd", "git status")
                put("flags", buildJsonArray { })
            })
        }))
        val row = db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().single()
        assertEquals("system.run", row.toolName)
        assertEquals("call-abc", row.toolCallKey)
        // args 对象序列化为有界 JSON 文本摘要
        assertTrue(row.summary!!.contains("git status"))

        // 迟到重放同键 started（引擎 id 去重外的防御面）：不把已完成调用翻回 running、不覆盖名称
        p.applyEvent(toolEv("01JTL00000000000000000012", EventEnvelope.TOOL_COMPLETED, runId = "r1", data = buildJsonObject {
            put("toolCallId", "call-abc")
        }))
        p.applyEvent(toolEv("01JTL00000000000000000013", EventEnvelope.TOOL_STARTED, runId = "r1", data = buildJsonObject {
            put("name", "system.run"); put("toolCallId", "call-abc")
        }))
        assertEquals("completed", db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().single().status)
    }

    // ── ③ 终态先于 started（丢失面）：落终态行不缺计数，名称缺省 '' ──

    @Test
    fun finishedWithoutStartedStillCountsWithBlankName() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)
        p.applyEvent(toolEv("01JTL00000000000000000021", EventEnvelope.TOOL_FAILED, runId = "r1", data = buildJsonObject {
            put("toolCallKey", "tk-lost")
        }))
        val row = db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().single()
        assertEquals("failed", row.status)
        assertEquals("", row.toolName) // UI 兜底呈现，不虚构具体工具名
        assertEquals(null, row.summary)

        // 后续 toolCallKey 缺失的毒帧 → 跳过计数，不建行
        val before = db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().size
        p.applyEvent(toolEv("01JTL00000000000000000022", EventEnvelope.TOOL_COMPLETED, runId = "r1", data = buildJsonObject {}))
        assertEquals(before, db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().size)
        assertEquals(1, p.stats.eventsSkipped)
    }

    // ── ④ run.status：每 run 最新阶段帧 + run 终态清行 ──

    @Test
    fun runStatusKeepsLatestPhaseAndClearsOnTerminal() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // 两帧阶段（status 变体含 retry）→ 同 run 只留最新
        p.applyEvent(toolEv("01JTL00000000000000000031", EventEnvelope.RUN_STATUS, runId = "r1", data = buildJsonObject {
            put("phase", "preparing_context")
        }, ts = "2026-11-06T08:00:01.000Z"))
        p.applyEvent(toolEv("01JTL00000000000000000032", EventEnvelope.RUN_STATUS, runId = "r1", data = buildJsonObject {
            put("phase", "starting_model")
            put("retry", buildJsonObject { put("attempt", 2); put("maxAttempts", 5); put("reason", "rate_limit") })
        }, ts = "2026-11-06T08:00:02.000Z"))
        var phases = db.hubQueries.selectRunStatusByConversation(CONV).executeAsList()
        assertEquals(1, phases.size)
        assertEquals("starting_model", phases.single().phase)
        assertEquals("重试 2/5（rate_limit）", phases.single().detail)
        assertEquals(CONV, phases.single().conversationId)

        // aborted 变体：phase='aborted' + 有界 errorMessage（另一 run 的帧并存）
        p.applyEvent(toolEv("01JTL00000000000000000033", EventEnvelope.RUN_STATUS, sk = SK, runId = "r2", data = buildJsonObject {
            put("phase", "aborted"); put("errorMessage", "user cancel"); put("stopReason", "user_cancel")
        }, ts = "2026-11-06T08:00:03.000Z"))
        phases = db.hubQueries.selectRunStatusByConversation(CONV).executeAsList()
        assertEquals(2, phases.size)

        // run 终态 → 对应 run 的阶段帧清行（r1 完成；r2 阶段帧保留直到其自身终态）
        p.applyEvent(toolEv("01JTL00000000000000000034", EventEnvelope.RUN_COMPLETED, runId = "r1", data = buildJsonObject {}))
        phases = db.hubQueries.selectRunStatusByConversation(CONV).executeAsList()
        assertEquals(1, phases.size)
        assertEquals("r2", phases.single().runId)
        assertEquals("aborted", phases.single().phase)
    }

    // ── ⑤ observeWorkActivity Flow 镜像（wp-15 UI 数据面） ──

    @Test
    fun observeWorkActivityMirrorsProjection() = runTest {
        val db = newDb()
        val client = DefaultHubClient(
            scope = backgroundScope,
            runtime = HubRuntime(backgroundScope, ToolMemoryCredentialStore()),
            db = db,
        )
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // 空库 → 空快照
        val empty = client.observeWorkActivity(CONV).first()
        assertTrue(empty.tools.isEmpty())
        assertTrue(empty.phases.isEmpty())

        p.applyEvent(toolEv("01JTL00000000000000000041", EventEnvelope.TOOL_STARTED, runId = "r1", data = buildJsonObject {
            put("toolName", "shell.exec"); put("toolCallKey", "tk-1"); put("inputSummary", "ls")
        }))
        p.applyEvent(toolEv("01JTL00000000000000000042", EventEnvelope.RUN_STATUS, runId = "r1", data = buildJsonObject {
            put("phase", "starting_model")
        }))
        val snap = client.observeWorkActivity(CONV).first()
        assertEquals(1, snap.tools.size)
        assertEquals("shell.exec", snap.tools.single().toolName)
        assertEquals("running", snap.tools.single().status)
        assertEquals(1, snap.phases.size)
        assertEquals("starting_model", snap.phases.single().phase)

        // 未映射会话（未知 sessionKey）行照常捕获（事件路由未学习不丢事件，与审批同纪律），
        // 但不进按会话查询——CONV 的 Flow 仍只有 tk-1 一行
        p.applyEvent(toolEv("01JTL00000000000000000043", EventEnvelope.TOOL_STARTED, sk = "sk-ghost", runId = "r2", data = buildJsonObject {
            put("toolName", "web.fetch"); put("toolCallKey", "tk-2")
        }))
        assertEquals(1, client.observeWorkActivity(CONV).first().tools.size)
        assertEquals("tk-1", client.observeWorkActivity(CONV).first().tools.single().toolCallKey)
    }

    // ── ⑥ 累积上限裁剪 + 会话删除/化身更替清空 ──

    @Test
    fun pruneCapAndConversationCleanup() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // 205 个独立调用 → 裁到 200，最早 5 个被清（累积上限，防无界增长）
        repeat(205) { i ->
            p.applyEvent(toolEv(ulidOf(i + 1), EventEnvelope.TOOL_STARTED, runId = "r1", data = buildJsonObject {
                put("toolName", "t.$i"); put("toolCallKey", "tk-$i")
            }))
        }
        val rows = db.hubQueries.selectToolEventsByConversation(CONV).executeAsList()
        assertEquals(200, rows.size)
        assertTrue(rows.none { it.toolCallKey == "tk-0" })
        assertTrue(rows.none { it.toolCallKey == "tk-4" })
        assertTrue(rows.any { it.toolCallKey == "tk-5" })
        assertTrue(rows.any { it.toolCallKey == "tk-204" })

        // 会话删除 → 工具行与阶段帧同清（session.removed）
        p.applyEvent(ev("01JTL000000000000000000F1", EventEnvelope.SESSION_REMOVED, sk = SK, data = buildJsonObject {
            put("sessionKey", SK)
        }))
        assertEquals(0, db.hubQueries.selectToolEventsByConversation(CONV).executeAsList().size)
        assertEquals(0, db.hubQueries.selectRunStatusByConversation(CONV).executeAsList().size)
    }

    // ── fixture ──

    private fun ulidOf(i: Int): String = "01JTL" + i.toString().padStart(21, '0') // 26 位唯一占位 ULID（仅测试内唯一即可）

    private fun toolEv(
        id: String,
        type: String,
        sk: String? = SK,
        runId: String? = null,
        data: kotlinx.serialization.json.JsonObject,
        ts: String = T0,
    ) = ev(id, type, sk, data, runId, ts)

    private fun ev(
        id: String,
        type: String,
        sk: String? = SK,
        data: kotlinx.serialization.json.JsonObject,
        runId: String? = null,
        ts: String = T0,
    ) = EventEnvelope(id = id, seq = 1L, type = type, gatewayId = GW, sessionKey = sk, runId = runId, ts = ts, data = data)

    private fun fixtureSnapshot() = SnapshotResponse(
        snapshotWatermark = WM,
        generatedAt = T0,
        gateways = listOf(GatewaySummary(gatewayId = GW, name = "mini", online = false, agents = listOf(AgentRef("main", "Main")))),
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
        const val CONV = "01JCONV00000000000000000000"
        const val SK = "sk-main"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-06T08:00:00.000Z"
    }
}

/** 内存凭据仓（测试用；与本包 ApprovalProjectionTest 的同名类同型——不同文件各自私有，命名加前缀防重声明）。 */
private class ToolMemoryCredentialStore : CredentialStore {
    private var token: String? = null
    override suspend fun saveRefreshToken(token: String) { this.token = token }
    override suspend fun loadRefreshToken(): String? = token
    override suspend fun clear() { token = null }
}
