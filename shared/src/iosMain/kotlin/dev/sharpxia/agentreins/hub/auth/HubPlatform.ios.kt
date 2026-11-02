package dev.sharpxia.agentreins.hub.auth

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.posix.memcpy

actual fun hubDevicePlatform(): String = "ios"

actual fun platformCredentialStore(): CredentialStore = KeychainCredentialStore()

/**
 * iOS 凭据存储：Keychain Generic Password（service=dev.sharpxia.agentreins.hub, account=refreshToken），
 * 可访问性 AfterFirstUnlockThisDeviceOnly（后台刷新可用、不随备份迁移）。
 * 纯 CoreFoundation 构造查询字典与数据（CFDataCreate/CFStringCreateWithCString），
 * 凭据不再以明文文件落 Documents（直连时代的 connection.json 模式已随 P4 退役）。
 */
@OptIn(ExperimentalForeignApi::class)
private class KeychainCredentialStore : CredentialStore {
    private val service = "dev.sharpxia.agentreins.hub"
    private val account = "refreshToken"

    override suspend fun saveRefreshToken(token: String) {
        // 覆盖写：先删再加，避免 errSecDuplicateItem
        delete()
        val bytes = token.encodeToByteArray()
        memScoped {
            val query = baseQuery()
            val data = bytes.usePinned { pin ->
                CFDataCreate(null, pin.addressOf(0).reinterpret(), bytes.size.convert())
            }
            if (data == null) {
                CFRelease(query)
                error("CFDataCreate failed")
            }
            CFDictionaryAddValue(query, kSecValueData, data)
            CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
            val status = SecItemAdd(query, null)
            CFRelease(data)
            CFRelease(query)
            if (status != errSecSuccess) error("Keychain SecItemAdd failed: $status")
        }
    }

    override suspend fun loadRefreshToken(): String? {
        val query = baseQuery()
        CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
        return memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            if (status != errSecSuccess) return@memScoped null
            val raw = result.value ?: return@memScoped null
            val length = CFDataGetLength(raw.reinterpret())
            if (length <= 0) return@memScoped null
            val src = CFDataGetBytePtr(raw.reinterpret()) ?: return@memScoped null
            ByteArray(length.toInt())
                .also { out -> out.usePinned { memcpy(it.addressOf(0), src, length.convert()) } }
                .decodeToString()
                .ifEmpty { null }
        }
    }

    override suspend fun clear() {
        delete()
    }

    private fun delete() {
        val query = baseQuery()
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun baseQuery(): CFMutableDictionaryRef {
        val query = CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: error("CFDictionaryCreateMutable returned null")
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        addString(query, kSecAttrService, service)
        addString(query, kSecAttrAccount, account)
        return query
    }

    /** 字典按 kCFType 回调自行 retain，我们创建出的 +1 引用在加入后即释放。 */
    private fun addString(dict: CFMutableDictionaryRef, key: COpaquePointer?, value: String) {
        val ref = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)
        CFDictionaryAddValue(dict, key, ref)
        CFRelease(ref)
    }
}
