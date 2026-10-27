package dev.sharpxia.agentreins.ui

import androidx.compose.ui.graphics.Color
import dev.sharpxia.agentreins.model.ChatEntry
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ToolCallEvent
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * 对话流行模型（M2.6）：给 LazyColumn 的扁平化行序列——消息/工具卡条目 + 日分隔头。
 * 纯函数，desktopTest 覆盖；时区显式传入便于测试。
 */

/** LazyColumn 的一行：日分隔头或原条目。 */
sealed interface ChatRow {
    /** 日分隔头（取当天任一时间戳渲染日期文案）。 */
    data class DayHeader(val atMs: Long) : ChatRow

    /** 原对话条目（消息 / 工具卡）。 */
    data class Entry(val entry: ChatEntry) : ChatRow
}

/**
 * 条目序列 → 带日分隔的行序列：相邻两条已知时间戳的消息跨日时插入分隔头；
 * 无时间戳的条目归属前一分组（不产生分隔，也不打断已有分组）。
 */
fun buildChatRows(
    entries: List<ChatEntry>,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var lastDate: LocalDate? = null
    val usedIds = mutableSetOf<String>()
    for (entry in entries) {
        val ts = (entry as? Message)?.timestampMs
        if (ts != null) {
            val date = dateOf(ts, zone)
            if (date != lastDate) {
                rows += ChatRow.DayHeader(ts)
                lastDate = date
            }
        }
        // id 撞号防御：LazyColumn key 必须唯一，重复 id 会在布局期抛异常直接崩溃
        // （真机实锤）。正常已被上游保证，这里对残余冲突改写 id 兜底。
        var id = entry.id
        var n = 2
        while (!usedIds.add(id)) {
            id = "${entry.id}#${n}"
            n++
        }
        val safe = if (id == entry.id) entry else when (entry) {
            is Message -> entry.copy(id = id)
            is ToolCallEvent -> entry.copy(id = id)
            else -> entry
        }
        rows += ChatRow.Entry(safe)
    }
    return rows
}

/** 日分隔文案：今天 / 昨天 / M月d日（跨年补年份）。 */
fun dayHeaderLabel(atMs: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val now = Clock.System.now()
    val today = now.toLocalDateTime(zone).date
    val date = dateOf(atMs, zone)
    return when {
        date == today -> "今天"
        date == today.minus(1, DateTimeUnit.DAY) -> "昨天"
        date.year != today.year -> "${date.year}年${date.monthNumber}月${date.dayOfMonth}日"
        else -> "${date.monthNumber}月${date.dayOfMonth}日"
    }
}

private fun dateOf(ms: Long, zone: TimeZone): LocalDate =
    Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone).date

// ---- 头像色板（会话渠道标识体系：kind/标题哈希取色，明暗两版通用） ----

private val avatarPalette = listOf(
    0xFF10A37F, 0xFF5B8DEF, 0xFF8E6BE8, 0xFFE86B8A,
    0xFFE8A33D, 0xFF38A3A5, 0xFF6B9E4F, 0xFFD96C4F,
).map { Color(it) }

/** 会话头像底色：kind 优先（同渠道同色），否则按标题哈希（同会话恒定）。 */
fun avatarColor(kind: String?, title: String): Color {
    val seed = kind?.takeIf { it.isNotBlank() } ?: title
    val index = seed.hashCode().let { if (it < 0) it.inv() else it } % avatarPalette.size
    return avatarPalette[index]
}
