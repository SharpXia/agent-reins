package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.command.DefaultCommandClient
import dev.sharpxia.agentreins.hubdb.HubDatabase
import dev.sharpxia.agentreins.hub.projection.AgentRef
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ConversationRow
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.GatewaySummary
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hub.HubApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * wp-4 修法二：RunOriginRegistry「本端发起」判定用例（desktopTest 验收项）：
 * ① 本端提交过命令（message.send 登记）的会话 → run 终态放行（isRunLocal true，消费即清除）；
 * ② 无记录（他端发起 / run.started 未映射）→ 终态抑制（isRunLocal false）；
 * ③ compact 静默通道覆盖准入 → 终态抑制；再次 SEND → 放行恢复；
 * ④ DefaultCommandClient 提交面装配：sendMessage 登记准入、compactConversation 登记静默；
 * ⑤ reset 清空（登出不残留）；
 * ⑥ wp-4 回归修复（混合形态 2026-11-06）：同 agent 卡 + TG 两跳键 + 先 SEND 后 TG run——
 *    feedRunOrigin 只喂 twoHop==0 自学习行，主线卡 SEND 标记不得经两跳连结外溢到同 agent 的
 *    TG/channel/任务 run（修前此形态泄漏「运行完成」通知）；对照自学习主键 run 照常放行；
 * ⑦ SEND 登记时机：submit 未取得 PERSISTED 回执（FAILED/网络失败）不登记准入。
 */
class RunOriginRegistryTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun registry() = RunOriginRegistry()

    // ── ① SEND 准入 → 终态放行（消费即清除） ──

    @Test
    fun sendMarkedSessionAdmitsRunTerminal() {
        val r = registry()
        r.markSubmission("conv-1", RunOriginRegistry.Kind.SEND)
        r.noteRunStarted("gw1", "r1", "conv-1")
        assertTrue(r.isRunLocal("gw1", "r1"), "本端提交过命令的会话终态应放行")
        // 消费即清除：同 run 二次终态不再放行（防陈旧标记跨终态复用）
        assertFalse(r.isRunLocal("gw1", "r1"))
    }

    // ── ② 无记录 → 抑制 ──

    @Test
    fun unmarkedSessionsSuppressRunTerminal() {
        val r = registry()
        r.noteRunStarted("gw1", "r2", null) // run.started 时映射未学习
        r.noteRunStarted("gw1", "r3", "conv-remote") // 他端发起、本端从未提交
        assertFalse(r.isRunLocal("gw1", "r2"))
        assertFalse(r.isRunLocal("gw1", "r3"))
        // 未标记的 run 直接查终态 = 无记录抑制
        assertFalse(r.isRunLocal("gw1", "r-never-started"))
        assertFalse(r.isRunLocal("gw1", null))
    }

    // ── ③ COMPACT 静默覆盖 + SEND 恢复 ──

    @Test
    fun compactDisarmsSendThenNextSendRearms() {
        val r = registry()
        r.markSubmission("conv-1", RunOriginRegistry.Kind.SEND)
        r.markSubmission("conv-1", RunOriginRegistry.Kind.COMPACT) // compact 触发静默（wp-4 scope）
        r.noteRunStarted("gw1", "r4", "conv-1")
        assertFalse(r.isRunLocal("gw1", "r4"), "compact 触发的 run 终态应静默")

        r.markSubmission("conv-1", RunOriginRegistry.Kind.SEND)
        r.noteRunStarted("gw1", "r5", "conv-1")
        assertTrue(r.isRunLocal("gw1", "r5"), "再次消息发起后恢复准入")
    }

    // ── ④ DefaultCommandClient 提交面装配（sendMessage / compactConversation） ──

    @Test
    fun commandSubmissionFaceRegistersRegistry() = runTest {
        val db = newDb()
        val receipt = CommandReceipt(commandId = "01JCMD", idempotencyKey = "k", state = "PERSISTED", acceptedAt = "t")
        val r = registry()
        val commands = DefaultCommandClient(RegisterFakeHubApi(receipt), RegisterStubAuth(), db, r)

        // message.send → SEND 准入（run 终态放行）
        commands.sendMessage("conv-1", "hello")
        r.noteRunStarted("gw1", "rA", "conv-1")
        assertTrue(r.isRunLocal("gw1", "rA"))

        // compactConversation → COMPACT 覆盖准入（run 终态静默）
        commands.compactConversation("conv-1")
        r.noteRunStarted("gw1", "rB", "conv-1")
        assertFalse(r.isRunLocal("gw1", "rB"), "compact 触发的 run 终态应静默")
    }

    // ── ⑤ reset ──

    @Test
    fun resetClearsAllMarks() {
        val r = registry()
        r.markSubmission("conv-1", RunOriginRegistry.Kind.SEND)
        r.noteRunStarted("gw1", "r6", "conv-1")
        r.reset()
        r.noteRunStarted("gw1", "r7", "conv-1")
        assertFalse(r.isRunLocal("gw1", "r6"))
        assertFalse(r.isRunLocal("gw1", "r7"))
    }

    // ── ⑥ wp-4 回归修复（混合形态 2026-11-06）：同 agent 卡 + TG 两跳键 + 先 SEND 后 TG run ──
    // 修前链条：SEND 登记 conversationId 级 + feedRunOrigin 对两跳连结行也解析出主线卡 →
    // TG 触发的 run 被误判本端 → 终态在本端弹「运行完成/失败」（通知面回归）。修后：两跳行
    // 解析结果不喂登记面，终态静默；自学习主键 run 照常放行。

    @Test
    fun twoHopKeyRunAfterSendIsNotAdmittedAsLocal() = runTest {
        val db = newDb()
        val registry = registry()
        val commands = DefaultCommandClient(RegisterFakeHubApi(persistedReceipt()), RegisterStubAuth(), db, registry)
        val projector = DefaultEventProjector(db)
        val client = DefaultHubClient(
            scope = backgroundScope,
            runtime = HubRuntime(backgroundScope, RegistryMemoryCredentialStore()),
            db = db,
        )
        projector.applySnapshot(fixtureSnapshot()) // main 卡（gw=GW, agentId=main）

        // 本端 SEND（PERSISTED 后登记 SEND 准入）+ 主键自学习行（twoHop=0，回执链学习通道成果）
        commands.sendMessage(CONV, "hello")
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // 同 agent 的 TG 键经 session.updated 两跳连结到同一张主线卡（joinStagedSessionAgents 路径）
        projector.applyEvent(ev("01JT4000000000000000000061", EventEnvelope.SESSION_UPDATED, sk = SK2, data = buildJsonObject {
            put("sessionKey", SK2); put("agentId", "main"); put("label", "")
            put("lifecycleRevision", 1L); put("hasActiveRun", false)
        }))
        val joined = convKeyOf(db, GW, SK2)
        assertEquals(CONV, joined?.conversationId)
        assertEquals(1L, joined?.twoHop)

        // TG run.started 喂帧（两跳行）→ 不喂登记面 → 不标本端 → 终态静默
        client.feedRunOrigin(
            registry,
            ev("01JT4000000000000000000062", EventEnvelope.RUN_STARTED, sk = SK2, runId = "r-tg", data = buildJsonObject {
                put("runId", "r-tg"); put("agentId", "main")
            }),
        )
        assertFalse(registry.isRunLocal(GW, "r-tg"), "TG 两跳键的 run 不得因主线卡 SEND 标记判本端")

        // 对照：主键（自学习 twoHop=0）run.started 喂帧 → 准入 → 终态放行
        client.feedRunOrigin(
            registry,
            ev("01JT4000000000000000000063", EventEnvelope.RUN_STARTED, sk = SK, runId = "r-main", data = buildJsonObject {
                put("runId", "r-main"); put("agentId", "main")
            }),
        )
        assertTrue(registry.isRunLocal(GW, "r-main"), "自学习主键的 run 照常判本端")
    }

    // ── ⑦ SEND 登记时机：submit 未取得 PERSISTED 回执（确定性拒绝）不登记准入 ──

    @Test
    fun failedSendDoesNotAdmit() = runTest {
        val db = newDb()
        val registry = registry()
        val commands = DefaultCommandClient(FailingHubApi(), RegisterStubAuth(), db, registry)
        commands.sendMessage("conv-1", "hello") // HubException(HUB-BIZ-003) → FAILED，返回 null
        registry.noteRunStarted("gw1", "rX", "conv-1")
        assertFalse(registry.isRunLocal("gw1", "rX"), "提交未达（FAILED）的消息不得开 SEND 准入")
    }

    // ── fixture（同 SessionAgentTwoHopMappingTest 形态，文件内私有副本） ──

    private fun persistedReceipt() =
        CommandReceipt(commandId = "01JCMD2", idempotencyKey = "k2", state = "PERSISTED", acceptedAt = "t2")

    private fun convKeyOf(db: HubDatabase, gw: String, sk: String) =
        db.hubQueries.selectConversationByKey(gw, sk).executeAsOneOrNull()

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
        const val SK = "sk-self"
        const val SK2 = "agent:main:telegram:default:direct:5716197848" // channel 形（真机留痕键；仅作不透明串用，ADR-14 不解析）
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-06T08:00:00.000Z"
    }
}

