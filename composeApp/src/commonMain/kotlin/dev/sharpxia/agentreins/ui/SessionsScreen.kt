package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import dev.sharpxia.agentreins.ui.components.SandSheet
import dev.sharpxia.agentreins.ui.components.UnreadBadge
import dev.sharpxia.agentreins.ui.components.sandPressFill
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
    onOpenAgent: (String) -> Unit = {},
) {
    if (model.sessions.isEmpty()) {
        ConnectionEmptyState(model)
    } else {
        SessionList(
            model = model,
            offline = model.status !is GatewayStatus.Connected,
            onOpenSession = onOpenSession,
            onOpenAgent = onOpenAgent,
        )
    }
}

/** 新建会话：选 agent（M3.6 反馈①）——干净会话从选定 agent 开始。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NewSessionSheet(
    model: AppModel,
    onDismiss: () -> Unit,
) {
    val summaries = remember(model.sessions, model.agentProfiles) {
        dev.sharpxia.agentreins.AgentRegistry.summarize(model.sessions, model.agentProfiles)
    }
    SandSheet(onDismiss = onDismiss, title = "新建会话") {
        Text(
            "选择一个 Agent 开始干净对话（Agent 的配置与记忆保留）",
            style = InkTheme.type.textSm,
            color = InkTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
        )
        if (summaries.isEmpty()) {
            Text(
                "尚无 agent（连接网关后出现）",
                style = InkTheme.type.textMd,
                color = InkTheme.colors.textTertiary,
                modifier = Modifier.padding(20.dp),
            )
        }
        summaries.forEach { s ->
            AgentTintScope(s.profile.tint) {
                val tint = LocalAgentTint.current
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            model.createNewSession(s.profile.agentId) // 完成后经导航事件流直达新会话
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AgentAvatar(tint, s.profile.name, size = 36)
                    Column(Modifier.weight(1f)) {
                        Text(s.profile.name, style = InkTheme.type.textLg, color = InkTheme.colors.ink)
                        Text(
                            "${s.sessionCount} 个会话",
                            style = InkTheme.type.textXs,
                            color = InkTheme.colors.textTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionList(
    model: AppModel,
    offline: Boolean,
    onOpenSession: (String) -> Unit,
    onOpenAgent: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHapticPlayer()
    var showStash by remember { mutableStateOf(false) } // 收纳视图：归档 + 已隐藏
    var showNewSheet by remember { mutableStateOf(false) }
    val totalUnread = model.sessions.sumOf { it.unread }
    // Agent 过滤（真机反馈⑤-5）：null = All；选中后会话页只显示该 agent 的会话。
    // rememberSaveable：进会话再返回（destination 离开组合）不能把筛选静默丢回 All
    var agentFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val summaries = remember(model.sessions, model.agentProfiles) {
        dev.sharpxia.agentreins.AgentRegistry.summarize(model.sessions, model.agentProfiles)
    }
    val filterMatches: (Session) -> Boolean = { s ->
        agentFilter == null || (s.agentId ?: sessionKeyAgentId(s.id)) == agentFilter
    }
    val mainSessions = remember(model.sessions, agentFilter) {
        SessionListMapper.visibleForList(model.sessions.filter { !it.hidden }.filter(filterMatches))
    }
    val archived = remember(model.sessions, agentFilter) {
        model.sessions.filter { it.archived && !it.hidden }.filter(filterMatches)
    }
    val hidden = remember(model.sessions, agentFilter) {
        model.sessions.filter { it.hidden }.filter(filterMatches)
    }
    val pinnedCount = mainSessions.count { it.pinned }
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed,
    )
    val selectedProfile = agentFilter?.let {
        model.agentProfiles[it] ?: dev.sharpxia.agentreins.AgentProfile.default(it)
    }

    StatusBarTapEffect {
        if (mainSessions.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet(
                drawerContainerColor = InkTheme.colors.elevated,
            ) {
                Text(
                    "Agents",
                    style = InkTheme.type.headingMd,
                    color = InkTheme.colors.ink,
                    modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 6.dp),
                )
                // All（默认）：显示全部会话
                DrawerAgentRow(
                    name = "All",
                    subtitle = "全部会话",
                    tintName = null,
                    selected = agentFilter == null,
                    onClick = {
                        haptics.tap()
                        agentFilter = null
                        showStash = false
                        scope.launch { drawerState.close() }
                    },
                )
                summaries.forEach { s ->
                    DrawerAgentRow(
                        name = s.profile.name,
                        subtitle = "${s.sessionCount} 个会话",
                        tintName = s.profile.tint,
                        selected = agentFilter == s.profile.agentId,
                        onClick = {
                            haptics.tap()
                            agentFilter = s.profile.agentId
                            showStash = false
                            scope.launch { drawerState.close() }
                        },
                        onEdit = {
                            agentFilter = s.profile.agentId
                            scope.launch { drawerState.close() }
                            onOpenAgent(s.profile.agentId)
                        },
                    )
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            ) {
                // 头部左侧入口（真机反馈⑤-5）：All 时抽屉图标；选中 agent 时其头像
                if (agentFilter != null && selectedProfile != null) {
                    AgentTintScope(selectedProfile.tint) {
                        AgentAvatar(LocalAgentTint.current, selectedProfile.name, size = 34)
                    }
                    Spacer(Modifier.size(2.dp))
                }
                androidx.compose.material3.IconButton(onClick = {
                    haptics.tap()
                    scope.launch { drawerState.open() }
                }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Filled.Menu,
                        contentDescription = "打开 Agents 抽屉",
                        tint = InkTheme.colors.ink,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            showStash -> "收纳"
                            agentFilter != null -> selectedProfile?.name ?: agentFilter!!
                            else -> "会话"
                        },
                        style = InkTheme.type.headingMd,
                        color = InkTheme.colors.ink,
                    )
                    Text(
                        when {
                            showStash -> "归档 ${archived.size} · 隐藏 ${hidden.size}"
                            offline -> "离线中 · 显示缓存列表"
                            agentFilter != null -> "${mainSessions.size} 个会话 · 来自 ${selectedProfile?.name ?: agentFilter}"
                            else -> "${mainSessions.size} 个会话"
                        },
                        style = InkTheme.type.textSm,
                        color = InkTheme.colors.textTertiary,
                    )
                }
            // 一键已读（M3.6 反馈③）：全部未读打本地已读标记（overlay 持久化）
            if (totalUnread > 0 && !showStash) {
                Text(
                    "全部已读",
                    style = InkTheme.type.textSm,
                    color = InkTheme.colors.ink, // 墨水双色基线（review R2-P1-9：info 蓝是第三种蓝）
                    modifier = Modifier
                        .clickable { haptics.tap(); model.markAllRead() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Text(
                "＋",
                style = InkTheme.type.headingSm,
                color = InkTheme.colors.ink,
                modifier = Modifier
                    .clickable { haptics.tap(); showNewSheet = true }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            TextButton(onClick = { showStash = !showStash }) {
                Text(
                    if (showStash) "返回" else "收纳(${archived.size + hidden.size})",
                    style = InkTheme.type.textMd,
                    color = if (archived.isEmpty() && hidden.isEmpty()) InkTheme.colors.textDisabled else InkTheme.colors.ink,
                )
            }
        }
        if (showNewSheet) {
            NewSessionSheet(model = model, onDismiss = { showNewSheet = false })
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

/** 抽屉行（真机反馈⑤-5）：All/Agent 选择项，tint 头像 + 名称 + 会话数；编辑入口进 Agent 域页。 */
@Composable
private fun DrawerAgentRow(
    name: String,
    subtitle: String,
    tintName: dev.sharpxia.agentreins.ui.theme.AgentTintName?,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .sandPressFill(interaction, 0)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        when (tintName) {
            null -> {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(InkTheme.colors.surfaceTertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Home,
                        contentDescription = null,
                        tint = InkTheme.colors.ink,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            else -> dev.sharpxia.agentreins.ui.theme.AgentTintScope(tintName) {
                AgentAvatar(LocalAgentTint.current, name)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                name,
                style = if (selected) InkTheme.type.headingXs else InkTheme.type.textLg,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = InkTheme.colors.ink,
            )
            Text(subtitle, style = InkTheme.type.textXs, color = InkTheme.colors.textTertiary)
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选",
                tint = InkTheme.colors.ink,
                modifier = Modifier.size(16.dp),
            )
        }
        if (onEdit != null) {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑 Agent",
                    tint = InkTheme.colors.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
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
        val interaction = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .sandPressFill(interaction, 0)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                )
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
                        // weight 占满余量、时间钉右缘（review R2-P1-1）
                        modifier = Modifier.weight(1f),
                    )
                    formatRelativeTime(session.lastActiveAtMs).takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = InkTheme.type.textXs,
                            color = c.textTertiary,
                            modifier = Modifier.padding(start = 8.dp),
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
                        modifier = Modifier.weight(1f),
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
