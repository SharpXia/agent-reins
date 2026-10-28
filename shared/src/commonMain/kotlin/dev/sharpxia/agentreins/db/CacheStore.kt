package dev.sharpxia.agentreins.db

import dev.sharpxia.agentreins.model.ChatEntry
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.ThinkingEntry
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.openclaw.Outbox
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 本地缓存接缝（M2.5 数据基座，A1 单库简化）。缓存是加速层不是真相源——
 * chat.history / sessions.list 始终权威，实现必须容忍整库丢失（返回空即降级）。
 *
 * 全部方法同步（SQLite 本地、行数量级小），调用方决定调度；存储不可用时由
 * [createAppCacheStore] 返回 [NoopCacheStore]，行为退化为无缓存（A14 原则：不可用即禁用）。
 */
interface CacheStore {
    /** 存储是否真实可用（false = Noop 降级，UI 不显示缓存来源提示）。 */
    val available: Boolean

    // -- 会话列表缓存（断网冷启动可见上次列表） --
    fun loadSessions(gatewayId: String): List<Session>
    fun saveSessions(gatewayId: String, sessions: List<Session>)

    // -- 消息缓存（重启秒开，后台刷新覆盖） --
    fun loadMessages(gatewayId: String, sessionKey: String): List<ChatEntry>
    fun saveMessages(gatewayId: String, sessionKey: String, entries: List<ChatEntry>)

    // -- 发送队列 outbox（断线排队、重连补发） --
    fun loadOutbox(gatewayId: String): List<Outbox.Command>
    fun upsertOutbox(command: Outbox.Command, gatewayId: String)
    fun deleteOutbox(idempotencyKey: String, gatewayId: String)

    // -- 通用 KV（草稿、分页游标、lastSeq 等） --
    fun kvGet(gatewayId: String, key: String): String?
    fun kvPut(gatewayId: String, key: String, value: String)
}

/** 存储不可用时的空实现：全部读空、写丢弃。 */
object NoopCacheStore : CacheStore {
    override val available: Boolean = false
    override fun loadSessions(gatewayId: String): List<Session> = emptyList()
    override fun saveSessions(gatewayId: String, sessions: List<Session>) = Unit
    override fun loadMessages(gatewayId: String, sessionKey: String): List<ChatEntry> = emptyList()
    override fun saveMessages(gatewayId: String, sessionKey: String, entries: List<ChatEntry>) = Unit
    override fun loadOutbox(gatewayId: String): List<Outbox.Command> = emptyList()
    override fun upsertOutbox(command: Outbox.Command, gatewayId: String) = Unit
    override fun deleteOutbox(idempotencyKey: String, gatewayId: String) = Unit
    override fun kvGet(gatewayId: String, key: String): String? = null
    override fun kvPut(gatewayId: String, key: String, value: String) = Unit
}

/** 缓存条目 JSON 编解码（A3：存解析后的富对象，与内存模型同构）。 */
internal object EntryCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val TYPE_MESSAGE = "message"
    private const val TYPE_TOOL = "tool_call"
    private const val TYPE_THINKING = "thinking"

    fun encode(entry: ChatEntry): String = when (entry) {
        is Message -> buildJsonObject {
            put("type", TYPE_MESSAGE)
            put("entry", json.encodeToString(Message.serializer(), entry))
        }
        is ToolCallEvent -> buildJsonObject {
            put("type", TYPE_TOOL)
            put("entry", json.encodeToString(ToolCallEvent.serializer(), entry))
        }
        // 瞬态条目不落缓存（transcript 无 thinking，无可回放语义）；decode 无此型即丢弃
        is ThinkingEntry -> buildJsonObject { put("type", TYPE_THINKING) }
    }.toString()

    fun decode(payload: String): ChatEntry? = runCatching {
        val obj = Json.parseToJsonElement(payload).jsonObject
        val raw = obj["entry"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        when (obj.str("type")) {
            TYPE_MESSAGE -> json.decodeFromString(Message.serializer(), raw)
            TYPE_TOOL -> json.decodeFromString(ToolCallEvent.serializer(), raw)
            else -> null
        }
    }.getOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
}
