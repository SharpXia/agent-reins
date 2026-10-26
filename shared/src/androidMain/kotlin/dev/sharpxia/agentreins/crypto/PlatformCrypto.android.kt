package dev.sharpxia.agentreins.crypto

import java.security.MessageDigest
import java.security.SecureRandom

// Android 编译目标（v1.1 出包）：API 33+ 有原生 Ed25519/SHA，SecureRandom 全版本可用。
actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
