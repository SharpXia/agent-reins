package dev.sharpxia.agentreins.hub.command

import kotlinx.coroutines.flow.Flow

/** 命令十态阶梯（07-command-lifecycle.md §2.1）。 */
enum class CommandState {
    PENDING_LOCAL,
    PERSISTED,
    WAITING_BRIDGE,
    DELIVERING,
    GATEWAY_ACKED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    INDETERMINATE,
    ;

    /** 仅四终态到达才出队（转入本地历史展示）；INDETERMINATE 是终态但不自动重发（07 §2.4/§5.3）。 */
    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == INDETERMINATE
}

enum class CommandType { MESSAGE_SEND, READ_MARK, RUN_CANCEL, APPROVAL_DECIDE }

/**
 * 队列条目最小字段（05 §3.9 O-08）。idempotencyKey = 客户端 UUID，生成后**永不更换**；
 * 命令重试必须复用同键（Hub 按（发送者, key）唯一索引返回原结果）；HUB-BIZ-001 作废该条目、禁止换键；
 * 唯一允许换键 = HUB-GW-004 投递超期后的用户显式重发（新意图新键，07 §5.1）。
 */
data class CommandEntry(
    val idempotencyKey: String,
    val type: CommandType,
    val conversationId: String? = null,
    /** 目标资源 id（runId / approvalId），不透明字符串：不解析、不数值化，仅回传。 */
    val targetId: String? = null,
    val payloadJson: String,
    val state: CommandState,
    val commandId: String? = null,
    val errorCode: String? = null,
    val createdAtMs: Long,
)

/** 本地持久化命令队列（P3 从 Outbox 骨架改造落 SQLite v2 的 commands 表，§5.2）。 */
interface CommandQueue {
    suspend fun enqueue(entry: CommandEntry)

    fun observeAll(): Flow<List<CommandEntry>>

    /** 断线恢复 / 重试入口：对未终态条目按同键幂等重试（契约无命令状态查询 REST 端点，进度只经事件流）。 */
    suspend fun retryUnfinished()
}
