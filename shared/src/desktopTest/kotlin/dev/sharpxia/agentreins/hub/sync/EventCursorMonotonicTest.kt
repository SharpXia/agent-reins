package dev.sharpxia.agentreins.hub.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.EventProjector
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hubdb.HubDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * FLOW-3（2026-11-22）：同步引擎事件应用串行化回归锚——onEventArrived 的游标读改写（读 c →
 * 应用 → 按陈旧 c 判大回写）与并发入口交错曾可把持久游标拉回已应用事件 id 之下（WSS 帧收集
 * 与 R4 校准两条协程；桌面 scope = Dispatchers.Default 真并行），resume/catchUp 重投旧事件
 * 且 delta 追加非幂等即重复正文。
 *
 * 确定性复现：e1（EARLY）在投影应用段闩住线程（游标已读、未写回），e2（LATE）此刻全速跑完，
 * 再放行 e1——修复前 e1 按陈旧 c（null）条件回写，游标终值 = EARLY（回退）；修复后 applyMutex
 * 串行，e2 停在锁上等 e1 写回后再进，游标终值 = LATE（单调，INV-2）。事件用未注册类型
 * （投影忽略内容、游标路径不受影响），测试只锁游标语义不锁投影。
 */
class EventCursorMonotonicTest {

    @Test
    fun concurrentOnEventArrivedKeepsCursorMonotonic() = runBlocking {
        val db = newDb()
        val base = DefaultEventProjector(db)
        val e1InProjection = CountDownLatch(1)
        val releaseE1 = CountDownLatch(1)
        val gated = object : EventProjector by base {
            override fun applyEvent(envelope: EventEnvelope) {
                if (envelope.id == EARLY) {
                    e1InProjection.countDown()
                    // 线程阻塞（非挂起）：模拟应用长事务，留出「游标已读未写」的并发插入窗
                    releaseE1.await()
                }
                base.applyEvent(envelope)
            }
        }
        val cursors = DbEventCursorStore(db)
        val engine = DefaultSyncEngine(StubApi(), StubAuth(), gated, cursors)

        val e1 = launch(Dispatchers.Default) { engine.onEventArrived(envelope(EARLY)) }
        assertTrue(e1InProjection.await(5, TimeUnit.SECONDS), "e1 未在超时内进入投影应用段")
        val e2 = launch(Dispatchers.Default) { engine.onEventArrived(envelope(LATE)) }
        delay(200) // 有锁：e2 必停在 applyMutex 上（e1 未放行）；无锁：e2 此时已全程跑完
        assertFalse(e2.isCompleted, "e2 未被串行化：游标读改写仍在并发交错（FLOW-3 修复失效）")
        releaseE1.countDown()
        e1.join()
        e2.join()
        assertEquals(LATE, cursors.eventCursor(), "游标终值须为已应用最大事件 id（不得回退到 EARLY）")
    }

    private fun newDb(): HubDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HubDatabase.Schema.create(driver)
        return HubDatabase(driver)
    }

    private fun envelope(id: String) = EventEnvelope(
        id = id, seq = 1L, type = "test.unknown.type", gatewayId = "gw1", sessionKey = "sk-1",
        runId = null, ts = "2026-11-22T00:00:00Z",
        data = kotlinx.serialization.json.buildJsonObject { },
    )

    companion object {
        /** 字典序 EARLY < LATE（ULID 时间序）；事件类型未注册（投影忽略内容，游标路径照常）。 */
        private val EARLY = "01JAAA00000000000000000001"
        private val LATE = "01JAAA00000000000000000002"
    }
}

/** onEventArrived 单测桩：事件路径不触网，其余端点 TODO（对应单测不触网，HubSyncFixtureTest 同纪律）。 */
private class StubApi : HubApi {
    override suspend fun login(req: dev.sharpxia.agentreins.hub.auth.LoginRequest) = TODO()
    override suspend fun refresh(req: dev.sharpxia.agentreins.hub.auth.RefreshRequest) = TODO()
    override suspend fun registerDevice(req: dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest) = TODO()
    override suspend fun listDevices() = TODO()
    override suspend fun deleteDevice(deviceId: String) = TODO()
    override suspend fun registerApnsToken(req: dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest) = TODO()
    override suspend fun gateways() = TODO()
    override suspend fun conversations(limit: Int?, cursor: String?) = ConversationsPage(conversations = emptyList())
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
    override suspend fun events(cursor: String, limit: Int?) =
        EventsPage(events = emptyList(), nextCursor = cursor, hasMore = false, currentWatermark = cursor)
}

private class StubAuth : TokenManager {
    override suspend fun login(username: String, password: String, mfaCode: String?) = TODO()
    override suspend fun registerCurrentDevice(displayName: String, platform: String, model: String?, appVersion: String?) = TODO()
    override suspend fun restoreFromStoredRefreshToken() = false
    override suspend fun <T> withBearer(block: suspend (String) -> T): T = block("flow3-test-token")
    override fun currentAccessToken(): String? = null
    override suspend fun clearCredentials() {}
}
