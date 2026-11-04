package dev.sharpxia.agentreins.hub.projection

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AgentRef(val agentId: String, val label: String)

/** 网关与 Agent 列表项（08 §4.1【MVP 必需】）；运行期 online 由 gateway.online/offline 事件更新。 */
@Serializable
data class GatewaySummary(
    val gatewayId: String,
    val name: String,
    val online: Boolean,
    val lastSeenAt: String? = null,
    val agents: List<AgentRef> = emptyList(),
)

@Serializable
data class GatewaysPage(val gateways: List<GatewaySummary> = emptyList())

/**
 * 会话行（契约 v1.1.0：required 10 字段）。化身不出 REST 面（02 R7）：sessionId/sessionKey/
 * lifecycleRevision 已撤，化身更替经事件流 session.updated 的 lifecycleRevision 感知；
 * 前端会话句柄 = conversationId（化身更替后不变，05 §3.10）。
 */
@Serializable
data class ConversationRow(
    val conversationId: String,
    /** 归属网关：一个账号可挂多网关（08 §4.1），会话必须能归属。 */
    val gatewayId: String,
    val label: String,
    val agentId: String,
    val lastMessagePreview: String,
    val lastSeq: Long,
    val lastActiveAt: String,
    /** Hub 投影计算值（排除本用户行/合成行/未落定流式行），客户端不得发明第二套计数（05 §3.7）。 */
    val unreadCount: Int,
    val hasActiveRun: Boolean,
    /** 对账标记（ADR-05）：true=投影可能滞后于 Gateway，UI 呈现「对账中」。 */
    val reconciling: Boolean,
)

/**
 * 消息行（v1.1：__openclaw 锚已撤，App 面禁出 openclaw 字段）。合并键 = messageId（upsert）+
 * seq（当前化身内插入顺序）；与事件信封 id 是两条独立序列（05 §2.2/§3.11）。
 */
@Serializable
data class MessageRow(
    /** Hub ULID，upsert 主键。 */
    val messageId: String,
    val seq: Long,
    /** user / assistant / ""（v1.1 additive：空串=compaction/reset 合成行，渲染为分隔条）。 */
    val role: String,
    val content: String,
    /** final / streaming / failed；流式行允许跳过 delta，终态由 created/replaced 收敛（05 §3.11）。 */
    val state: String,
    /** 仅用户行：sending/delivered/read/failed；服务端现状不产出 read 态，UI 不得依赖。 */
    val uiState: String? = null,
    /** message.created 携带，驱动发送方 UI 翻转（乐观 UI 对账，05 §3.8）。 */
    val commandId: String? = null,
    val idempotencyKey: String? = null,
    val timestamp: String? = null,
)

