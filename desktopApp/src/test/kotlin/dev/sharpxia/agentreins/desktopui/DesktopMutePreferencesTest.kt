package dev.sharpxia.agentreins.desktopui

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * wp-9 桌面按会话静音偏好存储单测（desktopTest）：静音集与总开关的内存权威读写 + 持久化
 * 往返（跨实例读取 = 重启后回落语义）+ 静音键前缀隔离（总开关键不混入静音集）。
 * 用独立 probe 节点（随机 UUID）隔离，finally removeNode 清理，不污染用户真实偏好。
 */
class DesktopMutePreferencesTest {

    private fun probeNode(): Preferences =
        Preferences.userRoot().node("agentreins-test/wp-9-probe/${UUID.randomUUID()}")

    @Test
    fun muteToggleRoundTripsAcrossInstances() {
        val node = probeNode()
        try {
            val first = DesktopMutePreferences(node)
            assertFalse(first.isMuted("conv-1"), "初始态不静音")
            assertTrue(first.notificationsEnabled, "总开关初始 = 开")

            first.setMuted(true, "conv-1")
            first.setMuted(true, "conv-2")
            assertTrue(first.isMuted("conv-1") && first.isMuted("conv-2"))
            assertEquals(setOf("conv-1", "conv-2"), first.mutedConversationIds())

            // 新实例读同一节点 = 重启后回落语义：静音集持久化可见
            val second = DesktopMutePreferences(node)
            assertEquals(setOf("conv-1", "conv-2"), second.mutedConversationIds())
            assertTrue(second.isMuted("conv-1"))

            // 解除静音：单键移除，其余保留
            first.setMuted(false, "conv-1")
            assertEquals(setOf("conv-2"), first.mutedConversationIds())
            assertEquals(setOf("conv-2"), DesktopMutePreferences(node).mutedConversationIds())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun notificationsEnabledRoundTripsAndDoesNotLeakIntoMutedSet() {
        val node = probeNode()
        try {
            val first = DesktopMutePreferences(node)
            first.setNotificationsEnabled(false)
            first.setMuted(true, "conv-3")

            val second = DesktopMutePreferences(node)
            assertFalse(second.notificationsEnabled, "总开关持久化可见")
            assertEquals(setOf("conv-3"), second.mutedConversationIds(), "总开关键不得混入静音集")
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun nullNodeDegradesToMemoryOnlyWithoutThrowing() {
        // Preferences 不可用（node = null）：全程内存态、不抛——静默降级纪律同面延伸
        val prefs = DesktopMutePreferences(node = null)
        prefs.setMuted(true, "conv-mem")
        assertTrue(prefs.isMuted("conv-mem"))
        assertEquals(setOf("conv-mem"), prefs.mutedConversationIds())
        prefs.setNotificationsEnabled(false)
        assertFalse(prefs.notificationsEnabled)
        prefs.setMuted(false, "conv-mem")
        assertTrue(prefs.mutedConversationIds().isEmpty())
    }
}
