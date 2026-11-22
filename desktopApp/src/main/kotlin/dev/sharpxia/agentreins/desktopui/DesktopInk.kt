package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 桌面墨水设计令牌（DSK-1 清偿轮 2026-11-22）：镜像 iOS InkColors（iosApp/iosApp/Theme/
 * InkTheme.swift），light/dark 两段令牌逐值移植；WCAG 比值注释随值照抄（iOS 侧 python3
 * WCAG 2.x 相对亮度公式实测，双模式文本面成对 ≥4.5）。
 *
 * 值源 = iOS InkColors WCAG 实测成对令牌（双模式 ≥4.5）。
 * 已知例外（不在本收敛范围）：BotCharacter/AvatarSpec 角色调色板两表（AgentTint
 * strongLight/strongDark，AvatarSpec.kt:16-30）是角色系统自有体系（跨端同表对锁），
 * 不并入本令牌层；本仓桌面 UI 语义色的唯一合法来源即本文件 + [LocalDesktopInk]。
 *
 * Material 槽位映射见 [inkColorScheme]：消费过的槽位（primary/onError/onSurfaceVariant/
 * outlineVariant/primary/primaryContainer/secondaryContainer/surface/surfaceVariant/error
 * 等全量映射），未消费槽位给灰阶安全默认（防 M3 紫系默认色漏出组件默认值）。
 */
@Immutable
data class DesktopInk(
    val isDark: Boolean,
    // ── 文字三阶 + 禁用 ──
    /** 主文字/图形墨色。 */
    val ink: Color,
    /** 次级文字（ink 降透明成对，落在 paper 上保持 ≥4.5）。 */
    val textSecondary: Color,
    /** 弱化文字（时间戳/说明/占位族）。 */
    val textTertiary: Color,
    /** 禁用文字（预留给真正 disabled 语义，非弱化说明）。 */
    val textDisabled: Color,
    // ── 面系 ──
    /** 纸面（应用底）。 */
    val paper: Color,
    /** 抬升面（卡片浮层）。 */
    val elevated: Color,
    /** 三级面（ink 5.9% 薄底：助手气泡/JSON 卡面语义，叠 paper ≈ 0xEFEFEF(light)）。 */
    val surfaceTertiary: Color,
    /** 禁用面。 */
    val surfaceDisabled: Color,
    /** 反转面。 */
    val inverse: Color,
    // ── 描边 ──
    val borderPrimary: Color,
    val borderSecondary: Color,
    val borderTertiary: Color,
    /** 遮罩。 */
    val scrim: Color,
    // ── 功能色四族（text/border/ring/subtle 成对）──
    val infoText: Color,
    val infoBorder: Color,
    val infoRing: Color,
    val infoSubtle: Color,
    val dangerText: Color,
    val dangerBorder: Color,
    val dangerRing: Color,
    val dangerSubtle: Color,
    val successText: Color,
    val successBorder: Color,
    val successRing: Color,
    val successSubtle: Color,
    val warningText: Color,
    val warningBorder: Color,
    val warningRing: Color,
    val warningSubtle: Color,
    /** 骨架屏底。 */
    val shimmerBase: Color,
    /** 等待/连接中蓝（与 infoText 同族）。 */
    val awaiting: Color,
    val awaitingSubtle: Color,
    // ── accent 语义（链接/交互强调）──
    /** accent 语义面唯一合法来源：Markdown 链接、选中态、斜杠面板命令名等交互强调。
     *  全局 tint 维持 ink 单色语言不动（对齐 iOS 四条铁律之①④）。 */
    val accent: Color,
    // ── 选中面（行选中实底 + 行内文字对）──
    /** 选中行底色面（= ink，不透明实底；对齐 iOS 2026-11-22 对比度收口轮）。 */
    val selectionFill: Color,
    /** 选中行主文字。 */
    val onSelection: Color,
    /** 选中行弱化文字。 */
    val onSelectionMuted: Color,
    // ── 按钮面（统一圆钮语义，预留给面组件）──
    val controlFace: Color,
    val onControlFace: Color,
    val onControlFaceMuted: Color,
)

