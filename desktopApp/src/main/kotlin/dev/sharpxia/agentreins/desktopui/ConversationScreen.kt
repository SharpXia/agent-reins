package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.UiMessage
import kotlinx.coroutines.launch

private const val OPTIMISTIC_SEQ_SENTINEL: Long = Long.MAX_VALUE

/**
 * 会话页（wp-6 验收④）：消息时间线（乐观行 + 流式行 + 合成行分隔条）+ 发送 + 已读上报。
 * 数据源 = 投影库 Flow（observeMessages，seq 升序）；写路径只走 commands 幂等队列。
 */
@Composable
internal fun ConversationScreen(
    client: DefaultHubClient,
    conversationId: String,
    label: String,
) {
    val messages by client.observeMessages(conversationId).collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 已读上报基线：只认真实服务端 seq（乐观行占位 seq=Long.MAX_VALUE、failed 行非服务端消息，均不计入）
    var reportedReadUpTo by remember { mutableStateOf(0L) }

    // 已读上报（会话完整渲染后 readUpTo = 已渲染最大真实 seq；只推进不回退，避免噪声）
    val maxRenderedSeq = messages
        .filter { it.seq != OPTIMISTIC_SEQ_SENTINEL && it.state != "failed" }
        .maxOfOrNull { it.seq } ?: 0L
    LaunchedEffect(conversationId, maxRenderedSeq) {
        if (maxRenderedSeq > reportedReadUpTo) {
            reportedReadUpTo = maxRenderedSeq
            client.markRead(conversationId, maxRenderedSeq)
        }
    }

    // 新消息到达时贴底（流式行内容增长不触发，只在条数变化时贴一次）
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部：会话名（投影 label；操作/Markdown 在 wp-10/wp-18）
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.ifBlank { "（未命名会话）" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 消息时间线
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { it.messageId }) { m ->
                MessageRow(m)
            }
        }

        // 发送区
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("发送消息…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty()) return@Button
                        input = ""
                        scope.launch { client.commands?.sendMessage(conversationId, text) }
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Text("发送")
                }
            }
        }
    }
}

/**
 * 消息行（MVP 纯文本；state ∈ final/streaming/failed、uiState 仅用户行 sending/delivered/read/failed）：
 * - role=""：compaction/reset 合成行 → 分隔条
 * - role=user：右对齐气泡，uiState 标签（发送中/发送失败/已送达/已读）
 * - role=assistant：左对齐卡片，streaming 行带 ▌ 光标与「流式生成中」标签
 */
@Composable
private fun MessageRow(m: UiMessage) {
    when {
        m.role.isEmpty() -> CompactionSeparator()
        m.role == "user" -> UserMessageRow(m)
        else -> AssistantMessageRow(m)
    }
}

@Composable
private fun UserMessageRow(m: UiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = m.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTs(m.ts), fontSize = 11.sp, color = Color(0xFF999999))
            when (m.uiState) {
                "sending" -> TagChip("发送中…", Color(0xFF666666), Modifier.padding(start = 6.dp))
                "failed" -> TagChip("发送失败", MaterialTheme.colorScheme.error, Modifier.padding(start = 6.dp))
                "delivered" -> TagChip("已送达", Color(0xFF2E9E44), Modifier.padding(start = 6.dp))
                "read" -> TagChip("已读", Color(0xFF2E9E44), Modifier.padding(start = 6.dp))
                else -> Unit
            }
            if (m.state == "failed") {
                TagChip("消息失败", MaterialTheme.colorScheme.error, Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun AssistantMessageRow(m: UiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                // 流式行：▌ 光标提示增长中（05 §3.11：流式行允许跳过 delta，终态由 created 收敛）
                text = if (m.state == "streaming") m.content.ifBlank { "…" } + "▌" else m.content,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTs(m.ts), fontSize = 11.sp, color = Color(0xFF999999))
            if (m.state == "streaming") {
                TagChip("流式生成中", Color(0xFF2B6CB0), Modifier.padding(start = 6.dp))
            }
        }
    }
}
