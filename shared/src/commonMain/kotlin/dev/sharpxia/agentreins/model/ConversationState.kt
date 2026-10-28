package dev.sharpxia.agentreins.model

/**
 * 单个会话的对话流状态：有序条目 + 思考流 + 运行指示。
 * 由 AgentEventParser 纯函数归并 agent 事件流与 chat.history 回填产生，UI 只读。
 */
data class ConversationState(
    val sessionKey: String,
    val entries: List<ChatEntry> = emptyList(),
    /** thinking 流的最新快照（同一文本反复全量重放，取最新）。 */
    val thinkingText: String? = null,
    /** 进行中的 run（lifecycle start → end），UI 显示生成中指示。 */
    val activeRunId: String? = null,
    /** transcript 是否还有更早的历史（chat.hasMore），向上翻页懒加载的开关。 */
    val hasMoreHistory: Boolean = true,
    /** 首页历史是否已到达（真机反馈 bug②）：false 时 UI 显示加载态而非空态。 */
    val historyLoaded: Boolean = false,
) {
    val messages: List<Message> get() = entries.filterIsInstance<Message>()
    val toolCalls: List<ToolCallEvent> get() = entries.filterIsInstance<ToolCallEvent>()
}
