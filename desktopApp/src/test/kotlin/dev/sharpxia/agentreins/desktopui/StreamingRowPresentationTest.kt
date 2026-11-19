package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 2026-11-19 纯显示偏好（流式输出/思考中指示）：助手流式行呈现决策纯函数单测（:desktopApp:test
 * 锁行为不只锁存取——TimelineScrollTest 纯函数回归锚同纪律；desktopApp 模块测试任务实名
 * :desktopApp:test，「desktopTest」简写实指 shared 的 :shared:desktopTest，2026-11-19 清账③
 * 纠名）。桌面↔iOS parity 口径：iOS 侧 HubStore.streamingPlayback/thinkingIndicator
 * （UserDefaults 持久化）+ HubMessageRow 早退分支/typewriter/waveform 徽标的 runActive 门控
 * 同语义，iOS 无单测 target，桌面回归锚兼作两端行为契约不漂移。
 */
class StreamingRowPresentationTest {

    // ── ① 活跃 run（runActive=true）八矩阵：streaming × (contentBlank × streamingPlayback × thinkingIndicator) ──

    @Test
    fun streamingRowMatrix() {
        // 流式开 + 思考开（= 现状既有行为）：空正文占位、非空正文渐进（▌ 光标 + 流式 chip）
        assertEquals(
            StreamingRowPresentation.Placeholder,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = true, thinkingIndicator = true, runActive = true),
        )
        assertEquals(
            StreamingRowPresentation.Progressive,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = true, thinkingIndicator = true, runActive = true),
        )
        // 流式开 + 思考关：空正文行静默（思考指示关 = 占位信号撤显示）、非空照常渐进
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = true, thinkingIndicator = false, runActive = true),
        )
        assertEquals(
            StreamingRowPresentation.Progressive,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = true, thinkingIndicator = false, runActive = true),
        )
        // 流式关 + 思考开：非空增量行回落占位（等 final 权威行落库时整段接管）
        assertEquals(
            StreamingRowPresentation.Placeholder,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = false, thinkingIndicator = true, runActive = true),
        )
        // 流式关 + 思考关：双关全静默
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = false, thinkingIndicator = false, runActive = true),
        )
        // 流式关 + 思考开 + 空正文：占位（同现状空正文分支口径）
        assertEquals(
            StreamingRowPresentation.Placeholder,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = false, thinkingIndicator = true, runActive = true),
        )
        // 流式关 + 思考关 + 空正文：静默
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = false, thinkingIndicator = false, runActive = true),
        )
    }

    // ── ② 僵尸流式行（runActive=false，2026-11-19 清账①）：不挂任何进行中信号 ──
    //      空正文 = Silent 整行撤显示；非空正文 = null 按普通内容整段渲染（调用侧光标/chip
    //      随 presentation 门控，无 ▌ 无「流式生成中」）——与两开关取值无关（会话无活跃 run
    //      时「正在处理中/流式生成中」都是假信号；场景 = run 终态事件未达或终态后迟到 delta
    //      重建行，等 final 权威行或用户新意图收敛）。

    @Test
    fun zombieStreamingRows() {
        // 空正文：两开关组合均静默（整行撤显示）
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = true, thinkingIndicator = true, runActive = false),
        )
        assertEquals(
            StreamingRowPresentation.Silent,
            streamingRowPresentation("streaming", contentBlank = true, streamingPlayback = false, thinkingIndicator = false, runActive = false),
        )
        // 非空正文：四组合均 null（普通内容整段渲染，无进行中信号）
        assertNull(streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = true, thinkingIndicator = true, runActive = false))
        assertNull(streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = false, thinkingIndicator = true, runActive = false))
        assertNull(streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = true, thinkingIndicator = false, runActive = false))
        assertNull(streamingRowPresentation("streaming", contentBlank = false, streamingPlayback = false, thinkingIndicator = false, runActive = false))
    }

    // ── ③ final/failed 行恒返回 null：不进占位决策、照常整段渲染（重进会话不丢数据的 UI 面；
    //      两开关与 runActive 对非流式行零影响） ──

    @Test
    fun nonStreamingStatesReturnNull() {
        assertNull(streamingRowPresentation("final", contentBlank = false, streamingPlayback = true, thinkingIndicator = true, runActive = true))
        assertNull(streamingRowPresentation("final", contentBlank = true, streamingPlayback = false, thinkingIndicator = false, runActive = true))
        assertNull(streamingRowPresentation("failed", contentBlank = false, streamingPlayback = false, thinkingIndicator = true, runActive = true))
        assertNull(streamingRowPresentation("failed", contentBlank = true, streamingPlayback = true, thinkingIndicator = false, runActive = true))
        assertNull(streamingRowPresentation("final", contentBlank = false, streamingPlayback = true, thinkingIndicator = true, runActive = false))
        assertNull(streamingRowPresentation("failed", contentBlank = true, streamingPlayback = true, thinkingIndicator = false, runActive = false))
    }

    // ── ④ 占位文案两态逐字锁（2026-11-07 二轮回复占位行既有文案同源，桌面↔iOS 不漂移） ──

    @Test
    fun placeholderTextStates() {
        assertEquals("正在思考…▌", assistantPlaceholderText(thinkingNow = true, agentLabel = "hermes"))
        assertEquals("hermes 正在处理中…▌", assistantPlaceholderText(thinkingNow = false, agentLabel = "hermes"))
    }
}
