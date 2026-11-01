package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationOk
import dev.sharpxia.agentreins.hub.auth.DeviceRegistrationRequest
import dev.sharpxia.agentreins.hub.auth.DevicesPage
import dev.sharpxia.agentreins.hub.auth.LoginOk
import dev.sharpxia.agentreins.hub.auth.LoginRequest
import dev.sharpxia.agentreins.hub.auth.RefreshOk
import dev.sharpxia.agentreins.hub.auth.RefreshRequest
import dev.sharpxia.agentreins.hub.projection.ApprovalDecisionRequest
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.ConversationsPage
import dev.sharpxia.agentreins.hub.projection.EventsPage
import dev.sharpxia.agentreins.hub.projection.GatewaysPage
import dev.sharpxia.agentreins.hub.projection.MessagesPage
import dev.sharpxia.agentreins.hub.projection.ReadCursorRequest
import dev.sharpxia.agentreins.hub.projection.SendMessageRequest
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hub.projection.CancelRunRequest

/**
 * Hub REST 客户端（v1 契约 14 端点；P0 骨架，P1 起填充 Ktor 实现）。
 *
 * 契约钉版（P0 验收②，docs/plans/hub-migration-plan.md §6-P0）：
 * - REST 契约：`api/hub-api-v1.yaml` — OpenAPI 3.0.3，info.version = **v1.0.0**，`x-hub-api-version: 1`，
 *   冻结 git tag `hub-api-v1.0.0`（AgentReinService 仓，冻结日 2026-11-01）
 * - WSS 帧契约：`api/hub-wss-frames-v1.schema.json` — JSON Schema 2020-12，protocolVersion 支持集 = {1}
 * - 版本策略（08 §6.1）：/v1 内仅 additive（新增端点、可选请求字段、响应字段、错误码）；
 *   破坏性变更升 /v2 并行 ≥90 天。客户端义务 = 忽略未知字段、宽松解析、不因新增字段失败
 * - 通用（08 §1.1）：每响应带 `x-hub-api-version: 1` 与 `x-request-id`（排障必须引用）；
 *   错误形状见 [HubErrorPayload]；命令类端点共用 202 + CommandReceipt（ADR-07：进度只经事件流）
 */
interface HubApi {
    // ── 认证（#1 / #2，公开端点） ─────────────────────────────────────────
    /** 登录两步流第①步：创建会话级 accessToken（2h，未绑定设备、无 refreshToken）。 */
    suspend fun login(req: LoginRequest): LoginOk

    /** Token 刷新：refreshToken 一次一用轮换，旧值立即失效；轮换后 60s 内重放幂等。 */
    suspend fun refresh(req: RefreshRequest): RefreshOk

    // ── 设备（#3 / #4 / #5，Bearer） ─────────────────────────────────────
    /** 两步流第②步：注册设备并签发该设备 refreshToken（30 天滑动）；会话 Token 随即升级为绑定 Token（ADR-15）。 */
    suspend fun registerDevice(req: DeviceRegistrationRequest): DeviceRegistrationOk

    /** 设备清单；current 标记发起请求的设备。 */
    suspend fun listDevices(): DevicesPage

    /** 撤销设备（可撤任意自有设备含当前）：该设备全部 Token 即时失效，其 WSS 被服务端关闭（204 无体）。 */
    suspend fun deleteDevice(deviceId: String)

    // ── 网关（#6，08 §4.1【MVP 必需】） ──────────────────────────────────
    /** 网关与 Agent 列表；无分页。冷启动优先用快照自带 gateways 数组，此端点用于校准/刷新。 */
    suspend fun gateways(): GatewaysPage

    // ── 会话与消息（#7 / #8 / #9 / #10，Bearer） ─────────────────────────
    /** 会话列表：不透明 keyset 分页（最近活跃降序），limit 默认 50 / 上限 200；cursor 原样回传、禁止自构。 */
    suspend fun conversations(limit: Int? = null, cursor: String? = null): ConversationsPage

    /** 历史消息：无 before 返回尾页（默认 50 / 上限 200），before=seq 向上翻页；翻页只 upsert、不推进 cursor。 */
    suspend fun messages(conversationId: String, before: Long? = null, limit: Int? = null): MessagesPage

    /** 发送消息命令 message.send（202 CommandReceipt）；同键同内容重放返回原 commandId，同键不同内容 409 HUB-BIZ-001。 */
    suspend fun sendMessage(conversationId: String, req: SendMessageRequest): CommandReceipt

    /** 已读上报命令 read.mark；readUpTo 指向当前化身的既有精确 message seq（05 §3.10）。 */
    suspend fun markRead(conversationId: String, req: ReadCursorRequest): CommandReceipt

    // ── 命令（#11 / #12，Bearer） ────────────────────────────────────────
    /** 取消 Run 命令 run.cancel；runId 只能从事件流获得；已结束后取消为幂等成功。 */
    suspend fun cancelRun(runId: String, req: CancelRunRequest): CommandReceipt

    /** 审批决定命令 approval.decide；decision ∈ approve/reject；approvalId 从 approval.requested 载荷获得。B-4 假设未验证，不做主路径。 */
    suspend fun decideApproval(approvalId: String, req: ApprovalDecisionRequest): CommandReceipt

    // ── 同步（#13 / #14，Bearer） ────────────────────────────────────────
    /** 全量快照：无状态；首次初始化 / 游标过期（410）/ 缓存怀疑失真时直接调用。不含 approvals 与 run 明细。 */
    suspend fun snapshot(): SnapshotResponse

    /** 事件增量（同端点两用途）：返回 (cursor, …] 按 id 升序；串行分页至 hasMore=false 且 nextCursor==currentWatermark。 */
    suspend fun events(cursor: String, limit: Int? = null): EventsPage
}
