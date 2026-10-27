package dev.sharpxia.agentreins.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import dev.sharpxia.agentreins.openclaw.Outbox
import dev.sharpxia.agentreins.ui.components.InkMarkdown
import dev.sharpxia.agentreins.ui.components.SandSheet
import dev.sharpxia.agentreins.ui.components.SandSheetAction
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.AgentTintScope
import dev.sharpxia.agentreins.ui.theme.InkTheme
import dev.sharpxia.agentreins.ui.theme.LocalAgentTint
import dev.sharpxia.agentreins.ui.theme.agentTintFor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 对话流（M3.3 墨水风重绘）：日分隔、助手墨水平文（Markdown v2 表格/嵌套/高亮）、
 * 用户 tint 气泡、流式 shimmer、消息入场/高度过渡、长按菜单、outbox 待发气泡。
 * 滚动定位/分页/键盘逻辑沿用 M2.6（真机打磨过的行为不动）。
 */
@Composable
internal fun ChatScreen(
    model: AppModel,
    sessionKey: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    val state = model.conversation
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHapticPlayer()
    val clipboard = LocalClipboardManager.current
    var initialScrollDone by remember { mutableStateOf(false) }
    var showJumpToLatest by remember { mutableStateOf(false) }

    val rows = remember(state?.entries) { buildChatRows(state?.entries ?: emptyList()) }
    val pending = model.pendingOutbox
    val contentRowCount = rows.size + pending.size
    val lastTextLen = (state?.entries?.lastOrNull() as? Message)?.content?.length ?: 0
    val runActive = state?.activeRunId != null
    val totalRows = contentRowCount +
        (if (state?.hasMoreHistory == true && contentRowCount > 0) 1 else 0) +
        (if (contentRowCount == 0 && !runActive) 1 else 0) +
        (if (runActive) 1 else 0)
    LaunchedEffect(totalRows, contentRowCount, lastTextLen, runActive) {
        if (contentRowCount == 0) return@LaunchedEffect
        if (!initialScrollDone) {
            listState.scrollToItem(totalRows - 1)
            initialScrollDone = true
            return@LaunchedEffect
        }
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= totalRows - 3) {
            listState.animateScrollToItem(totalRows - 1)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible < info.totalItemsCount - 2
        }.collect { showJumpToLatest = it }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }.collect { atTop -> if (atTop) model.loadOlderMessages() }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if (scrolling) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
        }
    }
    StatusBarTapEffect {
        if (contentRowCount > 0) scope.launch { listState.animateScrollToItem(0) }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(InkTheme.colors.paper)
            .dismissKeyboardOnTap(),
    ) {
        var showStartFresh by remember { mutableStateOf(false) }
        ChatTopBar(model, sessionKey, runActive, onBack, onShowStartFresh = { showStartFresh = true })
        if (showStartFresh) {
            StartFreshDialog(
                onConfirm = {
                    showStartFresh = false
                    model.startFresh()
                },
                onDismiss = { showStartFresh = false },
            )
        }
        if (model.queuedCount > 0) {
            QueuedBanner(count = model.queuedCount, onRetry = { haptics.tap(); model.retryQueuedNow() })
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .alpha(if (contentRowCount > 0 && !initialScrollDone) 0f else 1f),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state?.hasMoreHistory == true && contentRowCount > 0) {
                    item(key = "loading-older") {
                        Text(
                            "上拉加载更早消息…",
                            style = InkTheme.type.textXs,
                            color = InkTheme.colors.textTertiary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
                if (contentRowCount == 0 && !runActive) {
                    item(key = "empty") {
                        Text(
                            "暂无消息，发一条开始对话",
                            style = InkTheme.type.textSm,
                            color = InkTheme.colors.textTertiary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
                items(rows, key = { row ->
                    when (row) {
                        is ChatRow.DayHeader -> "day-${row.atMs}"
                        is ChatRow.Entry -> row.entry.id
                    }
                }) { row ->
                    // 新消息入场：淡入 + 轻微上移（M3.3；animateItem 处理重排/新行）
                    @OptIn(ExperimentalFoundationApi::class)
                    (Modifier.animateItem())
                    when (row) {
                        is ChatRow.DayHeader -> DayHeaderRow(row.atMs)
                        is ChatRow.Entry -> when (val entry = row.entry) {
                            is ToolCallEvent -> ToolCallCard(entry)
                            is Message -> ChatMessageItem(
                                model = model,
                                message = entry,
                                onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                                onResend = { text -> haptics.tap(); model.resendText(text) },
                            )
                        }
                    }
                }
                items(pending, key = { "outbox-${it.idempotencyKey}" }) { cmd ->
                    PendingOutboxItem(
                        cmd = cmd,
                        onRetry = { haptics.tap(); model.retryQueuedNow() },
                        onDelete = { haptics.tap(); model.deleteQueued(cmd) },
                    )
                }
                if (runActive) {
                    item(key = "typing") { TypingIndicator(state?.thinkingText) }
                }
            }
            if (showJumpToLatest) {
                Surface(
                    onClick = {
                        haptics.tap()
                        if (totalRows > 0) scope.launch { listState.animateScrollToItem(totalRows - 1) }
                    },
                    shape = CircleShape,
                    color = InkTheme.colors.elevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkTheme.colors.borderPrimary),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "查看最新消息",
                        tint = InkTheme.colors.ink,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
            }
        }

        ChatInputBar(
            model = model,
            haptics = haptics,
            modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
        )
    }
}

@Composable
private fun ChatTopBar(
    model: AppModel,
    sessionKey: String,
    runActive: Boolean,
    onBack: () -> Unit,
    onShowStartFresh: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = InkTheme.colors.paper) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    model.select(null)
                    onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = InkTheme.colors.ink,
                    )
                }
                Column(Modifier.weight(1f)) {
                    val session = model.sessions.firstOrNull { it.id == sessionKey }
                    Text(
                        session?.title ?: sessionKey,
                        style = InkTheme.type.headingSm,
                        color = InkTheme.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        session?.kind?.takeIf { it.isNotBlank() } ?: sessionKey,
                        style = InkTheme.type.textXs,
                        color = InkTheme.colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "会话菜单",
                            tint = InkTheme.colors.textSecondary,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("从头开始", style = InkTheme.type.textMd) },
                            leadingIcon = {
                                Icon(ArIcons.Retry, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = { menuOpen = false; onShowStartFresh() },
                        )
                    }
                }
            }
            if (runActive) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 52.dp, bottom = 6.dp),
                ) {
                    TypingDots()
                    Spacer(Modifier.size(8.dp))
                    Text(
                        "生成中…",
                        style = InkTheme.type.textXs,
                        color = InkTheme.colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuedBanner(count: Int, onRetry: () -> Unit) {
    val c = InkTheme.colors
    Surface(color = c.warning.subtle, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                "$count 条消息待发送，重连后自动补发",
                style = InkTheme.type.textSm,
                color = c.warning.text,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("立即重试", style = InkTheme.type.textSm, color = c.warning.text)
            }
        }
    }
}

