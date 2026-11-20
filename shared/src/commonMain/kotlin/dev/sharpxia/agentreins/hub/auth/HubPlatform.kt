package dev.sharpxia.agentreins.hub.auth

/**
 * 平台凭据存储：iOS=Keychain、Android=AndroidKeyStore AES-GCM 封装后落私有文件、Desktop=~/.agentreins 文件 chmod 600。
 * 各端只存 refreshToken（accessToken 永不落盘，08 §1.1/§2.1）。
 */
expect fun platformCredentialStore(): CredentialStore

/** 设备注册 platform 字段：契约枚举 ios / mac-catalyst / android / desktop（hub-api-v1.yaml DeviceRegistrationRequest，v1.5.1 增 mac-catalyst）。 */
expect fun hubDevicePlatform(): String

/** 设备注册 model 字段（08 §2.4）：机型硬件标识（如 iPhone15,2 / MacBookPro18,3），未上报 = null（读面 omitempty）。 */
expect fun hubDeviceModel(): String?
