package dev.sharpxia.agentreins.ui

import kotlin.time.Clock

/** 对话 UI 的小格式化工具（commonMain 可用，无平台依赖）。 */

/** 耗时：485ms / 3.2s / 1m12s；无数据显示 —。 */
fun formatDuration(ms: Long?): String = when {
    ms == null -> "—"
    ms < 1_000 -> "${ms}ms"
    ms < 60_000 -> "${ms / 1_000}.${(ms % 1_000) / 100}s"
    else -> "${ms / 60_000}m${(ms % 60_000) / 1_000}s"
}

/** 相对时间：刚刚 / 5分钟前 / 3小时前 / 2天前 / 45天前。 */
fun formatRelativeTime(epochMs: Long?, nowMs: Long = Clock.System.now().toEpochMilliseconds()): String {
    if (epochMs == null) return ""
    val diff = nowMs - epochMs
    val minute = 60_000L
    return when {
        diff < minute -> "刚刚"
        diff < 60 * minute -> "${diff / minute}分钟前"
        diff < 24 * 60 * minute -> "${diff / (60 * minute)}小时前"
        else -> "${diff / (24 * 60 * minute)}天前"
    }
}
