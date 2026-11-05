package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiMessage

/**
 * 消息操作规则（wp-10 桌面 parity，纯函数层；口径与 iOS wp-8 逐一对应）：
 * - 复制全文：非合成行且正文非空（合成行 role="" 是 compaction/reset 标记行，无正文语义）；
 * - 重发：仅「失败的用户行 + 非空正文」——助手失败行无对应命令面（无 run.rerun），只提供拷贝；
 *   空正文不可重发（发送条本就禁空，重发空 payload 无意义）；
 * - 失败原因：wp-4 六值脱敏闭集（refusal/timeout/rate_limit/context_length/state_contention/unknown）
 *   → 人读文案；仅 failed 行携带，未分类失败（reason=NULL）不虚构；
 * - 空正文防线（wp-8③ 渲染侧兜底）：投影层零过滤、空 content 行照常入库，防线只能在渲染侧。
 *
 * 重发语义（ADR-07）：用户显式重发 = 新意图新键——sendMessage 每次调用生成新幂等键
 * （DefaultCommandClient.newUuid），不做键复用，Hub commands 表落独立记录。
 */

/** 可重发行 = 失败的用户行（发送失败）且正文非空。 */
internal fun isResendable(m: UiMessage): Boolean =
    m.role == "user" && (m.uiState == "failed" || m.state == "failed") && m.content.isNotEmpty()

/** 可复制行 = 非合成行（role≠""）且正文非空。 */
internal fun isCopyable(m: UiMessage): Boolean = m.role.isNotEmpty() && m.content.isNotEmpty()

/** 失败原因（六值闭集 → 人读文案）；仅 failed 行携带，未分类失败为 null（不虚构）。 */
internal fun failedReasonLabel(m: UiMessage): String? {
    if (m.state != "failed" && m.uiState != "failed") return null
    return when (m.errorKind) {
        "refusal" -> "内容拒绝"
        "timeout" -> "超时"
        "rate_limit" -> "限流"
        "context_length" -> "上下文超限"
        "state_contention" -> "状态冲突"
        "unknown" -> "未知原因"
        else -> null
    }
}

/**
 * final/failed 空正文助手行 → 渲染占位「（无文本内容）」（italics 降调）；
 * streaming 空行走 typing 占位（…▌，wp-7 同型），不算占位行。口径 = [ConversationScreen.MessageRow]
 * 的 assistant 分支（role 非空且非 user）。
 */
internal fun isEmptyBodyPlaceholder(m: UiMessage): Boolean =
    m.role.isNotEmpty() && m.role != "user" && m.state != "streaming" && m.content.isEmpty()

/**
 * 空正文用户行（发送在途/失败）→ 渲染占位「…」，维持状态徽标照常翻转
 * （wp-8③：不渲染空气泡）。空正文防线只在渲染侧，投影不发明内容。
 */
internal fun isEmptyUserBody(m: UiMessage): Boolean = m.role == "user" && m.content.isEmpty()
