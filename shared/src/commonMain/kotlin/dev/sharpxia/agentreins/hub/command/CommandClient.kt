package dev.sharpxia.agentreins.hub.command

import dev.sharpxia.agentreins.hub.projection.CommandReceipt

/**
 * 命令类端点封装（08 §4.4–4.8；v1.1 命令目标 = conversationId，sessionKey 定位已撤）：
 * 一律 202 + CommandReceipt（首个可见状态 = PERSISTED），后续进度不经 HTTP 轮询、
 * 只经事件流 command.acked / command.failed 送达（ADR-07）。
 * 全部经本地幂等队列（PENDING_LOCAL → 提交 → PERSISTED → 事件流收敛终态）。
 */
interface CommandClient {
    /**
     * 发送消息：内部生成幂等键（永不更换），同步插乐观行（uiState=sending）+ PENDING_LOCAL 队列，
     * 提交成功返回 receipt（PERSISTED）；确定性拒绝返回 null（队列条目 FAILED + 错误码）。
     */
    suspend fun sendMessage(conversationId: String, text: String): CommandReceipt?

    /** 已读上报（会话完整渲染后调用；readUpTo = 已渲染最大 message seq）。 */
    suspend fun markRead(conversationId: String, readUpTo: Long)

    /** 取消 Run（runId 从事件流获得；已结束后取消为幂等成功）。 */
    suspend fun cancelRun(runId: String)

    /**
     * 审批决定：decision ∈ approve/reject。B-4 假设未验证：预检失败走确定性 FAILED。
     * 返回 receipt（PERSISTED 回执，生效以 approval.resolved 事件为准）；返回 null = 决策通道
     * 不可用（确定性拒绝 → 队列条目 FAILED+错误码，或网络不可达 → PENDING_LOCAL 不自动重放），
     * 调用方须显式呈现降级态（wp-9），不得静默。
     */
    suspend fun decideApproval(approvalId: String, decision: String): CommandReceipt?

    /**
     * v1.1 新端点（#16/#17/#19，openclaw 原生指令）：**幂等键由 Hub 生成**（请求体无 idempotencyKey
     * 字段，#16 仅有可选键），与本地队列的幂等纪律不兼容——不走队列、直调一次，失败由调用方提示
     * 手动重试。新会话经 roster 同步自动出现在列表（R4 校准兜底）。
     * #16 实现固定携带 mainSession=true（v1.1 additive，主线锚定）：服务端组装
     * sessionKey=agent:<agentId>:main（客户端永不拼键，ADR-14），同名 key adopt 不 fork。
     */
    suspend fun createConversation(gatewayId: String, agentId: String, label: String?): CommandReceipt

    suspend fun compactConversation(conversationId: String): CommandReceipt

    suspend fun renameConversation(conversationId: String, label: String): CommandReceipt

    /** 断线恢复入口：未终态条目按同键同载荷幂等重发（05 §3.9 义务 6）。 */
    suspend fun retryUnfinished()
}