/** sendMessage/compactConversation 可编排的 HubApi 桩（其余端点不触达，同 ApprovalProjectionTest 模式）。 */
private open class RegisterFakeHubApi(private val receipt: CommandReceipt) : HubApi {
    private fun unused(): Nothing = TODO("not used in RunOriginRegistryTest")

    /** sendMessage 行为注入点（FailingHubApi 覆写为抛 HubException）。 */
    open suspend fun onSendMessage(conversationId: String, req: SendMessageRequest): CommandReceipt = receipt

    override suspend fun login(req: LoginRequest) = unused()
    override suspend fun refresh(req: RefreshRequest) = unused()
    override suspend fun registerDevice(req: DeviceRegistrationRequest) = unused()
    override suspend fun listDevices() = unused()
    override suspend fun deleteDevice(deviceId: String) = unused()
    override suspend fun registerApnsToken(req: ApnsTokenRequest) = unused()
    override suspend fun gateways() = unused()
    override suspend fun conversations(limit: Int?, cursor: String?) = unused()
    override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = unused()
    override suspend fun sendMessage(conversationId: String, req: SendMessageRequest): CommandReceipt = onSendMessage(conversationId, req)
    override suspend fun markRead(conversationId: String, req: ReadCursorRequest) = unused()
    override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest) = unused()
    override suspend fun compactConversation(conversationId: String): CommandReceipt = receipt
    override suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse = unused()
    override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest) = unused()
    override suspend fun cancelRun(runId: String, req: CancelRunRequest) = unused()
    override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest) = unused()
    override suspend fun snapshot() = unused()
    override suspend fun events(cursor: String, limit: Int?) = unused()
}

/** sendMessage 确定性拒绝桩（⑦ 用）：submit 走 FAILED 分支不返回回执。 */
private class FailingHubApi : RegisterFakeHubApi(
    CommandReceipt(commandId = "", idempotencyKey = "", state = "FAILED", acceptedAt = ""),
) {
    override suspend fun onSendMessage(conversationId: String, req: SendMessageRequest): CommandReceipt =
        throw HubException(HubErrorPayload(code = "HUB-BIZ-003", message = "stub rejected"), 429, null)
}

/** withBearer 直通（不触网）。 */
private class RegisterStubAuth : TokenManager {
    override suspend fun login(username: String, password: String, mfaCode: String?) = TODO()
    override suspend fun registerCurrentDevice(displayName: String, platform: String, model: String?, appVersion: String?) = TODO()
    override suspend fun restoreFromStoredRefreshToken() = false
    override suspend fun <T> withBearer(block: suspend (String) -> T): T = block("tok")
    override fun currentAccessToken(): String? = "tok"
    override suspend fun clearCredentials() {}
}

/** 内存凭据仓（⑥ 构造 DefaultHubClient 用；与他文件同名类不冲突）。 */
private class RegistryMemoryCredentialStore : CredentialStore {
    private var token: String? = null
    override suspend fun saveRefreshToken(token: String) { this.token = token }
    override suspend fun loadRefreshToken(): String? = token
    override suspend fun clear() { token = null }
}
