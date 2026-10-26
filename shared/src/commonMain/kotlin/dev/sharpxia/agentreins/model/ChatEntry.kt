package dev.sharpxia.agentreins.model

/** 对话流里的一项（消息或工具调用卡片），ConversationState 的有序条目。 */
sealed interface ChatEntry {
    /** 稳定标识：消息用服务端消息 id / 流式 itemId，工具用 toolCallId。 */
    val id: String
}
