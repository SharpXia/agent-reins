package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wp-10 消息操作规则纯函数单测（desktopApp jvmTest）：可重发/可复制判定、失败原因六值闭集
 * 映射、空正文防线渲染侧兜底。口径与 iOS wp-8（HubViews.swift messageContextMenu /
 * canResend / failedReasonLabel）逐一对应，回归锚 = 桌面↔iOS parity 不漂移。
 */
class MessageActionRulesTest {

    private fun msg(
        role: String = "user",
        content: String = "hello",
        state: String = "final",
        uiState: String? = null,
        errorKind: String? = null,
    ) = UiMessage(
        messageId = "m1", seq = 1, role = role, content = content,
        state = state, uiState = uiState, ts = null, errorKind = errorKind,
    )

    // ── 复制全文：非合成行（role≠""）且正文非空 ──

    @Test
    fun copyOfferedForNonEmptyNonSyntheticRows() {
        assertTrue(isCopyable(msg(role = "user")))
        assertTrue(isCopyable(msg(role = "assistant", content = "# 标题\n正文")))
        // 空正文无可复制内容
        assertFalse(isCopyable(msg(role = "user", content = "")))
        // 合成行（role=""，compaction/reset 标记行）无正文语义——iOS 侧同口径不挂菜单
        assertFalse(isCopyable(msg(role = "", content = "已压缩")))
    }

    // ── 重发：仅「失败的用户行 + 非空正文」 ──

    @Test
    fun resendOnlyForFailedUserRowsWithBody() {
        // 命中：uiState=failed（乐观行发送失败）与 state=failed（服务端消息失败）两条路径共列
        assertTrue(isResendable(msg(role = "user", uiState = "failed")))
        assertTrue(isResendable(msg(role = "user", state = "failed", errorKind = "unknown")))
        // 助手失败行无对应命令面（无 run.rerun），只提供拷贝
        assertFalse(isResendable(msg(role = "assistant", state = "failed")))
        // 空正文防线：重发空 payload 无意义（发送条本就禁空）
        assertFalse(isResendable(msg(role = "user", uiState = "failed", content = "")))
        // 非 failed 态不可重发
        assertFalse(isResendable(msg(role = "user", uiState = "sending")))
        assertFalse(isResendable(msg(role = "user", uiState = "delivered")))
        assertFalse(isResendable(msg(role = "user", uiState = "read")))
        assertFalse(isResendable(msg(role = "user", state = "streaming")))
        // 合成行不在重发面
        assertFalse(isResendable(msg(role = "", uiState = "failed")))
    }

    // ── 失败原因：六值脱敏闭集 → 人读文案；未分类不虚构 ──

    @Test
    fun failedReasonMapsSixValueClosedSet() {
        assertEquals("内容拒绝", failedReasonLabel(msg(state = "failed", errorKind = "refusal")))
        assertEquals("超时", failedReasonLabel(msg(state = "failed", errorKind = "timeout")))
        assertEquals("限流", failedReasonLabel(msg(uiState = "failed", errorKind = "rate_limit")))
        assertEquals("上下文超限", failedReasonLabel(msg(state = "failed", errorKind = "context_length")))
        assertEquals("状态冲突", failedReasonLabel(msg(state = "failed", errorKind = "state_contention")))
        assertEquals("未知原因", failedReasonLabel(msg(state = "failed", errorKind = "unknown")))
        // 未分类失败（reason=NULL）不虚构文案
        assertNull(failedReasonLabel(msg(state = "failed", errorKind = null)))
        // 仅 failed 行携带；final/streaming 行带 reason 也不展示
        assertNull(failedReasonLabel(msg(state = "final", errorKind = "refusal")))
        assertNull(failedReasonLabel(msg(state = "streaming", errorKind = "timeout")))
    }

    // ── 空正文防线（wp-8③ 渲染侧兜底：投影层零过滤，防线只能在渲染侧） ──

    @Test
    fun emptyAssistantFinalRowUsesPlaceholder() {
        // final/failed 空助手行 → 「（无文本内容）」占位
        assertTrue(isEmptyBodyPlaceholder(msg(role = "assistant", content = "", state = "final")))
        assertTrue(isEmptyBodyPlaceholder(msg(role = "assistant", content = "", state = "failed")))
        // streaming 空行是 typing 占位（…▌，wp-7 同型），不算占位行
        assertFalse(isEmptyBodyPlaceholder(msg(role = "assistant", content = "", state = "streaming")))
        // 非空不占位
        assertFalse(isEmptyBodyPlaceholder(msg(role = "assistant", content = "正文", state = "final")))
        // 用户行与合成行不适用该口径
        assertFalse(isEmptyBodyPlaceholder(msg(role = "user", content = "", state = "final")))
        assertFalse(isEmptyBodyPlaceholder(msg(role = "", content = "", state = "final")))
    }

    @Test
    fun emptyUserBodyUsesPlaceholder() {
        // 空 SENDING 用户行不渲染空气泡（…占位 + 状态徽标照常翻转）
        assertTrue(isEmptyUserBody(msg(role = "user", content = "", uiState = "sending")))
        assertTrue(isEmptyUserBody(msg(role = "user", content = "", uiState = "failed")))
        assertFalse(isEmptyUserBody(msg(role = "user", content = "正文")))
        assertFalse(isEmptyUserBody(msg(role = "assistant", content = "")))
    }
}
