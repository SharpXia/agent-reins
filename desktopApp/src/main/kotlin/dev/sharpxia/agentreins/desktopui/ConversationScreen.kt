package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiMessage
import kotlinx.coroutines.launch

private const val OPTIMISTIC_SEQ_SENTINEL: Long = Long.MAX_VALUE

/**
 * 会话页（wp-6 验收④ + wp-10 交互 parity + wp-18 渲染）：消息时间线（乐观行 + 流式行 +
 * 合成行分隔条）+ 发送 + 已读上报。
 * 数据源 = 投影库 Flow（observeMessages，seq 升序）；写路径只走 commands 幂等队列。
 *
 * wp-10（对齐 iOS wp-8/wp-9）：
 * - 消息操作：右键菜单——复制全文（非合成行非空正文）/ 失败行重发（新意图新键，ADR-07）；
 * - Stop：活跃 run 期间 composer 常驻 Stop（grokbot 式，替代发送键），activeRunId 缺失
 *   （映射未学习）降级「结束中」提示态，不假设 runId 恒有值；
 * - 审批卡：归属本会话的 pending 审批横条置顶于时间线上方（审批是 run 的前置门，不进滚动区），
 *   批准/拒绝走幂等队列，决策通道不可用显式降级可重试；
 * - 空正文防线：发送条禁空白 + 渲染侧占位（投影层零过滤，空 content 行照常入库）。
 * wp-18：助手行走 [MarkdownText] 轻量 Markdown 渲染（标题/列表/代码块/粗斜体；流式行带
 * ▌ 光标渐进渲染），无 Markdown 语素或解析异常自动回退 wp-6 纯文本路径。
 */
@Composable
internal fun ConversationScreen(
    client: DefaultHubClient,
    conversationId: String,
    label: String,
    conversations: List<UiConversation>,
    approvals: List<UiApproval>,
    decisions: ApprovalDecisionState,
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

    // wp-8②：实时行——conversationId 是打开时刻的句柄，hasActiveRun/activeRunId 随 run.* 事件
    // 后续翻转，Stop 按钮态一律从会话列表现值取，不得用打开时刻快照（会话列表 Flow 持续推送）。
    val liveConv = conversations.firstOrNull { it.conversationId == conversationId }
    val isRunActive = liveConv?.hasActiveRun == true
    // Stop 可执行 = 活跃 run 且 activeRunId 已习得（wp-5 v4 投影）。映射未学习场景（null——
    // DefaultEventProjector.setRun 在 resolveConversation 失败时直接 return，不落库）置灰为
    // 「结束中」提示态，不假设 runId 恒有值、不误触。
    val canCancelRun = !liveConv?.activeRunId.isNullOrEmpty()

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部：会话名（投影 label）
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

        // wp-9：审批横条（置顶于时间线上方）——审批是 run 的前置门，出现即需注意，不进滚动区防淹没
        if (approvals.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                approvals.forEach { a ->
                    ApprovalCard(a, conversations, decisions, client, scope)
                }
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
                MessageRow(m, client, conversationId, scope)
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
                if (isRunActive) {
                    // wp-8②：Stop 按钮（grokbot 式常驻）——活跃 run 期间替代发送键；中止后部分
                    // 输出保留由 Gateway 权威收敛（run.aborted 回流投影库，UI 只翻转回发送键）。
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = {
                                val runId = liveConv?.activeRunId ?: return@Button
                                scope.launch { client.commands?.cancelRun(runId) }
                            },
                            enabled = canCancelRun,
                        ) { Text("■ 停止") }
                        if (!canCancelRun) {
                            Text("结束中", fontSize = 10.sp, color = Color(0xFF999999))
                        }
                    }
                } else {
                    // 空正文防线（发送侧）：空白草稿禁发（isNotBlank = 去空白后非空），点击再兜一层
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
}

/**
 * 消息行（wp-6 渲染基线 + wp-10 操作 + wp-18 助手行 Markdown；state ∈ final/streaming/failed、
 * uiState 仅用户行 sending/delivered/read/failed）：
 * - role=""：compaction/reset 合成行 → 分隔条（无操作菜单）
 * - role=user：右对齐气泡，uiState 标签（发送中/发送失败/已送达/已读）
 * - role=assistant：左对齐卡片，Markdown 渲染（wp-18），streaming 行带 ▌ 光标与「流式生成中」标签
 * 操作菜单（wp-10，桌面右键 = iOS 长按菜单同位）：复制全文 / 失败行重发。
 */
@Composable
private fun MessageRow(
    m: UiMessage,
    client: DefaultHubClient,
    conversationId: String,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    when {
        m.role.isEmpty() -> CompactionSeparator()
        else -> {
            val clipboard = LocalClipboardManager.current
            ContextMenuArea(
                items = {
                    buildList {
                        if (isCopyable(m)) {
                            add(
                                ContextMenuItem("复制全文") {
                                    clipboard.setText(AnnotatedString(m.content))
                                },
                            )
                        }
                        if (isResendable(m)) {
                            add(
                                ContextMenuItem("重发") {
                                    // 用户显式重发 = 新意图新键（sendMessage 每次生成新幂等键，ADR-07）
                                    scope.launch {
                                        client.commands?.sendMessage(conversationId, m.content)
                                    }
                                },
                            )
                        }
                    }
                },
            ) {
                when {
                    m.role == "user" -> UserMessageRow(m)
                    else -> AssistantMessageRow(m)
                }
            }
        }
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
                // wp-8③ 空正文防线：空 SENDING 用户行不渲染空气泡，占位「…」+ 状态徽标照常翻转
                text = if (isEmptyUserBody(m)) "…" else m.content,
                color = if (isEmptyUserBody(m)) Color(0xFF999999) else Color.Unspecified,
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
        FailedReasonLine(m)
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
            when {
                // 流式空行：typing 占位（…▌，wp-7 同型；05 §3.11：流式行允许跳过 delta，终态由 created 收敛）
                m.state == "streaming" && m.content.isBlank() -> Text(
                    "…▌",
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // wp-8③ 空正文防线：final/failed 空助手行渲染占位（不再出现空气泡——
                // 按 MessageActions.isEmptyBodyPlaceholder 既定契约补上占位文案）
                isEmptyBodyPlaceholder(m) -> Text(
                    "（无文本内容）",
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // wp-18：助手行走轻量 Markdown 渲染器（标题/列表/代码块/粗斜体，流式行尾随
                // ▌ 光标）；无 Markdown 语素或解析异常时内置回退 wp-6 纯文本路径
                else -> MarkdownText(
                    content = m.content,
                    showCursor = m.state == "streaming",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
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
        FailedReasonLine(m)
    }
}

/** wp-8③：失败原因行（六值脱敏闭集 → 人读文案；未分类失败 reason=NULL 不虚构文案）。 */
@Composable
private fun FailedReasonLine(m: UiMessage) {
    val reason = failedReasonLabel(m) ?: return
    Text(
        text = "⚠ $reason",
        fontSize = 11.sp,
        color = Color(0xFFC53030),
        modifier = Modifier.padding(top = 2.dp),
    )
}
