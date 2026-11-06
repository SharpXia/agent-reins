package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.UiConversation

/**
 * 会话列表（wp-6 验收③）：卡片唯一来源 = Hub 投影（快照/列表校准 upsert，v1.1 卡片键 = conversationId）。
 * 未读徽标 = 投影 unreadCount（排除本用户行/合成行/未落定流式行，05 §3.7）；hasActiveRun/reconciling
 * 为投影计算值，UI 只呈现不发明。
 * wp-9：右键菜单按会话静音开关（iOS HubStore.setMuted 桌面同位——本地偏好持久化，跨登出
 * 保留；静音吞回复/run 终态通知、不吞审批，口径与协调器 [DesktopNotificationCoordinator]
 * 一致）+ 已静音卡片「已静音」标识（设置页清单管理入口同面可见）。
 */
@Composable
internal fun ConversationListPanel(
    conversations: List<UiConversation>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    mutedIds: Set<String> = emptySet(),
    onToggleMute: (conversationId: String, muted: Boolean) -> Unit = { _, _ -> },
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无会话", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "登录并追平同步后自动出现（快照 + R4 校准）",
                            fontSize = 11.sp,
                            color = Color(0xFF888888),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(conversations, key = { it.conversationId }) { conv ->
                        ConversationCard(
                            conv = conv,
                            selected = conv.conversationId == selectedId,
                            muted = conv.conversationId in mutedIds,
                            onToggleMute = onToggleMute,
                            onClick = { onSelect(conv.conversationId) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConversationCard(
    conv: UiConversation,
    selected: Boolean,
    muted: Boolean,
    onToggleMute: (conversationId: String, muted: Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    // wp-9 右键菜单：menuOpen/menuOffset 为卡片私有组合态；右键 = Press + Secondary 按钮
    // （compose 1.12 无 PointerEventType.SecondaryClick，按 PointerEvent.button 判定），
    // 位置经 LocalDensity 转 DpOffset 定锚（桌面 Compose 语境菜单惯用式，左键 clickable 不受影响）
    var menuOpen by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .pointerInput(conv.conversationId) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                                val pos = event.changes.firstOrNull()?.position ?: continue
                                menuOffset = DpOffset(
                                    with(density) { pos.x.toDp() },
                                    with(density) { pos.y.toDp() },
                                )
                                menuOpen = true
                            }
                        }
                    }
                }
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.label.ifBlank { "（未命名会话）" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (muted) {
                    TagChip("已静音", Color(0xFF777777))
                    Spacer(Modifier.width(6.dp))
                }
                if (conv.hasActiveRun) {
                    ActiveRunDot()
                    Spacer(Modifier.width(6.dp))
                }
                if (conv.reconciling) {
                    TagChip("对账中", Color(0xFFB26A00))
                    Spacer(Modifier.width(6.dp))
                }
                if (conv.unread > 0) {
                    UnreadBadge(conv.unread)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.preview.ifBlank { " " },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF777777),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = formatTs(conv.lastActiveAt),
                    fontSize = 11.sp,
                    color = Color(0xFF999999),
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = menuOffset,
        ) {
            DropdownMenuItem(
                text = { Text(if (muted) "取消静音" else "静音") },
                onClick = {
                    menuOpen = false
                    onToggleMute(conv.conversationId, !muted)
                },
            )
        }
    }
}
