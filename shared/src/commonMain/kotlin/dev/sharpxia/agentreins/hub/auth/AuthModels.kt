package dev.sharpxia.agentreins.hub.auth

import kotlinx.serialization.Serializable

@Serializable
data class UserRef(val userId: String, val displayName: String)

/** 登录请求（口令仅入登录请求体，不落任何文件/日志；MFA 为预留位，MVP 服务端不校验）。 */
@Serializable
data class LoginRequest(val username: String, val password: String, val mfaCode: String? = null)

/** 200：accessToken 不透明字符串——仅内存持有、不持久化、不解析内容（08 §1.1/§2.1）。 */
@Serializable
data class LoginOk(
    val user: UserRef,
    val accessToken: String,
    val tokenType: String,
    val expiresIn: Long,
    val mfaRequired: Boolean,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** refreshToken 一次一用：成功即轮换、旧值立即失效；deviceId 必带——绑定语义延续，无需重跑设备注册。 */
@Serializable
data class RefreshOk(
    val deviceId: String,
    val accessToken: String,
    val expiresIn: Long,
    val refreshToken: String,
    val refreshExpiresIn: Long,
)

/** platform ∈ ios/android/desktop。 */
@Serializable
data class DeviceRegistrationRequest(
    val displayName: String,
    val platform: String,
    val model: String? = null,
    val appVersion: String? = null,
)

/** 201：refreshToken 仅此一次下发，交 CredentialStore 落安全存储。 */
@Serializable
data class DeviceRegistrationOk(val deviceId: String, val refreshToken: String, val refreshExpiresIn: Long)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val displayName: String,
    val platform: String,
    val lastSeenAt: String? = null,
    val createdAt: String? = null,
    /** 标记发起请求的设备。 */
    val current: Boolean? = null,
)

@Serializable
data class DevicesPage(val devices: List<DeviceInfo> = emptyList())