@Serializable
data class ConversationsPage(
    val conversations: List<ConversationRow> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class MessagesPage(
    val conversationId: String,
    val messages: List<MessageRow> = emptyList(),
    val hasMore: Boolean = false,
    val oldestSeq: Long? = null,
    val newestSeq: Long? = null,
)

/**
 * 事件信封（REST 增量与 WSS event 帧完全同构，同一 events 表行；hub-wss-frames-v1.schema.json
 * EventEnvelopeBase：required = id/type/gatewayId/ts/data）。
 * 幂等去重键 = id（INV-1）；**未知 type：忽略内容但必须照常推进游标**（06 §12.2 义务 2）。
 * data 统一用 JsonElement 承载——宽松解析，各域载荷按 type 分发解析。
 * 可省字段（06 §2.2 矩阵）：seq（gateway/read/command 域）、sessionKey（gateway/read 域不带——
 * eventlog 服务端强制校验；command 域「命令关联会话时带」）、runId（run/tool/approval 域必带）。
 * 信封不含 conversationId（read.updated 载荷才有）——事件→卡片路由经投影库 hubConvKeys 映射。
 */
@Serializable
data class EventEnvelope(
    /** 全局事件 ULID：去重键 + 游标推进依据（06 §2.1）。 */
    val id: String,
    /** 会话内事件序号：eventlog 单调整数、跨化身连续；与消息投影 sessionSeq 是两条独立序列。 */
    val seq: Long? = null,
    val type: String,
    val gatewayId: String,
    val sessionKey: String? = null,
    val runId: String? = null,
    val ts: String,
    val data: JsonElement,
) {
    companion object {
        // 21 类事件类型（06 §3 注册表镜像，唯一注册清单；新类型只增不改义）。
        // artifact 域已核准预留、本期不实现。
        const val MESSAGE_DELTA = "message.delta"
        const val MESSAGE_CREATED = "message.created"
        const val MESSAGE_REPLACED = "message.replaced"
        const val MESSAGE_FAILED = "message.failed"
        const val RUN_STARTED = "run.started"
        const val RUN_COMPLETED = "run.completed"
        const val RUN_FAILED = "run.failed"
        const val RUN_ABORTED = "run.aborted"
        const val TOOL_STARTED = "tool.started"
        const val TOOL_COMPLETED = "tool.completed"
        const val TOOL_FAILED = "tool.failed"
        const val SESSION_CREATED = "session.created"
        const val SESSION_UPDATED = "session.updated"
        const val SESSION_REMOVED = "session.removed"
        const val GATEWAY_ONLINE = "gateway.online"
        const val GATEWAY_OFFLINE = "gateway.offline"
        const val APPROVAL_REQUESTED = "approval.requested"
        const val APPROVAL_RESOLVED = "approval.resolved"
        const val COMMAND_ACKED = "command.acked"
        const val COMMAND_FAILED = "command.failed"
        const val READ_UPDATED = "read.updated"
    }
}

@Serializable
data class EventsPage(
    val events: List<EventEnvelope> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    /** 服务端当前水位；追平判定 = hasMore=false 且 nextCursor==currentWatermark。 */
    val currentWatermark: String,
)

/** 全量快照（08 §4.9 / yaml SnapshotResponse：六字段全 required）：不含 approvals 与 run 明细（只能从事件流累积）。 */
@Serializable
data class SnapshotResponse(
    /** 空系统为 26 个 0 的零值 ULID；落定后 cursor = 此值。 */
    val snapshotWatermark: String,
    val generatedAt: String,
    /** 冷启动网关列表来源：offline 网关在此即可见（不依赖事件）。 */
    val gateways: List<GatewaySummary> = emptyList(),
    val conversations: List<ConversationRow> = emptyList(),
    /** conversationId → 该会话尾页 50 行（08 §8）。 */
    val messages: Map<String, List<MessageRow>> = emptyMap(),
    /** conversationId → readUpTo（多设备合并最大值，INV-7）。 */
    val readCursors: Map<String, Long> = emptyMap(),
)

// ── 命令请求/回执（08 §4.4–4.8 共用 202 + CommandReceipt） ──────────────────

@Serializable
data class SendMessageRequest(val idempotencyKey: String, val message: String)

@Serializable
data class ReadCursorRequest(val idempotencyKey: String, val readUpTo: Long)

@Serializable
data class CancelRunRequest(val idempotencyKey: String)

/** decision 请求值 ∈ approve/reject（Hub 归一化为 approved/rejected 广播）。 */
@Serializable
data class ApprovalDecisionRequest(val idempotencyKey: String, val decision: String, val note: String? = null)

/** #16 conversation.create：label 与 idempotencyKey 均可选（缺省 Hub 生成幂等键、重试安全）。 */
@Serializable
data class CreateConversationRequest(val label: String? = null, val idempotencyKey: String? = null)

/** #19 conversation.rename：label 必填非空（缺失/空串 → 400 HUB-BIZ-004）。 */
@Serializable
data class RenameConversationRequest(val label: String)

/** #18 Agent 状态（纯投影派生，无命令无副作用；限流走列表类 60 次/分/设备）。 */
@Serializable
data class AgentStatusResponse(
    val agentId: String,
    val gatewayId: String,
    val gatewayOnline: Boolean,
    val activeConversations: Int,
    val activeRuns: Int,
    val lastSeenAt: String? = null,
)

@Serializable
data class CommandReceipt(
    val commandId: String,
    val idempotencyKey: String,
    val state: String,
    val acceptedAt: String,
)
