package dev.sharpxia.agentreins.model

import kotlinx.serialization.Serializable

/** 会话中的一条消息。 */
@Serializable
data class Message(
    override val id: String,
    val sessionId: String,
    val role: Role,
    val content: String,
    val timestampMs: Long? = null,
    /** 流式未定型（assistant 回复仍在推送中）。 */
    val streaming: Boolean = false,
    /** 网关 transcript entryId（__openclaw.id；sessions.fork 锚点，仅真实报文携带）。 */
    val entryId: String? = null,
) : ChatEntry {
    @Serializable
    enum class Role { USER, ASSISTANT, SYSTEM }
}
