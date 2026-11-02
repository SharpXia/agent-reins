package dev.sharpxia.agentreins.hub.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun hubDevicePlatform(): String = "android"

actual fun platformCredentialStore(): CredentialStore = KeystoreCredentialStore()

/**
 * Android 凭据存储：AES-256-GCM 密钥存 AndroidKeyStore（不可导出），密文落应用私有目录文件。
 * 目录取 HOME 兜底（无 Context 注入的技术债模式；v1.1 换 context.filesDir 时一并迁移路径）。
 */
private class KeystoreCredentialStore : CredentialStore {
    private val alias = "agentreins.hub.refresh"
    private val dataDir: File
        get() = File(System.getenv("HOME") ?: "/data/local/tmp", ".agentreins").apply { mkdirs() }
    private val file: File get() = File(dataDir, "hub-refresh-token.bin")

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override suspend fun saveRefreshToken(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(token.encodeToByteArray())
        file.writeBytes(ByteArray(1) { iv.size.toByte() } + iv + ct)
    }

    override suspend fun loadRefreshToken(): String? {
        val bytes = file.takeIf { it.exists() }?.readBytes() ?: return null
        if (bytes.size < 2) return null
        val ivLen = bytes[0].toInt() and 0xFF
        if (bytes.size < 1 + ivLen) return null
        val iv = bytes.copyOfRange(1, 1 + ivLen)
        val ct = bytes.copyOfRange(1 + ivLen, bytes.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(ct).decodeToString()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun clear() {
        file.delete()
    }
}
