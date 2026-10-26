package dev.sharpxia.agentreins.openclaw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * `openclaw qr` 生成的移动端配对 setup payload（docs/protocol-notes.md §3.5）：
 * QR 原文是 `oc-pair://<base64url(json)>`，json 字段 url / bootstrapToken 必需，
 * expiresAtMs 可选（短时效一次性凭证，非共享网关 token），
 * urls 可选（备用地址数组，docs/remote-access-design.md §6：到家/外出自动切换）。
 */
data class PairingSetup(
    val url: String,
    val bootstrapToken: String,
    val expiresAtMs: Long?,
    /** 备用网关地址（按序 failover 候选，已过滤非法条目/主地址/重复项）。 */
    val urls: List<String> = emptyList(),
)

/**
 * 解析扫码/粘贴得到的 setup code。与网关 decodePairingSetupCode 对齐：
 * `oc-pair://` 前缀可省略；base64url 字符集校验失败或 JSON 缺字段均视为无效。
 */
fun parsePairingSetupCode(input: String): PairingSetup {
    val trimmed = input.trim().trimEnd('=')
    val code = if (trimmed.length >= 10 && trimmed.take(10).lowercase() == "oc-pair://") {
        trimmed.substring(10)
    } else {
        trimmed
    }
    if (code.isEmpty() || !code.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
        throw IllegalArgumentException("setup code 不是合法的 oc-pair 编码")
    }
    val json = code.decodeBase64Url().decodeToString()
    val obj = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (_: Exception) {
        throw IllegalArgumentException("setup code 解码后不是合法 JSON")
    }
    val url = obj["url"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("setup code 缺少网关地址")
    val token = obj["bootstrapToken"]?.jsonPrimitive?.contentOrNull
        ?: throw IllegalArgumentException("setup code 缺少 bootstrapToken")
    if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
        throw IllegalArgumentException("setup code 的网关地址必须是 ws:// 或 wss://：$url")
    }
    // urls 可选：只收合法 ws/wss 条目，剔除主地址与重复项，保序（探测顺序即优先级）
    val urls = (obj["urls"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.filter { it.startsWith("ws://") || it.startsWith("wss://") }
        ?.filter { it != url }
        ?.distinct()
        ?: emptyList()
    return PairingSetup(
        url = url,
        bootstrapToken = token,
        expiresAtMs = obj["expiresAtMs"]?.jsonPrimitive?.longOrNull,
        urls = urls,
    )
}
