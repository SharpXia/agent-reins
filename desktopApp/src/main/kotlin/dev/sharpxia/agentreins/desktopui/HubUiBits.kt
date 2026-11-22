package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.UiAttachment
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 共享小部件与格式化助手（wp-6 桌面 Hub 面 + wp-10 交互 parity 复用）。助手行 Markdown 渲染器在 MarkdownText.kt（wp-18）。 */

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

// ActiveRunDot（活跃 Run 圆点）已随角色系统批次3（2026-11-20）退役：唯一调用点
// ConversationListPanel ConversationCard 行尾圆点由行首 36dp BotCharacter 六态承接
// （活跃含义并入角色），定义删除——余点审计（全 desktopApp grep）仅此一处调用。

/**
 * 消息附件 chip 行（v1.2.0 多模态，设计稿 §6 桌面行「user 行附件 chip 渲染」；assistant 行
 * 空正文+有附件同用）：chip 面只呈现元数据（mediaId 尾段 / fileName / mimeType / url），
 * 不经端点 #24 拉取字节——桌面最小口径（iOS 缩略图自定义 loader 是另一档实现，此处有意
 * 不对齐）。source=external 行附 URL 小字原样透出（不改写不代理）；未知 source 原样兜底
 * 走图标行不丢行。URL 小字可点（2026-11-23 批次二轮）：此前纯展示不可点，点按出「打开/
 * 复制」气泡，openUri 同走 normalizeUrl 归一（与正文链接口径一致，知情注释）。
 */
@Composable
internal fun AttachmentChipsRow(attachments: List<UiAttachment>, modifier: Modifier = Modifier) {
    // DSK-1 散写收敛：墨水令牌单点读取（chip 薄底面 + 弱化小字）
    val ink = LocalDesktopInk.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        attachments.forEach { a ->
            Surface(color = ink.surfaceTertiary, shape = RoundedCornerShape(8.dp)) { // 原 0x14888888 薄灰底
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    val label = a.fileName
                        ?: a.mediaId?.let { "…${it.takeLast(8)}" }
                        ?: "图片"
                    val icon = if (a.mimeType?.startsWith("image/") == true) "🖼" else "📎"
                    Text("$icon $label", fontSize = 12.sp)
                    // 跨模块属性无智能转换：url 先落本地再判空（Kotlin 编译纪律）
                    val url = a.url
                    if (url != null) {
                        var bubbleOpen by remember(url) { mutableStateOf(false) }
                        Text(
                            text = url,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = ink.textTertiary, // 原 0xFF777777
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { bubbleOpen = true },
                        )
                        if (bubbleOpen) {
                            LinkActionPopup(
                                displayUrl = url,
                                normalizedUrl = normalizeUrl(url),
                                onDismiss = { bubbleOpen = false },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分隔条（role="" 的 compaction/reset 合成行，06 §3.1.2；渲染为系统分隔条不占消息位）。 */
@Composable
internal fun CompactionSeparator() {
    // DSK-1 散写收敛：分隔线（原 0x33888888 ≈ 12% 合成灰）→ borderPrimary、弱化小字 → textTertiary
    val ink = LocalDesktopInk.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ink.borderPrimary))
        Text(
            "会话已压缩 / 重置",
            fontSize = 11.sp,
            color = ink.textTertiary, // 原 0xFF8A8A8A
            modifier = Modifier.padding(vertical = 4.dp),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(ink.borderPrimary))
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

/** 等宽小字（设备 id、错误码等）。缺省色 = 弱化文字令牌（原 0xFF666666，DSK-1 散写收敛）。 */
@Composable
internal fun MonoText(text: String, modifier: Modifier = Modifier, color: Color = LocalDesktopInk.current.textTertiary) {
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
                color = LocalDesktopInk.current.textTertiary, // 原 0xFF888888（DSK-1 散写收敛）
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
