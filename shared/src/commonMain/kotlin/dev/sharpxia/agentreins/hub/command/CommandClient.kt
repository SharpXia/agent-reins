package dev.sharpxia.agentreins.hub.command

import dev.sharpxia.agentreins.hub.projection.CommandReceipt

/**
 * 命令类端点封装（08 §4.4–4.8）：一律 202 + CommandReceipt（首个可见状态 = PERSISTED），
 * 后续进度不经 HTTP 轮询、只经事件流 command.acked / command.failed 送达（ADR-07）。
 * 全部经本地幂等队列（PENDING_LOCAL → 提交 → PERSISTED → 事件流收敛终态）。
 */
interface CommandClient {
    /**
     * 发送消息：内部生成幂等键（永不更换），同步插乐观行（uiState=sending）+ PENDING_LOCAL 队列，
     * 提交成功返回 receipt（PERSISTED）；确定性拒绝返回 null（队列条目 FAILED + 错误码）。
     */
    suspend fun sendMessage(
        conversationId: String,
        gatewayId: String,
        sessionKey: String,
        text: String,
    ): CommandReceipt?

    /** 已读上报（会话完整渲染后调用；readUpTo = 已渲染最大 message seq）。 */
    suspend fun markRead(conversationId: String, gatewayId: String, sessionKey: String, readUpTo: Long)

    /** 取消 Run（runId 从事件流获得；已结束后取消为幂等成功）。 */
    suspend fun cancelRun(runId: String, sessionKey: String?)

    /** 审批决定：decision ∈ approve/reject。B-4 假设未验证：预检失败走确定性 FAILED。 */
    suspend fun decideApproval(approvalId: String, decision: String, sessionKey: String?)

    /** 断线恢复入口：未终态条目按同键同载荷幂等重发（05 §3.9 义务 6）。 */
    suspend fun retryUnfinished()
}
