package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.ToolCallEvent

/**
 * 会话列表 + 对话流（M2）。
 * 对话页唯一数据源是 model.conversation（ConversationState）：历史回填 + agent 事件流
 * 归并，LazyColumn 增量渲染；工具调用走 ToolCallCard。
 */
@Composable
fun SessionsScreen(model: AppModel, modifier: Modifier = Modifier) {
    if (model.status !is GatewayStatus.Connected) {
        EmptyState(model, modifier)
        return
    }

    val selected = model.selectedSession
    if (selected == null) {
        SessionList(model, modifier)
    } else {
        ChatScreen(model, selected, modifier)
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
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.size(10.dp))
                Text("连接中…", style = MaterialTheme.typography.titleMedium)
            }
            is GatewayStatus.PairingPending -> {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.size(10.dp))
                Text("等待 owner 批准设备…", style = MaterialTheme.typography.titleMedium)
                st.requestId?.let {
                    Spacer(Modifier.size(4.dp))
                    Text("requestId: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                val st = model.status
                if (st is GatewayStatus.Error) {
                    Text("自动连接失败", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        st.message ?: "未知错误",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
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
private fun SessionList(model: AppModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    StatusBarTapEffect {
        if (model.sessions.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }
    Column(modifier.fillMaxSize()) {
        Text(
            "会话（${model.sessions.size}）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp),
        )
        LazyColumn(state = listState) {
            items(model.sessions, key = { it.id }) { s ->
                SessionRow(s, onClick = { model.select(s) })
            }
        }
    }
}

@Composable
private fun SessionRow(session: Session, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    session.title ?: session.id,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                formatRelativeTime(session.lastActiveAtMs).takeIf { it.isNotEmpty() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                session.id,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ChatScreen(model: AppModel, sessionKey: String, modifier: Modifier = Modifier) {
    val state = model.conversation
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var initialScrollDone by remember { mutableStateOf(false) }
    var showJumpToLatest by remember { mutableStateOf(false) }

    // 首次有内容：直接定位到底部（历史是旧→新排列）；此后仅贴底时跟随新消息
    val entryCount = state?.entries?.size ?: 0
    val lastTextLen = (state?.entries?.lastOrNull() as? Message)?.content?.length ?: 0
    val runActive = state?.activeRunId != null
    LaunchedEffect(entryCount, lastTextLen, runActive) {
        val count = listState.layoutInfo.totalItemsCount
        if (count == 0) return@LaunchedEffect
        if (!initialScrollDone) {
            listState.scrollToItem(count - 1)
            initialScrollDone = true
            return@LaunchedEffect
        }
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= count - 2) {
            listState.animateScrollToItem(count - 1)
        }
    }

    // 贴底检测：离开底部即亮出「查看最新」（新回复/新卡片到达时用户正在翻旧消息）
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

    Column(modifier.fillMaxSize().dismissKeyboardOnTap()) {
        ChatTopBar(model, sessionKey, runActive)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state?.hasMoreHistory == true && entryCount > 0) {
                    item(key = "loading-older") {
                        Text(
                            "上拉加载更早消息…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                    }
                }
                if (entryCount == 0 && !runActive) {
                    item(key = "empty") {
                        Text(
                            "暂无消息，发一条开始对话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
                items(state?.entries ?: emptyList(), key = { it.id }) { entry ->
                    when (entry) {
                        is ToolCallEvent -> ToolCallCard(entry)
                        is Message -> ChatMessageItem(entry)
                    }
                }
                if (runActive) {
                    item(key = "typing") { TypingIndicator(state?.thinkingText) }
                }
            }
            if (showJumpToLatest) {
                Surface(
                    onClick = {
                        val last = (state?.entries?.size ?: 0)
                        if (last > 0) scope.launch { listState.animateScrollToItem(last - 1) }
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "查看最新消息",
                            modifier = Modifier.size(16.dp),
                        )
                        Text("最新", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ChatInputBar(model)
    }
}

@Composable
private fun ChatTopBar(model: AppModel, sessionKey: String, runActive: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.select(null) }) { Text("←") }
            Column(Modifier.weight(1f)) {
                Text(
                    model.sessions.firstOrNull { it.id == sessionKey }?.title ?: sessionKey,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    sessionKey,
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
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            ) {
                CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.size(6.dp))
                Text("生成中…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChatMessageItem(message: Message) {
    when (message.role) {
        Message.Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Message.Role.ASSISTANT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp),
                // 加宽到 94%：代码块类内容 320dp 气泡太挤
                modifier = Modifier.fillMaxWidth(0.94f),
            ) {
                if (message.content.isBlank()) {
                    Text(
                        if (message.streaming) "…" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    MarkdownText(
                        message.content,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
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

@Composable
private fun TypingIndicator(thinkingText: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
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
private fun ChatInputBar(model: AppModel) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        OutlinedTextField(
            value = model.draft,
            onValueChange = { model.draft = it },
            placeholder = { Text("发送消息…") },
            modifier = Modifier.weight(1f),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { model.send() }),
        )
        TextButton(
            onClick = { model.send() },
            enabled = model.draft.isNotBlank(),
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        ) {
            Text("发送")
        }
    }
}
