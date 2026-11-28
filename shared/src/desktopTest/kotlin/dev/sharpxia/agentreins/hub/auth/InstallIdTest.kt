package dev.sharpxia.agentreins.hub.auth

import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.HubJson
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.CreateGatewayRequest
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse
import dev.sharpxia.agentreins.hub.projection.GatewaysPage
import dev.sharpxia.agentreins.hub.projection.MediaUpload
import dev.sharpxia.agentreins.hub.projection.MessagesPage
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v1.6.0 设备身份（2026-11-28）客户端侧回归：安装标识生成/持久/登出不清 + 注册请求透传 +
 * 线上形状（:shared:desktopTest 真门禁）。服务端幂等语义由 AgentReinService authz 测试承载。
 *
 * 所有 token 值均为测试桩占位串，不含任何真实凭据。
 */
class InstallIdTest {

    /** 契约形状 `[A-Za-z0-9_-]{16,64}`。 */
    private val shape = Regex("^[A-Za-z0-9_-]{16,64}$")

    /** 持久化替身（模拟三平台实现的落盘语义：installId 独立于凭据、clear 不清）。 */
    private class PersistentStore : CredentialStore {
        var refresh: String? = null
        var install: String? = null
        var installWrites = 0

        override suspend fun saveRefreshToken(token: String) {
            refresh = token
        }

        override suspend fun loadRefreshToken(): String? = refresh

        override suspend fun clear() {
            refresh = null // 登出只清凭据
        }

        override suspend fun loadInstallId(): String? = install

        override suspend fun saveInstallId(id: String) {
            installWrites++
            install = id
        }
    }

    /** 只实现 login/registerDevice；其余端点不在本测试路径。 */
    private class FakeApi : HubApi {
        val registrations = mutableListOf<DeviceRegistrationRequest>()

        override suspend fun login(req: LoginRequest): LoginOk =
            LoginOk(UserRef("user-1", "u"), "access#1", "Bearer", 7200, false)

        override suspend fun registerDevice(req: DeviceRegistrationRequest): DeviceRegistrationOk {
            registrations += req
            return DeviceRegistrationOk("device-${registrations.size}", "refresh#${registrations.size}", 2592000)
        }

        override suspend fun refresh(req: RefreshRequest): RefreshOk = unused()
        override suspend fun listDevices(): DevicesPage = unused()
        override suspend fun deleteDevice(deviceId: String) = unused()
        override suspend fun registerApnsToken(req: ApnsTokenRequest) = unused()
        override suspend fun gateways(): GatewaysPage = unused()
        override suspend fun conversations(limit: Int?, cursor: String?): ConversationsPage = unused()
        override suspend fun messages(conversationId: String, before: Long?, limit: Int?): MessagesPage = unused()
        override suspend fun sendMessage(conversationId: String, req: dev.sharpxia.agentreins.hub.projection.SendMessageRequest): CommandReceipt = unused()
        override suspend fun uploadMedia(conversationId: String, bytes: ByteArray, mime: String, fileName: String?, width: Int?, height: Int?): MediaUpload = unused()
        override suspend fun fetchMediaContent(mediaId: String): ByteArray = unused()
        override suspend fun commands(conversationId: String) = unused()
        override suspend fun models(conversationId: String) = unused()
        override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest): CommandReceipt = unused()
        override suspend fun compactConversation(conversationId: String): CommandReceipt = unused()
        override suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse = unused()
        override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest): CommandReceipt = unused()
        override suspend fun markRead(conversationId: String, req: ReadCursorRequest): CommandReceipt = unused()
        override suspend fun cancelRun(runId: String, req: CancelRunRequest): CommandReceipt = unused()
        override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest): CommandReceipt = unused()
        override suspend fun snapshot(): SnapshotResponse = unused()
        override suspend fun createGateway(req: CreateGatewayRequest): GatewayCreatedResponse = unused()
        override suspend fun rotateBridgeToken(gatewayId: String): BridgeTokenIssuedResponse = unused()
        override suspend fun deleteGateway(gatewayId: String) = unused()
        override suspend fun events(cursor: String, limit: Int?): EventsPage = unused()

        private fun unused(): Nothing = error("not used in this test")
    }

    @Test
    fun generatedInstallIdMatchesContractShape() = runBlocking {
        val a = generateInstallId()
        val b = generateInstallId()
        assertTrue(shape.matches(a), "installId 须合契约形状: $a")
        assertEquals(32, a.length) // 128 位随机 hex
        assertNotEquals(a, b, "两次生成不得相同")
        // 持久语义：首次生成即写、再读恒等且不再写
        val store = PersistentStore()
        val first = store.installId()
        assertEquals(1, store.installWrites)
        assertEquals(first, store.installId())
        assertEquals(1, store.installWrites)
    }

    @Test
    fun installIdSurvivesLogoutAndIsStableAcrossRegistrations() = runBlocking {
        val api = FakeApi()
        val store = PersistentStore()
        val tm = DefaultTokenManager(api, store)

        tm.login("u", "pw", null)
        tm.registerCurrentDevice("iPhone", "ios", "iPhone17,1", null)
        val install = api.registrations.single().installId
        assertTrue(install != null && shape.matches(install), "注册请求须携带合形 installId: $install")

        // 登出（撤销设备 + 清凭据）后重登：安装标识不变——服务端据此复用同一设备行。
        tm.clearCredentials()
        assertNull(store.refresh, "登出须清凭据")
        assertEquals(install, store.install, "登出不得清安装标识")
        tm.login("u", "pw", null)
        tm.registerCurrentDevice("iPhone", "ios", "iPhone17,1", "1.1.0")
        assertEquals(install, api.registrations.last().installId, "同安装再次注册须报同一 installId")
        assertEquals(1, store.installWrites)
    }

    @Test
    fun defaultStoreWithoutPersistenceStillSendsWellFormedId() = runBlocking {
        // 缺省实现（测试替身/内存实现）不持久：仍现生成合形标识，只是跨进程不保证复用。
        val store = object : CredentialStore {
            var refresh: String? = null
            override suspend fun saveRefreshToken(token: String) { refresh = token }
            override suspend fun loadRefreshToken(): String? = refresh
            override suspend fun clear() { refresh = null }
        }
        val api = FakeApi()
        val tm = DefaultTokenManager(api, store)
        tm.login("u", "pw", null)
        tm.registerCurrentDevice("d", "desktop", null, null)
        val id = api.registrations.single().installId
        assertTrue(id != null && shape.matches(id), "缺省实现也须报合形 installId: $id")
    }

    @Test
    fun wireShapeOmitsNullAndEncodesPresentInstallId() {
        // 旧客户端语义：不携带 = 字段不出现在线上体（服务端按未携带处理=恒新建）。
        val legacy = HubJson.encodeToString(DeviceRegistrationRequest.serializer(), DeviceRegistrationRequest("d", "ios"))
        assertTrue(!legacy.contains("installId"), "null installId 不得上线: $legacy")
        val withId = HubJson.encodeToString(
            DeviceRegistrationRequest.serializer(),
            DeviceRegistrationRequest("d", "mac-catalyst", installId = "install-aaaaaaaaaaaaaaaa"),
        )
        assertTrue(withId.contains("\"installId\":\"install-aaaaaaaaaaaaaaaa\""), withId)
        // 解码兼容：无字段体可解（explicitNulls=false）。
        assertNull(HubJson.decodeFromString(DeviceRegistrationRequest.serializer(), legacy).installId)
    }
}
