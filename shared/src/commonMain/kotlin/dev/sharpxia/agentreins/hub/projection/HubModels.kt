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
    /**
     * 可选（v1.1 additive，2026-11-06，wp-3 活跃 run 权威回填）：会话当前 RUNNING run 的
     * Gateway 原生 runId（服务端 runs.state='RUNNING' 权威行，快照/校准随行带出）——
     * 冷启动后 Stop 按钮（REST #11 run.cancel 路径参数）即可用，无需等事件流 run.started
     * 补齐（此前快照/校准路径不带 runId，冷启动后 Stop 恒「结束中」）。无 RUNNING run
     * 字段省略；宽解析容忍缺省——老服务端快照行无此字段即 null，事件流 run.* 仍为
     * 活跃翻转的实时通道（与 setConversationActiveRun 事件路径共用同一列）。
     */
    val activeRunId: String? = null,
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
    /**
     * 可选（v1.1 additive，2026-11-05，wp-4）：失败行原因分类，仅 `state: failed` 行可携带
     * （message.failed 的 reason 脱敏六值闭集 refusal/timeout/rate_limit/context_length/
     * state_contention/unknown）；非失败行与未分类失败行字段省略。宽解析容忍缺省——
     * 老快照/历史行无此字段即 null。落库映射 hubMessages.reason，与事件路径共用同一列。
     */
    val errorKind: String? = null,
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
        // 事件类型（06 §3 注册表镜像，唯一注册清单；新类型只增不改义）。
        // artifact 域已核准预留、本期不实现。run.status 为 wp-15 additive（2026-11-06），
        // 21 类基础上第 22 类——老客户端走 else 忽略分支语义不变（06 §12.2）。
        const val MESSAGE_DELTA = "message.delta"
        const val MESSAGE_CREATED = "message.created"
        const val MESSAGE_REPLACED = "message.replaced"
        const val MESSAGE_FAILED = "message.failed"
        const val RUN_STARTED = "run.started"
        const val RUN_COMPLETED = "run.completed"
        const val RUN_FAILED = "run.failed"
        const val RUN_ABORTED = "run.aborted"
        const val RUN_STATUS = "run.status"
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

/**
 * #16 conversation.create：label 与 idempotencyKey 均可选（缺省 Hub 生成幂等键、重试安全）。
 * mainSession（v1.1 additive，#16 主线锚定 2026-11-06）：true = 锚定该 agent 专属主线——
 * 服务端据此在命令载荷组装 sessionKey = agent:<agentId>:main（客户端永不拼键，ADR-14），
 * 桥侧原样透传 Gateway sessions.create 原生可选 key（同名 key 为 adopt 语义，不 fork）；
 * 缺省（null，不编码）保持现状 dashboard 键行为，Gateway 仍是会话归属权威。
 */
@Serializable
data class CreateConversationRequest(
    val label: String? = null,
    val idempotencyKey: String? = null,
    val mainSession: Boolean? = null,
)

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

/**
 * 端点 #21 命令目录项（v1.1 additive，2026-11-07）：Gateway commands.list 的**有界透传视图**——
 * 服务端只回白名单字段，args/nativeName 等内幕字段不过客户端面（去会话化纪律同时生效：
 * 请求/响应体零 sessionId/sessionKey）。展示/发送文本取 textAliases 首选（Gateway mapCommand
 * 已剥前导 "/"，name 本无斜杠），缺省由客户端补 "/"；category 为 Gateway 六分类的可读枚举
 * （session/options/status/management/media/tools），未知值客户端原样展示。
 */
@Serializable
data class CommandListItem(
    /** 归一命令名（无前导 "/"，Gateway 已剥）；客户端合并去重键 = 去前导 "/" 后本值。 */
    val name: String,
    /** 展示/发送文本别名（契约 "/" 前缀闭集 ≤20）；缺省时客户端以 "/" + name 兜底。 */
    val textAliases: List<String>? = null,
    /** 人读描述（≤2000，客户端面板一行截断展示）。 */
    val description: String,
    /** Gateway 六分类（session/options/status/management/media/tools）；上游缺省即 null。 */
    val category: String? = null,
    /** 来源：native / skill / plugin。 */
    val source: String,
    /** true = 接参数：选中预填「名+空格」续写（既有行为，零改动）。 */
    val acceptsArgs: Boolean,
    /** 仅 skill 来源带（展示名）；native/plugin 缺省 null。 */
    val skillDisplayName: String? = null,
    /**
     * 可选（v1.1 additive，2026-11-08 修复轮，斜杠交互卡数据面）：参数定义有界视图——
     * 白名单仅 name/required/choices + dynamic；choices 为 Gateway 静态选项闭集（动态
     * 闭集 Gateway 不解析为值、仅标 dynamic:true——2026-11-08 自检轮勘误，动态选项的
     * 值经其他结构化出口解析，如 /think 的 levels 来自 #22 thinkingLevels），客户端
     * 交互卡点选项发送 `/命令 <值>`（值原样回填，ADR-14）。无参命令/旧桥（includeArgs
     * 仍 false）缺省 null。
     */
    val args: List<CommandArg>? = null,
)

