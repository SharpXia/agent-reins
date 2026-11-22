package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 2026-11-22 B3 根修回归锚：`maxRenderedReadSeq` 排除流式行（已读上报不借「未来 seq」）。
 *
 * 根因链：投影层流式占位行 seq=conv.lastSeq+1（不推进 lastSeq，DefaultEventProjector 同口径）
 * 恰与该 run assistant final 行落库的真实 seq 同值——旧内联口径把流式行计入水位，流式期按该
 * seq 上报被服务端 readUpTo 存在性校验恒拒（400 HUB-BIZ-004），基线已毒化且 final 收敛时
 * seq 同值不触发重报→游标永久落后一拍（countUnread=1、重进才自愈）。排除后：流式期水位停
 * 在 lastSeq，final 收敛时 messageId 换新（dstream:→26hex）、state=streaming→final，水位真实
 * 跳变 lastSeq→S 触发重报且此刻行已存在→受理回声清零。
 * 与 iOS HubViews.swift HubChatView maxRenderedSeq 口径双端对锁（iOS 无单测 target，桌面
 * 回归锚兼作两端行为契约不漂移，StreamingTypewriterPacingTest 同纪律）。
 */
class ReadReportWatermarkTest {

    private fun msg(id: String, seq: Long, state: String) = UiMessage(
        messageId = id,
        seq = seq,
        role = "assistant",
        content = "内容",
        state = state,
        uiState = null,
        ts = null,
    )

    @Test
    fun streamingRowsExcludedFromWatermark() {
        // 快照两行 final + 流式占位行 seq=lastSeq+1（与未来 final 行真实 seq 同值）：
        // 流式期水位停在 2，不借未来 seq 3 上报（服务端该 seq 行尚不存在，恒拒 400）
        val messages = listOf(
            msg("user-1", 1L, "final"),
            msg("done-2", 2L, "final"),
            msg("dstream:run-abc", 3L, "streaming"),
        )
        assertEquals(2L, maxRenderedReadSeq(messages))
    }

    @Test
    fun finalConvergenceJumpsWatermarkToRealSeq() {
        // final 收敛落库：messageId 换新（dstream:→26hex）、seq 同值 3、state=final → 计入，
        // 水位 2→3 真实跳变触发重报（旧口径水位同值不触发=游标落后一拍的收敛盲区）
        val messages = listOf(
            msg("user-1", 1L, "final"),
            msg("done-2", 2L, "final"),
            msg("01hgr8m3k2vq5w7x9z1a3b4c5d", 3L, "final"),
        )
        assertEquals(3L, maxRenderedReadSeq(messages))
    }

    @Test
    fun optimisticSentinelAndFailedRowsExcluded() {
        // 乐观哨兵 seq=Long.MAX_VALUE 与 failed 行不计入；流式+哨兵+failed 全排除时水位回落
        // 到最后的真实行
        val messages = listOf(
            msg("opt-sentinel", Long.MAX_VALUE, "final"),
            msg("bad-5", 5L, "failed"),
            msg("dstream:run-abc", 6L, "streaming"),
            msg("done-4", 4L, "final"),
        )
        assertEquals(4L, maxRenderedReadSeq(messages))
    }

    @Test
    fun emptyOrFullyExcludedMessagesYieldZero() {
        assertEquals(0L, maxRenderedReadSeq(emptyList()))
        // 会话刚打开只有流式占位行：水位 0，不预支已读
        assertEquals(0L, maxRenderedReadSeq(listOf(msg("dstream:run-abc", 1L, "streaming"))))
    }
}
