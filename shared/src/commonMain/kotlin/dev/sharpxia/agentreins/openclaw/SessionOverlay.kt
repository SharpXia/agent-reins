package dev.sharpxia.agentreins.openclaw

import dev.sharpxia.agentreins.model.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 会话列表本地覆盖层（M2.6）：pinned/archived 的本地显式状态，叠加在网关列表之上。
 *
 * 协议事实（2026-10-26 真网关探针）：`sessions.patch {key, patch:{pinned/archived}}`
 * 存在但要求 `operator.admin` scope，升级 scope 会触发 PAIRING_REQUIRED（scope-upgrade）
 * 重批准——为守住「静默重连」体验，M2.6 的归档/置顶先落本地 overlay（kv 持久化、
 * 跨重启、离线可用），网关同步留待 M3+ 与 scope 升级流程一并做。
 *
 * 语义：overlay 只存用户**显式**操作过的键值（含反向值，如把网关置顶会话本地取消置顶
 * → 存 pinned=false）；网关刷新后每次都要重新叠加，未被操作过的字段一律跟网关走。
 */
object SessionOverlay {

    /** 单会话的覆盖值；null = 本地不覆盖该字段（跟网关）。 */
    @Serializable
    data class Entry(
        val pinned: Boolean? = null,
        val archived: Boolean? = null,
        /** 本地隐藏（M3.2 隐私分区；网关无此概念，纯本地意图）。 */
        val hidden: Boolean? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** 反序列化（kv 读出的 JSON）；坏数据按空处理——overlay 是加速层，不能因它崩 UI。 */
    fun parse(raw: String?): Map<String, Entry> =
        if (raw.isNullOrBlank()) emptyMap()
        else runCatching { json.decodeFromString<Map<String, Entry>>(raw) }.getOrDefault(emptyMap())

    fun serialize(overlay: Map<String, Entry>): String = json.encodeToString(overlay)

    /**
     * 叠加：base（网关/缓存列表）每行按 overlay 覆盖 pinned/archived，再按列表统一排序。
     * overlay 里指向不存在会话的条目静默忽略（会话可能已被网关清理）。
     */
    fun apply(base: List<Session>, overlay: Map<String, Entry>): List<Session> {
        if (overlay.isEmpty()) return base
        val adjusted = base.map { s ->
            val e = overlay[s.id] ?: return@map s
            s.copy(
                pinned = e.pinned ?: s.pinned,
                archived = e.archived ?: s.archived,
                hidden = e.hidden ?: s.hidden,
            )
        }
        return SessionListMapper.sort(adjusted)
    }

    /**
     * 纯更新：把 [sessionKey] 的 [pinned]/[archived]/[hidden] 写入覆盖层（非 null 才写）。
     * null = 不动该字段（它没有「恢复跟网关」语义——本地意图一旦写下就一直生效）。
     */
    fun toggled(
        overlay: Map<String, Entry>,
        sessionKey: String,
        pinned: Boolean? = null,
        archived: Boolean? = null,
        hidden: Boolean? = null,
    ): Map<String, Entry> {
        require(pinned != null || archived != null || hidden != null) {
            "toggled: at least one field must be non-null"
        }
        val current = overlay[sessionKey] ?: Entry()
        val next = current.copy(
            pinned = pinned ?: current.pinned,
            archived = archived ?: current.archived,
            hidden = hidden ?: current.hidden,
        )
        return overlay + (sessionKey to next)
    }
}
