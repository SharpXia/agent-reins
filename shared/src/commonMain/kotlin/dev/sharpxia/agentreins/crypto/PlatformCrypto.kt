package dev.sharpxia.agentreins.crypto

/** 平台哈希原语（common 无 stdlib 实现，各端走系统库）。 */
expect fun sha256(data: ByteArray): ByteArray

expect fun sha512(data: ByteArray): ByteArray

/** 加密安全随机字节（设备身份 seed 生成用）。 */
expect fun secureRandomBytes(count: Int): ByteArray
