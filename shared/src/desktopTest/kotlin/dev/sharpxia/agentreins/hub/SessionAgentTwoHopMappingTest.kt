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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wp-4 修法一：通知归属两跳学习投影用例（desktopTest 验收项）：
 * ① session.updated 未映射 → 暂存 (g, sk, agentId)，卡片已在时即时连结写 hubConvKeys（twoHop=1）；
 * ② 卡片未到位 → 仅暂存；applyConversations 同 agent 卡到位 → convKey 连结且后续 run 终态/审批
 *    可路由（分域：run.started 不翻转卡片活跃态、message.created 不入时间线）；
 * ③ session.removed：两跳行清映射+暂存、卡片保留；自学习行级联删卡（防误删主线卡）；
 * ④ 快照重建后再连结 + 自学习行不降级（twoHop CASE 保序）；
 * ⑤ 登出 wipeLocalData 清暂存；
 * ⑥ 两跳连结键的 message.created 仍不入主线卡（app 主会话隔离——TG/channel 收口对位）。
 */
class SessionAgentTwoHopMappingTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun convKeyOf(db: HubDatabase, gw: String, sk: String) =
        db.hubQueries.selectConversationByKey(gw, sk).executeAsOneOrNull()

    private fun stagedOf(db: HubDatabase, gw: String, agentId: String) =
        db.hubQueries.selectSessionAgentsByCard(gw, agentId).executeAsList()

    // ── ① 未映射 session.updated → 暂存 + 即时连结（twoHop=1），身份分域不写卡 ──

    @Test
    fun sessionUpdatedUnmappedStagesAndJoinsImmediately() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot()) // 卡 CONV (gw=GW, agentId=main)；无映射
        assertTrue(stagedOf(db, GW, "main").isEmpty())

        // 未映射 session.updated（他端新会话/键形任意）→ 暂存 (GW, sk2, main) + 卡片已在 → 即时连结
        p.applyEvent(ev("01JT4000000000000000000001", EventEnvelope.SESSION_UPDATED, sk = SK2, data = buildJsonObject {
            put("sessionKey", SK2); put("agentId", "main"); put("label", "他端会话")
            put("lifecycleRevision", 5L); put("hasActiveRun", true)
        }))
        val staged = stagedOf(db, GW, "main").single()
        assertEquals(SK2, staged.sessionKey)
        assertEquals("main", staged.agentId)
        val key = convKeyOf(db, GW, SK2)
        assertNotNull(key)
        assertEquals(CONV, key.conversationId)
        assertEquals(1L, key.twoHop)

        // 分域：该 session.updated 不写卡片身份（label 不被劫持、hasActiveRun 不被 channel run 翻转）
        val conv = db.hubQueries.selectConversation(CONV).executeAsOne()
        assertEquals("主线", conv.label)
        assertFalse(conv.hasActiveRun == 1L)

        // 分域：run.started 不翻转（活跃态是本端 Stop 面权威）
        p.applyEvent(ev("01JT4000000000000000000002", EventEnvelope.RUN_STARTED, sk = SK2, runId = "r9", data = buildJsonObject {
            put("runId", "r9"); put("agentId", "main")
        }))
        assertFalse(db.hubQueries.selectConversation(CONV).executeAsOne().hasActiveRun == 1L)
        assertNull(db.hubQueries.selectConversation(CONV).executeAsOne().activeRunId)
    }

    // ── ② 卡片未到位仅暂存；applyConversations 连结后 run 终态/审批可路由 ──

    @Test
    fun calibrationJoinsStagedKeyThenRunTerminalAndApprovalRoute() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot()) // 只有 main 卡；cinema 卡未在

        // session.created（#16 新会话/他端触发）：卡片未到位 → 仅暂存，不建卡（铁律）
        p.applyEvent(ev("01JT4000000000000000000011", EventEnvelope.SESSION_CREATED, sk = SK3, data = buildJsonObject {
            put("sessionKey", SK3); put("agentId", "cinema"); put("label", ""); put("hasActiveRun", false)
        }))
        assertEquals(SK3, stagedOf(db, GW, "cinema").single().sessionKey)
        assertNull(convKeyOf(db, GW, SK3))
        assertEquals(1, db.hubQueries.selectConversations().executeAsList().size) // 未建卡

        // R4 校准：同 (gw, agentId) 卡到位 → convKey 连结
        p.applyConversations(
            listOf(
                ConversationRow(
                    conversationId = CONV, gatewayId = GW, label = "主线", agentId = "main",
                    lastMessagePreview = "", lastSeq = 0L, lastActiveAt = T0,
                    unreadCount = 0, hasActiveRun = false, reconciling = false,
                ),
                ConversationRow(
                    conversationId = CONV2, gatewayId = GW, label = "cinema 主线", agentId = "cinema",
                    lastMessagePreview = "", lastSeq = 0L, lastActiveAt = T0,
                    unreadCount = 0, hasActiveRun = false, reconciling = false,
                ),
            ),
        )
        val key = convKeyOf(db, GW, SK3)
        assertNotNull(key)
        assertEquals(CONV2, key.conversationId)
        assertEquals(1L, key.twoHop)

        // 连结后 run 终态可路由：两跳行 terminal 走守卫路径（activeRunId=null → 照常翻转关闭语义）
        p.applyEvent(ev("01JT4000000000000000000012", EventEnvelope.RUN_FAILED, sk = SK3, runId = "r10", data = buildJsonObject {
            put("runId", "r10"); put("errorKind", "timeout")
        }))
        val conv2 = db.hubQueries.selectConversation(CONV2).executeAsOne()
        assertEquals(0L, conv2.hasActiveRun)
        assertNull(conv2.activeRunId)

        // 连结后审批可归属：approval.requested 落 CONV2（通知/审批卡带会话名的数据基础）
        p.applyEvent(ev("01JT4000000000000000000013", EventEnvelope.APPROVAL_REQUESTED, sk = SK3, runId = "r10", data = buildJsonObject {
            put("runId", "r10"); put("approvalId", "ap-2h"); put("toolName", "bash"); put("payloadSummary", "ls")
        }))
        val approval = db.hubQueries.selectPendingApprovals().executeAsList().single()
        assertEquals(CONV2, approval.conversationId)
        assertEquals(0, p.stats.eventsSkipped)
    }

    // ── ③ session.removed：两跳行清映射+暂存保卡；自学习行级联删卡 ──

    @Test
    fun sessionRemovedKeepsCardForTwoHopAndCascadesForSelf() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV) // 自学习映射（主键）
        p.applyEvent(ev("01JT4000000000000000000021", EventEnvelope.SESSION_UPDATED, sk = SK2, data = buildJsonObject {
            put("sessionKey", SK2); put("agentId", "main"); put("label", "")
            put("lifecycleRevision", 1L); put("hasActiveRun", false)
        }))
        assertNotNull(convKeyOf(db, GW, SK2))

        // 两跳行 session.removed：清暂存 + 清映射，**卡片保留**（channel 会话删除不得误删主线卡）
        p.applyEvent(ev("01JT4000000000000000000022", EventEnvelope.SESSION_REMOVED, sk = SK2, data = buildJsonObject {
            put("sessionKey", SK2)
        }))
        assertTrue(stagedOf(db, GW, "main").isEmpty())
        assertNull(convKeyOf(db, GW, SK2))
        assertNotNull(db.hubQueries.selectConversation(CONV).executeAsOneOrNull())

        // 自学习行 session.removed：维持既有级联删卡语义
        p.applyEvent(ev("01JT4000000000000000000023", EventEnvelope.SESSION_REMOVED, sk = SK, data = buildJsonObject {
            put("sessionKey", SK)
        }))
        assertNull(db.hubQueries.selectConversation(CONV).executeAsOneOrNull())
        assertNull(convKeyOf(db, GW, SK))
    }

    // ── ④ 快照重建后再连结；自学习行不被暂存重连结降级 ──

    @Test
    fun snapshotRejoinKeepsSelfLearnedRowsAndRejoinsStaged() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        p.applyEvent(ev("01JT4000000000000000000031", EventEnvelope.SESSION_UPDATED, sk = SK2, data = buildJsonObject {
            put("sessionKey", SK2); put("agentId", "main"); put("label", "")
            put("lifecycleRevision", 2L); put("hasActiveRun", false)
        }))
        assertEquals(1L, convKeyOf(db, GW, SK2)!!.twoHop)

        // 该键随后被自学习（命令 ack 回执链等价面：直写自学习行）→ twoHop 翻 0（消息面全量恢复）
        db.hubQueries.upsertConvKey(GW, SK2, CONV)
        assertEquals(0L, convKeyOf(db, GW, SK2)!!.twoHop)

        // 快照整体重建（清卡回灌 + 暂存保留重连结）→ 自学习行**不降级**回两跳
        p.applySnapshot(fixtureSnapshot())
        assertEquals(0L, convKeyOf(db, GW, SK2)!!.twoHop)
        assertNotNull(db.hubQueries.selectConversation(CONV).executeAsOneOrNull())

        // 纯两跳行：重建后由快照卡行重连结恢复
        p.applyEvent(ev("01JT4000000000000000000032", EventEnvelope.SESSION_UPDATED, sk = SK4, data = buildJsonObject {
            put("sessionKey", SK4); put("agentId", "main"); put("label", "")
            put("lifecycleRevision", 3L); put("hasActiveRun", false)
        }))
        assertEquals(1L, convKeyOf(db, GW, SK4)!!.twoHop)
        p.applySnapshot(fixtureSnapshot())
        assertEquals(CONV, convKeyOf(db, GW, SK4)?.conversationId)
        assertEquals(1L, convKeyOf(db, GW, SK4)?.twoHop)
    }

    // ── ⑤ 登出 wipeLocalData 清暂存（含十一表全清回归；登记表 reset 语义见 RunOriginRegistryTest） ──

    @Test
    fun wipeLocalDataClearsSessionAgents() = runTest {
        val db = newDb()
        val client = DefaultHubClient(
            scope = backgroundScope,
            runtime = HubRuntime(backgroundScope, TwoHopMemoryCredentialStore()),
            db = db,
        )
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        p.applyEvent(ev("01JT4000000000000000000041", EventEnvelope.SESSION_UPDATED, sk = SK2, data = buildJsonObject {
            put("sessionKey", SK2); put("agentId", "main"); put("label", "")
            put("lifecycleRevision", 1L); put("hasActiveRun", false)
        }))
        assertTrue(stagedOf(db, GW, "main").isNotEmpty())

        client.wipeLocalData()
        assertTrue(stagedOf(db, GW, "main").isEmpty())
        assertNull(convKeyOf(db, GW, SK2))
        assertEquals(0, db.hubQueries.selectConversations().executeAsList().size)
    }

    // ── ⑥ 两跳连结键的 message.created 不入主线卡（隔离回归锚） ──

    @Test
    fun messageCreatedOnTwoHopKeyDoesNotTouchMainCard() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        p.applyEvent(ev("01JT4000000000000000000051", EventEnvelope.SESSION_UPDATED, sk = SK2, data = buildJsonObject {
            put("sessionKey", SK2); put("agentId", "main"); put("label", "")
            put("lifecycleRevision", 1L); put("hasActiveRun", false)
        }))
        assertNotNull(convKeyOf(db, GW, SK2))

        // channel/任务会话的 assistant 回复：不入主线卡时间线、不推未读（2026-11-06 TG 收口对位）
        p.applyEvent(ev("01JT4000000000000000000052", EventEnvelope.MESSAGE_CREATED, sk = SK2, data = buildJsonObject {
            put("messageId", "m-tg-1"); put("role", "assistant"); put("content", "TG 回复")
            put("sourceMessageSeq", 1L)
        }))
        assertEquals(0, db.hubQueries.selectMessages(CONV).executeAsList().size)
        assertEquals(0, db.hubQueries.selectConversation(CONV).executeAsOne().unreadCount)
        assertTrue(p.stats.eventsSkipped > 0)
    }

    // ── fixture ──

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
        const val CONV2 = "01JCONV20000000000000000000"
        const val SK = "sk-self"
        const val SK2 = "agent:main:telegram:default:direct:5716197848" // channel 形（真机留痕键；仅作不透明串用，ADR-14 不解析）
        const val SK3 = "sk-new-session"
        const val SK4 = "sk-second"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-06T08:00:00.000Z"
    }
}

/** 内存凭据仓（测试用；两跳映射用例私有，与他文件同名类不冲突）。 */
private class TwoHopMemoryCredentialStore : CredentialStore {
    private var token: String? = null
    override suspend fun saveRefreshToken(token: String) { this.token = token }
    override suspend fun loadRefreshToken(): String? = token
    override suspend fun clear() { token = null }
}
