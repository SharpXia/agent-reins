package dev.sharpxia.agentreins.model

/** 对话流里的一项（消息或工具调用卡片），ConversationState 的有序条目。 */
sealed interface ChatEntry {
    /** 稳定标识：消息用服务端消息 id / 流式 itemId，工具用 toolCallId。 */
    val id: String
}

/**
 * 思考流条目（M3.6 反馈②）：按事件序落位在条目流里，天然绑定其后的工具/回复输出。
 * 瞬态——run 终局即清除（transcript 不含 thinking，历史回填无从对账，若保留会被
 * mergeHistory 甩到列表尾部，再现「思考沉底」）。
 */
data class ThinkingEntry(
    override val id: String,
    val text: String,
) : ChatEntry
