package dev.sharpxia.agentreins.desktopui

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * wp-14 关窗行为偏好存储单测：缺省值（缺省 = 关窗最小化到托盘，验收「默认关窗最小化」）+
 * 持久化往返（新实例读同一节点 = 重启后回落语义）+ Preferences 不可用时纯内存态降级不抛。
 * 用独立 probe 节点（随机 UUID）隔离，finally removeNode 清理，不污染用户真实偏好。
 */
class DesktopWindowPreferencesTest {

    private fun probeNode(): Preferences =
        Preferences.userRoot().node("agentreins-test/wp-14-probe/${UUID.randomUUID()}")

    @Test
    fun defaultIsMinimizeToTrayOnClose() {
        val node = probeNode()
        try {
            assertTrue(DesktopWindowPreferences(node).minimizeToTrayOnClose, "缺省 = 关窗最小化到托盘")
            // 空节点（无键）也回落缺省 true
            val fresh = probeNode()
            try {
                assertTrue(DesktopWindowPreferences(fresh).minimizeToTrayOnClose, "空节点回落缺省 true")
            } finally {
                fresh.removeNode()
            }
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun closeBehaviorRoundTripsAcrossInstances() {
        val node = probeNode()
        try {
            val first = DesktopWindowPreferences(node)
            assertTrue(first.minimizeToTrayOnClose)

            first.setMinimizeToTrayOnClose(false)
            assertFalse(first.minimizeToTrayOnClose, "内存镜像即时生效")
            // 新实例读同一节点 = 重启后回落语义：关窗退出持久化可见
            val second = DesktopWindowPreferences(node)
            assertFalse(second.minimizeToTrayOnClose, "关窗退出持久化可见")

            // 切回关窗最小化
            first.setMinimizeToTrayOnClose(true)
            assertTrue(DesktopWindowPreferences(node).minimizeToTrayOnClose, "切回最小化持久化可见")
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun nullNodeDegradesToMemoryOnlyWithoutThrowing() {
        // Preferences 不可用（node = null）：缺省 true + 全程内存态、不抛——静默降级纪律同面延伸
        val prefs = DesktopWindowPreferences(node = null)
        assertTrue(prefs.minimizeToTrayOnClose, "降级态缺省 = 关窗最小化")
        prefs.setMinimizeToTrayOnClose(false)
        assertFalse(prefs.minimizeToTrayOnClose)
        prefs.setMinimizeToTrayOnClose(true)
        assertTrue(prefs.minimizeToTrayOnClose)
        assertEquals(true, prefs.minimizeToTrayOnClose)
    }
}
