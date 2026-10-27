package dev.sharpxia.agentreins

import dev.sharpxia.agentreins.model.ApprovalRequest
import dev.sharpxia.agentreins.model.ConversationState
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 网关连接配置。凭据只存在于运行时与系统安全存储，绝不以明文进入仓库。
 * 凭证优先级由客户端解析：bootstrapToken（扫码配对）> apiKey（共享 token）> deviceToken。
 */
data class GatewayConfig(
    val baseUrl: String,
    val apiKey: String = "",
    /** `openclaw qr` setup code 中的 bootstrap token（短时效、按角色分段兑换）。 */
    val bootstrapToken: String? = null,
    /** hello-ok 下发的设备凭证；配对成功后可免共享 token 直连。 */
    val deviceToken: String? = null,
    /** 额外申请的 scope（M3.5：审批面板开启时带 operator.approvals；scope 升级需 owner 重批准）。 */
    val extraScopes: List<String> = emptyList(),
) {
    override fun toString(): String = "GatewayConfig(baseUrl=$baseUrl, credentials=***masked***)"
}

/**
 * Agent 后端的统一接缝：UI 只认本接口与 model 包下的五个类型。
 *
 * v1.0 由唯一真实实现 OpenClawClient（网关 WS 为主、OpenAI 兼容 HTTP 为备）塑形；
 * v1.1 计划接入 OpenAI 兼容直连与自建编排引擎。接口随真实实现演进，防过度抽象。
 */
interface AgentClient {
    /** 连接状态（含断线重连后的最新状态）。 */
    val status: StateFlow<GatewayStatus>

    /** 会话列表（连接后由网关事件驱动更新）。 */
    val sessions: StateFlow<List<Session>>

    suspend fun connect(config: GatewayConfig)

    suspend fun disconnect()

    /**
     * 指定会话的对话流状态（单一数据源）：历史回填 + agent 事件流归并的有序条目
     * （Message / ToolCallEvent），UI 直接渲染该状态。
     */
    suspend fun conversation(sessionKey: String): StateFlow<ConversationState>

    /** 回填会话历史（chat.history，重置分页取最新一页），与事件态合并（参数/结果来自 transcript）。 */
    suspend fun loadHistory(sessionKey: String, limit: Int = 50)

    /** 向上翻页懒加载：取更早一页历史并前插；是否还有更早页见 ConversationState.hasMoreHistory。 */
    suspend fun loadHistoryOlder(sessionKey: String, limit: Int = 50)

    /** 待审批请求流。 */
    val approvals: Flow<ApprovalRequest>

    suspend fun send(sessionId: String, content: String)

    /** 对审批请求做出决定。 */
    suspend fun respondApproval(requestId: String, approved: Boolean)
}
