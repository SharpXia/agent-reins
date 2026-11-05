package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.command.DefaultCommandClient
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
import dev.sharpxia.agentreins.hub.projection.MessageRow
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wp-9 审批投影与决策链路用例（desktopTest 验收项）：
 * ① approval.requested → hubApprovals pending 行落库（含归属未学习 conversationId=NULL 兜底）；
 * ② approval.resolved（app/host 双来源）→ 终态收敛出 pending（卡片离场 = 收敛）；
 * ③ DefaultHubClient.observePendingApprovals Flow 镜像投影（UiApproval 字段映射 + null 归属）；
 * ④ decideApproval 决策通道三分支：PERSISTED 回执 / Hub FAILED（B-4 预检未通过场景）→ null+队列
 *    FAILED / 网络不可达 → null+队列 PENDING_LOCAL（不自动重放，降级态显式呈现的依据）。
 */
class ApprovalProjectionTest {

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    // ── ① requested → pending（归属映射命中 / 未学习 NULL 兜底 + updatedAt 排序） ──

    @Test
    fun requestedLandsPendingWithAttribution() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // 映射命中：归属本会话
        p.applyEvent(ev("01JAP000000000000000000001", EventEnvelope.APPROVAL_REQUESTED, sk = SK, runId = "r1", data = buildJsonObject {
            put("runId", "r1"); put("approvalId", "ap-1"); put("toolName", "bash"); put("payloadSummary", "rm -rf /tmp/x")
        }))
        val mapped = db.hubQueries.selectPendingApprovals().executeAsList().single()
        assertEquals("ap-1", mapped.approvalId)
        assertEquals("bash", mapped.toolName)
        assertEquals("rm -rf /tmp/x", mapped.payloadSummary)
        assertEquals(CONV, mapped.conversationId)
        assertEquals("pending", mapped.status)
        assertNull(mapped.decidedBy)

