package dev.sharpxia.agentreins.openclaw

actual fun gatewayPlatform(): String = "android"

actual fun gatewayDeviceFamily(): String = "Android"

actual fun gatewayClientId(): String = "openclaw-android"

// Android 为编译目标（v1.1 出包）：无 Application context 的库内以 HOME 环境变量兜底，
// 出包时改为 Keystore + context.filesDir（技术债登记于 DEVELOPMENT_PLAN）。
private val dataDir: java.io.File
    get() = java.io.File(System.getenv("HOME") ?: "/data/local/tmp", ".agentreins")

private val seedFile: java.io.File get() = java.io.File(dataDir, "device-seed.bin")
private val connectionFile: java.io.File get() = java.io.File(dataDir, "connection.json")

actual fun readDeviceSeed(): ByteArray? = seedFile.takeIf { it.exists() }?.readBytes()

actual fun writeDeviceSeed(seed: ByteArray) {
    seedFile.parentFile?.mkdirs()
    seedFile.writeBytes(seed)
}

actual fun readStoredConnectionJson(): String? = connectionFile.takeIf { it.exists() }?.readText()

actual fun writeStoredConnectionJson(json: String) {
    connectionFile.parentFile?.mkdirs()
    connectionFile.writeText(json)
}

actual fun epochMs(): Long = System.currentTimeMillis()
