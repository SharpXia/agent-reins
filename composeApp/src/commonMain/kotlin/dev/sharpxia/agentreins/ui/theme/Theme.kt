package dev.sharpxia.agentreins.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * M2.6 的 ChatGPT 式色板与 AgentReinsTheme 已退役（M3.0）：设计语言基准换成
 * Grok Bot 墨水系，见 InkColors.kt / InkTheme.kt。本文件只保留组件仍引用的
 * 尺寸/密度 token（与颜色无关），M3.2/M3.3 重绘对应组件后逐步回收。
 */

/** 主题外的固定尺寸/密度 token（主题无关， CompositionLocal 下发）。 */
@Immutable
data class ArTokens(
    /** 间距阶：4/8/12/16/24。 */
    val spaceXs: Int = 4,
    val spaceS: Int = 8,
    val spaceM: Int = 12,
    val spaceL: Int = 16,
    val spaceXl: Int = 24,
    /** 会话列表头像。 */
    val avatarSize: Int = 42,
    /** 用户气泡最大宽度占对话区比例。 */
    val bubbleMaxWidthFraction: Float = 0.82f,
    /** 气泡圆角。 */
    val bubbleRadius: Int = 18,
    /** 发送按钮直径。 */
    val sendButtonSize: Int = 34,
)

val LocalArTokens = staticCompositionLocalOf { ArTokens() }

/** 组件里取尺寸 token 的便捷入口。 */
object ArTheme {
    val tokens: ArTokens
        @Composable get() = LocalArTokens.current
}