        // 映射未学习（未知 sessionKey）：照常落 pending，conversationId=NULL——UI 以「未关联会话」
        // 呈现，不得丢卡（丢卡 = 审批永远无人可见）；ts 晚于 ap-1 支撑排序断言
        p.applyEvent(ev("01JAP000000000000000000002", EventEnvelope.APPROVAL_REQUESTED, sk = "sk-ghost", runId = "r2", data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-2"); put("toolName", "web"); put("payloadSummary", "GET https://x")
        }, ts = "2026-11-05T08:00:01.000Z"))
        val rows = db.hubQueries.selectPendingApprovals().executeAsList()
        assertEquals(2, rows.size)
        val unmapped = rows.single { it.approvalId == "ap-2" }
        assertNull(unmapped.conversationId)
        assertEquals("pending", unmapped.status)
        // 查询按 updatedAt 升序（ts 早者在前）
        assertEquals(listOf("ap-1", "ap-2"), rows.map { it.approvalId })
    }

    // ── ② resolved → 终态收敛出 pending（app/host 双来源；未知 id 幂等无害） ──

    @Test
    fun resolvedConvergesOutOfPending() {
        val db = newDb()
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)
        p.applyEvent(ev("01JAP000000000000000000003", EventEnvelope.APPROVAL_REQUESTED, sk = SK, runId = "r1", data = buildJsonObject {
            put("runId", "r1"); put("approvalId", "ap-1"); put("toolName", "bash"); put("payloadSummary", "s")
        }))
        p.applyEvent(ev("01JAP000000000000000000004", EventEnvelope.APPROVAL_REQUESTED, sk = SK, runId = "r2", data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-2"); put("toolName", "web"); put("payloadSummary", "s")
        }))

        // App 侧决定（approval.decide 路径）→ approved，decidedBy 记录来源
        p.applyEvent(ev("01JAP000000000000000000005", EventEnvelope.APPROVAL_RESOLVED, sk = SK, runId = "r1", data = buildJsonObject {
            put("runId", "r1"); put("approvalId", "ap-1"); put("decision", "approved"); put("decidedBy", "app")
        }))
        // 宿主机人工处理 → rejected，同样收敛出 pending
        p.applyEvent(ev("01JAP000000000000000000006", EventEnvelope.APPROVAL_RESOLVED, sk = SK, runId = "r2", data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-2"); put("decision", "rejected"); put("decidedBy", "host")
        }))
        assertEquals(0, db.hubQueries.selectPendingApprovals().executeAsList().size)

        // 未知 approvalId 的 resolved：UPDATE 零行、不建行不报错（幂等无害）
        val before = db.hubQueries.selectPendingApprovals().executeAsList().size
        p.applyEvent(ev("01JAP000000000000000000007", EventEnvelope.APPROVAL_RESOLVED, sk = SK, runId = "r9", data = buildJsonObject {
            put("runId", "r9"); put("approvalId", "ap-none"); put("decision", "approved"); put("decidedBy", "app")
        }))
        assertEquals(before, db.hubQueries.selectPendingApprovals().executeAsList().size)
        assertEquals(0, p.stats.eventsSkipped)
    }

    // ── ③ observePendingApprovals Flow 镜像投影（wp-9 UI 数据面） ──

    @Test
    fun observePendingApprovalsMirrorsProjection() = runTest {
        val db = newDb()
        // 未登录（restore 未调用）客户端：observe 是纯投影库面，认证栈不参与
        val client = DefaultHubClient(
            scope = backgroundScope,
            runtime = HubRuntime(backgroundScope, MemoryCredentialStore()),
            db = db,
        )
        val p = DefaultEventProjector(db)
        p.applySnapshot(fixtureSnapshot())
        db.hubQueries.upsertConvKey(GW, SK, CONV)

        // 空库 → 空列表
        assertTrue(client.observePendingApprovals().first().isEmpty())

        // 投影落 pending → Flow 镜像出 UiApproval（字段映射 + 归属）
        p.applyEvent(ev("01JAP000000000000000000008", EventEnvelope.APPROVAL_REQUESTED, sk = SK, runId = "r1", data = buildJsonObject {
            put("runId", "r1"); put("approvalId", "ap-1"); put("toolName", "bash"); put("payloadSummary", "ls -la")
        }))
        p.applyEvent(ev("01JAP000000000000000000009", EventEnvelope.APPROVAL_REQUESTED, sk = "sk-ghost", runId = "r2", data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-2"); put("toolName", "web"); put("payloadSummary", "GET https://x")
        }))
        val list = client.observePendingApprovals().first()
        assertEquals(2, list.size)
        val mapped = list.first { it.approvalId == "ap-1" }
        assertEquals("r1", mapped.runId)
        assertEquals(CONV, mapped.conversationId)
        assertEquals("bash", mapped.toolName)
        assertEquals("ls -la", mapped.payloadSummary)
        assertNull(list.first { it.approvalId == "ap-2" }.conversationId)

        // resolved → 行离场，Flow 收敛为空（卡片消失 = 收敛，UI 不维护第二份状态）
        p.applyEvent(ev("01JAP00000000000000000000A", EventEnvelope.APPROVAL_RESOLVED, sk = SK, runId = "r1", data = buildJsonObject {
            put("runId", "r1"); put("approvalId", "ap-1"); put("decision", "approved"); put("decidedBy", "app")
        }))
        p.applyEvent(ev("01JAP00000000000000000000B", EventEnvelope.APPROVAL_RESOLVED, sk = "sk-ghost", runId = "r2", data = buildJsonObject {
            put("runId", "r2"); put("approvalId", "ap-2"); put("decision", "rejected"); put("decidedBy", "host")
        }))
        assertTrue(client.observePendingApprovals().first().isEmpty())
    }

    // ── ④ decideApproval 决策通道三分支（降级态判定的依据） ──

    /** 非契约异常（模拟网络不可达；DefaultCommandClient 对非 HubException 保持 PENDING_LOCAL）。 */
    private class FakeNetworkError : Exception("network down")

    @Test
    fun decideApprovalChannelOutcomes() = runTest {
        // a. PERSISTED 回执（202）→ 返回 receipt、队列 PERSISTED（生效仍以 approval.resolved 收敛）
        val okDb = newDb()
        val okApi = FakeHubApi { key, _ ->
            CommandReceipt(commandId = "01JCMD00000000000000000000", idempotencyKey = key, state = "PERSISTED", acceptedAt = T0)
        }
        val ok = DefaultCommandClient(okApi, ApprovalStubAuth(), okDb)
        val receipt = ok.decideApproval("ap-1", "approve")
        assertTrue(receipt != null)
        assertEquals("PERSISTED", okDb.hubQueries.selectCommandByKey(receipt.idempotencyKey).executeAsOne().state)

        // b. Hub 确定性拒绝（B-4 预检未通过场景，403 HUB-BIZ-004）→ null；条目落终态 FAILED——
        //    不可重试队列里查不到（selectNonTerminalCommands 排除终态），UI 据此显式呈降级态不静默
        val rejectedDb = newDb()
        val rejectedApi = FakeHubApi { _, _ ->
            throw HubException(HubErrorPayload(code = "HUB-BIZ-004", message = "预检未通过"), 403, "req-1")
        }
        val rejected = DefaultCommandClient(rejectedApi, ApprovalStubAuth(), rejectedDb)
        assertNull(rejected.decideApproval("ap-1", "approve"))
        assertTrue(rejectedDb.hubQueries.selectNonTerminalCommands().executeAsList().isEmpty())

        // c. 网络不可达（非 HubException 异常）→ null；条目保持 PENDING_LOCAL（approval.decide 不在
        //    retryUnfinished 重放面，待用户显式重试 = 新意图新键）
        val offlineDb = newDb()
        val offlineApi = FakeHubApi { _, _ -> throw FakeNetworkError() }
        val offline = DefaultCommandClient(offlineApi, ApprovalStubAuth(), offlineDb)
        assertNull(offline.decideApproval("ap-1", "reject"))
        val pendingCmd = offlineDb.hubQueries.selectNonTerminalCommands().executeAsList().single()
        assertEquals("approval.decide", pendingCmd.type)
        assertEquals("PENDING_LOCAL", pendingCmd.state)
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
        const val SK = "sk-main"
        const val WM = "01JAAA0000000000000000000000"
        const val T0 = "2026-11-05T08:00:00.000Z"
    }
}

