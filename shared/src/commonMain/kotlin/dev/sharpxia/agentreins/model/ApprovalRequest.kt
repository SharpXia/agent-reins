package dev.sharpxia.agentreins.model

import kotlinx.serialization.Serializable

/** 网关 approvals 通道下发的一次审批请求，对应 UI 中的审批卡片。 */
@Serializable
data class ApprovalRequest(
    val id: String,
    val sessionId: String? = null,
    /** 待审批操作的摘要（一句话）。 */
    val summary: String,
    /** 操作详情原文（JSON 字符串），展开查看。 */
    val detailJson: String? = null,
    val requestedAtMs: Long? = null,
)