/** light 令牌实例（值源 = InkColors.light()，逐值照抄含 WCAG 注释）。 */
val DesktopInkLight: DesktopInk = DesktopInk(
    isDark = false,
    ink = Color(0xFF1C1C1C),
    textSecondary = Color(0xAD1C1C1C),
    textTertiary = Color(0x8F1C1C1C),
    textDisabled = Color(0x661C1C1C),
    paper = Color(0xFFFCFCFC),
    elevated = Color(0xFFFFFFFF),
    surfaceTertiary = Color(0x0F1C1C1C),
    surfaceDisabled = Color(0x0A1C1C1C),
    inverse = Color(0xFF1C1C1C),
    borderPrimary = Color(0x1F1C1C1C),
    borderSecondary = Color(0x141C1C1C),
    borderTertiary = Color(0x0F1C1C1C),
    scrim = Color(0x661C1C1C),
    infoText = Color(0xFF0B6DD3),
    infoBorder = Color(0xFF1680F3),
    infoRing = Color(0xFF1E86FC),
    infoSubtle = Color(0x171680F3),
    dangerText = Color(0xFFCD2834),
    dangerBorder = Color(0xFFE72E3B),
    dangerRing = Color(0xFFE72E3B),
    dangerSubtle = Color(0x17FB2B3E),
    successText = Color(0xFF00854A),
    successBorder = Color(0xFF10AB61),
    successRing = Color(0xFF10AB61),
    successSubtle = Color(0x1714C16F),
    warningText = Color(0xFFAA6300),
    warningBorder = Color(0xFFDA830E),
    warningRing = Color(0xFFDA830E),
    warningSubtle = Color(0x17F59312),
    shimmerBase = Color(0x401C1C1C),
    awaiting = Color(0xFF0B6DD3),
    awaitingSubtle = Color(0x171680F3),
    // 链接/交互强调；paper 5.93:1 / 气泡面 5.30 / 卡面 4.74
    accent = Color(0xFF0A61BC),
    // = ink（今日与全局 tint 同值）
    selectionFill = Color(0xFF1C1C1C),
    // 16.61:1 on selectionFill
    onSelection = Color(0xFFFCFCFC),
    // 8.13:1 on selectionFill
    onSelectionMuted = Color(0xFFB3B3B3),
    controlFace = Color(0xFFFFFFFF),
    // 17.04:1 on controlFace
    onControlFace = Color(0xFF1C1C1C),
    // 5.10:1 on controlFace
    onControlFaceMuted = Color(0xFF6E6E6E),
)

/** dark 令牌实例（值源 = InkColors.dark()，逐值照抄含 WCAG 注释）。 */
val DesktopInkDark: DesktopInk = DesktopInk(
    isDark = true,
    ink = Color(0xFFF5F5F5),
    textSecondary = Color(0xA8F5F5F5),
    textTertiary = Color(0x8AF5F5F5),
    textDisabled = Color(0x61F5F5F5),
    paper = Color(0xFF121212),
    elevated = Color(0xFF1E1E1E),
    surfaceTertiary = Color(0x0AF5F5F5),
    surfaceDisabled = Color(0x05F5F5F5),
    inverse = Color(0xFFF5F5F5),
    borderPrimary = Color(0x1AF5F5F5),
    borderSecondary = Color(0x0FF5F5F5),
    borderTertiary = Color(0x0AF5F5F5),
    scrim = Color(0xB3000000),
    infoText = Color(0xFF4D9BFF),
    infoBorder = Color(0xFF116DD2),
    infoRing = Color(0xFF1884FC),
    infoSubtle = Color(0x121884FC),
    dangerText = Color(0xFFFF6463),
    dangerBorder = Color(0xFFCF2B35),
    dangerRing = Color(0xFFCF2B35),
    dangerSubtle = Color(0x12FC2C3E),
    successText = Color(0xFF00BA69),
    successBorder = Color(0xFF0E9F5A),
    successRing = Color(0xFF0E9F5A),
    successSubtle = Color(0x1214C370),
    warningText = Color(0xFFE28600),
    warningBorder = Color(0xFFC8770C),
    warningRing = Color(0xFFC8770C),
    warningSubtle = Color(0x12F69412),
    shimmerBase = Color(0x38F5F5F5),
    awaiting = Color(0xFF4D9BFF),
    awaitingSubtle = Color(0x121884FC),
    // = infoText 同值；paper 6.64:1 / 气泡面 6.11 / 卡面 5.51
    accent = Color(0xFF4D9BFF),
    // = ink（今日与全局 tint 同值）
    selectionFill = Color(0xFFF5F5F5),
    // 17.18:1 on selectionFill
    onSelection = Color(0xFF121212),
    // 5.27:1 on selectionFill
    onSelectionMuted = Color(0xFF666666),
    controlFace = Color(0xFF2A2A2A),
    // 12.26:1 on controlFace
    onControlFace = Color(0xFFEDEDED),
    // 6.04:1 on controlFace
    onControlFaceMuted = Color(0xFFA8A8A8),
)