@Composable
private fun DayHeaderRow(atMs: Long) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            dayHeaderLabel(atMs),
            style = InkTheme.type.textXs,
            color = InkTheme.colors.textTertiary,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageItem(
    model: AppModel,
    message: Message,
    onCopy: (String) -> Unit,
    onResend: (String) -> Unit,
) {
    // agent 身份色作用域：会话的 agent tint 派生用户气泡与强调（BotTintScope 等价）
    val session = model.sessions.firstOrNull { it.id == message.sessionId }
    val agentId = session?.agentId ?: sessionKeyAgentId(message.sessionId)
    val tintName = model.agentProfiles[agentId]?.tint
        ?: agentId?.let { agentTintFor(it) }
        ?: AgentTintName.GRAY
    AgentTintScope(tintName) {
        when (message.role) {
            Message.Role.USER -> UserBubble(model, message, onCopy, onResend)
            // 助手消息：墨水平文无气泡；流式中 shimmer，定型后 Markdown v2。
            // 长按区贴内容（全宽 clickable 吞点击的坑，真机实锤）
            Message.Role.ASSISTANT -> {
                var menuOpen by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier
                            .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                            .animateContentSize(),
                    ) {
                        if (message.streaming) {
                            ShimmerStreamingText(message.content)
                        } else if (message.content.isNotBlank()) {
                            InkMarkdown(message.content)
                        }
                        if (menuOpen) {
                            MessageActionsSheet(
                                message = message,
                                onDismiss = { menuOpen = false },
                                onCopy = onCopy,
                                onResend = onResend,
                                onQuote = { model.quoteMessage(it) },
                                onFork = { model.forkFrom(it) },
                            )
                        }
                    }
                }
            }
            // agent 的 commentary（item 流 kind=preamble）：过程说明行内注
            Message.Role.SYSTEM -> Text(
                message.content,
                style = InkTheme.type.textSm,
                fontStyle = FontStyle.Italic,
                color = InkTheme.colors.textTertiary,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
    }
}

