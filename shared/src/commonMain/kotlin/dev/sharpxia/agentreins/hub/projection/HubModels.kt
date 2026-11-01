package dev.sharpxia.agentreins.hub.projection

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** 网关行元数据信封：原样透传保存、不解释、不数值化（05 §2.3）。 */
@Serializable
data class OpenclawAnchor(
    val id: String,
    val seq: Long,
)

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
 * 会话行。**契约 required 13 字段全量建模**（hub-api-v1.yaml:783，P0 建模；缺字段会在反序列化或验收对比时翻车）。
 * 不透明 ID 禁令（06 §12.2 义务 3）：sessionKey/sessionId/gatewayId 原样透传、不解析、不数值化。
 */
@Serializable
data class ConversationRow(
    val conversationId: String,
    /** 归属网关：一个账号可挂多网关（08 §4.1），会话必须能归属。 */
    val gatewayId: String,
    /** Gateway 逻辑会话键 `agent:<agentId>:<mainKey>` 文法——原样保留、不解析。 */
    val sessionKey: String,
    /** 当前化身（物理会话代次）标识；化身更替后新代次 seq 从 1 重计（05 §3.10）。 */
    val sessionId: String,
    /** 化身围栏计数：变化即化身更替（新 sessionId + 新 revision 随 session.updated 下发）。 */
    val lifecycleRevision: Long,
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

/** 消息行。合并键 = messageId（upsert）+ seq（当前化身内插入顺序）；与事件信封 id 是两条独立序列（05 §2.2/§3.11）。 */
@Serializable
data class MessageRow(
    /** Hub ULID，upsert 主键。 */
    val messageId: String,
    val conversationId: String? = null,
    val seq: Long,
    /** 仅 user/assistant（服务端读取面已过滤合成行）；宽松解析用 String，勿建封闭枚举。 */
    val role: String,
    val content: String,
    /** final / streaming / failed；流式行允许跳过 delta，终态由 created/replaced 收敛（05 §3.11）。 */
    val state: String,
    /** 仅用户行：sending/delivered/read/failed；服务端现状不产出 read 态，UI 不得依赖。 */
    val uiState: String? = null,
    @SerialName("__openclaw") val openclaw: OpenclawAnchor? = null,
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
 * 事件信封（REST 增量与 WSS event 帧完全同构，同一 events 表行）。
 * 幂等去重键 = id（INV-1）；**未知 type：忽略内容但必须照常推进游标**（06 §12.2 义务 2）。
 * data 统一用 JsonElement 承载——宽松解析，各域载荷按 type 分发解析（P2）。
 */
@Serializable
data class EventEnvelope(
    /** 全局事件 ULID：去重键 + 游标推进依据（06 §2.1）。 */
    val id: String,
    val type: String,
    val conversationId: String? = null,
    /** run/tool 域信封必带（06 §2.2）。 */
    val runId: String? = null,
    val data: JsonElement? = null,
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

/** 全量快照（08 §4.9）：不含 approvals 与 run 明细（两者只能从事件流累积，交接包 §5.4 边界）。 */
@Serializable
data class SnapshotResponse(
    /** 空系统为 26 个 0 的零值 ULID；落定后 cursor = 此值。 */
    val snapshotWatermark: String,
    val generatedAt: String,
    /** 冷启动网关列表来源：offline 网关在此即可见（不依赖事件）。 */
    val gateways: List<GatewaySummary> = emptyList(),
    val conversations: List<ConversationRow> = emptyList(),
    /** 每会话尾页 50 行；键为 conversationId（P2 对 yaml 校对定形）。 */
    val messages: Map<String, List<MessageRow>>? = null,
    /** readCursors 结构 P2 对 yaml 校对后定形。 */
    val readCursors: JsonElement? = null,
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

@Serializable
data class CommandReceipt(
    val commandId: String,
    val idempotencyKey: String,
    val state: String,
    val acceptedAt: String,
)
