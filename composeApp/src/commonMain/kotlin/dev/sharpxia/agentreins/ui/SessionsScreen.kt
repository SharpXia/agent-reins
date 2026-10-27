package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.openclaw.SessionListMapper
import kotlin.math.abs
import dev.sharpxia.agentreins.ui.theme.ArTheme
import kotlinx.coroutines.launch

/**
 * 会话列表（M2.6 新基线）：头像 + 标题 + 预览 + 时间/未读，滑动操作（右滑置顶、
 * 左滑归档）与长按菜单；归档默认过滤、断网可见缓存列表（M2.5 语义不变）。
 * pinned/archived 走本地 overlay（AppModel），网关同步留 M3+（探针：需 operator.admin）。
 */
@Composable
fun SessionsScreen(model: AppModel, modifier: Modifier = Modifier) {
    val selected = model.selectedSession
    when {
        // 断网冷启动仍可见缓存列表（M2.5：缓存是加速层，离线可读）
        selected == null && model.sessions.isNotEmpty() ->
            SessionList(model, modifier, offline = model.status !is GatewayStatus.Connected)
        selected == null -> EmptyState(model, modifier)
        else -> ChatScreen(model, selected, modifier)
    }
}

@Composable
private fun EmptyState(model: AppModel, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val st = model.status) {
            is GatewayStatus.Connecting -> {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Text("连接中…", style = MaterialTheme.typography.titleMedium)
            }
            is GatewayStatus.PairingPending -> {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.size(12.dp))
                Text("等待 owner 批准设备…", style = MaterialTheme.typography.titleMedium)
                st.requestId?.let {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "requestId: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                if (st is GatewayStatus.Error) {
                    Text("自动连接失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        st.message ?: "未知错误",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "回前台或到「连接」页重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text("未连接网关", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "到「连接」页扫码配对或填入地址与 API Key",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionList(model: AppModel, modifier: Modifier = Modifier, offline: Boolean = false) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHapticPlayer()
    var showArchived by remember { mutableStateOf(false) }
    // 归档默认过滤；置顶排序由 SessionListMapper/SessionOverlay 保证
    val visible = remember(model.sessions, showArchived) {
        SessionListMapper.visibleForList(model.sessions, showArchived)
    }
    StatusBarTapEffect {
        if (visible.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
    Column(modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("会话", style = MaterialTheme.typography.titleLarge)
                Text(
                    when {
                        offline -> "离线中 · 显示缓存列表"
                        else -> "${visible.size} 个会话"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 归档过滤开关（有归档会话才亮）
            val archivedCount = model.sessions.count { it.archived }
            if (archivedCount > 0) {
                TextButton(onClick = { showArchived = !showArchived }) {
                    Text(if (showArchived) "隐藏归档" else "归档($archivedCount)")
                }
            }
        }
        LazyColumn(state = listState) {
            items(visible, key = { it.id }) { s ->
                SwipeableSessionRow(
                    session = s,
                    onClick = { model.select(s) },
                    onPin = { pinned ->
                        if (pinned) haptics.success() else haptics.tap()
                        model.setSessionPinned(s, pinned)
                    },
                    onArchive = { archived ->
                        haptics.tap()
                        model.setSessionArchived(s, archived)
                    },
                )
            }
        }
    }
}

/**
 * 可滑动的会话行：StartToEnd（右滑）= 置顶/取消置顶，EndToStart（左滑）= 归档/取消归档。
 * confirmValueChange 返回 false = 执行动作后弹回（swipe-to-act，非滑动删除）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeableSessionRow(
    session: Session,
    onClick: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
) {
    // confirmValueChange 在 remember 的 state 里只捕获一次，经 rememberUpdatedState 读最新会话
    val currentSession by rememberUpdatedState(session)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onPin(!currentSession.pinned)
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onArchive(!currentSession.archived)
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // 按实时偏移量渐显：拖动一开始就有色块与图标（此前 targetValue 过半才变，
            // 大半段拖动空白——真机反馈「没有明显指示器」实锤）
            SwipeActionBackground(
                offsetPx = runCatching { dismissState.requireOffset() }.getOrDefault(0f),
                pinned = session.pinned,
                archived = session.archived,
            )
        },
    ) {
        SessionRowContent(
            session = session,
            onClick = onClick,
            onPin = onPin,
            onArchive = onArchive,
        )
    }
}

/** 滑动背景：拖动即现的方向色块 + 图标 + 动作文案（随滑动进度淡入）。 */
@Composable
private fun SwipeActionBackground(offsetPx: Float, pinned: Boolean, archived: Boolean) {
    if (abs(offsetPx) < 1f) return
    val thresholdPx = with(LocalDensity.current) { 64.dp.toPx() }
    val progress = (abs(offsetPx) / thresholdPx).coerceIn(0f, 1f)
    val pin = offsetPx > 0f
    val container = if (pin) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val content = if (pin) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    val icon = when {
        pin -> ArIcons.Pin
        archived -> ArIcons.Unarchive
        else -> ArIcons.Archive
    }
    val label = when {
        pin -> if (pinned) "取消置顶" else "置顶"
        archived -> "取消归档"
        else -> "归档"
    }
    Surface(color = container.copy(alpha = 0.3f + 0.7f * progress), modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (pin) Arrangement.Start else Arrangement.End,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .graphicsLayer { alpha = progress },
        ) {
            if (!pin) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
                Spacer(Modifier.size(8.dp))
            }
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(22.dp))
            if (pin) {
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowContent(
    session: Session,
    onClick: () -> Unit,
    onPin: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val tokens = ArTheme.tokens
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        SessionAvatar(session)
        Spacer(Modifier.size(tokens.spaceM.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.pinned) {
                    Icon(
                        ArIcons.Pin,
                        contentDescription = "已置顶",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    session.title ?: session.id,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (session.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                formatRelativeTime(session.lastActiveAtMs).takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.preview?.takeIf { it.isNotBlank() } ?: session.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (session.unread > 0) {
                    Spacer(Modifier.size(tokens.spaceS.dp))
                    UnreadBadge(session.unread)
                }
            }
        }
        SessionRowMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            pinned = session.pinned,
            archived = session.archived,
            onPin = { onPin(it) },
            onArchive = { onArchive(it) },
        )
    }
}

/** 头像：kind/标题定色圆底 + 首字符。 */
@Composable
private fun SessionAvatar(session: Session) {
    val tokens = ArTheme.tokens
    val title = session.title ?: session.id
    Box(
        Modifier
            .size(tokens.avatarSize.dp)
            .clip(CircleShape)
            .background(avatarColor(session.kind, title)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun UnreadBadge(unread: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(
            if (unread > 99) "99+" else unread.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}

/** 长按菜单：置顶/取消置顶、归档/取消归档。 */
@Composable
private fun SessionRowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    pinned: Boolean,
    archived: Boolean,
    onPin: (Boolean) -> Unit,
    onArchive: (Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(if (pinned) "取消置顶" else "置顶") },
            leadingIcon = { Icon(ArIcons.Pin, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onPin(!pinned); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (archived) "取消归档" else "归档") },
            leadingIcon = {
                Icon(
                    if (archived) ArIcons.Unarchive else ArIcons.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { onArchive(!archived); onDismiss() },
        )
    }
}
