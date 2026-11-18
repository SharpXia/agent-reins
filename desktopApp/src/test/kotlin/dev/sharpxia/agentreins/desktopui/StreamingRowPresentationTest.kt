package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 2026-11-19 纯显示偏好（流式输出/思考中指示）：助手流式行呈现决策纯函数单测（desktopTest
 * 锁行为不只锁存取——TimelineScrollTest 纯函数回归锚同纪律）。桌面↔iOS parity 口径：iOS 侧
 * HubStore.streamingPlayback/thinkingIndicator（UserDefaults 持久化）+ HubMessageRow 早退分支
 * 同语义，iOS 无单测 target，桌面回归锚兼作两端行为契约不漂移。
 */
class StreamingRowPresentationTest {

    // ── ① 六矩阵：streaming × (contentBlank × streamingPlayback × thinkingIndicator) ──

    @Test
    fun streamingRowMatrix() {
        // 流式开 + 思考开（= 现状既有行为）：空正文占位、非空正文渐进（▌ 光标 + 流式 chip）
        assertEquals(
            StreamingRowPresentation.Placeholder,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = true, thinkingIndicator = true),
        )
        assertEquals(
            StreamingRowPresentation.Progressive,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = true, thinkingIndicator = true),
        )
        // 流式开 + 思考关：空正文行静默（思考指示关 = 占位信号撤显示）、非空照常渐进
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = true, thinkingIndicator = false),
        )
        assertEquals(
            StreamingRowPresentation.Progressive,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = true, thinkingIndicator = false),
        )
        // 流式关 + 思考开：非空增量行回落占位（等 final 权威行落库时整段接管）
        assertEquals(
            StreamingRowPresentation.Placeholder,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = false, thinkingIndicator = true),
        )
        // 流式关 + 思考关：双关全静默
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = false, thinkingIndicator = false),
        )
        // 流式关 + 思考开 + 空正文：占位（同现状空正文分支口径）
        assertEquals(
            StreamingRowPresentation.Placeholder,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = false, thinkingIndicator = true),
        )
        // 流式关 + 思考关 + 空正文：静默
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = false, thinkingIndicator = false),
        )
    }

    // ── ② final/failed 行恒返回 null：不进占位决策、照常整段渲染（重进会话不丢数据的 UI 面；
    //      两开关对非流式行零影响） ──

    @Test
    fun nonStreamingStatesReturnNull() {
        assertNull(streamingRowPresentation("final", contentBlank = false, streamingPlayback = true, thinkingIndicator = true))
        assertNull(streamingRowPresentation("final", contentBlank = true, streamingPlayback = false, thinkingIndicator = false))
        assertNull(streamingRowPresentation("failed", contentBlank = false, streamingPlayback = false, thinkingIndicator = true))
        assertNull(streamingRowPresentation("failed", contentBlank = true, streamingPlayback = true, thinkingIndicator = false))
    }

    // ── ③ 占位文案两态逐字锁（2026-11-07 二轮回复占位行既有文案同源，桌面↔iOS 不漂移） ──

    @Test
    fun placeholderTextStates() {
        assertEquals("正在思考…▌", assistantPlaceholderText(thinkingNow = true, agentLabel = "hermes"))
        assertEquals("hermes 正在处理中…▌", assistantPlaceholderText(thinkingNow = false, agentLabel = "hermes"))
    }
}
