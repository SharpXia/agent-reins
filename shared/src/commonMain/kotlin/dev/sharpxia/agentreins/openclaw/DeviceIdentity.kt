package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.crypto.Ed25519
import dev.sharpxia.agentreins.crypto.secureRandomBytes
import dev.sharpxia.agentreins.crypto.sha256

/**
 * 网关设备身份（device pairing，docs/protocol-notes.md §3.4）：
 * Ed25519 seed 持久化于平台存储；deviceId = sha256(publicKeyRaw) 的 hex（服务器强制派生规则）。
 */
class DeviceIdentity private constructor(
    val seed: ByteArray,
    val publicKeyRaw: ByteArray,
) {
    val deviceIdHex: String = sha256(publicKeyRaw).hexString()

    fun sign(payload: ByteArray): ByteArray = Ed25519.sign(seed, payload)

    companion object {
        fun loadOrCreate(): DeviceIdentity {
            val existing = readDeviceSeed()
            if (existing != null && existing.size == 32) {
                return DeviceIdentity(existing, Ed25519.publicKey(existing))
            }
            val seed = secureRandomBytes(32)
            writeDeviceSeed(seed)
            return DeviceIdentity(seed, Ed25519.publicKey(seed))
        }
    }
}

private const val HEX = "0123456789abcdef"

internal fun ByteArray.hexString(): String = buildString {
    for (b in this@hexString) {
        val v = b.toInt() and 0xff
        append(HEX[v ushr 4])
        append(HEX[v and 15])
    }
}

private const val B64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

/** 标准带 padding 的 base64url（网关 device.publicKey / device.signature 的线上格式）。 */
internal fun ByteArray.base64Url(): String = buildString {
    var i = 0
    while (i < size) {
        val b0 = this@base64Url[i].toInt() and 0xff
        val b1 = if (i + 1 < size) this@base64Url[i + 1].toInt() and 0xff else -1
        val b2 = if (i + 2 < size) this@base64Url[i + 2].toInt() and 0xff else -1
        append(B64URL[b0 ushr 2])
        append(B64URL[(b0 and 3) shl 4 or (if (b1 >= 0) b1 ushr 4 else 0)])
        if (b1 < 0) { append("=="); break }
        append(B64URL[(b1 and 0xf) shl 2 or (if (b2 >= 0) b2 ushr 6 else 0)])
        if (b2 < 0) { append('='); break }
        append(B64URL[b2 and 0x3f])
        i += 3
    }
}

/** base64url 解码（容忍缺失 padding），用于 setup code / 未来同类 oc- 编码字段。 */
internal fun String.decodeBase64Url(): ByteArray {
    val cleaned = trimEnd('=')
    val out = ByteArray((cleaned.length * 3) / 4 + 3)
    var buf = 0
    var bits = 0
    var len = 0
    for (c in cleaned) {
        val v = when (c) {
            in 'A'..'Z' -> c - 'A'
            in 'a'..'z' -> c - 'a' + 26
            in '0'..'9' -> c - '0' + 52
            '-' -> 62
            '_' -> 63
            else -> throw IllegalArgumentException("非法 base64url 字符: $c")
        }
        buf = ((buf shl 6) or v) and 0xFFFFF
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[len++] = (buf shr bits).toByte()
        }
    }
    return out.copyOf(len)
}
