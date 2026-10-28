package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
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
    val totalUnread = model.sessions.sumOf { it.unread }
    // 展开菜单的行（真机反馈⑦-3）：同一时刻至多一行展开；滚动/他处触摸即收起
    var revealedRowId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) revealedRowId = null
    }
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
                modifier = Modifier.fillMaxHeight(),
            ) {
                // 内容置顶（真机反馈⑥-3）：显式顶部锚定 + 可滚动，铺满抽屉高度
                Column(
                    Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
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
                            // ＋ 即建（真机反馈⑥-1）：新建走抽屉内联，不再经 ModalBottomSheet
                            onCreate = {
                                haptics.tap()
                                model.createNewSession(s.profile.agentId)
                                scope.launch { drawerState.close() }
                            },
                            creating = model.creatingSession,
                        )
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
            ) {
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
                // Agent 头像（真机反馈⑥-4）：Menu 右侧区域最左，点击进 Agent 主页配置
                if (agentFilter != null && selectedProfile != null) {
                    AgentTintScope(selectedProfile.tint) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clickable { onOpenAgent(selectedProfile.agentId) },
                            contentAlignment = Alignment.Center,
                        ) {
                            AgentAvatar(LocalAgentTint.current, selectedProfile.name, size = 34)
                        }
                    }
                    Spacer(Modifier.size(2.dp))
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
                // 状态栏回顶 Phase-1 诊断（AGENTREINS_DEV_SCROLLPROBE=1）：按钮直调 scrollToItem(0)，验证 Compose 侧滚动控制
                if (model.devScrollProbe) {
                    Text(
                        "⤒回顶测试",
                        style = InkTheme.type.textSm,
                        color = InkTheme.colors.textTertiary,
                        modifier = Modifier
                            .clickable {
                                println("AgentReins[P1] test button -> listState.scrollToItem(0)")
                                scope.launch { listState.scrollToItem(0) }
                            }
                            .padding(horizontal = 6.dp),
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
            // 新建会话（真机反馈⑦-5）：＋ 弹 agent 选择列表——首项为当前过滤的 agent，
            // 点击即创建并直达；DropdownMenu 为真机已验证的弹层（消息长按菜单同款）
            Box {
                var pickerOpen by remember { mutableStateOf(false) }
                Text(
                    "＋",
                    style = InkTheme.type.headingSm,
                    color = InkTheme.colors.ink,
                    modifier = Modifier
                        .clickable { haptics.tap(); pickerOpen = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = pickerOpen,
                    onDismissRequest = { pickerOpen = false },
                    containerColor = InkTheme.colors.elevated,
                ) {
                    val options = buildList {
                        agentFilter?.let { cur ->
                            // 过滤 agent 可能不在 summaries（可见会话全归档）：合成首项保证
                            // 「在当前 agent 新建」始终可得
                            val summary = summaries.firstOrNull { it.profile.agentId == cur }
                                ?: dev.sharpxia.agentreins.AgentSummary(
                                    profile = model.agentProfiles[cur]
                                        ?: dev.sharpxia.agentreins.AgentProfile.default(cur),
                                    sessionCount = 0,
                                    unreadTotal = 0,
                                    lastActiveAtMs = null,
                                    mainSessionKey = null,
                                )
                            add(summary to true)
                        }
                        summaries.forEach { s ->
                            if (agentFilter == null || s.profile.agentId != agentFilter) add(s to false)
                        }
                    }
                    if (options.isEmpty()) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("尚无 agent", style = InkTheme.type.textMd, color = InkTheme.colors.textTertiary) },
                            onClick = {},
                        )
                    }
                    options.forEach { (s, first) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        if (first) "在「" + s.profile.name + "」新建会话" else s.profile.name,
                                        style = InkTheme.type.textLg,
                                        color = InkTheme.colors.ink,
                                    )
                                    Text(
                                        "${s.sessionCount} 个会话",
                                        style = InkTheme.type.textXs,
                                        color = InkTheme.colors.textTertiary,
                                    )
                                }
                            },
                            leadingIcon = {
                                AgentTintScope(s.profile.tint) {
                                    AgentAvatar(LocalAgentTint.current, s.profile.name, size = 30)
                                }
                            },
                            onClick = {
                                pickerOpen = false
                                model.createNewSession(s.profile.agentId)
                            },
                        )
                    }
                }
            }
            TextButton(onClick = { showStash = !showStash }) {
                Text(
                    if (showStash) "返回" else "收纳(${archived.size + hidden.size})",
                    style = InkTheme.type.textMd,
                    color = if (archived.isEmpty() && hidden.isEmpty()) InkTheme.colors.textDisabled else InkTheme.colors.ink,
                )
            }
        }
        // 错误可见面（真机反馈⑥-1）：新建会话等 RPC 失败不再静默，点击横幅清除
        model.lastError?.let { err ->
            Surface(
                color = InkTheme.colors.danger.subtle,
                shape = RoundedCornerShape(InkTheme.shapes.md.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { model.lastError = null },
            ) {
                Text(
                    err,
                    style = InkTheme.type.textSm,
                    color = InkTheme.colors.danger.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        LazyColumn(state = listState) {
            if (showStash) {
                if (archived.isNotEmpty()) {
                    item(key = "hdr-archived") { SectionHeader("归档") }
                    items(archived, key = { "a-${it.id}" }) { s ->
                        SwipeRevealRow(model, s, onOpenSession, haptics, revealed = revealedRowId == s.id, onRevealedChange = { r -> revealedRowId = if (r) s.id else null })
                    }
                }
                if (hidden.isNotEmpty()) {
                    item(key = "hdr-hidden") { SectionHeader("已隐藏") }
                    items(hidden, key = { "h-${it.id}" }) { s ->
                        SwipeRevealRow(model, s, onOpenSession, haptics, revealed = revealedRowId == s.id, onRevealedChange = { r -> revealedRowId = if (r) s.id else null })
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
                    SwipeRevealRow(model, s, onOpenSession, haptics, revealed = revealedRowId == s.id, onRevealedChange = { r -> revealedRowId = if (r) s.id else null })
                }
            }
        }
        }
    }
}

/**
 * 会话行滑动操作（真机反馈⑦-3 定稿）：仅「从右向左」滑展开操作菜单（置顶/归档 或
 * 恢复/隐藏），过半吸附，二次点击执行；**右向手势（含左缘起点）不消费——让位抽屉
 * 边缘手势**（方向感知自绘裁决，不用 detectHorizontalDragGestures——它吃掉双向
 * slop 导致抽屉左滑被屏蔽）；展开后任意非按钮触摸/滚动即收起（revealedRowId 钩子）。
 */
@Composable
private fun SwipeRevealRow(
    model: AppModel,
    session: Session,
    onOpenSession: (String) -> Unit,
    haptics: HapticPlayer,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val revealPx = with(density) { 128.dp.toPx() }
    val stash = session.archived || session.hidden
    val scope = rememberCoroutineScope()
    val currentSession by rememberUpdatedState(session)
    var offsetX by remember(session.id) { mutableStateOf(0f) }

    // 收起态同步：外部（滚动/他行展开/抽屉）判定收起时回弹归零
    LaunchedEffect(revealed) {
        if (!revealed && offsetX != 0f) {
            val from = offsetX
            androidx.compose.animation.core.animate(
                initialValue = from,
                targetValue = 0f,
                animationSpec = androidx.compose.animation.core.tween(160),
            ) { v, _ -> offsetX = v }
        }
    }
    fun settle() {
        val open = offsetX < -revealPx / 2
        onRevealedChange(open)
        val target = if (open) -revealPx else 0f
        val from = offsetX
        scope.launch {
            androidx.compose.animation.core.animate(
                initialValue = from,
                targetValue = target,
                animationSpec = androidx.compose.animation.core.tween(180),
            ) { v, _ -> offsetX = v }
        }
    }
    Box(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxSize(),
        ) {
            SwipeActionButton(
                label = when {
                    !stash && currentSession.pinned -> "取消置顶"
                    !stash -> "置顶"
                    else -> "恢复"
                },
                icon = if (!stash && currentSession.pinned) ArIcons.Pin else if (stash) ArIcons.Unarchive else ArIcons.Pin,
                tint = InkTheme.colors.success,
                onClick = {
                    haptics.success()
                    when {
                        !stash -> model.setSessionPinned(currentSession, !currentSession.pinned)
                        currentSession.archived -> model.setSessionArchived(currentSession, false)
                        else -> model.setSessionHidden(currentSession, false)
                    }
                    onRevealedChange(false)
                },
            )
            SwipeActionButton(
                label = when {
                    stash -> if (currentSession.hidden) "取消隐藏" else "隐藏"
                    else -> "归档"
                },
                icon = if (stash) ArIcons.EyeOff else ArIcons.Archive,
                tint = if (stash && currentSession.hidden) InkTheme.colors.success else InkTheme.colors.warning,
                onClick = {
                    haptics.tap()
                    when {
                        stash -> model.setSessionHidden(currentSession, !currentSession.hidden)
                        else -> model.setSessionArchived(currentSession, true)
                    }
                    onRevealedChange(false)
                },
            )
        }
        Surface(
            color = InkTheme.colors.paper,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offsetX }
                .pointerInput(session.id, revealPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        var total = 0f
                        var engaged = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.any { it.isConsumed }) return@awaitEachGesture // 他手势赢了
                            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                            if (!change.pressed) return@awaitEachGesture
                            total += change.position.x - change.previousPosition.x
                            if (kotlin.math.abs(total) > viewConfiguration.touchSlop) {
                                if (total > 0f) return@awaitEachGesture // 右向：不消费，让位抽屉
                                engaged = true
                                change.consume()
                                break
                            }
                        }
                        while (engaged) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - change.previousPosition.x
                            if (dx != 0f) {
                                offsetX = (offsetX + dx).coerceIn(-revealPx, 0f)
                                change.consume()
                            }
                        }
                        settle()
                    }
                },
        ) {
            SessionRowContent(
                model = model,
                session = currentSession,
                stash = stash,
                onClick = {
                    if (revealed) onRevealedChange(false) // 展开时点行=先收起，不进会话
                    else onOpenSession(session.id)
                },
            )
        }
    }
}

