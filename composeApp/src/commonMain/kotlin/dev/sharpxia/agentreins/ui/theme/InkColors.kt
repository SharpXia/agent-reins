package dev.sharpxia.agentreins.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * M3 墨水系设计令牌 · 颜色（逆向报告 §4.1 逐值移植）。
 *
 * 骨架：黑白双色墨水系——light 纸 `#fcfcfc`/墨 `#1c1c1c`，dark 纸 `#121212`/墨 `#f5f5f5`；
 * 全系统仅 蓝/红/绿/橙 4 个功能语义色，各配 文本色/边框/环/底色 四件套 × 双主题。
 * 特殊态（shimmer/玻璃/awaiting）在报告里为动态生成，此处按观感定值。
 *
 * ⚠️ 报告色值是 web 风格 `#rrggbbaa`（alpha 在尾），Compose Color(Long) 是 ARGB
 * （alpha 在头）——一律经 [rgba] 换算，不要手抄 hex（首版就栽在这：暗色全局泛黄）。
 */
@Immutable
data class FuncColors(
    val text: Color,
    val border: Color,
    val ring: Color,
    val subtle: Color,
)

/** 报告 §4.1 的 `#rrggbbaa`（RGBA，alpha 尾字节）→ Compose ARGB。 */
private fun rgba(rgb: Long, alpha: Int): Color = Color((alpha.toLong() shl 24) or rgb)

@Immutable
data class InkColors(
    val isDark: Boolean,
    // ---- 中性骨架 ----
    /** text-primary / ink：主文本与描边基准。 */
    val ink: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textDisabled: Color,
    /** background-secondary（paper）：页面底色。 */
    val paper: Color,
    /** background-primary（elevated）：分组卡片底。 */
    val elevated: Color,
    /** background-tertiary：次级表面（按压填充/选中底）。 */
    val surfaceTertiary: Color,
    /** background-disabled。 */
    val surfaceDisabled: Color,
    /** background-inverse：反色块/主按钮底。 */
    val inverse: Color,
    val borderPrimary: Color,
    val borderSecondary: Color,
    val borderTertiary: Color,
    /** 遮罩（Sheet 底）。 */
    val scrim: Color,
    // ---- 玻璃拟态（haze 落地前的近似：半透明 + 描边） ----
    val glassFill: Color,
    val glassStroke: Color,
    // ---- 功能色四件套 ----
    val info: FuncColors,
    val danger: FuncColors,
    val success: FuncColors,
    val warning: FuncColors,
    // ---- 特殊态 ----
    /** 流式输出微光基色（shimmer 高亮端用 textTertiary）。 */
    val shimmerBase: Color,
    /** agent 等待/执行中状态色（审批卡/运行中徽标）。 */
    val awaiting: Color,
    val awaitingSubtle: Color,
) {
    companion object {
        private const val INK_RGB = 0x1C1C1CL
        private const val PAPER_RGB = 0xFCFCFCL
        private const val INK_D_RGB = 0xF5F5F5L
        private const val PAPER_D_RGB = 0x121212L

        fun light() = InkColors(
            isDark = false,
            ink = Color(0xFF1C1C1C),
            textSecondary = rgba(INK_RGB, 0xAD), // 68%
            textTertiary = rgba(INK_RGB, 0x8F), // 56%
            textDisabled = rgba(INK_RGB, 0x66), // 40%
            paper = Color(0xFFFCFCFC),
            elevated = Color(0xFFFFFFFF),
            surfaceTertiary = rgba(INK_RGB, 0x0F), // 6%
            surfaceDisabled = rgba(INK_RGB, 0x0A), // 4%
            inverse = Color(0xFF1C1C1C),
            borderPrimary = rgba(INK_RGB, 0x1F), // 12%
            borderSecondary = rgba(INK_RGB, 0x14), // 8%
            borderTertiary = rgba(INK_RGB, 0x0F), // 6%
            scrim = rgba(INK_RGB, 0x66),
            glassFill = rgba(PAPER_RGB, 0xB3),
            glassStroke = rgba(INK_RGB, 0x1F),
            info = FuncColors(
                text = Color(0xFF0B6DD3), border = Color(0xFF1680F3),
                ring = Color(0xFF1E86FC), subtle = rgba(0x1680F3, 0x17),
            ),
            danger = FuncColors(
                text = Color(0xFFCD2834), border = Color(0xFFE72E3B),
                ring = Color(0xFFE72E3B), subtle = rgba(0xFB2B3E, 0x17),
            ),
            success = FuncColors(
                text = Color(0xFF00854A), border = Color(0xFF10AB61),
                ring = Color(0xFF10AB61), subtle = rgba(0x14C16F, 0x17),
            ),
            warning = FuncColors(
                text = Color(0xFFAA6300), border = Color(0xFFDA830E),
                ring = Color(0xFFDA830E), subtle = rgba(0xF59312, 0x17),
            ),
            shimmerBase = rgba(INK_RGB, 0x40),
            awaiting = Color(0xFF0B6DD3),
            awaitingSubtle = rgba(0x1680F3, 0x17),
        )

        fun dark() = InkColors(
            isDark = true,
            ink = Color(0xFFF5F5F5),
            textSecondary = rgba(INK_D_RGB, 0xA8), // 66%
            textTertiary = rgba(INK_D_RGB, 0x8A), // 54%
            textDisabled = rgba(INK_D_RGB, 0x61), // 38%
            paper = Color(0xFF121212),
            elevated = Color(0xFF1E1E1E),
            surfaceTertiary = rgba(INK_D_RGB, 0x0A), // 4%
            surfaceDisabled = rgba(INK_D_RGB, 0x05), // 2%
            inverse = Color(0xFFF5F5F5),
            borderPrimary = rgba(INK_D_RGB, 0x1A), // 10%
            borderSecondary = rgba(INK_D_RGB, 0x0F), // 6%
            borderTertiary = rgba(INK_D_RGB, 0x0A), // 4%
            scrim = rgba(0x000000, 0xB3),
            glassFill = rgba(PAPER_D_RGB, 0xB3),
            glassStroke = rgba(INK_D_RGB, 0x1A),
            info = FuncColors(
                text = Color(0xFF4D9BFF), border = Color(0xFF116DD2),
                ring = Color(0xFF1884FC), subtle = rgba(0x1884FC, 0x12),
            ),
            danger = FuncColors(
                text = Color(0xFFFF6463), border = Color(0xFFCF2B35),
                ring = Color(0xFFCF2B35), subtle = rgba(0xFC2C3E, 0x12),
            ),
            success = FuncColors(
                text = Color(0xFF00BA69), border = Color(0xFF0E9F5A),
                ring = Color(0xFF0E9F5A), subtle = rgba(0x14C370, 0x12),
            ),
            warning = FuncColors(
                text = Color(0xFFE28600), border = Color(0xFFC8770C),
                ring = Color(0xFFC8770C), subtle = rgba(0xF69412, 0x12),
            ),
            shimmerBase = rgba(INK_D_RGB, 0x38),
            awaiting = Color(0xFF4D9BFF),
            awaitingSubtle = rgba(0x1884FC, 0x12),
        )
    }
}
