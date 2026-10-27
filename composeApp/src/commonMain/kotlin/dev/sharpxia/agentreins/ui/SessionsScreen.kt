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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import dev.sharpxia.agentreins.openclaw.SessionListMapper
import kotlin.math.abs
import dev.sharpxia.agentreins.ui.components.ConnectionEmptyState
import dev.sharpxia.agentreins.ui.components.SectionHeader
import dev.sharpxia.agentreins.ui.components.UnreadBadge
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.AgentTintScope
import dev.sharpxia.agentreins.ui.theme.InkTheme
import dev.sharpxia.agentreins.ui.theme.LocalAgentTint
import kotlinx.coroutines.launch

/**
 * 会话列表（M3.2 墨水风重绘）：
 * - 行：agent tint 头像（未注册 seed 散列）+ 标题/预览 + 相对时间 + 未读徽标 + 置顶 Pin
 * - 操作：右滑置顶、左滑归档（swipe-to-act，指示器实时渐显），长按菜单含隐藏
 * - 分区：主列表（归档/隐藏过滤）↔ 收纳视图（归档 + 已隐藏两段）
 * - pinned/archived/hidden 全走本地 overlay（AppModel；网关同步留 scope 升级流程）
 * 断网可见缓存列表（M2.5 语义不变）。
 */
@Composable
fun SessionsScreen(
    model: AppModel,
    onOpenSession: (String) -> Unit,
    onOpenArchived: () -> Unit = {},
) {
    if (model.sessions.isEmpty()) {
        ConnectionEmptyState(model)
    } else {
        SessionList(
            model = model,
            offline = model.status !is GatewayStatus.Connected,
            onOpenSession = onOpenSession,
        )
    }
}

@Composable
private fun SessionList(
    model: AppModel,
    offline: Boolean,
    onOpenSession: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHapticPlayer()
    var showStash by remember { mutableStateOf(false) } // 收纳视图：归档 + 已隐藏
    val mainSessions = remember(model.sessions) {
        SessionListMapper.visibleForList(model.sessions.filter { !it.hidden })
    }
    val archived = remember(model.sessions) { model.sessions.filter { it.archived && !it.hidden } }
    val hidden = remember(model.sessions) { model.sessions.filter { it.hidden } }
    val pinnedCount = mainSessions.count { it.pinned }

    StatusBarTapEffect {
        if (mainSessions.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (showStash) "收纳" else "会话", style = InkTheme.type.headingMd, color = InkTheme.colors.ink)
                Text(
                    when {
                        showStash -> "归档 ${archived.size} · 隐藏 ${hidden.size}"
                        offline -> "离线中 · 显示缓存列表"
                        else -> "${mainSessions.size} 个会话"
                    },
                    style = InkTheme.type.textSm,
                    color = InkTheme.colors.textTertiary,
                )
            }
            TextButton(onClick = { showStash = !showStash }) {
                Text(
                    if (showStash) "返回" else "收纳(${archived.size + hidden.size})",
                    style = InkTheme.type.textMd,
                    color = if (archived.isEmpty() && hidden.isEmpty()) InkTheme.colors.textDisabled else InkTheme.colors.ink,
                )
            }
        }
        LazyColumn(state = listState) {
            if (showStash) {
                if (archived.isNotEmpty()) {
                    item(key = "hdr-archived") { SectionHeader("归档") }
                    items(archived, key = { "a-${it.id}" }) { s ->
                        SwipeableSessionRow(model, s, onOpenSession, haptics)
                    }
                }
                if (hidden.isNotEmpty()) {
                    item(key = "hdr-hidden") { SectionHeader("已隐藏") }
                    items(hidden, key = { "h-${it.id}" }) { s ->
                        SwipeableSessionRow(model, s, onOpenSession, haptics)
                    }
                }
                if (archived.isEmpty() && hidden.isEmpty()) {
                    item(key = "stash-empty") {
                        Text(
                            "没有归档或隐藏的会话",
                            style = InkTheme.type.textMd,
                            color = InkTheme.colors.textTertiary,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            } else {
                items(mainSessions, key = { it.id }) { s ->
                    SwipeableSessionRow(model, s, onOpenSession, haptics)
                }
            }
        }
    }
}

/**
 * 可滑动的会话行：StartToEnd（右滑）= 置顶/取消置顶，EndToStart（左滑）= 归档/取消归档。
 * confirmValueChange 返回 false = 执行动作后弹回（swipe-to-act，非滑动删除）。
 * 收纳视图里方向语义反转（取消归档/取消隐藏），见 [stashRow]。
 */
@Composable
private fun SwipeableSessionRow(
    model: AppModel,
    session: Session,
    onOpenSession: (String) -> Unit,
    haptics: HapticPlayer,
) {
    // confirmValueChange 在 remember 的 state 里只捕获一次，经 rememberUpdatedState 读最新会话
    val currentSession by rememberUpdatedState(session)
    val stash = session.archived || session.hidden
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (stash) {
                        haptics.tap()
                        if (currentSession.hidden) model.setSessionHidden(currentSession, false)
                        else model.setSessionArchived(currentSession, false)
                    } else {
                        if (!currentSession.pinned) haptics.success() else haptics.tap()
                        model.setSessionPinned(currentSession, !currentSession.pinned)
                    }
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (stash) {
                        haptics.tap()
                        model.setSessionHidden(currentSession, !currentSession.hidden)
                    } else {
                        haptics.tap()
                        model.setSessionArchived(currentSession, true)
                    }
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
    )
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            SwipeActionBackground(
                offsetPx = runCatching { dismissState.requireOffset() }.getOrDefault(0f),
                session = session,
                stash = stash,
            )
        },
    ) {
        SessionRowContent(model, session, stash, onClick = { onOpenSession(session.id) })
    }
}

