package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.ApnsTokenRequest
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.DefaultTokenManager
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshOk
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.HubErrorPayload
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest
import dev.sharpxia.agentreins.hub.projection.CreateConversationRequest
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.RenameConversationRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * wp-17 端点 #20（POST /v1/devices/apns-token）fixture 测试：①ApnsTokenRequest 线格式
 * （请求体恰为必填 token 单字段，与契约 ApnsTokenRequest schema 一致）；②Bearer 路径——
 * 登录态（restore 静默刷新起步）下 token 经 TokenManager.withBearer 送达 REST 桩，含
 * AUTH-002 过期单飞刷新后重试一次的既有语义。DefaultHubClient.reportApnsToken 的门面即
 * 「栈查找 + withBearer 透传」（agentStatus 同款），无独立逻辑，不另测。
 */
class HubApnsTokenTest {

    // ── ① 请求体线格式（契约：object，required [token]，maxLength 512，空串 = 清除/退订） ──

    @Test
    fun requestWireShapeMatchesContract() {
        // 64 字节 = 128 hex 字符（契约 token 的实际形态）
        val hex = "3f".repeat(64)
        assertEquals("""{"token":"$hex"}""", HubJson.encodeToString(ApnsTokenRequest.serializer(), ApnsTokenRequest(hex)))
        // 空串照发（客户端不自行拦截：清除语义由服务端裁决）
        assertEquals("""{"token":""}""", HubJson.encodeToString(ApnsTokenRequest.serializer(), ApnsTokenRequest("")))
    }

    // ── ② Bearer 路径：restore 后 token 经 withBearer 送达端点（DefaultTokenManager 真实现） ──

    @Test
    fun tokenReachesEndpointThroughWithBearer() = runTest {
        val api = RecordingApi()
        val tm = DefaultTokenManager(api, MemStore())

        // 启动恢复：静默刷新起步 → accessToken 就绪（08 §2.2），与真机冷启动同路径
        assertTrue(tm.restoreFromStoredRefreshToken())

        tm.withBearer { api.registerApnsToken(ApnsTokenRequest("3f".repeat(64))) }
        assertEquals("3f".repeat(64), api.lastApnsToken)
        assertEquals(1, api.refreshCalls) // 仅恢复耗 1 次刷新，上报本身不再触发

        // 撞 AUTH-002（token 过期）：withBearer 单飞刷新后重试原请求一次（08 §7），上报不丢。
        // （block 参数 = 刷新后的 accessToken，只作 stale 判定；上报体恒为设备 token 本身）
        api.lastApnsToken = null
        val stale = tm.currentAccessToken()!!
        tm.withBearer { token ->
            if (token == stale) throw HubException(HubErrorPayload(code = "HUB-AUTH-002", message = "expired"), 401, "req-test")
            api.registerApnsToken(ApnsTokenRequest("3f".repeat(64)))
        }
        assertEquals("3f".repeat(64), api.lastApnsToken) // 刷新后原请求重试一次，token 上报不丢
        assertEquals(2, api.refreshCalls)                // 恰一次单飞刷新（无并发重复）
    }
}

private class MemStore : CredentialStore {
    private var token: String = "refresh#1"
    override suspend fun saveRefreshToken(token: String) { this.token = token }
    override suspend fun loadRefreshToken(): String? = token
    override suspend fun clear() { token = "" }
}

/** 记录 apns-token 上报的 REST 桩：refresh 可编排（与 TokenManagerSingleFlightTest 同款占位 token）。 */
private class RecordingApi : HubApi {
    var refreshCalls = 0
    var lastApnsToken: String? = null

    override suspend fun refresh(req: RefreshRequest): RefreshOk {
        refreshCalls++
        return RefreshOk(
            deviceId = "dev-1",
            accessToken = "refresh#$refreshCalls",
            expiresIn = 7200,
            refreshToken = "refresh#rotated-$refreshCalls",
            refreshExpiresIn = 2592000,
        )
    }

    override suspend fun registerApnsToken(req: ApnsTokenRequest) {
        lastApnsToken = req.token
    }

    override suspend fun login(req: LoginRequest) = TODO()
    override suspend fun registerDevice(req: DeviceRegistrationRequest) = TODO()
    override suspend fun listDevices() = TODO()
    override suspend fun deleteDevice(deviceId: String) = TODO()
    override suspend fun gateways() = TODO()
    override suspend fun conversations(limit: Int?, cursor: String?) = TODO()
    override suspend fun messages(conversationId: String, before: Long?, limit: Int?) = TODO()
    override suspend fun sendMessage(conversationId: String, req: SendMessageRequest) = TODO()
    override suspend fun markRead(conversationId: String, req: ReadCursorRequest) = TODO()
    override suspend fun createConversation(gatewayId: String, agentId: String, req: CreateConversationRequest) = TODO()
    override suspend fun compactConversation(conversationId: String) = TODO()
    override suspend fun agentStatus(gatewayId: String, agentId: String) = TODO()
    override suspend fun renameConversation(conversationId: String, req: RenameConversationRequest) = TODO()
    override suspend fun cancelRun(runId: String, req: CancelRunRequest) = TODO()
    override suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest) = TODO()
    override suspend fun snapshot() = TODO()
    override suspend fun events(cursor: String, limit: Int?) = TODO()
}
