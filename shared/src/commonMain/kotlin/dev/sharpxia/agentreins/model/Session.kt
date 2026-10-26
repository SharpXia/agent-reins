package dev.sharpxia.agentreins.model

import kotlinx.serialization.Serializable

/** 网关上的一次 Agent 会话。 */
@Serializable
data class Session(
    val id: String,
    val title: String? = null,
    /** 最近一条消息的预览，供会话列表展示。 */
    val preview: String? = null,
    /** 最近活跃时间（epoch millis）。 */
    val lastActiveAtMs: Long? = null,
)
