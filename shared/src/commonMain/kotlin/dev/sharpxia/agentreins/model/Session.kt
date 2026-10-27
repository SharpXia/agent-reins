package dev.sharpxia.agentreins.model

import kotlinx.serialization.Serializable

/**
 * 网关上的一次 Agent 会话。M2.5 起带会话管理富字段（A7 子集）：
 * unread（未读数）/ pinned（置顶）/ archived（归档）/ kind（渠道类型），
 * 列表可离线渲染；color/runtime/outputTokens 留 v1.1。
 */
@Serializable
data class Session(
    val id: String,
    val title: String? = null,
    /** 最近一条消息的预览，供会话列表展示。 */
    val preview: String? = null,
    /** 最近活跃时间（epoch millis）。 */
    val lastActiveAtMs: Long? = null,
    /** 未读消息数（>0 列表显未读点；非当前会话的 agent 事件累加）。 */
    val unread: Int = 0,
    /** 置顶会话排在最前。 */
    val pinned: Boolean = false,
    /** 归档会话列表默认过滤。 */
    val archived: Boolean = false,
    /** 会话类型（main/telegram/…渠道 surface），列表可按渠道渲染标识。 */
    val kind: String? = null,
    /** 归属 agentId（M3：Agent 域分组/身份色）。网关行字段，缺失时按 key 形态解析。 */
    val agentId: String? = null,
    /** 本地隐藏（M3.2：隐私分区，纯本地 overlay 值，网关无此概念）。 */
    val hidden: Boolean = false,
)

/** 会话 key `agent:<agentId>:<surface>` → agentId（telegram/cron 等嵌套 surface 取第二段）。 */
fun sessionKeyAgentId(key: String): String? {
    if (!key.startsWith("agent:")) return null
    return key.removePrefix("agent:").substringBefore(':').takeIf { it.isNotBlank() }
}