/** 命令参数有界视图（与桥帧 $defs/CommandListItem.args.items 同形）。 */
@Serializable
data class CommandArg(
    /** 参数名。 */
    val name: String,
    /** true = 必填参数（裸发命令按 Gateway 用法提示回应）。 */
    val required: Boolean? = null,
    /** 可选：静态选项闭集（≤50，交互卡数据面）。 */
    val choices: List<CommandChoice>? = null,
    /** 可选（v1.1 additive，2026-11-08 自检轮）：true = Gateway 函数型动态闭集（值按当前模型/配置解析，commands.list 不下发值）。 */
    val dynamic: Boolean? = null,
)

/** 选项条目：value 为发送文本取值（原样回填），label 为展示名。 */
@Serializable
data class CommandChoice(val value: String, val label: String)

/** 端点 #21 响应体（v1.1 additive）：truncated=true 表示列表被截断（Gateway 500 上限无分页或桥帧预算 256KiB）。 */
@Serializable
data class CommandsList(
    val commands: List<CommandListItem> = emptyList(),
    val truncated: Boolean? = null,
)

/**
 * 端点 #22 命令目录项（v1.1 additive，2026-11-08）：Gateway models.list 的**有界透传视图**
 * ——服务端只回白名单字段（id/name/provider/available + thinkingLevels/thinkingDefault/
 * current，2026-11-08 自检轮 additive），contextWindow/runtimeChoices/agentRuntime 等
 * 其余内幕字段不过客户端面（去会话化纪律同时生效：请求/响应体零 sessionId/sessionKey）。
 * id 为 Gateway 原生模型标识（provider/model 规范形）——客户端交互卡点按钮发送
 * `/model <id>` 即用此值，客户端不拼装不改写（ADR-14：原样保留不数值化）。
 */
@Serializable
data class ModelOptionItem(
    /** Gateway 原生模型标识（provider/model 规范形；交互卡发送文本取值）。 */
    val id: String,
    /** 模型显示名。 */
    val name: String,
    /** 供应商标识（交互卡分节/小字展示）。 */
    val provider: String,
    /** true/缺省 = 可选；false = 当前不可用（客户端按钮置灰，不编造原因）。Gateway 投影对当前模型抽掉本键（缺失 + 无 current 标记的组合不出现）。 */
    val available: Boolean? = null,
    /**
     * 可选（v1.1 additive，2026-11-08 自检轮）：该模型的思考等级闭集（Gateway 原生
     * thinkingLevels:[{id,label}] 有界透出——/think 动态闭集的结构化数据出口；/think 卡
     * 数据源 = 会话当前模型条目的本字段 + default 前置，镜像 OpenClaw
     * listThinkingLevelChoices 形状）。
     */
    val thinkingLevels: List<CommandChoice>? = null,
    /** 可选（v1.1 additive，2026-11-08 自检轮）：该模型的默认思考等级（卡面标注当前默认档）。 */
    val thinkingDefault: String? = null,
    /** 可选（v1.1 additive，2026-11-08 自检轮）：true = 会话当前模型（桥把 Gateway 投影「抽掉 available 键」显式化，不臆造扩义；非投影路径无标记）。 */
    val current: Boolean? = null,
)

/** 端点 #22 响应体（v1.1 additive）：truncated=true 表示列表被截断（条目上限 100 或桥帧预算 256KiB）。 */
@Serializable
data class ModelsList(
    val models: List<ModelOptionItem> = emptyList(),
    val truncated: Boolean? = null,
)

@Serializable
data class CommandReceipt(
    val commandId: String,
    val idempotencyKey: String,
    val state: String,
    val acceptedAt: String,
)
