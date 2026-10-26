package dev.sharpxia.agentreins.openclaw

/** 客户端身份三件套 + 设备 seed 持久化（各平台 expect）。 */
expect fun gatewayPlatform(): String

expect fun gatewayDeviceFamily(): String

/** 网关 handshake 的 client.id 封闭枚举值（docs/protocol-notes.md §3.3）。 */
expect fun gatewayClientId(): String

/** 读取持久化的设备 seed（32B）；无则 null。 */
expect fun readDeviceSeed(): ByteArray?

/** 持久化设备 seed（32B）。存储介质由各平台决定（iOS: 文件, v1.0 前迁 Keychain）。 */
expect fun writeDeviceSeed(seed: ByteArray)

/** 读取持久化的最近连接配置（JSON 字符串）；无则 null。 */
expect fun readStoredConnectionJson(): String?

/** 持久化最近连接配置（JSON 字符串）。介质与 device seed 同级，M3 一并迁 Keychain/Keystore。 */
expect fun writeStoredConnectionJson(json: String)
