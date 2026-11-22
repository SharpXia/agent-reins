package dev.sharpxia.agentreins.desktopui

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 窗口帧存档单测（DSK-3，Catalyst MacWindowFrame 同位面）：持久化往返（新实例读同一节点 =
 * 重启恢复语义）+ 退化帧守门（NaN/低于存档下限 480 不存不恢复，防异常几何污染存档）+
 * 恢复帧夹紧可视屏区（离屏帧拉回可见条、帧尺寸收到屏区并集、无屏信息保守放行）+
 * node = null 纯内存态不抛（存储纪律同面）。probe 节点随机 UUID 隔离，finally removeNode
 * 清理，不污染用户真实偏好（DesktopWindowPreferencesTest 同纪律）。
 */
class DesktopWindowFrameStoreTest {

    private fun probeNode(): Preferences =
        Preferences.userRoot().node("agentreins-test/window-frame-probe/${UUID.randomUUID()}")

    @Test
    fun frameRoundTripsAcrossInstances() {
        val node = probeNode()
        try {
            assertNull(DesktopWindowFrameStore(node).load(), "空节点 = 无档（首启默认帧路径）")

            DesktopWindowFrameStore(node)
                .save(DesktopWindowFrameStore.Frame(120f, 80f, 1280f, 800f))
            val restored = DesktopWindowFrameStore(node).load()
            assertEquals(DesktopWindowFrameStore.Frame(120f, 80f, 1280f, 800f), restored, "帧跨实例恢复")
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun degenerateFramesAreRejectedOnSaveAndLoad() {
        val node = probeNode()
        try {
            val store = DesktopWindowFrameStore(node)
            // 退化帧整笔丢弃（save/load 双面守门，minValidSize 480 同口径）
            store.save(DesktopWindowFrameStore.Frame(0f, 0f, 320f, 480f))
            store.save(DesktopWindowFrameStore.Frame(0f, 0f, 480f, 100f))
            store.save(DesktopWindowFrameStore.Frame(Float.NaN, 0f, 1280f, 800f))
            assertNull(store.load(), "退化帧不进内存镜像")
            assertNull(DesktopWindowFrameStore(node).load(), "退化帧不落持久层")

            // 档内已有有效帧后，退化 save 不得覆盖（异常几何不污染存档）
            store.save(DesktopWindowFrameStore.Frame(10f, 20f, 1280f, 800f))
            store.save(DesktopWindowFrameStore.Frame(0f, 0f, 1f, 1f))
            assertEquals(
                DesktopWindowFrameStore.Frame(10f, 20f, 1280f, 800f),
                DesktopWindowFrameStore(node).load(),
                "有效存档不被退化帧覆盖",
            )
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun corruptArchiveFallsBackToNull() {
        val node = probeNode()
        try {
            // 键残缺（只有 x/w）：getFloat 缺省 NaN → 有效性门拒绝，回落无档
            node.putFloat("hub.windowFrame.v1.x", 100f)
            node.putFloat("hub.windowFrame.v1.w", 1280f)
            assertNull(DesktopWindowFrameStore(node).load(), "残缺档回落无档")
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun nullNodeDegradesToMemoryOnlyWithoutThrowing() {
        val prefs = DesktopWindowFrameStore(node = null)
        assertNull(prefs.load())
        prefs.save(DesktopWindowFrameStore.Frame(1f, 2f, 1280f, 800f))
        assertEquals(DesktopWindowFrameStore.Frame(1f, 2f, 1280f, 800f), prefs.load(), "降级态内存镜像生效")
    }

    @Test
    fun clampPullsOffscreenFrameBackToVisibleStrip() {
        val screens = listOf(
            DesktopWindowFrameStore.ScreenRect(0f, 0f, 1440f, 900f),
        )
        // 窗口整体拖出右下屏外后存档：夹回左缘/上缘留 64dp 可见条（Catalyst onScreen 拒收的桌面夹紧变体）
        val clamped = DesktopWindowFrameStore.clampToScreens(
            DesktopWindowFrameStore.Frame(1500f, 1000f, 1280f, 800f),
            screens,
        )
        assertEquals(1440f - 64f, clamped.x, "右离屏夹回：左缘 64dp 可见条")
        assertEquals(900f - 64f, clamped.y, "下离屏夹回：上缘 64dp 可见条")
        assertEquals(1280f, clamped.w)
        assertEquals(800f, clamped.h)
    }

    @Test
    fun clampShrinksOversizedFrameToScreenUnion() {
        val screens = listOf(DesktopWindowFrameStore.ScreenRect(0f, 0f, 1024f, 640f))
        val clamped = DesktopWindowFrameStore.clampToScreens(
            DesktopWindowFrameStore.Frame(0f, 0f, 2560f, 1600f),
            screens,
        )
        assertEquals(1024f, clamped.w, "帧宽收到屏区并集")
        assertEquals(640f, clamped.h, "帧高收到屏区并集")
    }

    @Test
    fun clampMultiScreenUsesUnionAndPassthroughWithoutScreens() {
        val screens = listOf(
            DesktopWindowFrameStore.ScreenRect(0f, 0f, 1440f, 900f),
            DesktopWindowFrameStore.ScreenRect(1440f, 0f, 1920f, 1080f),
        )
        // 落在副屏内的帧不误拉回主屏（并集口径）
        val onSecondary = DesktopWindowFrameStore.clampToScreens(
            DesktopWindowFrameStore.Frame(2000f, 100f, 1280f, 800f),
            screens,
        )
        assertEquals(2000f, onSecondary.x)
        assertEquals(100f, onSecondary.y)
        // 无屏信息保守放行（Catalyst onScreen 空表同口径）：原帧原样返回
        val frame = DesktopWindowFrameStore.Frame(-9999f, -9999f, 1280f, 800f)
        assertSame(frame, DesktopWindowFrameStore.clampToScreens(frame, emptyList()))
        // 屏小于可见条（区间倒置）：不抛，取中点收场
        val tiny = DesktopWindowFrameStore.clampToScreens(
            DesktopWindowFrameStore.Frame(0f, 0f, 1280f, 800f),
            listOf(DesktopWindowFrameStore.ScreenRect(0f, 0f, 100f, 60f)),
        )
        assertTrue(tiny.w <= 100f && tiny.h <= 60f, "帧尺寸不超微屏")
    }
}
