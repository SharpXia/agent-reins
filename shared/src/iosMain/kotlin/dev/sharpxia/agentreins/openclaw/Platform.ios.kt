package dev.sharpxia.agentreins.openclaw

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSHomeDirectory
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite

actual fun gatewayPlatform(): String = "ios"

actual fun gatewayDeviceFamily(): String = "iPhone"

actual fun gatewayClientId(): String = "openclaw-ios"

// M1 spike：Documents 文件存储（POSIX IO，规避 NSData 桥接名差异）；M3 打磨时迁 Keychain（v1.0 规格项）。
private fun seedPath(): String = NSHomeDirectory() + "/Documents/agentreins-device-seed.bin"

private fun connectionPath(): String = NSHomeDirectory() + "/Documents/agentreins-connection.json"

@OptIn(ExperimentalForeignApi::class)
private fun readBytes(path: String, expected: Int): ByteArray? {
    val f = fopen(path, "rb") ?: return null
    try {
        val out = ByteArray(expected)
        val n = out.usePinned { fread(it.addressOf(0), 1u, expected.toULong(), f) }
        return if (n > 0uL) out.copyOf(n.toInt()) else null
    } finally {
        fclose(f)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeBytes(path: String, data: ByteArray) {
    val f = fopen(path, "wb") ?: error("cannot write file at $path")
    try {
        data.usePinned { fwrite(it.addressOf(0), 1u, data.size.toULong(), f) }
    } finally {
        fclose(f)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun readDeviceSeed(): ByteArray? = readBytes(seedPath(), 32)?.takeIf { it.size == 32 }

actual fun writeDeviceSeed(seed: ByteArray) = writeBytes(seedPath(), seed)

actual fun readStoredConnectionJson(): String? = readBytes(connectionPath(), 16 * 1024)?.decodeToString()

actual fun writeStoredConnectionJson(json: String) = writeBytes(connectionPath(), json.encodeToByteArray())
