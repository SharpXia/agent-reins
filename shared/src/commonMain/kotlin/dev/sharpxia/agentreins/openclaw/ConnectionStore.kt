package dev.sharpxia.agentreins.openclaw

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 最近一次成功连接的凭据快照（预填 + deviceToken 免 token 直连），JSON 落盘由平台 actual 完成。 */
data class StoredConnection(
    val url: String,
    val apiKey: String,
    val deviceToken: String?,
    /** 备用网关地址（多地址 failover，remote-access-design.md §6）；旧存量缺该键时为空。 */
    val urls: List<String> = emptyList(),
)

/** 连接配置持久化（JSON 字符串，字段 url/apiKey/deviceToken/urls）。 */
object ConnectionStore {

    fun load(): StoredConnection? {
        val json = readStoredConnectionJson() ?: return null
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return null
            val urls = runCatching {
                obj["urls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            }.getOrDefault(emptyList())
            StoredConnection(
                url = url,
                apiKey = obj["apiKey"]?.jsonPrimitive?.contentOrNull ?: "",
                deviceToken = obj["deviceToken"]?.jsonPrimitive?.contentOrNull,
                urls = urls,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun save(snapshot: StoredConnection) {
        val json = buildJsonObject {
            put("url", snapshot.url)
            put("apiKey", snapshot.apiKey)
            snapshot.deviceToken?.let { put("deviceToken", it) }
            if (snapshot.urls.isNotEmpty()) {
                put("urls", JsonArray(snapshot.urls.map { JsonPrimitive(it) }))
            }
        }.toString()
        writeStoredConnectionJson(json)
    }
}
