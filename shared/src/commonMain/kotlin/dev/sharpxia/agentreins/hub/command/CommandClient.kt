package dev.sharpxia.agentreins.hub.command

import dev.sharpxia.agentreins.hub.projection.CommandReceipt

/**
 * 命令类端点封装（08 §4.4–4.8）：一律 202 + CommandReceipt（首个可见状态 = PERSISTED），
 * 后续进度不经 HTTP 轮询、只经事件流 command.acked / command.failed 送达（ADR-07）。
 * 命令类限流每用户 120 次/分，超限 429 按 retryAfterMs 退避（08 §9.1）。
 */
interface CommandClient {
    suspend fun sendMessage(conversationId: String, idempotencyKey: String, message: String): CommandReceipt

    suspend fun markRead(conversationId: String, idempotencyKey: String, readUpTo: Long): CommandReceipt

    /** runId 从事件流获得；run 已结束后取消为幂等成功。 */
    suspend fun cancelRun(runId: String, idempotencyKey: String): CommandReceipt

    /** decision ∈ approve/reject（Hub 归一化为 approved/rejected 广播）。B-4 假设未验证：预检失败走确定性 FAILED + approval.resolved(decidedBy=host)。 */
    suspend fun decideApproval(approvalId: String, idempotencyKey: String, decision: String, note: String? = null): CommandReceipt
}
