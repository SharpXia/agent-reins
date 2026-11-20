package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.DEFAULT_AVATAR_PROFILE
import dev.sharpxia.agentreins.hub.avatar.UPDATED_AT_EPOCH
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.5.0 形象档缓存单测（照抄 DesktopDisplayPreferencesTest 三用例形态 + 键族不重叠断言，
 * 设计稿 clientPlan desktopApp/src/test 锚点）：缺省 = 默认档（正圆+GRAY+standard+epoch 哨兵，
 * 双端同源常量）/ 跨实例往返（新实例读同一节点 = 重启后回落语义）/ node = null 纯内存降级
 * 不抛 / 同节点键族不重叠（三班偏好 + 形象档键 hub.avatarProfile.v1）。用独立 probe 节点
 * （随机 UUID）隔离，finally removeNode 清理，不污染用户真实偏好。
 */
class DesktopAvatarPreferencesTest {

    private fun probeNode(): Preferences =
        Preferences.userRoot().node("agentreins-test/avatar-prefs-probe/${UUID.randomUUID()}")

    /** 非默认档样例（胶囊造型 + CYAN + off + 真实时间戳）。 */
    private fun sampleProfile() = AvatarProfile(
        listOf(0.94, 0.98, 1.06, 1.10, 1.06, 0.98, 0.94, 0.98, 1.06, 1.10, 1.06, 0.98),
        AvatarPalette.CYAN,
        AvatarMotion.OFF,
        "2026-11-20T08:00:00.000Z",
    )

    @Test
    fun defaultsToAvatarDefaultProfile() {
        val node = probeNode()
        try {
            val prefs = DesktopAvatarPreferences(node)
            assertEquals(DEFAULT_AVATAR_PROFILE, prefs.profile, "缺省 = 默认档（正圆+GRAY+standard+epoch 哨兵）")
            assertEquals(UPDATED_AT_EPOCH, prefs.profile.updatedAt)
            // 空节点（无键）同样回落默认档（覆盖首装/旧档无键/持久化损坏）
            val fresh = probeNode()
            try {
                val empty = DesktopAvatarPreferences(fresh)
                assertEquals(DEFAULT_AVATAR_PROFILE, empty.profile)
            } finally {
                fresh.removeNode()
            }
            // 坏 JSON 静默回落默认档不抛（宽松解析纪律）
            node.put(DesktopAvatarPreferences.KEY_AVATAR_PROFILE, "{not-a-json")
            assertEquals(DEFAULT_AVATAR_PROFILE, DesktopAvatarPreferences(node).profile)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun avatarPrefsRoundTripsAcrossInstances() {
        val node = probeNode()
        try {
            val first = DesktopAvatarPreferences(node)
            assertEquals(DEFAULT_AVATAR_PROFILE, first.profile)

            val sample = sampleProfile()
            first.setProfile(sample)
            assertEquals(sample, first.profile, "内存镜像即时生效")
            // 新实例读同一节点 = 重启后回落语义：跨登出/重启保留可见
            assertEquals(sample, DesktopAvatarPreferences(node).profile, "持久化可见")

            // 默认档覆盖：setProfile(DEFAULT) 往返同样成立
            first.setProfile(DEFAULT_AVATAR_PROFILE)
            assertEquals(DEFAULT_AVATAR_PROFILE, DesktopAvatarPreferences(node).profile)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun mergeRemoteIsLastWriteWinsWithIdempotentSkip() {
        val node = probeNode()
        try {
            val prefs = DesktopAvatarPreferences(node)
            val sample = sampleProfile()
            assertTrue(prefs.mergeRemote(sample), "远端新档（真实时间戳 > epoch 哨兵）被接受")
            assertEquals(sample, prefs.profile)
            // 同值幂等跳过：发起设备自收 avatar.updated 帧不回环
            assertFalse(prefs.mergeRemote(sample), "同 updatedAt 幂等跳过")
            assertEquals(sample, prefs.profile)
            // 旧档丢弃（epoch 哨兵 < 真实时间戳）
            assertFalse(prefs.mergeRemote(DEFAULT_AVATAR_PROFILE), "旧档不覆盖新档")
            assertEquals(sample, prefs.profile)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun nullNodeDegradesToMemoryOnlyWithoutThrowing() {
        // Preferences 不可用（node = null）：缺省默认档 + 全程内存态、不抛——静默降级纪律同面延伸
        val prefs = DesktopAvatarPreferences(node = null)
        assertEquals(DEFAULT_AVATAR_PROFILE, prefs.profile)
        val sample = sampleProfile()
        prefs.setProfile(sample)
        assertEquals(sample, prefs.profile)
        assertTrue(prefs.mergeRemote(sample) == false, "同值幂等（降级态同样收敛）")
        prefs.setProfile(DEFAULT_AVATAR_PROFILE)
        assertEquals(DEFAULT_AVATAR_PROFILE, prefs.profile)
    }

    @Test
    fun clearRemovesKeyAndResetsToDefault() {
        // 清账轮 2026-11-20（跨端复核④收口）：登出清缓存——键移除 + 内存回默认档；
        // 重登后新实例回落默认档（换账号不残留上一用户档，权威档由登录回拉兜底）。
        val node = probeNode()
        try {
            val prefs = DesktopAvatarPreferences(node)
            prefs.setProfile(sampleProfile())
            assertEquals(sampleProfile(), prefs.profile)

            prefs.clear()
            assertEquals(DEFAULT_AVATAR_PROFILE, prefs.profile, "内存回默认档")
            assertEquals(
                DEFAULT_AVATAR_PROFILE,
                DesktopAvatarPreferences(node).profile,
                "持久化键已移除（新实例回落默认档）",
            )
            assertTrue(node.get(DesktopAvatarPreferences.KEY_AVATAR_PROFILE, null) == null, "节点键已移除")

            // 清空后收敛链照常（重登回拉 = mergeRemote 接受权威档）
            assertTrue(prefs.mergeRemote(sampleProfile()), "登出后远端权威档可重新收敛")
            assertEquals(sampleProfile(), prefs.profile)
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun avatarKeyDoesNotOverlapSiblingPreferenceKeys() {
        // 同节点键族不重叠（DesktopWindowPreferences.kt:50/:115 纪律）：三班偏好 + 形象档
        // 各自写键后，节点键集应恰含四个互异 hub.* 键（互不覆盖 = 写后读回各自成立）。
        val node = probeNode()
        try {
            DesktopWindowPreferences(node).setMinimizeToTrayOnClose(false)
            DesktopDisplayPreferences(node).setStreamingPlayback(false)
            DesktopAvatarPreferences(node).setProfile(sampleProfile())

            val keys = node.keys().toSet()
            assertTrue("hub.minimizeToTrayOnClose" in keys)
            assertTrue("hub.streamingPlayback" in keys)
            assertTrue(DesktopAvatarPreferences.KEY_AVATAR_PROFILE in keys)
            // 形象档键不撞 hub.muted. 前缀枚举回灌（HubStore.swift:1567-1573 同族纪律）
            assertTrue(keys.none { it.startsWith("hub.muted.") })
            // 写后读回：各班值互不串扰
            assertFalse(DesktopWindowPreferences(node).minimizeToTrayOnClose)
            assertFalse(DesktopDisplayPreferences(node).streamingPlayback)
            assertEquals(sampleProfile(), DesktopAvatarPreferences(node).profile)
            assertTrue(DesktopDisplayPreferences(node).thinkingIndicator, "流式输出翻转不影响思考中指示")
        } finally {
            node.removeNode()
        }
    }
}
