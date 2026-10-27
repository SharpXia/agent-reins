package dev.sharpxia.agentreins.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * AgentReins 设计基线（M2.6 方案 A）：Material3 深度定制 token，设计语言参照
 * ChatGPT 官方 App——中性灰阶 + 品牌绿点缀，用户消息灰气泡、助手消息无气泡平文。
 * 暗色全套跟随系统。M3 里程碑的审批卡片/通知/状态面板长在这套基线上。
 */

// ---- 品牌色 ----

/** 品牌绿（未读徽标/强调态）。取 ChatGPT 品牌色系。 */
val ArGreen = Color(0xFF10A37F)

// ---- 明色（ChatGPT iOS light：白底、浅灰气泡、近黑文字） ----

private val LightColors = lightColorScheme(
    primary = ArGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCF3EC),
    onPrimaryContainer = Color(0xFF07503E),
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color(0xFF0D0D0D),
    tertiaryContainer = Color(0xFFFDECC8),
    onTertiaryContainer = Color(0xFF5F4200),
    tertiary = Color(0xFF7A5900),
    background = Color.White,
    onBackground = Color(0xFF0D0D0D),
    surface = Color.White,
    onSurface = Color(0xFF0D0D0D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF9F9F9),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE3E3E3),
    outlineVariant = Color(0xFFE5E5E5),
)

// ---- 暗色（ChatGPT iOS dark：#212121 底、深灰气泡、浅色文字） ----

private val DarkColors = darkColorScheme(
    primary = ArGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF07503E),
    onPrimaryContainer = Color(0xFFB5EBD8),
    secondaryContainer = Color(0xFF2A2A2A),
    onSecondaryContainer = Color(0xFFECECEC),
    tertiaryContainer = Color(0xFF4A3F1F),
    onTertiaryContainer = Color(0xFFEFD8A7),
    tertiary = Color(0xFFECC24A),
    background = Color(0xFF212121),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF212121),
    onSurface = Color(0xFFECECEC),
    surfaceContainerLowest = Color(0xFF171717),
    surfaceContainerLow = Color(0xFF1E1E1E),
    surfaceContainer = Color(0xFF262626),
    surfaceContainerHigh = Color(0xFF2F2F2F),
    surfaceContainerHighest = Color(0xFF383838),
    outlineVariant = Color(0xFF333333),
)

/** 组件圆角规范：气泡 18 / 卡片 14 / 输入框 26（胶囊）。 */
private val ArShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

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
    /** 用户气泡最大宽度占对话区比例（ChatGPT 同款不过半屏太多）。 */
    val bubbleMaxWidthFraction: Float = 0.82f,
    /** 气泡圆角。 */
    val bubbleRadius: Int = 18,
    /** 发送按钮直径。 */
    val sendButtonSize: Int = 34,
)

val LocalArTokens = staticCompositionLocalOf { ArTokens() }

/** 全局唯一主题入口：App()/各预览均经此取色。 */
@Composable
fun AgentReinsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = ArShapes,
        content = content,
    )
}

/** 组件里取尺寸 token 的便捷入口。 */
object ArTheme {
    val tokens: ArTokens
        @Composable get() = LocalArTokens.current
}
