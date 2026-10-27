package dev.sharpxia.agentreins.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * M3 墨水系主题入口：InkColors/InkType/InkShapes 经 CompositionLocal 下发，
 * 同时把核心令牌桥进 MaterialTheme colorScheme——现存 M3 组件（卡片/开关/菜单等）
 * 不改代码即随墨水系换肤。主按钮 = 反色块（inverse），是这套语言最显眼的特征。
 * 同名 object 承载取值入口（MaterialTheme 同款模式）：`InkTheme.colors.ink`。
 */

val LocalInkColors = staticCompositionLocalOf { InkColors.light() }
val LocalInkType = staticCompositionLocalOf { InkType() }
val LocalInkShapes = staticCompositionLocalOf { InkShapes() }

/** 把墨水令牌映射进 M3 colorScheme（现存量组件自动换肤）。 */
private fun InkColors.toMaterialScheme(): ColorScheme = if (isDark) darkScheme else lightScheme

private val InkColors.lightScheme
    get() = lightColorScheme(
        primary = inverse,
        onPrimary = paper,
        primaryContainer = surfaceTertiary,
        onPrimaryContainer = ink,
        secondary = textSecondary,
        onSecondary = paper,
        secondaryContainer = surfaceTertiary,
        onSecondaryContainer = ink,
        tertiary = success.text,
        onTertiary = paper,
        tertiaryContainer = success.subtle,
        onTertiaryContainer = success.text,
        error = danger.text,
        onError = Color.White,
        errorContainer = danger.subtle,
        onErrorContainer = danger.text,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = surfaceTertiary,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = paper,
        surfaceContainerLow = elevated,
        surfaceContainer = elevated,
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = elevated,
        outline = borderPrimary,
        outlineVariant = borderSecondary,
        inverseSurface = inverse,
        inverseOnSurface = paper,
        scrim = scrim,
    )

private val InkColors.darkScheme
    get() = darkColorScheme(
        primary = inverse,
        onPrimary = paper,
        primaryContainer = surfaceTertiary,
        onPrimaryContainer = ink,
        secondary = textSecondary,
        onSecondary = paper,
        secondaryContainer = surfaceTertiary,
        onSecondaryContainer = ink,
        tertiary = success.text,
        onTertiary = paper,
        tertiaryContainer = success.subtle,
        onTertiaryContainer = success.text,
        error = danger.text,
        onError = paper,
        errorContainer = danger.subtle,
        onErrorContainer = danger.text,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = surfaceTertiary,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = paper,
        surfaceContainerLow = elevated,
        surfaceContainer = elevated,
        surfaceContainerHigh = elevated,
        surfaceContainerHighest = elevated,
        outline = borderPrimary,
        outlineVariant = borderSecondary,
        inverseSurface = inverse,
        inverseOnSurface = paper,
        scrim = scrim,
    )

/** 圆角 6 级 → M3 Shapes（供 M3 组件取用）。 */
private val materialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

/** 全局唯一主题入口（M2.6 AgentReinsTheme 退役）。 */
@Composable
fun InkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) InkColors.dark() else InkColors.light()
    CompositionLocalProvider(
        LocalInkColors provides colors,
        LocalInkType provides InkType(),
        LocalInkShapes provides InkShapes(),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialScheme(),
            shapes = materialShapes,
            content = content,
        )
    }
}

/** 组件取墨水令牌的统一入口：`InkTheme.colors.paper` / `InkTheme.type.textSm`。 */
object InkTheme {
    val colors: InkColors @Composable get() = LocalInkColors.current
    val type: InkType @Composable get() = LocalInkType.current
    val shapes: InkShapes @Composable get() = LocalInkShapes.current
}
