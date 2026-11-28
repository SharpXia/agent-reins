package dev.sharpxia.agentreins.hub.auth

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
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
import platform.posix.uname
import platform.posix.utsname

/**
 * platform 契约值（v1.5.1 枚举增 mac-catalyst）：Catalyst 与 iOS 共用同一 K/N 二进制
 * （Xcode 侧 stamp 改写平台面），编译期分不出运行面——运行时以 uname machine 判：
 * Mac 硬件机型（MacBookPro18,3 等）= mac-catalyst，iPhone 系/iPad 系 = ios，
 * 裸架构（arm64/x86_64，模拟器）= ios。iOS 16+ 隐私门下 UIDevice.name 恒回通用名
 * （真机=iPhone、Catalyst=iPad），platform/displayName 两面协同才可辨 Mac。
 */
@OptIn(ExperimentalForeignApi::class)
private fun unameMachine(): String? = memScoped {
    val uts = alloc<utsname>()
    if (uname(uts.ptr) != 0) return@memScoped null
    uts.machine.toKString().takeIf { it.isNotEmpty() }
}

actual fun hubDevicePlatform(): String =
    if (unameMachine()?.startsWith("Mac") == true) "mac-catalyst" else "ios"

actual fun hubDeviceModel(): String? =
    unameMachine()?.takeUnless { it == "arm64" || it == "x86_64" } // 模拟器裸架构不报机型

actual fun platformCredentialStore(): CredentialStore = KeychainCredentialStore()

/**
 * iOS 凭据存储：Keychain Generic Password（service=dev.sharpxia.agentreins.hub, account=
 * refreshToken / installId），可访问性 AfterFirstUnlockThisDeviceOnly（后台刷新可用、不随
 * 备份迁移）。纯 CoreFoundation 构造查询字典与数据（CFDataCreate/CFStringCreateWithCString），
 * 凭据不再以明文文件落 Documents（直连时代的 connection.json 模式已随 P4 退役）。
 *
 * v1.6.0 安装标识（2026-11-28）：独立 keychain 项 account=installId，与 refreshToken 同
 * service 不同项——[clear]（登出）只删凭据项，安装标识跨登出/重登/升级存活；ThisDeviceOnly
 * 可访问性使它不随备份迁移到新机（新机 = 新安装 = 新设备行，符合「真实设备区分」）。
 */
@OptIn(ExperimentalForeignApi::class)
private class KeychainCredentialStore : CredentialStore {
    private val service = "dev.sharpxia.agentreins.hub"

    override suspend fun saveRefreshToken(token: String) = save(ACCOUNT_REFRESH_TOKEN, token)

    override suspend fun loadRefreshToken(): String? = load(ACCOUNT_REFRESH_TOKEN)

    override suspend fun loadInstallId(): String? = load(ACCOUNT_INSTALL_ID)

    override suspend fun saveInstallId(id: String) = save(ACCOUNT_INSTALL_ID, id)

    override suspend fun clear() {
        // 登出只清凭据，不清安装标识（同安装重登复用同一服务端设备行）。
        delete(ACCOUNT_REFRESH_TOKEN)
    }

    private suspend fun save(account: String, value: String) {
        // 覆盖写：先删再加，避免 errSecDuplicateItem
        delete(account)
        val bytes = value.encodeToByteArray()
        memScoped {
            val query = baseQuery(account)
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

    private fun load(account: String): String? {
        val query = baseQuery(account)
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

    private fun delete(account: String) {
        val query = baseQuery(account)
        SecItemDelete(query)
        CFRelease(query)
    }

    private fun baseQuery(account: String): CFMutableDictionaryRef {
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

    private companion object {
        const val ACCOUNT_REFRESH_TOKEN = "refreshToken"
        const val ACCOUNT_INSTALL_ID = "installId"
    }

    /** 字典按 kCFType 回调自行 retain，我们创建出的 +1 引用在加入后即释放。 */
    private fun addString(dict: CFMutableDictionaryRef, key: COpaquePointer?, value: String) {
        val ref = CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)
        CFDictionaryAddValue(dict, key, ref)
        CFRelease(ref)
    }
}
