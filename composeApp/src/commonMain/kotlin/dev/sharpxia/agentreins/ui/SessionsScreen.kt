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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import dev.sharpxia.agentreins.openclaw.Conversation
import dev.sharpxia.agentreins.openclaw.ConversationKind
import dev.sharpxia.agentreins.openclaw.SessionCategory
import dev.sharpxia.agentreins.openclaw.SessionClassifier
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
 * 会话首页（M4 P2 Conversation-first 重构）：
 * - 行 = conversation（1 agent ↔ 1 主线线程，承载其全部主 session；渠道直连为独立线程）
 * - 行内容：agent tint 头像 + agent 显示名（+ 渠道徽标）+ 预览 + 相对时间 + 未读徽标 + Pin
 * - 操作：右滑置顶/归档（conversation 级 overlay），长按菜单含隐藏；收纳视图 = 归档+隐藏
 * - 派生（subagent）/系统/未识别会话不进首页——治理入口在 agent 主页 sessions list
 * 断网可见缓存派生的列表（M2.5 语义不变）。
 */
@Composable
fun SessionsScreen(
    model: AppModel,
    onOpenConversation: (String) -> Unit,
    onOpenAgent: (String) -> Unit = {},
    onCreateFor: (String) -> Unit = {},
    onOpenTask: (String) -> Unit = {},
) {
    if (model.conversations.isEmpty()) {
        ConnectionEmptyState(model)
    } else {
        ConversationList(
            model = model,
            offline = model.status !is GatewayStatus.Connected,
            onOpenConversation = onOpenConversation,
            onOpenAgent = onOpenAgent,
            onCreateFor = onCreateFor,
            onOpenTask = onOpenTask,
        )
    }
}