/** 按明暗模式取令牌实例。 */
fun desktopInk(dark: Boolean): DesktopInk = if (dark) DesktopInkDark else DesktopInkLight

/** 视图内取墨水令牌：`val ink = LocalDesktopInk.current`。缺省 light（iOS InkColorsKey 同口径）。 */
val LocalDesktopInk = staticCompositionLocalOf { DesktopInkLight }

/** 挂载点便捷读取（根装配 [inkColorScheme] 同帧下发）。 */
@Composable
fun rememberDesktopInk(): DesktopInk = desktopInk(dark = isSystemInDarkTheme())

/**
 * 墨水令牌 → Material [ColorScheme] 槽位映射（DSK-1 根装配）：
 * - primary=ink / onPrimary=paper（单色墨水语言，iOS 全局 tint=ink 同源）
 * - background/surface=paper、onBackground/onSurface=ink
 * - surfaceVariant=surfaceTertiary（助手气泡/卡面薄底，iOS HubMessageRow 同源语义；
 *   onSurfaceVariant=textSecondary（≈ 既有 M3 默认中强调灰））
 * - primaryContainer=ink 24% 薄底（iOS 用户气泡 Color.accentColor.opacity(0.24) 语义——
 *   iOS 全局 tint=ink，即 ink 24%；onPrimaryContainer=ink 同 iOS 气泡内文字）
 * - secondaryContainer=selectionFill（选中行实底，iOS listRowBackground 同源），
 *   onSecondaryContainer=onSelection 成对（消费点 ConversationListPanel 选中行文字须同帧配对）
 * - error=dangerText / onError=paper / errorContainer=dangerSubtle / onErrorContainer=dangerText
 * - outline=paper 向 ink 61% 实底合成（M3 Switch 未选中拇指取 outline 填充，禁半透明）
 *   / outlineVariant=borderTertiary
 * - surfaceContainer 系 = paper 向 ink 的灰阶合成（无紫系默认漏出）
 */