/** 展开菜单按钮（二次点击执行）。 */
@Composable
private fun SwipeActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: dev.sharpxia.agentreins.ui.theme.FuncColors,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .width(60.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint.text, modifier = Modifier.size(20.dp))
        Text(label, style = InkTheme.type.textXs, color = tint.text, maxLines = 1)
    }
}

/** 抽屉行（真机反馈⑤-5/⑥-1）：All/Agent 选择 + ＋ 内联新建 + ✎ 编辑进 Agent 域页。 */
@Composable
private fun DrawerAgentRow(
    name: String,
    subtitle: String,
    tintName: AgentTintName?,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onCreate: (() -> Unit)? = null,
    creating: Boolean = false,
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
            else -> AgentTintScope(tintName) {
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
        if (onCreate != null) {
            if (creating) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = InkTheme.colors.awaiting,
                )
            } else {
                IconButton(onClick = onCreate, modifier = Modifier.size(32.dp)) {
                    Text("＋", style = InkTheme.type.headingSm, color = InkTheme.colors.ink)
                }
            }
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
            // 头像用 agent 身份（真机反馈⑦-1）：名称取 agent 注册名，非会话标题首字
            val agentName = model.agentProfiles[agentId]?.name ?: agentId ?: session.title ?: session.id
            AgentAvatar(tint, agentName)
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
