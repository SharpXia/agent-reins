package dev.sharpxia.agentreins.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA512
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

// 空输入坑：对空数组 addressOf(0) 在 K/N 下抛 ArrayIndexOutOfBoundsException（越界检查），
// 用 1 字节占位数组保指针有效、len 仍传 0（读不到任何字节，结果与空输入等价）。
// 2026-11-01 worktree 首次跑通 iOS 模拟器测试时实证（main 从未跑过 iOS test，desktopTest 走 JVM 不受影响）。
private inline fun <T> withInput(data: ByteArray, block: (UByteArray) -> T): T =
    (if (data.isEmpty()) ubyteArrayOf(0u) else data.asUByteArray()).let(block)

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray = ByteArray(32).also { out ->
    withInput(data) { input ->
        input.usePinned { inPin ->
            out.asUByteArray().usePinned { outPin ->
                CC_SHA256(inPin.addressOf(0), data.size.toUInt(), outPin.addressOf(0))
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha512(data: ByteArray): ByteArray = ByteArray(64).also { out ->
    withInput(data) { input ->
        input.usePinned { inPin ->
            out.asUByteArray().usePinned { outPin ->
                CC_SHA512(inPin.addressOf(0), data.size.toUInt(), outPin.addressOf(0))
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(maxOf(count, 1)).also { buf ->
    buf.asUByteArray().usePinned { pin ->
        SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pin.addressOf(0))
    }
}