/** 滑动背景：拖动即现的方向色块 + 图标 + 动作文案（随滑动进度淡入；墨水功能色）。 */
@Composable
private fun SwipeActionBackground(offsetPx: Float, session: Session, stash: Boolean) {
    if (abs(offsetPx) < 1f) return
    val c = InkTheme.colors
    val thresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    val progress = (abs(offsetPx) / thresholdPx).coerceIn(0f, 1f)
    val pin = offsetPx > 0f
    // 主列表：右滑置顶（绿）/左滑归档（蓝）；收纳视图：右滑恢复（绿）/左滑隐藏↔取消隐藏（橙）
    val func = when {
        pin && stash -> c.success
        pin -> c.success
        stash && session.hidden -> c.info
        stash -> c.warning
        else -> c.info
    }
    val label = when {
        stash && pin -> if (session.hidden) "取消隐藏" else "取消归档"
        stash && !pin -> if (session.hidden) "归档" else "隐藏"
        pin -> if (session.pinned) "取消置顶" else "置顶"
        else -> "归档"
    }
    val icon = when {
        pin && stash -> ArIcons.Unarchive
        pin -> ArIcons.Pin
        stash && session.hidden -> ArIcons.Unarchive
        stash -> ArIcons.EyeOff
        else -> ArIcons.Archive
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(func.subtle.copy(alpha = func.subtle.alpha + (1f - func.subtle.alpha) * 0.7f * progress)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (pin) Arrangement.Start else Arrangement.End,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .graphicsLayer { alpha = progress },
        ) {
            if (!pin) {
                Text(label, style = InkTheme.type.textSm, color = func.text)
                Spacer(Modifier.size(8.dp))
            }
            Icon(icon, contentDescription = label, tint = func.text, modifier = Modifier.size(22.dp))
            if (pin) {
                Spacer(Modifier.size(8.dp))
                Text(label, style = InkTheme.type.textSm, color = func.text)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRowContent(
    model: AppModel,
    session: Session,
    stash: Boolean,
    onClick: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val agentId = session.agentId ?: sessionKeyAgentId(session.id)
    val tintName = model.agentProfiles[agentId]?.tint
        ?: agentId?.let { dev.sharpxia.agentreins.ui.theme.agentTintFor(it) }
        ?: AgentTintName.GRAY
    AgentTintScope(tintName) {
        val tint = LocalAgentTint.current
        val c = InkTheme.colors
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            AgentAvatar(tint, session.title ?: session.id)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (session.pinned && !stash) {
                        Icon(
                            ArIcons.Pin,
                            contentDescription = "已置顶",
                            tint = tint.strong,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                    }
                    if (session.hidden) {
                        Icon(
                            ArIcons.EyeOff,
                            contentDescription = "已隐藏",
                            tint = c.textTertiary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                    }
                    Text(
                        session.title ?: session.id,
                        style = InkTheme.type.textLg,
                        fontWeight = if (session.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    formatRelativeTime(session.lastActiveAtMs).takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = InkTheme.type.textXs,
                            color = c.textTertiary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        session.preview?.takeIf { it.isNotBlank() } ?: session.id,
                        style = InkTheme.type.textSm,
                        color = c.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (session.unread > 0) {
                        Spacer(Modifier.size(8.dp))
                        UnreadBadge(session.unread)
                    }
                }
            }
            SessionRowMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                session = session,
                stash = stash,
                model = model,
            )
        }
    }
}

/** 长按菜单：置顶 / 归档 / 隐藏（收纳视图里为对应反向操作）。 */
@Composable
private fun SessionRowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    session: Session,
    stash: Boolean,
    model: AppModel,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!stash) {
            DropdownMenuItem(
                text = { Text(if (session.pinned) "取消置顶" else "置顶") },
                leadingIcon = { Icon(ArIcons.Pin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { model.setSessionPinned(session, !session.pinned); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text(if (session.archived) "取消归档" else "归档") },
            leadingIcon = {
                Icon(
                    if (session.archived) ArIcons.Unarchive else ArIcons.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { model.setSessionArchived(session, !session.archived); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (session.hidden) "取消隐藏" else "隐藏") },
            leadingIcon = {
                Icon(
                    if (session.hidden) ArIcons.Unarchive else ArIcons.EyeOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { model.setSessionHidden(session, !session.hidden); onDismiss() },
        )
    }
}
