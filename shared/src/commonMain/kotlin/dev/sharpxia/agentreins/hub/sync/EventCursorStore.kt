package dev.sharpxia.agentreins.hub.sync

/**
 * 同步游标持久化最小集（05 §3.5 O-01；P2 落 SQLite v2 的 sync_state/kv）。
 * 只存全局事件 ULID 与快照水位——禁止持久化 Gateway 数值 offset / 连接级 seq（O-12）。
 * eventCursor 单调不减、跨化身有效（INV-2）。
 */
interface EventCursorStore {
    fun eventCursor(): String?

    fun updateEventCursor(id: String)

    fun snapshotWatermark(): String?

    fun updateSnapshotWatermark(watermark: String)

    fun clear()
}
