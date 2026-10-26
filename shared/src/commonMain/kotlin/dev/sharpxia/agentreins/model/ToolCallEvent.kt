package dev.sharpxia.agentreins.model

import kotlinx.serialization.Serializable

/** Agent 事件流中解析出的一次工具调用，对应 UI 中的工具调用卡片。 */
@Serializable
data class ToolCallEvent(
    /** 服务端 toolCallId（item 流的 `tool:call_…` 与 command_output 共用它聚合）。 */
    override val id: String,
    val sessionId: String? = null,
    val toolName: String,
    /** item 流给出的人类可读摘要（如 "Exec 第四批补丁…"）。 */
    val title: String? = null,
    /** 参数原文（JSON 字符串），卡片展开态展示。事件流不带参数——来自 chat.history 的 toolCall content。 */
    val argumentsJson: String? = null,
    /** 结果原文（exec 类为命令输出文本，来自 command_output 流或 chat.history 的 toolResult）。 */
    val resultJson: String? = null,
    val exitCode: Int? = null,
    val startedAtMs: Long? = null,
    val durationMs: Long? = null,
    val state: State = State.RUNNING,
) : ChatEntry {
    @Serializable
    enum class State { RUNNING, SUCCEEDED, FAILED }
}