/** 用户 tint 气泡：bubble 底 + 墨水文本 + 右上小圆角（Grok bubbleUser 形态）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    model: AppModel,
    message: Message,
    onCopy: (String) -> Unit,
    onResend: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val tint = LocalAgentTint.current
    val c = InkTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        BoxWithConstraints {
            Surface(
                color = tint.bubble,
                shape = RoundedCornerShape(InkTheme.shapes.lg.dp, 5.dp, InkTheme.shapes.lg.dp, InkTheme.shapes.lg.dp),
            ) {
                Text(
                    message.content,
                    style = InkTheme.type.textLg,
                    color = c.ink,
                    modifier = Modifier
                        .widthIn(max = maxWidth * 0.82f)
                        .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
            if (menuOpen) {
                MessageActionsSheet(
                    message = message,
                    onDismiss = { menuOpen = false },
                    onCopy = onCopy,
                    onResend = onResend,
                    onQuote = { model.quoteMessage(it) },
                    onFork = { model.forkFrom(it) },
                )
            }
        }
    }
}

/** 长按 MessageActionsSheet（M3.4）：复制 / 引用 / 重发(用户) / 分支(助手)——Sheet 承载二级操作。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: Message,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onResend: (String) -> Unit,
    onQuote: (String) -> Unit,
    onFork: (Message) -> Unit,
) {
    SandSheet(
        onDismiss = onDismiss,
        title = "消息操作",
    ) {
        SandSheetAction("复制", icon = ArIcons.Copy, onClick = { onCopy(message.content); onDismiss() })
        SandSheetAction("引用", icon = ArIcons.Reply, onClick = { onQuote(message.content); onDismiss() })
        if (message.role == Message.Role.USER) {
            SandSheetAction("重发", icon = ArIcons.Retry, onClick = { onResend(message.content); onDismiss() })
            // fork 锚点必须是 user entry（网关实测）；分支挂在提问消息上
            SandSheetAction(
                "分支（从这条重新开话）",
                icon = ArIcons.Branch,
                onClick = { onFork(message); onDismiss() },
            )
        }
    }
}

/** 重置语义确认弹窗：对齐「丢什么/留什么」结构（自写文案，不抄原文）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StartFreshDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = InkTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从头开始？", style = InkTheme.type.headingSm, color = c.ink) },
        text = {
            Column {
                Text(
                    "这个会话的聊天记录将不再出现在新会话里。",
                    style = InkTheme.type.textMd,
                    color = c.textSecondary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "✓ 会保留：Agent 的指令、记忆与技能配置\n✗ 会留在旧会话：本次对话的全部消息",
                    style = InkTheme.type.textSm,
                    color = c.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("开新会话", color = c.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textTertiary)
            }
        },
        containerColor = c.elevated,
    )
}

/** outbox 待发气泡：灰阶弱化 + 「未发出」标记 + 重试/删除。 */
@Composable
private fun PendingOutboxItem(cmd: Outbox.Command, onRetry: () -> Unit, onDelete: () -> Unit) {
    val tint = LocalAgentTint.current
    val c = InkTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = tint.bubble.copy(alpha = tint.bubble.alpha * 0.55f),
                shape = RoundedCornerShape(InkTheme.shapes.lg.dp, 5.dp, InkTheme.shapes.lg.dp, InkTheme.shapes.lg.dp),
            ) {
                Text(
                    cmd.text,
                    style = InkTheme.type.textLg,
                    color = c.textTertiary,
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    "未发出",
                    style = InkTheme.type.textXs,
                    color = c.danger.text,
                )
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    Icon(ArIcons.Retry, contentDescription = "重试发送", tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "放弃发送", tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * 流式中文本 shimmer（textShimmerBase → textTertiary 微光扫过）；
 * 定型后走 Markdown。横向渐变窗口按动画进度平移，无需实测文本宽度。
 */
@Composable
private fun ShimmerStreamingText(content: String) {
    val c = InkTheme.colors
    val shift by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerShift",
    )
    val window = 420f
    val start = window * (shift * 2.4f - 1.2f)
    val brush = Brush.linearGradient(
        colors = listOf(c.shimmerBase, c.textTertiary, c.shimmerBase),
        start = Offset(start, 0f),
        end = Offset(start + window, 0f),
    )
    Text(
        content,
        style = TextStyle(
            fontSize = InkTheme.type.textLg.fontSize,
            lineHeight = InkTheme.type.textLg.lineHeight,
            brush = brush,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 生成中三点跳动。 */
@Composable
internal fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val color = InkTheme.colors.textTertiary
    Row {
        for (i in 0 until 3) {
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 450, delayMillis = i * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(5.dp)
                    .graphicsLayer { translationY = -3f * phase }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun TypingIndicator(thinkingText: String?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        TypingDots()
        Spacer(Modifier.size(8.dp))
        val lastThinking = thinkingText?.lineSequence()?.lastOrNull { it.isNotBlank() }
        Text(
            lastThinking?.let { "思考中：$it" } ?: "生成中…",
            style = InkTheme.type.textSm,
            fontStyle = FontStyle.Italic,
            color = InkTheme.colors.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * ComposerShell（M3.4）：引用条 QuoteBar（被引用消息随下一条发出）+ 圆角输入壳 +
 * 发送/停止态切换（run 进行中 = danger 停止键）。
 */
@Composable
private fun ChatInputBar(model: AppModel, haptics: HapticPlayer, modifier: Modifier = Modifier) {
    val c = InkTheme.colors
    val runActive = model.conversation?.activeRunId != null
    Column(modifier.fillMaxWidth().background(c.paper)) {
        // QuoteBar：引用条（M3.4）
        model.quotedText?.let { quoted ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(InkTheme.shapes.md.dp))
                    .background(c.surfaceTertiary)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 28.dp)
                        .background(c.inverse, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    quoted.lines().filter { it.isNotBlank() }.firstOrNull() ?: "",
                    style = InkTheme.type.textSm,
                    color = c.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "取消引用",
                    style = InkTheme.type.textXs,
                    color = c.info.text,
                    modifier = Modifier
                        .clickable { model.clearQuote() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            TextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                placeholder = {
                    Text("发送消息…", color = c.textTertiary, style = InkTheme.type.textMd)
                },
                textStyle = InkTheme.type.textMd,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceTertiary,
                    unfocusedContainerColor = c.surfaceTertiary,
                    disabledContainerColor = c.surfaceTertiary,
                    focusedTextColor = c.ink,
                    unfocusedTextColor = c.ink,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { haptics.tap(); model.send() }),
            )
            Spacer(Modifier.size(6.dp))
            // 发送/停止态切换：run 进行中优先停止
            if (runActive) {
                StopButton(haptics = haptics, onStop = { model.stopRun() })
            } else {
                val enabled = model.draft.isNotBlank()
                Box(
                    Modifier
                        .padding(bottom = 6.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (enabled) c.inverse else c.surfaceTertiary)
                        .clickable(enabled = enabled) {
                            haptics.tap()
                            model.send()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (enabled) c.paper else c.textDisabled,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/** 停止键：danger 底方块（Grok ComposerPrimaryButton 停止态）。 */
@Composable
private fun StopButton(haptics: HapticPlayer, onStop: () -> Unit) {
    val c = InkTheme.colors
    Box(
        Modifier
            .padding(bottom = 6.dp)
            .size(34.dp)
            .clip(CircleShape)
            .background(c.danger.text)
            .clickable {
                haptics.tap()
                onStop()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(c.paper, RoundedCornerShape(2.dp)),
        )
    }
}
