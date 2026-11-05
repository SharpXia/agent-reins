package dev.sharpxia.agentreins.hub.auth

import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.HubErrorPayload
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.GatewaysPage
import dev.sharpxia.agentreins.hub.projection.MessagesPage
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 静默刷新单飞断言（交接包 §5.1：并发 401 → 单次 refresh → 全部请求重试）。
 * 所有 token 值均为测试桩按序号生成的占位串（[fakeToken]），不含任何真实凭据。
 */
class TokenManagerSingleFlightTest {

    private fun fakeToken(kind: String, n: Int): String = kind + "#" + n

    private inner class MemStore : CredentialStore {
        var value: String? = fakeToken("refresh", 0)

        override suspend fun saveRefreshToken(token: String) {
            value = token
        }

        override suspend fun loadRefreshToken(): String? = value

        override suspend fun clear() {
            value = null
        }
    }

    /** 只实现 refresh；其余端点不在本测试路径。 */
    private open inner class FakeApi : HubApi {
        var refreshCalls = 0
        var lastRefreshToken: String? = null

        override suspend fun refresh(req: RefreshRequest): RefreshOk {
            refreshCalls++
            lastRefreshToken = req.refreshToken
            return RefreshOk(
                deviceId = "dev-1",
                accessToken = fakeToken("access", refreshCalls),
                expiresIn = 7200,
                refreshToken = fakeToken("refresh", refreshCalls),
                refreshExpiresIn = 2592000,
            )
        }

        override suspend fun login(req: LoginRequest): LoginOk = unused()
        override suspend fun registerDevice(req: DeviceRegistrationRequest): DeviceRegistrationOk = unused()
        override suspend fun listDevices(): DevicesPage = unused()
        override suspend fun deleteDevice(deviceId: String) {
            unused()
        }
        override suspend fun registerApnsToken(req: dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest) {
            unused()
        }
        override suspend fun gateways(): GatewaysPage = unused()
        override suspend fun conversations(limit: Int?, cursor: String?): ConversationsPage = unused()
        override suspend fun messages(conversationId: String, before: Long?, limit: Int?): MessagesPage = unused()
        override suspend fun sendMessage(conversationId: String, req: SendMessageRequest): CommandReceipt = unused()
        override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest): CommandReceipt = unused()
        override suspend fun compactConversation(conversationId: String): CommandReceipt = unused()
        override suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse = unused()
        override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest): CommandReceipt = unused()
        override suspend fun markRead(conversationId: String, req: ReadCursorRequest): CommandReceipt = unused()
        override suspend fun cancelRun(runId: String, req: CancelRunRequest): CommandReceipt = unused()
        override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest): CommandReceipt = unused()
        override suspend fun snapshot(): SnapshotResponse = unused()
        override suspend fun events(cursor: String, limit: Int?): EventsPage = unused()

        private fun unused(): Nothing = error("not used in this test")
    }

    private fun hubError(code: String) = HubException(HubErrorPayload(code = code, message = code), 401, "req-test")

    @Test
    fun concurrentAuth002TriggersExactlyOneRefresh() {
        runBlocking {
            val api = FakeApi()
            val store = MemStore()
            val tm = DefaultTokenManager(api, store)

            // 首次恢复：refresh #1
            assertTrue(tm.restoreFromStoredRefreshToken())
            assertEquals(1, api.refreshCalls)
            val stale = tm.currentAccessToken()!!

            // 8 个并发请求都带着同一个 stale token 撞 AUTH-002
            val results = (1..8).map {
                async(Dispatchers.Default) {
                    tm.withBearer { token -> if (token == stale) throw hubError("HUB-AUTH-002") else token }
                }
            }.awaitAll()

            assertEquals(2, api.refreshCalls, "并发 401 只允许触发一次 refresh（含首次恢复共 2 次）")
            assertTrue(results.all { it == fakeToken("access", 2) }, "全部请求应以同一新 token 重试成功")
            assertEquals(fakeToken("refresh", 1), api.lastRefreshToken, "第二次 refresh 必须用第一次轮换出的新 refreshToken")
            assertEquals(fakeToken("refresh", 2), store.value, "轮换后的 refreshToken 已落安全存储")
        }
    }

    @Test
    fun auth003ClearsCredentialsAndReportsNeedLogin() {
        runBlocking {
            val api = object : FakeApi() {
                override suspend fun refresh(req: RefreshRequest): RefreshOk = throw hubError("HUB-AUTH-003")
            }
            val store = MemStore()
            val tm = DefaultTokenManager(api, store)

            assertFalse(tm.restoreFromStoredRefreshToken())
            assertNull(store.value, "AUTH-003 必须清掉安全存储里的 refreshToken")
            assertNull(tm.currentAccessToken())
            // 无凭据 → 本地前置失败，引导登录
            assertFailsWith<HubException> { tm.withBearer { it } }
        }
    }

    @Test
    fun auth004ClearsAndInvokesRevokedHook() {
        runBlocking {
            var revoked = 0
            val api = FakeApi()
            val store = MemStore()
            val tm = DefaultTokenManager(api, store, onDeviceRevoked = { revoked++ })
            assertTrue(tm.restoreFromStoredRefreshToken())

            val e = assertFailsWith<HubException> { tm.withBearer { throw hubError("HUB-AUTH-004") } }
            assertEquals("HUB-AUTH-004", e.payload.code)
            assertEquals(1, revoked)
            assertNull(store.value)
            assertNull(tm.currentAccessToken())
        }
    }
}