@Composable
private fun ConversationList(
    model: AppModel,
    offline: Boolean,
    onOpenConversation: (String) -> Unit,
    onOpenAgent: (String) -> Unit,
    onCreateFor: (String) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHapticPlayer()
    var showStash by remember { mutableStateOf(false) } // 收纳视图：归档 + 已隐藏
    val totalUnread = model.conversations.sumOf { it.unread }
    // 展开菜单的行：同一时刻至多一行展开；滚动/他处触摸即收起
    var revealedRowId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) revealedRowId = null
    }
    // M4 反馈③：首页只列当前 app 渠道的 conversation（每 agent 恰一个）；渠道对话
    // 仅在 agent 编辑页可见。每 (agent × channel) 恰一个 conversation（反馈①）。
    val isAppConv: (Conversation) -> Boolean = { it.kind == ConversationKind.MAINLINE }
    val mainConvs = remember(model.conversations) {
        model.conversations.filter { isAppConv(it) && !it.hidden && !it.archived }
    }
    val archived = remember(model.conversations) {
        model.conversations.filter { isAppConv(it) && it.archived && !it.hidden }
    }
    val hidden = remember(model.conversations) {
        model.conversations.filter { isAppConv(it) && it.hidden }
    }
    val summaries = remember(model.sessions, model.agentProfiles) {
        dev.sharpxia.agentreins.AgentRegistry.summarize(model.sessions, model.agentProfiles)
    }
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed,
    )

    StatusBarTapEffect {
        if (mainConvs.isNotEmpty()) {
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
                // 内容置顶：显式顶部锚定 + 可滚动，铺满抽屉高度
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
                    // M4 反馈②：点行或点头像同一去处——该 agent 在本 app 的 conversation
                    summaries.forEach { s ->
                        DrawerAgentRow(
                            name = s.profile.name,
                            subtitle = "${s.sessionCount} 个会话",
                            tintName = s.profile.tint,
                            onClick = {
                                haptics.tap()
                                scope.launch { drawerState.close() }
                                val convId = model.conversationIdForAgent(s.profile.agentId)
                                if (convId != null) onOpenConversation(convId) else onCreateFor(s.profile.agentId)
                            },
                            onEdit = {
                                haptics.tap()
                                scope.launch { drawerState.close() }
                                onOpenAgent(s.profile.agentId)
                            },
                            // ＋ = 进入对话（无主线会话则创建首个主 session）
                            onCreate = {
                                haptics.tap()
                                scope.launch { drawerState.close() }
                                val convId = model.conversationIdForAgent(s.profile.agentId)
                                if (convId != null) onOpenConversation(convId) else onCreateFor(s.profile.agentId)
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
                // Agent 头像（筛选区已移除——M4 反馈②：抽屉是唯一 agent 入口）
                Column(Modifier.weight(1f)) {
                    Text(
                        if (showStash) "收纳" else "会话",
                        style = InkTheme.type.headingMd,
                        color = InkTheme.colors.ink,
                    )
                    Text(
                        when {
                            showStash -> "归档 ${archived.size} · 隐藏 ${hidden.size}"
                            offline -> "离线中 · 显示缓存列表"
                            else -> "${mainConvs.size} 个对话"
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
                    color = InkTheme.colors.ink,
                    modifier = Modifier
                        .clickable { haptics.tap(); model.markAllRead() }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            // ＋ 选 agent 进对话（无主线会话的 agent 直接创建首个主 session）
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
                    val options = summaries
                    if (options.isEmpty()) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("尚无 agent", style = InkTheme.type.textMd, color = InkTheme.colors.textTertiary) },
                            onClick = {},
                        )
                    }
                    options.forEach { s ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        s.profile.name,
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
                                val convId = model.conversationIdForAgent(s.profile.agentId)
                                if (convId != null) onOpenConversation(convId) else onCreateFor(s.profile.agentId)
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
                    items(archived, key = { "a-${it.id}" }) { c ->
                        SwipeRevealRow(model, c, onOpenConversation, haptics, revealed = revealedRowId == c.id, onRevealedChange = { r -> revealedRowId = if (r) c.id else null })
                    }
                }
                if (hidden.isNotEmpty()) {
                    item(key = "hdr-hidden") { SectionHeader("已隐藏") }
                    items(hidden, key = { "h-${it.id}" }) { c ->
                        SwipeRevealRow(model, c, onOpenConversation, haptics, revealed = revealedRowId == c.id, onRevealedChange = { r -> revealedRowId = if (r) c.id else null })
                    }
                }
                if (archived.isEmpty() && hidden.isEmpty()) {
                    item(key = "stash-empty") {
                        Text(
                            "没有归档或隐藏的对话",
                            style = InkTheme.type.textMd,
                            color = InkTheme.colors.textTertiary,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            } else {
                items(mainConvs, key = { it.id }) { c ->
                    SwipeRevealRow(model, c, onOpenConversation, haptics, revealed = revealedRowId == c.id, onRevealedChange = { r -> revealedRowId = if (r) c.id else null }, onOpenTask = onOpenTask)
                }
            }
        }
        }
    }
}

/**
 * conversation 行滑动操作（沿用真机打磨过的方向裁决）：仅「从右向左」滑展开操作菜单
 * （置顶/归档 或 恢复/隐藏），过半吸附，二次点击执行；右向手势（含左缘起点）不消费——
 * 让位抽屉边缘手势；展开后任意非按钮触摸/滚动即收起。
 */
@Composable
private fun SwipeRevealRow(
    model: AppModel,
    conv: Conversation,
    onOpenConversation: (String) -> Unit,
    haptics: HapticPlayer,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onOpenTask: (String) -> Unit = {},
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val revealPx = with(density) { 128.dp.toPx() }
    val stash = conv.archived || conv.hidden
    val scope = rememberCoroutineScope()
    val currentConv by rememberUpdatedState(conv)
    var offsetX by remember(conv.id) { mutableStateOf(0f) }

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
                    !stash && currentConv.pinned -> "取消置顶"
                    !stash -> "置顶"
                    else -> "恢复"
                },
                icon = if (!stash && currentConv.pinned) ArIcons.Pin else if (stash) ArIcons.Unarchive else ArIcons.Pin,
                tint = InkTheme.colors.success,
                onClick = {
                    haptics.success()
                    when {
                        !stash -> model.setConversationPinned(currentConv, !currentConv.pinned)
                        currentConv.archived -> model.setConversationArchived(currentConv, false)
                        else -> model.setConversationHidden(currentConv, false)
                    }
                    onRevealedChange(false)
                },
            )
            SwipeActionButton(
                label = when {
                    stash -> if (currentConv.hidden) "取消隐藏" else "隐藏"
                    else -> "归档"
                },
                icon = if (stash) ArIcons.EyeOff else ArIcons.Archive,
                tint = if (stash && currentConv.hidden) InkTheme.colors.success else InkTheme.colors.warning,
                onClick = {
                    haptics.tap()
                    when {
                        stash -> model.setConversationHidden(currentConv, !currentConv.hidden)
                        else -> model.setConversationArchived(currentConv, true)
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
                .pointerInput(conv.id, revealPx) {
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
            ConversationRowContent(
                model = model,
                conv = currentConv,
                stash = stash,
                onClick = {
                    if (revealed) onRevealedChange(false) // 展开时点行=先收起，不进对话
                    else onOpenConversation(currentConv.id)
                },
                onOpenTask = onOpenTask,
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

/** 抽屉行（M4 反馈②）：整行点击 = 进该 agent 的 app 对话；＋ 同义；✎ = 编辑页。 */
@Composable
private fun DrawerAgentRow(
    name: String,
    subtitle: String,
    tintName: AgentTintName?,
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
                style = InkTheme.type.textLg,
                fontWeight = FontWeight.Medium,
                color = InkTheme.colors.ink,
            )
            Text(subtitle, style = InkTheme.type.textXs, color = InkTheme.colors.textTertiary)
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
private fun ConversationRowContent(
    model: AppModel,
    conv: Conversation,
    stash: Boolean,
    onClick: () -> Unit,
    onOpenTask: (String) -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    // 任务详情 Sheet（M4 P5：点「已派发 N」→ 在途子任务清单 → 只读浏览）
    var taskSheet by remember { mutableStateOf(false) }
    val tintName = model.agentProfiles[conv.agentId]?.tint
        ?: dev.sharpxia.agentreins.ui.theme.agentTintFor(conv.agentId)
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
            val agentName = model.agentProfiles[conv.agentId]?.name ?: conv.agentId
            AgentAvatar(tint, agentName)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conv.pinned && !stash) {
                        Icon(
                            ArIcons.Pin,
                            contentDescription = "已置顶",
                            tint = tint.strong,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                    }
                    if (conv.hidden) {
                        Icon(
                            ArIcons.EyeOff,
                            contentDescription = "已隐藏",
                            tint = c.textTertiary,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.size(5.dp))
                    }
                    Text(
                        agentName,
                        style = InkTheme.type.textLg,
                        fontWeight = if (conv.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // weight 占满余量、时间钉右缘
                        modifier = Modifier.weight(1f),
                    )
                    val channelBadge = if (conv.kind == ConversationKind.CHANNEL) conv.channel else null
                    if (channelBadge != null) {
                        ChannelBadge(channelBadge)
                        Spacer(Modifier.size(6.dp))
                    }
                    formatRelativeTime(conv.lastActiveAtMs).takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = InkTheme.type.textXs,
                            color = c.textTertiary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 行内执行状态（M4 P5）：TG 语义——执行中替换 preview 行；
                    // 已派发子任务数（在途 subagent 会话）可点开任务详情
                    val (running, derived) = model.conversationRunStatus(conv)
                    if (running && !stash) {
                        TypingDots()
                        Spacer(Modifier.size(6.dp))
                        Text(
                            if (derived > 0) "执行中 · 已派发 $derived 个子任务" else "执行中",
                            style = InkTheme.type.textSm,
                            color = c.awaiting,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (derived > 0) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "详情",
                                style = InkTheme.type.textXs,
                                color = c.info.text,
                                modifier = Modifier
                                    .clickable { taskSheet = true }
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        Text(
                            conv.preview ?: conv.id,
                            style = InkTheme.type.textSm,
                            color = c.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (conv.unread > 0) {
                        Spacer(Modifier.size(8.dp))
                        UnreadBadge(conv.unread)
                    }
                }
            }
            ConversationRowMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                conv = conv,
                stash = stash,
                model = model,
            )
        }
    }
    if (taskSheet) {
        TaskDetailSheet(
            model = model,
            conv = conv,
            onDismiss = { taskSheet = false },
            onOpen = { key -> taskSheet = false; onOpenTask(key) },
        )
    }
}

/** 任务详情（M4 P5 D5）：在途派生会话清单 → 点击只读浏览该 session 的 history（不输入、不计未读）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TaskDetailSheet(
    model: AppModel,
    conv: Conversation,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val tasks = model.activeRunKeys
        .filter {
            SessionClassifier.classify(it) == SessionCategory.DERIVED &&
                dev.sharpxia.agentreins.model.sessionKeyAgentId(it) == conv.agentId
        }
        .sorted()
    SandSheet(onDismiss = onDismiss, title = "派发中的子任务 · ${tasks.size}") {
        if (tasks.isEmpty()) {
            Text(
                "当前没有进行中的子任务",
                style = InkTheme.type.textMd,
                color = InkTheme.colors.textTertiary,
                modifier = Modifier.padding(20.dp),
            )
        }
        tasks.forEach { key ->
            val interaction = remember { MutableInteractionSource() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .sandPressFill(interaction, 0)
                    .clickable(interactionSource = interaction, indication = null, onClick = { onOpen(key) })
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "subagent · ${key.substringAfterLast(':').take(13)}…",
                        style = InkTheme.type.textMd,
                        color = InkTheme.colors.ink,
                    )
                    Text(
                        "派生的后台任务会话",
                        style = InkTheme.type.textXs,
                        color = InkTheme.colors.textTertiary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingDots()
                    Spacer(Modifier.size(6.dp))
                    Text("执行中", style = InkTheme.type.textSm, color = InkTheme.colors.awaiting)
                }
            }
        }
        Text(
            "点击进入只读浏览（不发消息、不计未读）",
            style = InkTheme.type.textXs,
            color = InkTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

/** 渠道徽标（CHANNEL 线程的身份标注）。 */
@Composable
private fun ChannelBadge(channel: String) {
    Text(
        channel,
        style = InkTheme.type.textXs,
        color = InkTheme.colors.textTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(InkTheme.colors.surfaceTertiary)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** 长按菜单：置顶 / 归档 / 隐藏（收纳视图里为对应反向操作）——conversation 级 overlay。 */
@Composable
private fun ConversationRowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    conv: Conversation,
    stash: Boolean,
    model: AppModel,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (!stash) {
            DropdownMenuItem(
                text = { Text(if (conv.pinned) "取消置顶" else "置顶") },
                leadingIcon = { Icon(ArIcons.Pin, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { model.setConversationPinned(conv, !conv.pinned); onDismiss() },
            )
        }
        DropdownMenuItem(
            text = { Text(if (conv.archived) "取消归档" else "归档") },
            leadingIcon = {
                Icon(
                    if (conv.archived) ArIcons.Unarchive else ArIcons.Archive,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { model.setConversationArchived(conv, !conv.archived); onDismiss() },
        )
        DropdownMenuItem(
            text = { Text(if (conv.hidden) "取消隐藏" else "隐藏") },
            leadingIcon = {
                Icon(
                    if (conv.hidden) ArIcons.Unarchive else ArIcons.EyeOff,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
            onClick = { model.setConversationHidden(conv, !conv.hidden); onDismiss() },
        )
    }
}
