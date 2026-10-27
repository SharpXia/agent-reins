package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.model.Session
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * sessions.list 行解析与列表合并（纯函数，A6/A7）。
 *
 * 协议事实（protocol-notes §4，2026.9.9 实测 542 会话单页 100）：
 * 行含 key/label/agentId/kind/channel/updatedAt/archived/pinned/unread/lastActivityAt；
 * 信封含 limitApplied/hasMore/nextOffset 分页字段。
 */
object SessionListMapper {

    /** 单页请求条数（与官方 App 对齐的保守值）。 */
    const val PAGE_SIZE = 100

    /** 一页拉取结果：行 + 分页游标（hasMore=false 时 nextOffset 为 null）。 */
    data class Page(
        val sessions: List<Session>,
        val hasMore: Boolean,
        val nextOffset: Long?,
    )

    /** 解析 sessions.list 单页 payload。 */
    fun parsePage(payload: JsonObject): Page {
        val rows = payload["sessions"]?.jsonArray ?: return Page(emptyList(), hasMore = false, nextOffset = null)
        val sessions = rows.mapNotNull { row -> (row as? JsonObject)?.let(::parseRow) }
        val hasMore = payload.bool("hasMore") ?: false
        return Page(
            sessions = sessions,
            hasMore = hasMore,
            nextOffset = if (hasMore) payload.long("nextOffset") else null,
        )
    }

    /** 单行 → Session。字段缺失有兜底（label 缺用 key 尾段）；unread 兼容 int/bool 两形态。 */
    fun parseRow(row: JsonObject): Session? {
        val key = row.str("key") ?: return null
        return Session(
            id = key,
            title = row.str("label") ?: row.str("displayName") ?: key.substringAfterLast(':'),
            preview = row.str("preview"),
            lastActiveAtMs = row.long("updatedAt") ?: row.long("lastActivityAt"),
            unread = row.unreadCount(),
            pinned = row.bool("pinned") ?: false,
            archived = row.bool("archived") ?: false,
            kind = row.str("kind") ?: row.str("channel"),
            agentId = row.str("agentId") ?: dev.sharpxia.agentreins.model.sessionKeyAgentId(key),
        )
    }

    /** unread 官方为计数；兼容布尔形态（true→1）。 */
    private fun JsonObject.unreadCount(): Int {
        (this["unread"] as? kotlinx.serialization.json.JsonPrimitive)?.let { p ->
            p.intOrNull?.let { return it }
            p.booleanOrNull?.let { return if (it) 1 else 0 }
        }
        return 0
    }

    /**
     * 新列表 ⊕ 本地列表合并：fresh 行字段为准（网关权威），fresh 未覆盖的会话若仍在
     * cached 里（可能只是分页尾页未及）按 [keepMissing]=true 保留——防「翻页中断丢会话」。
     */
    fun merge(fresh: List<Session>, cached: List<Session>, keepMissing: Boolean = true): List<Session> {
        val freshByKey = fresh.associateBy { it.id }
        val merged = fresh + (if (keepMissing) cached.filter { it.id !in freshByKey } else emptyList())
        return sort(merged)
    }

    /** 列表统一排序：置顶在前，其后按 lastActiveAtMs 降序（无时间戳排最后）。 */
    fun sort(sessions: List<Session>): List<Session> = sessions.sortedWith(
        compareByDescending<Session> { it.pinned }
            .thenByDescending { it.lastActiveAtMs ?: Long.MIN_VALUE },
    )

    /** 列表过滤：归档默认隐藏（UI 提供「显示归档」开关时再放开）。 */
    fun visibleForList(sessions: List<Session>, includeArchived: Boolean = false): List<Session> =
        if (includeArchived) sessions else sessions.filterNot { it.archived }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull
}