/** 内存凭据仓（测试用，避免 desktop 平台实现触碰真实文件系统）。 */
private class MemoryCredentialStore : CredentialStore {
    private var token: String? = null
    override suspend fun saveRefreshToken(token: String) { this.token = token }
    override suspend fun loadRefreshToken(): String? = token
    override suspend fun clear() { token = null }
}

/** decideApproval 可编排的 HubApi 桩：其余端点不被本用例触达。 */
private class FakeHubApi(
    private val decide: suspend (idempotencyKey: String, decision: String) -> CommandReceipt,
) : HubApi {
    private fun unused(): Nothing = TODO("not used in ApprovalProjectionTest")
    override suspend fun login(req: dev.sharpxia.agentreins.hub.auth.LoginRequest) = unused()
    override suspend fun refresh(req: dev.sharpxia.agentreins.hub.auth.RefreshRequest) = unused()
    override suspend fun registerDevice(req: dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest) = unused()
    override suspend fun listDevices() = unused()
    override suspend fun deleteDevice(deviceId: String) = unused()
    override suspend fun gateways() = unused()
    override suspend fun conversations(limit: Int?, cursor: String?) = unused()
    override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = unused()
    override suspend fun sendMessage(conversationId: String, req: SendMessageRequest) = unused()
    override suspend fun markRead(conversationId: String, req: ReadCursorRequest) = unused()
    override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest) = unused()
    override suspend fun compactConversation(conversationId: String) = unused()
    override suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse = unused()
    override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest) = unused()
    override suspend fun cancelRun(runId: String, req: CancelRunRequest) = unused()
    override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest): CommandReceipt =
        decide(req.idempotencyKey, req.decision)
    override suspend fun snapshot() = unused()
    override suspend fun events(cursor: String, limit: Int?) = unused()
}

/** withBearer 直通（不触网）。 */
private class ApprovalStubAuth : TokenManager {
    override suspend fun login(username: String, password: String, mfaCode: String?) = TODO()
    override suspend fun registerCurrentDevice(displayName: String, platform: String, model: String?, appVersion: String?) = TODO()
    override suspend fun restoreFromStoredRefreshToken() = false
    override suspend fun <T> withBearer(block: suspend (String) -> T): T = block("tok")
    override fun currentAccessToken(): String? = "tok"
    override suspend fun clearCredentials() {}
}
