package dev.sharpxia.agentreins.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> throw IllegalArgumentException(c.toString())
}

private fun hex(s: String): ByteArray = ByteArray(s.length / 2) {
    ((hexDigit(s[2 * it]) shl 4) + hexDigit(s[2 * it + 1])).toByte()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

class CryptoVectorTest {

    @Test
    fun sha256Vectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256("abc".toByteArray()).toHex(),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            sha256(ByteArray(0)).toHex(),
        )
    }

    @Test
    fun sha512Vector() {
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            sha512("abc".toByteArray()).toHex(),
        )
    }

    // RFC 8032 §7.1（TEST 1–3）
    @Test
    fun ed25519PublicKeyVectors() {
        assertContentEquals(
            hex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"),
            Ed25519.publicKey(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")),
        )
        assertContentEquals(
            hex("3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"),
            Ed25519.publicKey(hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")),
        )
        assertContentEquals(
            hex("fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025"),
            Ed25519.publicKey(hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")),
        )
    }

    @Test
    fun ed25519SignVectors() {
        assertContentEquals(
            hex(
                "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc" +
                    "61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Ed25519.sign(hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"), ByteArray(0)),
        )
        assertContentEquals(
            hex(
                "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e" +
                    "458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
            Ed25519.sign(hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb"), hex("72")),
        )
        assertContentEquals(
            hex(
                "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae" +
                    "67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            ),
            Ed25519.sign(hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7"), hex("af82")),
        )
    }
}
