package dev.sharpxia.agentreins.hub.auth

import dev.sharpxia.agentreins.hub.DefaultHubApi
import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.HubConfig

/**
 * P1 组装件：REST 客户端 + 认证。sync / commands / projection 随 P2/P3 进 HubClient 门面。
 * [credentialStore] 默认平台安全存储；测试可注入内存实现。
 */
class HubAuthStack(
    config: HubConfig,
    credentialStore: CredentialStore = platformCredentialStore(),
    onDeviceRevoked: suspend () -> Unit = {},
) {
    val auth: DefaultTokenManager
    val api: HubApi

    init {
        // 鉴权 Token 由 TokenManager 现取，HubApi 自身不持有凭据
        lateinit var manager: DefaultTokenManager
        val restApi = DefaultHubApi(config, accessTokenProvider = { manager.currentAccessToken() })
        manager = DefaultTokenManager(restApi, credentialStore, onDeviceRevoked)
        auth = manager
        api = restApi
    }
}
