package dev.sharpxia.agentreins.hub.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 新会话卡可见性（2026-11-28 探针实锤的既有缺陷收口）：session.updated（reason=create）不建
 * 占位卡，新卡只经快照/列表校准可见；既有触发点「#16 回执 PERSISTED 后立即校准」早于网关
 * 建成会话（实测 ~2s），输掉竞态新卡要等下一 R4 节拍（分钟级）。现 conversation.create 终态
 * 回执（COMPLETED/FAILED）应用后锁外异步触发一次校准。本表锁触发条件：终态才触发、非创建类
 * 命令不触发、未注入作用域零触发（单测/直连组装零变化）。
 */
class CreateAckCalibrationTest {

    @Test
    fun settledCreateAckTriggersCalibrationOncePerTerminalAck() = runBlocking {
        val db = newDb()
        val api = CountingApi()
        // Unconfined：launch 立即执行到首个挂起点，校准请求在 onEventArrived 返回前完成（确定性）
        val engine = DefaultSyncEngine(api, CreateAckStubAuth(), DefaultEventProjector(db), DbEventCursorStore(db), CoroutineScope(Dispatchers.Unconfined))

        engine.onEventArrived(ack("01JAAA00000000000000000001", "PERSISTED"))
        engine.onEventArrived(ack("01JAAA00000000000000000002", "DELIVERING"))
        assertEquals(0, api.conversationsCalls, "非终态回执不触发校准")

        engine.onEventArrived(ack("01JAAA00000000000000000003", "COMPLETED"))
        assertEquals(1, api.conversationsCalls, "COMPLETED 回执触发一次校准（新卡即现）")

        engine.onEventArrived(ack("01JAAA00000000000000000004", "COMPLETED", commandType = "message.send"))
        engine.onEventArrived(ack("01JAAA00000000000000000005", "FAILED", commandType = "message.send"))
        assertEquals(1, api.conversationsCalls, "非创建类命令终态不触发校准")

        engine.onEventArrived(ack("01JAAA00000000000000000006", "FAILED"))
        assertEquals(2, api.conversationsCalls, "FAILED 回执同样触发（「已提交」收敛态经校准落地）")

        // 同事件重放（幂等去重 + 游标水位）不再触发
        engine.onEventArrived(ack("01JAAA00000000000000000006", "FAILED"))
        assertEquals(2, api.conversationsCalls, "重放事件不得重复触发校准")
    }

    @Test
    fun noScopeMeansNoTriggerAndNoCrash() = runBlocking {
        val db = newDb()
        val api = CountingApi()
        val engine = DefaultSyncEngine(api, CreateAckStubAuth(), DefaultEventProjector(db), DbEventCursorStore(db))
        engine.onEventArrived(ack("01JAAA00000000000000000001", "COMPLETED"))
        assertEquals(0, api.conversationsCalls)
    }

    @Test
    fun malformedAckPayloadIsIgnored() = runBlocking {
        val db = newDb()
        val api = CountingApi()
        val engine = DefaultSyncEngine(api, CreateAckStubAuth(), DefaultEventProjector(db), DbEventCursorStore(db), CoroutineScope(Dispatchers.Unconfined))
        // 载荷形状漂移（非对象 / 字段非原始值 / 缺字段）按不命中处理，不抛不触发
        val drifted = listOf(
            EventEnvelope(id = "01JAAA00000000000000000011", type = "command.acked", gatewayId = "gw1", ts = "t",
                data = kotlinx.serialization.json.JsonPrimitive("x")),
            EventEnvelope(id = "01JAAA00000000000000000012", type = "command.acked", gatewayId = "gw1", ts = "t",
                data = buildJsonObject { put("commandType", buildJsonObject { }); put("status", "COMPLETED") }),
            EventEnvelope(id = "01JAAA00000000000000000013", type = "command.acked", gatewayId = "gw1", ts = "t",
                data = buildJsonObject { put("commandType", "conversation.create") }),
            EventEnvelope(id = "01JAAA00000000000000000014", type = "session.updated", gatewayId = "gw1", ts = "t",
                data = buildJsonObject { put("commandType", "conversation.create"); put("status", "COMPLETED") }),
        )
        drifted.forEach { engine.onEventArrived(it) }
        assertEquals(0, api.conversationsCalls)
    }

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun ack(id: String, status: String, commandType: String = "conversation.create") = EventEnvelope(
        id = id, seq = 1L, type = "command.acked", gatewayId = "gw1", sessionKey = null, runId = null,
        ts = "2026-11-28T00:00:00Z",
        data = buildJsonObject {
            put("commandId", "cmd-$id")
            put("idempotencyKey", "idem-$id")
            put("commandType", commandType)
            put("status", status)
            put("previousStatus", "PERSISTED")
        },
    )
}

/** 只计数 conversations()（校准请求面）；其余端点不在本测试路径。 */
private class CountingApi : HubApi {
    var conversationsCalls = 0

    override suspend fun conversations(limit: Int?, cursor: String?): ConversationsPage {
        conversationsCalls++
        return ConversationsPage(conversations = emptyList())
    }

    override suspend fun login(req: dev.sharpxia.agentreins.hub.auth.LoginRequest) = TODO()
    override suspend fun refresh(req: dev.sharpxia.agentreins.hub.auth.RefreshRequest) = TODO()
    override suspend fun registerDevice(req: dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest) = TODO()
    override suspend fun listDevices() = TODO()
    override suspend fun deleteDevice(deviceId: String) = TODO()
    override suspend fun registerApnsToken(req: dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest) = TODO()
    override suspend fun gateways() = TODO()
    override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = TODO()
    override suspend fun sendMessage(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.SendMessageRequest) = TODO()
    override suspend fun uploadMedia(conversationId: String, bytes: ByteArray, mime: String, fileName: String?, width: Int?, height: Int?) = TODO()
    override suspend fun fetchMediaContent(mediaId: String) = TODO()
    override suspend fun commands(conversationId: String) = TODO()
    override suspend fun models(conversationId: String) = TODO()
    override suspend fun markRead(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.ReadCursorRequest) = TODO()
    override suspend fun createConversation(gatewayId: String, agentId: String, req: dev.sharpxia.agentreins.hub.projection.CreateConversationRequest) = TODO()
    override suspend fun compactConversation(conversationId: String) = TODO()
    override suspend fun agentStatus(gatewayId: String, agentId: String): dev.sharpxia.agentreins.hub.projection.AgentStatusResponse = TODO()
    override suspend fun renameConversation(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.RenameConversationRequest) = TODO()
    override suspend fun cancelRun(runId: String, req: dev.sharpxia.agentreins.hub.projection.CancelRunRequest) = TODO()
    override suspend fun decideApproval(approvalId: String, req: dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest) = TODO()
    override suspend fun snapshot(): SnapshotResponse = TODO()
    override suspend fun createGateway(req: dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest): dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse = TODO()
    override suspend fun rotateBridgeToken(gatewayId: String): dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse = TODO()
    override suspend fun deleteGateway(gatewayId: String) = TODO()
    override suspend fun events(cursor: String, limit: Int?): EventsPage = TODO()
}

private class CreateAckStubAuth : TokenManager {
    override suspend fun login(username: String, password: String, mfaCode: String?) = TODO()
    override suspend fun registerCurrentDevice(displayName: String, platform: String, model: String?, appVersion: String?) = TODO()
    override suspend fun restoreFromStoredRefreshToken() = false
    override suspend fun <T> withBearer(block: suspend (String) -> T): T = block("create-ack-test-token")
    override fun currentAccessToken(): String? = null
    override suspend fun clearCredentials() {}
}
