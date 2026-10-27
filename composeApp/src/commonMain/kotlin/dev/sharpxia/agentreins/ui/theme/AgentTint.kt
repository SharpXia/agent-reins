package dev.sharpxia.agentreins.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * M3 Agent 身份色（botTint 10 色系，逆向报告 §4.1）。
 *
 * 报告确认 tint 名录 gray/blue/red/orange/yellow/green/cyan/purple/magenta/brown，
 * 但具体色值按 {scheme, accent} 动态生成、未落静态表——此处按 iOS 系统色系定值：
 * light 模式 strong 取加深变体（浅色纸底上做文本/描边可读），dark 取标准亮变体。
 * 每色派生：strong（点/文本/描边）、bubble（气泡底 fill）、pill（标题 Pill 底）。
 * 作用域注入见 [LocalAgentTint]，头像/气泡/状态点/Pill 全链路同源取色。
 */
enum class AgentTintName { GRAY, BLUE, RED, ORANGE, YELLOW, GREEN, CYAN, PURPLE, MAGENTA, BROWN }

@Immutable
data class AgentTintColors(
    val name: AgentTintName,
    /** 前景：状态点、标题文本、描边强调。 */
    val strong: Color,
    /** 气泡/底色 fill（用户消息气泡、选中底）。 */
    val bubble: Color,
    /** Pill/标签底。 */
    val pill: Color,
)

private val STRONG_LIGHT = mapOf(
    AgentTintName.GRAY to 0xFF8E8E93L,
    AgentTintName.BLUE to 0xFF0069D9L,
    AgentTintName.RED to 0xFFD70015L,
    AgentTintName.ORANGE to 0xFFC93400L,
    AgentTintName.YELLOW to 0xFFB25000L,
    AgentTintName.GREEN to 0xFF248A3DL,
    AgentTintName.CYAN to 0xFF1C7A99L,
    AgentTintName.PURPLE to 0xFF8944ABL,
    AgentTintName.MAGENTA to 0xFFD5236BL,
    AgentTintName.BROWN to 0xFF8B6D4FL,
)

private val STRONG_DARK = mapOf(
    AgentTintName.GRAY to 0xFF98989DL,
    AgentTintName.BLUE to 0xFF0A84FFL,
    AgentTintName.RED to 0xFFFF453AL,
    AgentTintName.ORANGE to 0xFFFF9F0AL,
    AgentTintName.YELLOW to 0xFFFFD60AL,
    AgentTintName.GREEN to 0xFF30D158L,
    AgentTintName.CYAN to 0xFF64D2FFL,
    AgentTintName.PURPLE to 0xFFBF5AF2L,
    AgentTintName.MAGENTA to 0xFFFF375FL,
    AgentTintName.BROWN to 0xFFAC8E68L,
)

fun agentTint(name: AgentTintName, isDark: Boolean): AgentTintColors {
    val strong = Color(if (isDark) STRONG_DARK.getValue(name) else STRONG_LIGHT.getValue(name))
    // bubble：亮纸 14%、暗纸 22%（深底上才够可见）；pill 略低一档
    return AgentTintColors(
        name = name,
        strong = strong,
        bubble = strong.copy(alpha = if (isDark) 0.22f else 0.14f),
        pill = strong.copy(alpha = if (isDark) 0.18f else 0.12f),
    )
}

/** 未注册 agent 的稳定兜底：按 seed（agentId/session key）散列到 10 色。 */
fun agentTintFor(seed: String): AgentTintName =
    AgentTintName.entries[abs(seed.hashCode()) % AgentTintName.entries.size]

val LocalAgentTint = staticCompositionLocalOf { agentTint(AgentTintName.GRAY, isDark = false) }

/** BotTintScope 等价物：作用域内所有组件同源取该 agent 的 tint。 */
@Composable
fun AgentTintScope(name: AgentTintName, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAgentTint provides agentTint(name, InkTheme.colors.isDark),
        content = content,
    )
}
