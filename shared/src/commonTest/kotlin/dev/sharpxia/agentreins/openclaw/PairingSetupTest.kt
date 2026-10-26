package dev.sharpxia.agentreins.openclaw

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PairingSetupTest {

    /** 对齐网关 encodePairingSetupCode：无 padding 的 base64url。 */
    private fun encode(payload: String): String = payload.encodeToByteArray().base64Url().trimEnd('=')

    @Test
    fun parsesOcPairUrlWithAllFields() {
        val payload = buildJsonObject {
            put("url", "ws://192.0.2.66:18789")
            put("bootstrapToken", "bt-abc123")
            put("expiresAtMs", 1760000000000)
        }.toString()
        val setup = parsePairingSetupCode("oc-pair://" + encode(payload))
        assertEquals("ws://192.0.2.66:18789", setup.url)
        assertEquals("bt-abc123", setup.bootstrapToken)
        assertEquals(1760000000000L, setup.expiresAtMs)
    }

    @Test
    fun parsesBackupUrlsAndFiltersInvalidEntries() {
        // 远程接入场景（remote-access-design.md §6）：LAN 主地址 + wss 备用
        val payload = buildJsonObject {
            put("url", "ws://192.0.2.66:18789")
            put("bootstrapToken", "tok")
            put(
                "urls",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive("wss://gw.example.com:443"),
                        kotlinx.serialization.json.JsonPrimitive("ws://198.51.100.7:18789"), // Tailscale 备份
                        kotlinx.serialization.json.JsonPrimitive("https://evil.example.com"), // 非法 scheme 剔除
                        kotlinx.serialization.json.JsonPrimitive(""), // 空串剔除
                        kotlinx.serialization.json.JsonPrimitive("ws://192.0.2.66:18789"), // 与主地址重复剔除
                        kotlinx.serialization.json.JsonPrimitive("wss://gw.example.com:443"), // 重复项去重
                    ),
                ),
            )
        }.toString()
        val setup = parsePairingSetupCode(encode(payload))
        assertEquals(listOf("wss://gw.example.com:443", "ws://198.51.100.7:18789"), setup.urls)
    }

    @Test
    fun missingUrlsFieldYieldsEmptyList() {
        val payload = buildJsonObject {
            put("url", "wss://gw.example.com")
            put("bootstrapToken", "tok")
        }.toString()
        val setup = parsePairingSetupCode(encode(payload))
        assertEquals(emptyList(), setup.urls)
    }

    @Test
    fun parsesBareCodeWithoutPrefixAndOptionalFields() {
        val payload = buildJsonObject {
            put("url", "wss://gw.example.com")
            put("bootstrapToken", "tok")
        }.toString()
        val setup = parsePairingSetupCode(encode(payload))
        assertEquals("wss://gw.example.com", setup.url)
        assertEquals("tok", setup.bootstrapToken)
        assertNull(setup.expiresAtMs)
    }

    @Test
    fun roundTripsThroughBase64UrlCodec() {
        val bytes = ByteArray(64) { (it * 37 + 11).toByte() }
        assertEquals(bytes.toList(), bytes.base64Url().decodeBase64Url().toList())
    }

    @Test
    fun rejectsGarbageAndMissingFields() {
        assertFailsWith<IllegalArgumentException> { parsePairingSetupCode("not a code!") }
        assertFailsWith<IllegalArgumentException> {
            parsePairingSetupCode(encode("""{"url":"ws://x"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            parsePairingSetupCode(encode("""{"bootstrapToken":"t","url":"https://x"}"""))
        }
    }
}
