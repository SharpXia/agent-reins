package dev.sharpxia.agentreins.openclaw

actual fun gatewayPlatform(): String = "macos"

actual fun gatewayDeviceFamily(): String = "Mac"

actual fun gatewayClientId(): String = "openclaw-macos"

private val dataDir: java.io.File
    get() = java.io.File(System.getProperty("user.home"), ".agentreins")

private val seedFile: java.io.File get() = java.io.File(dataDir, "device-seed.bin")
private val connectionFile: java.io.File get() = java.io.File(dataDir, "connection.json")

actual fun readDeviceSeed(): ByteArray? = seedFile.takeIf { it.exists() }?.readBytes()

actual fun writeDeviceSeed(seed: ByteArray) {
    seedFile.parentFile?.mkdirs()
    seedFile.writeBytes(seed)
    seedFile.setReadable(false, false)
    seedFile.setReadable(true, true)
}

actual fun readStoredConnectionJson(): String? = connectionFile.takeIf { it.exists() }?.readText()

actual fun writeStoredConnectionJson(json: String) {
    connectionFile.parentFile?.mkdirs()
    connectionFile.writeText(json)
    connectionFile.setReadable(false, false)
    connectionFile.setReadable(true, true)
}

actual fun epochMs(): Long = System.currentTimeMillis()
