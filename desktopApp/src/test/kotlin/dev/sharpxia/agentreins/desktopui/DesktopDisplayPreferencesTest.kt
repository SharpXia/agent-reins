package dev.sharpxia.agentreins.desktopui

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-11-19 显示偏好存储单测（照抄 DesktopWindowPreferencesTest 三用例形态）：缺省值
 * （两开关缺省 = 开，保持既有行为；缺键回落 true）+ 持久化往返（新实例读同一节点 = 重启后
 * 回落语义）+ Preferences 不可用时纯内存态降级不抛。用独立 probe 节点（随机 UUID）隔离，
 * finally removeNode 清理，不污染用户真实偏好。
 */
class DesktopDisplayPreferencesTest {

    private fun probeNode(): Preferences =
        Preferences.userRoot().node("agentreins-test/display-prefs-probe/${UUID.randomUUID()}")

    @Test
    fun defaultsAreBothEnabled() {
        val node = probeNode()
        try {
            val prefs = DesktopDisplayPreferences(node)
            assertTrue(prefs.streamingPlayback, "缺省 = 流式输出开（保持现状）")
            assertTrue(prefs.thinkingIndicator, "缺省 = 思考中指示开（保持现状）")
            // 空节点（无键）也回落缺省 true（覆盖首装/旧档无键/持久化损坏）
            val fresh = probeNode()
            try {
                val empty = DesktopDisplayPreferences(fresh)
                assertTrue(empty.streamingPlayback, "空节点回落缺省 true（流式输出）")
                assertTrue(empty.thinkingIndicator, "空节点回落缺省 true（思考中指示）")
            } finally {
                fresh.removeNode()
            }
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun displayPrefsRoundTripsAcrossInstances() {
        val node = probeNode()
        try {
            val first = DesktopDisplayPreferences(node)
            assertTrue(first.streamingPlayback)
            assertTrue(first.thinkingIndicator)

            first.setStreamingPlayback(false)
            assertFalse(first.streamingPlayback, "内存镜像即时生效（流式输出）")
            // 两键互不串扰（同节点键族不重叠）
            assertTrue(first.thinkingIndicator, "流式输出翻转不影响思考中指示")
            // 新实例读同一节点 = 重启后回落语义：跨登出/重启保留可见
            val second = DesktopDisplayPreferences(node)
            assertFalse(second.streamingPlayback, "流式输出关持久化可见")
            assertTrue(second.thinkingIndicator)

            first.setThinkingIndicator(false)
            assertFalse(first.thinkingIndicator, "内存镜像即时生效（思考中指示）")
            assertFalse(DesktopDisplayPreferences(node).thinkingIndicator, "思考中指示关持久化可见")

            // 切回缺省
            first.setStreamingPlayback(true)
            first.setThinkingIndicator(true)
            assertTrue(DesktopDisplayPreferences(node).streamingPlayback, "流式输出切回开持久化可见")
            assertTrue(DesktopDisplayPreferences(node).thinkingIndicator, "思考中指示切回开持久化可见")
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun nullNodeDegradesToMemoryOnlyWithoutThrowing() {
        // Preferences 不可用（node = null）：两键缺省 true + 全程内存态、不抛——静默降级纪律同面延伸
        val prefs = DesktopDisplayPreferences(node = null)
        assertTrue(prefs.streamingPlayback, "降级态缺省 = 流式输出开")
        assertTrue(prefs.thinkingIndicator, "降级态缺省 = 思考中指示开")
        prefs.setStreamingPlayback(false)
        prefs.setThinkingIndicator(false)
        assertFalse(prefs.streamingPlayback)
        assertFalse(prefs.thinkingIndicator)
        assertEquals(false, prefs.streamingPlayback)
        assertEquals(false, prefs.thinkingIndicator)
        prefs.setStreamingPlayback(true)
        prefs.setThinkingIndicator(true)
        assertTrue(prefs.streamingPlayback)
        assertTrue(prefs.thinkingIndicator)
    }
}
