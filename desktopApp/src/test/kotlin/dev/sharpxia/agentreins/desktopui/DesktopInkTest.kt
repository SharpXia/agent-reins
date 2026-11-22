package dev.sharpxia.agentreins.desktopui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 墨水令牌层单测（DSK-1 清偿轮 2026-11-22）：light/dark 实例关键值断言 + inkColorScheme
 * Material 槽位映射锚。值源 = iOS InkColors（iosApp/iosApp/Theme/InkTheme.swift）逐值移植，
 * 本测试即「值漂移即红」的回归锚——两端令牌任一侧改值须同轮同步改对侧与断言。
 */
class DesktopInkTest {

    // ── light 实例关键值（InkColors.light() 对锚） ──

    @Test
    fun lightInstanceKeyValues() {
        assertEquals(Color(0xFF1C1C1C), DesktopInkLight.ink)
        assertEquals(Color(0xFFFCFCFC), DesktopInkLight.paper)
        assertEquals(Color(0xFFFFFFFF), DesktopInkLight.elevated)
        assertEquals(Color(0xAD1C1C1C), DesktopInkLight.textSecondary)
        assertEquals(Color(0x8F1C1C1C), DesktopInkLight.textTertiary)
        assertEquals(Color(0xFFCD2834), DesktopInkLight.dangerText)
        assertEquals(Color(0xFF00854A), DesktopInkLight.successText)
        assertEquals(Color(0xFFAA6300), DesktopInkLight.warningText)
        assertEquals(Color(0xFF0B6DD3), DesktopInkLight.infoText)
        assertEquals(Color(0xFF0A61BC), DesktopInkLight.accent)
        assertEquals(Color(0x0F1C1C1C), DesktopInkLight.surfaceTertiary)
        assertEquals(Color(0xFF1C1C1C), DesktopInkLight.selectionFill)
        assertEquals(Color(0xFFFCFCFC), DesktopInkLight.onSelection)
        assertFalse(DesktopInkLight.isDark)
    }

    // ── dark 实例关键值（InkColors.dark() 对锚） ──

    @Test
    fun darkInstanceKeyValues() {
        assertEquals(Color(0xFFF5F5F5), DesktopInkDark.ink)
        assertEquals(Color(0xFF121212), DesktopInkDark.paper)
        assertEquals(Color(0xFF1E1E1E), DesktopInkDark.elevated)
        assertEquals(Color(0xA8F5F5F5), DesktopInkDark.textSecondary)
        assertEquals(Color(0x8AF5F5F5), DesktopInkDark.textTertiary)
        assertEquals(Color(0xFFFF6463), DesktopInkDark.dangerText)
        assertEquals(Color(0xFF00BA69), DesktopInkDark.successText)
        assertEquals(Color(0xFFE28600), DesktopInkDark.warningText)
        assertEquals(Color(0xFF4D9BFF), DesktopInkDark.infoText)
        assertEquals(Color(0xFF4D9BFF), DesktopInkDark.accent)
        assertEquals(Color(0x0AF5F5F5), DesktopInkDark.surfaceTertiary)
        assertEquals(Color(0xFFF5F5F5), DesktopInkDark.selectionFill)
        assertEquals(Color(0xFF121212), DesktopInkDark.onSelection)
        assertTrue(DesktopInkDark.isDark)
    }

    // ── desktopInk(dark) 选择器 ──

    @Test
    fun pickerReturnsMatchedInstance() {
        assertEquals(DesktopInkLight, desktopInk(dark = false))
        assertEquals(DesktopInkDark, desktopInk(dark = true))
    }

    // ── inkColorScheme Material 槽位映射锚（DSK-1 根装配） ──

    @Test
    fun inkColorSchemeLightMapsMaterialSlots() {
        val scheme = inkColorScheme(dark = false)
        assertEquals(Color(0xFF1C1C1C), scheme.primary)
        assertEquals(Color(0xFFFCFCFC), scheme.onPrimary)
        assertEquals(Color(0xFFFCFCFC), scheme.background)
        assertEquals(Color(0xFFFCFCFC), scheme.surface)
        assertEquals(Color(0xFF1C1C1C), scheme.onBackground)
        assertEquals(Color(0xFF1C1C1C), scheme.onSurface)
        assertEquals(Color(0x0F1C1C1C), scheme.surfaceVariant)
        assertEquals(Color(0xFFCD2834), scheme.error)
        assertEquals(Color(0xFFFCFCFC), scheme.onError)
        // outline = 实底灰阶（M3 Switch 未选中拇指取 outline 填充，禁半透明薄墨——
        // blendOver 值不锚具体色值防浮点脆断，只锚不透明+非薄墨边界）。
        assertEquals(1f, scheme.outline.alpha)
        assertTrue(scheme.outline != DesktopInkLight.borderSecondary)
        assertEquals(Color(0x0F1C1C1C), scheme.outlineVariant)
        // 用户气泡面 = ink 24% 薄底（iOS Color.accentColor.opacity(0.24) 同语义）
        assertEquals(DesktopInkLight.ink.copy(alpha = 0.24f), scheme.primaryContainer)
        // 选中行实底 = selectionFill（iOS listRowBackground 同源）
        assertEquals(DesktopInkLight.selectionFill, scheme.secondaryContainer)
        assertEquals(DesktopInkLight.onSelection, scheme.onSecondaryContainer)
    }

    @Test
    fun inkColorSchemeDarkMapsMaterialSlots() {
        val scheme = inkColorScheme(dark = true)
        assertEquals(Color(0xFFF5F5F5), scheme.primary)
        assertEquals(Color(0xFF121212), scheme.background)
        assertEquals(Color(0xFF121212), scheme.surface)
        assertEquals(Color(0xFFF5F5F5), scheme.onBackground)
        assertEquals(Color(0x0AF5F5F5), scheme.surfaceVariant)
        assertEquals(Color(0xFFFF6463), scheme.error)
        assertEquals(Color(0xFF121212), scheme.onError)
        assertEquals(DesktopInkDark.ink.copy(alpha = 0.24f), scheme.primaryContainer)
        assertEquals(DesktopInkDark.selectionFill, scheme.secondaryContainer)
    }

    // ── 薄底令牌合成灰语义抽查（surfaceTertiary 叠 paper ≈ iOS 0xEFEFEF(light)） ──

    @Test
    fun surfaceTertiaryCompositesNearIosBubbleFace() {
        val composite = DesktopInkLight.surfaceTertiary.compositeOver(DesktopInkLight.paper)
        // iOS InkTheme 注释口径：surfaceTertiary 叠 paper ≈ 0xEFEFEF
        assertEquals(0xEF / 255f, composite.red, 0.01f)
        assertEquals(0xEF / 255f, composite.green, 0.01f)
        assertEquals(0xEF / 255f, composite.blue, 0.01f)
    }
}
