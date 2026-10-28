package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * M4 P1：conversation = 用户与某角色的线程。主线 conversation（1 agent ↔ 1 conversation）
 * 承载该 agent 全部主 session（main + ar-*）内容；渠道直连为独立线程。
 * 派生（subagent）/系统/未识别会话不产出 conversation——仅 agent 主页 sessions list 可见。
 */
enum class ConversationKind { MAINLINE, CHANNEL }

data class Conversation(
    val id: String,
    val agentId: String,
    val kind: ConversationKind,
    /** CHANNEL 线程的渠道桶（telegram…）；MAINLINE 为 null。 */
    val channel: String? = null,
    /** 成员 session key，按最近活跃降序；index 0 = 当前主 session（输入锚定目标）。 */
    val sessionKeys: List<String>,
    val preview: String? = null,
    val lastActiveAtMs: Long? = null,
    val unread: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val hidden: Boolean = false,
)

/** conversation 级本地覆盖层（pinned/archived/hidden 按 conversation id 存；kv 持久化）。 */
object ConversationOverlay {

    @Serializable
    data class Entry(
        val pinned: Boolean? = null,
        val archived: Boolean? = null,
        val hidden: Boolean? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun parse(raw: String?): Map<String, Entry> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching { json.decodeFromString<Map<String, Entry>>(raw) }.getOrDefault(emptyMap())

    fun serialize(overlay: Map<String, Entry>): String = json.encodeToString(overlay)

    /** 非 null 字段写入覆盖层（null = 不动该字段；本地意图一旦写下就一直生效）。 */
    fun toggled(
        overlay: Map<String, Entry>,
        conversationId: String,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        hidden: Boolean? = null,
    ): Map<String, Entry> {
        require(pinned != null || archived != null || hidden != null) {
            "toggled: at least one field must be non-null"
        }
        val current = overlay[conversationId] ?: Entry()
        val next = current.copy(
            pinned = pinned ?: current.pinned,
            archived = archived ?: current.archived,
            hidden = hidden ?: current.hidden,
        )
        return overlay + (conversationId to next)
    }
}

/**
 * sessions.list → conversations 派生（纯函数）。会话级 overlay（unread/readMark）已在
 * AppModel.publishSessions 叠加后传入；conversation 级 overlay 在此叠加。
 */
object ConversationRepository {

    fun convId(agentId: String, channel: String? = null): String =
        if (channel == null) "conv:$agentId" else "conv:$agentId:$channel"

    /** 某 agent 的全部主线 session key（最近活跃降序；index 0 = 输入锚定目标）。 */
    fun mainlineKeysOf(agentId: String, sessions: List<Session>): List<String> =
        sessions.filter {
            (it.agentId ?: sessionKeyAgentId(it.id)) == agentId &&
                SessionClassifier.classify(it.id) == SessionCategory.MAINLINE
        }.sortedByDescending { it.lastActiveAtMs ?: Long.MIN_VALUE }.map { it.id }

    fun build(
        sessions: List<Session>,
        overlay: Map<String, ConversationOverlay.Entry> = emptyMap(),
    ): List<Conversation> {
        val byAgent = sessions.groupBy { it.agentId ?: sessionKeyAgentId(it.id) ?: return@groupBy null }
        val convs = mutableListOf<Conversation>()
        for ((agentId, list) in byAgent) {
            if (agentId == null) continue
            val mainline = list.filter { SessionClassifier.classify(it.id) == SessionCategory.MAINLINE }
            if (mainline.isNotEmpty()) {
                convs += assemble(agentId, mainline, ConversationKind.MAINLINE, channel = null)
            }
            list.filter { SessionClassifier.classify(it.id) == SessionCategory.CHANNEL }
                .groupBy { SessionClassifier.channelBucketOf(it.id) ?: "channel" }
                .forEach { (bucket, rows) ->
                    convs += assemble(agentId, rows, ConversationKind.CHANNEL, channel = bucket)
                }
        }
        return convs
            .map { c ->
                overlay[c.id]?.let { e ->
                    c.copy(
                        pinned = e.pinned ?: c.pinned,
                        archived = e.archived ?: c.archived,
                        hidden = e.hidden ?: c.hidden,
                    )
                } ?: c
            }
            .sortedWith(
                compareByDescending<Conversation> { it.pinned }
                    .thenByDescending { it.lastActiveAtMs ?: Long.MIN_VALUE },
            )
    }

    private fun assemble(
        agentId: String,
        rows: List<Session>,
        kind: ConversationKind,
        channel: String?,
    ): Conversation {
        val sorted = rows.sortedByDescending { it.lastActiveAtMs ?: Long.MIN_VALUE }
        val latest = sorted.first()
        return Conversation(
            id = convId(agentId, channel),
            agentId = agentId,
            kind = kind,
            channel = channel,
            sessionKeys = sorted.map { it.id },
            preview = latest.preview?.takeIf { it.isNotBlank() } ?: latest.title,
            lastActiveAtMs = sorted.mapNotNull { it.lastActiveAtMs }.maxOrNull(),
            unread = sorted.sumOf { it.unread },
        )
    }
}