fun inkColorScheme(dark: Boolean): ColorScheme {
    val ink = desktopInk(dark)
    val userBubbleFace = ink.ink.copy(alpha = 0.24f)
    val scheme: ColorScheme = if (dark) {
        darkColorScheme(
            primary = ink.ink,
            onPrimary = ink.paper,
            primaryContainer = userBubbleFace,
            onPrimaryContainer = ink.ink,
            inversePrimary = ink.paper,
            secondary = ink.textSecondary,
            onSecondary = ink.paper,
            secondaryContainer = ink.selectionFill,
            onSecondaryContainer = ink.onSelection,
            tertiary = ink.accent,
            onTertiary = ink.paper,
            tertiaryContainer = ink.accent.copy(alpha = 0.16f),
            onTertiaryContainer = ink.ink,
            background = ink.paper,
            onBackground = ink.ink,
            surface = ink.paper,
            onSurface = ink.ink,
            surfaceVariant = ink.surfaceTertiary,
            onSurfaceVariant = ink.textSecondary,
            surfaceDim = blendOver(ink.paper, Color.Black, 0.30f),
            surfaceBright = blendOver(ink.paper, ink.ink, 0.08f),
            surfaceContainerLowest = blendOver(ink.paper, Color.Black, 0.12f),
            surfaceContainerLow = blendOver(ink.paper, ink.ink, 0.05f),
            surfaceContainer = blendOver(ink.paper, ink.ink, 0.08f),
            surfaceContainerHigh = blendOver(ink.paper, ink.ink, 0.11f),
            surfaceContainerHighest = blendOver(ink.paper, ink.ink, 0.14f),
            error = ink.dangerText,
            onError = ink.paper,
            errorContainer = ink.dangerSubtle,
            onErrorContainer = ink.dangerText,
            // outline 用实底灰阶（M3 outline ≈ onSurface 61% 合成，blendOver 去透明）：
            // Material Switch 未选中拇指取 outline 填充，半透明薄墨会让拇指形近乎消失
            //（2026-11-22 清债复核 advice①）。
            outline = blendOver(ink.paper, ink.ink, 0.61f),
            outlineVariant = ink.borderTertiary,
            scrim = ink.scrim,
            inverseSurface = ink.inverse,
            inverseOnSurface = ink.paper,
        )
    } else {
        lightColorScheme(
            primary = ink.ink,
            onPrimary = ink.paper,
            primaryContainer = userBubbleFace,
            onPrimaryContainer = ink.ink,
            inversePrimary = ink.paper,
            secondary = ink.textSecondary,
            onSecondary = ink.paper,
            secondaryContainer = ink.selectionFill,
            onSecondaryContainer = ink.onSelection,
            tertiary = ink.accent,
            onTertiary = ink.paper,
            tertiaryContainer = ink.accent.copy(alpha = 0.16f),
            onTertiaryContainer = ink.ink,
            background = ink.paper,
            onBackground = ink.ink,
            surface = ink.paper,
            onSurface = ink.ink,
            surfaceVariant = ink.surfaceTertiary,
            onSurfaceVariant = ink.textSecondary,
            surfaceDim = blendOver(ink.paper, ink.ink, 0.12f),
            surfaceBright = ink.elevated,
            surfaceContainerLowest = ink.elevated,
            surfaceContainerLow = blendOver(ink.paper, ink.ink, 0.03f),
            surfaceContainer = blendOver(ink.paper, ink.ink, 0.06f),
            surfaceContainerHigh = blendOver(ink.paper, ink.ink, 0.09f),
            surfaceContainerHighest = blendOver(ink.paper, ink.ink, 0.12f),
            error = ink.dangerText,
            onError = ink.paper,
            errorContainer = ink.dangerSubtle,
            onErrorContainer = ink.dangerText,
            // outline 同 light 支：实底灰阶（M3 outline ≈ onSurface 61% 合成）保 Switch 拇指可见。
            outline = blendOver(ink.paper, ink.ink, 0.61f),
            outlineVariant = ink.borderTertiary,
            scrim = ink.scrim,
            inverseSurface = ink.inverse,
            inverseOnSurface = ink.paper,
        )
    }
    return scheme
}

/**
 * 不透明灰阶合成（surfaceContainer 系专用）：top 以 [fraction] 强度压在 base 上，
 * 返回不透明色——Material 槽位避免半透明叠底漂移。
 */
private fun blendOver(base: Color, top: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    fun ch(b: Float, t: Float) = (b + (t - b) * f).coerceIn(0f, 1f)
    return Color(
        red = ch(base.red, top.red),
        green = ch(base.green, top.green),
        blue = ch(base.blue, top.blue),
        alpha = 1f,
    )
}

/**
 * 托盘图标品牌蓝（AWT TrayIcon 在组合外渲染，读不到 [LocalDesktopInk]；常驻菜单栏
 * 不随主题翻面）。字面量集中落在令牌层（本文件是全模块唯一 Color 字面量落点，与
 * BotCharacter/AvatarSpec 调色板两表同列已知例外）。
 */
val DesktopTrayBrandBlue: Color = Color(0xFF2B6CB0)
