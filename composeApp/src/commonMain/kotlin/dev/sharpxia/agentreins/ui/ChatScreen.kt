package dev.sharpxia.agentreins.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.openclaw.Outbox
import dev.sharpxia.agentreins.ui.theme.ArTheme
import kotlinx.coroutines.launch

/**
 * 对话流（M2.6 新基线）：日分隔、ChatGPT 式气泡（用户灰气泡右对齐/助手平文）、
 * 消息长按菜单（复制/重发）、outbox 待发气泡（重试/删除）、流式光标动画。
 * 数据源不变：model.conversation（ConversationState）。
 */
@Composable
internal fun ChatScreen(model: AppModel, sessionKey: String, modifier: Modifier = Modifier) {
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
    // 列表实际会发出的总行数：内容行 + 「加载更早」头 + 空态 + 生成中行（按数据算，
    // 不读 layoutInfo——组合帧它可能还是上一帧的值，差一项目标偏短）
    val totalRows = contentRowCount +
        (if (state?.hasMoreHistory == true && contentRowCount > 0) 1 else 0) +
        (if (contentRowCount == 0 && !runActive) 1 else 0) +
        (if (runActive) 1 else 0)
    // 首次有内容：直接定位到底部；此后仅贴底时跟随新消息
    LaunchedEffect(totalRows, lastTextLen, runActive) {
        if (totalRows == 0) return@LaunchedEffect
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
    // 贴底检测：离开底部即亮出「查看最新」
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible < info.totalItemsCount - 2
        }.collect { showJumpToLatest = it }
    }
    // 滚到顶懒加载更早一页历史
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }.collect { atTop -> if (atTop) model.loadOlderMessages() }
    }

    Column(
        modifier
            .fillMaxWidth()
            .imePadding()
            .dismissKeyboardOnTap(),
    ) {
        ChatTopBar(model, sessionKey, runActive)
        if (model.queuedCount > 0) {
            QueuedBanner(count = model.queuedCount, onRetry = { haptics.tap(); model.retryQueuedNow() })
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
                if (contentRowCount == 0 && !runActive) {
                    item(key = "empty") {
                        Text(
                            "暂无消息，发一条开始对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    when (row) {
                        is ChatRow.DayHeader -> DayHeaderRow(row.atMs)
                        is ChatRow.Entry -> when (val entry = row.entry) {
                            is ToolCallEvent -> ToolCallCard(entry)
                            is Message -> ChatMessageItem(
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
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "查看最新消息",
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
            }
        }

        ChatInputBar(model, haptics)
    }
}

@Composable
private fun ChatTopBar(model: AppModel, sessionKey: String, runActive: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { model.select(null) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
                Column(Modifier.weight(1f)) {
                    val session = model.sessions.firstOrNull { it.id == sessionKey }
                    Text(
                        session?.title ?: sessionKey,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        session?.kind?.takeIf { it.isNotBlank() } ?: sessionKey,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueuedBanner(count: Int, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Text(
                "⏳ $count 条消息待发送，重连后自动补发",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("立即重试", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun DayHeaderRow(atMs: Long) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            dayHeaderLabel(atMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageItem(message: Message, onCopy: (String) -> Unit, onResend: (String) -> Unit) {
    val tokens = ArTheme.tokens
    when (message.role) {
        Message.Role.USER -> {
            var menuOpen by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BoxWithConstraints {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(
                            tokens.bubbleRadius.dp,
                            5.dp,
                            tokens.bubbleRadius.dp,
                            tokens.bubbleRadius.dp,
                        ),
                    ) {
                        Text(
                            message.content,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .widthIn(max = maxWidth * tokens.bubbleMaxWidthFraction)
                                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    MessageMenu(
                        expanded = menuOpen,
                        onDismiss = { menuOpen = false },
                        showResend = true,
                        onCopy = { onCopy(message.content) },
                        onResend = { onResend(message.content) },
                    )
                }
            }
        }
        // 助手消息：ChatGPT 式平文（无气泡），流式中附闪烁光标
        Message.Role.ASSISTANT -> {
            var menuOpen by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = {}, onLongClick = { menuOpen = true }),
            ) {
                if (message.streaming) {
                    StreamingText(message.content)
                } else if (message.content.isNotBlank()) {
                    MarkdownText(message.content)
                }
                MessageMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    showResend = false,
                    onCopy = { onCopy(message.content) },
                    onResend = {},
                )
            }
        }
        // agent 的 commentary（item 流 kind=preamble）：过程说明文本
        Message.Role.SYSTEM -> Text(
            message.content,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
    }
}

/** 长按菜单：复制（两角色）/ 重发（仅用户消息）。 */
@Composable
private fun MessageMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    showResend: Boolean,
    onCopy: () -> Unit,
    onResend: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("复制") },
            leadingIcon = { Icon(ArIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp)) },
            onClick = { onCopy(); onDismiss() },
        )
        if (showResend) {
            DropdownMenuItem(
                text = { Text("重发") },
                leadingIcon = { Icon(ArIcons.Retry, contentDescription = null, modifier = Modifier.size(18.dp)) },
                onClick = { onResend(); onDismiss() },
            )
        }
    }
}

/** outbox 待发气泡：灰阶弱化文本 + 「未发出」标记 + 重试/删除。 */
@Composable
private fun PendingOutboxItem(cmd: Outbox.Command, onRetry: () -> Unit, onDelete: () -> Unit) {
    val tokens = ArTheme.tokens
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(
                    tokens.bubbleRadius.dp,
                    5.dp,
                    tokens.bubbleRadius.dp,
                    tokens.bubbleRadius.dp,
                ),
            ) {
                Text(
                    cmd.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                Text(
                    "未发出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    Icon(
                        ArIcons.Retry,
                        contentDescription = "重试发送",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "放弃发送",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** 流式中的消息：纯文本 + 尾部闪烁光标（定型后走 Markdown 渲染）。 */
@Composable
private fun StreamingText(content: String) {
    val caretAlpha by rememberCaretAlpha()
    val caretColor = MaterialTheme.colorScheme.primary
    val annotated = remember(content, caretAlpha, caretColor) {
        buildAnnotatedString {
            append(content)
            withStyle(SpanStyle(color = caretColor.copy(alpha = caretAlpha))) { append("▍") }
        }
    }
    Text(
        annotated,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun rememberCaretAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "caret")
    return transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretAlpha",
    )
}

/** 生成中三点跳动（ChatGPT 式）。 */
@Composable
internal fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val color = MaterialTheme.colorScheme.onSurfaceVariant
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
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ChatInputBar(model: AppModel, haptics: HapticPlayer) {
    val tokens = ArTheme.tokens
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        TextField(
            value = model.draft,
            onValueChange = { model.draft = it },
            placeholder = {
                Text("发送消息…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(tokens.sendButtonSize.dp),
            maxLines = 4,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { haptics.tap(); model.send() }),
        )
        Spacer(Modifier.size(6.dp))
        val enabled = model.draft.isNotBlank()
        Box(
            Modifier
                .padding(bottom = 6.dp)
                .size(tokens.sendButtonSize.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                )
                .clickable(enabled = enabled) {
                    haptics.tap()
                    model.send()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = if (enabled) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}
