package dev.sharpxia.agentreins.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA512
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

@OptIn(ExperimentalForeignApi::class)
actual fun sha256(data: ByteArray): ByteArray = ByteArray(32).also { out ->
    data.asUByteArray().usePinned { inPin ->
        out.asUByteArray().usePinned { outPin ->
            CC_SHA256(inPin.addressOf(0), data.size.toUInt(), outPin.addressOf(0))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha512(data: ByteArray): ByteArray = ByteArray(64).also { out ->
    data.asUByteArray().usePinned { inPin ->
        out.asUByteArray().usePinned { outPin ->
            CC_SHA512(inPin.addressOf(0), data.size.toUInt(), outPin.addressOf(0))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count).also { buf ->
    buf.asUByteArray().usePinned { pin ->
        SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pin.addressOf(0))
    }
}
