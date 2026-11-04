package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 共享小部件与格式化助手（wp-6 桌面 Hub 面）。MVP 纯文本渲染：Markdown/操作按钮在 wp-10/wp-18。 */

/** 未读徽标：数字为 Hub 投影的 unreadCount（05 §3.7，客户端不发明第二套计数）。 */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .background(MaterialTheme.colorScheme.error, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count < 100) count.toString() else "99+",
            color = MaterialTheme.colorScheme.onError,
            fontSize = 11.sp,
        )
    }
}

/** 小标签 chip：运行中 / 对账中 / 发送态等。 */
@Composable
internal fun TagChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 活跃 Run 圆点（hasActiveRun）。 */
@Composable
internal fun ActiveRunDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(Color(0xFF2E9E44), CircleShape),
    )
}

/** 分隔条（role="" 的 compaction/reset 合成行，06 §3.1.2；渲染为系统分隔条不占消息位）。 */
@Composable
internal fun CompactionSeparator() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33888888)))
        Text(
            "会话已压缩 / 重置",
            fontSize = 11.sp,
            color = Color(0xFF8A8A8A),
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33888888)))
    }
}

private val displayZone: ZoneId get() = ZoneId.systemDefault()

/**
 * 时间显示：消息 ts 有两种历史形态——服务端事件信封 ISO-8601（message.created 的 ts）与
 * 乐观行 epoch 毫秒串（DefaultCommandClient.nowText()），尽力解析为本地时区，失败原样返回。
 */
internal fun formatTs(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    runCatching { Instant.parse(raw) }.getOrNull()?.let { return formatInstant(it) }
    runCatching { OffsetDateTime.parse(raw) }.getOrNull()?.let { return formatInstant(it.toInstant()) }
    raw.toLongOrNull()?.let { return formatInstant(Instant.ofEpochMilli(it)) }
    return raw
}

private fun formatInstant(instant: Instant): String =
    DateTimeFormatter.ofPattern("MM-dd HH:mm")
        .withZone(displayZone)
        .format(instant)

/** 等宽小字（设备 id、错误码等）。 */
@Composable
internal fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = Color(0xFF666666)) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = modifier,
    )
}

/** 空态占位。 */
@Composable
internal fun EmptyHint(title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF888888),
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
