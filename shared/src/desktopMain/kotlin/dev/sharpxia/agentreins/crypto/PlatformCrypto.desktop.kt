package dev.sharpxia.agentreins.crypto

import java.security.MessageDigest
import java.security.SecureRandom

actual fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

actual fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { SecureRandom().nextBytes(it) }
