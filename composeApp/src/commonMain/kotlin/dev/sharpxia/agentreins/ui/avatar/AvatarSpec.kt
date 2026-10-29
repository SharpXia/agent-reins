package dev.sharpxia.agentreins.ui.avatar

import dev.sharpxia.agentreins.AgentProfile

/** 表情枚举：声明顺序 = 默认派生序 = 编辑网格序（跨端契约，勿重排）。 */
enum class AgentExpression { CALM, HAPPY, EXCITED, THINKING, CONFUSED, SLEEPY, SAD, SMUG, SURPRISED, LOVE }

/** 未定制表情的稳定兜底：加盐 + 比特混淆，两层都不可省——
 *  ① 相对 agentTintFor（裸 seed.hashCode()）换独立散列输入，防表情索引被色索引锁死（同色必撞脸）；
 *  ② xor + ushr16 比特混淆：乘子 31 是奇数、Int32 回绕对 mod 2 透明，纯后缀盐破不了奇偶锁定
 *     （python3 复刻 Java hashCode 实测 2088 id：纯盐每 tint 恰 5 个表情、同色碰撞率 0.202；
 *     混淆后 10/10 distinct、0.104 ≈ 随机）。盐串 ":expression" 内容可换，混淆步骤不可删。
 *  本函数只活在 Kotlin 内，桥只送已解析值；Int.mod 恒非负（不复刻 agentTintFor 的 abs 负值边角）。 */
fun agentExpressionFor(seed: String): AgentExpression {
    val h = (seed + ":expression").hashCode()
    return AgentExpression.entries[(h xor (h ushr 16)).mod(AgentExpression.entries.size)]
}

/** 统一解析入口：已定制且合法 → 用之；否则按 agentId 散列。Swift 永远不调这个（桥只送已解析值）。 */
fun avatarExpressionOf(agentId: String, profiles: Map<String, AgentProfile>): AgentExpression =
    profiles[agentId]?.expression
        ?.takeIf { it.isNotEmpty() }
        ?.let { runCatching { AgentExpression.valueOf(it) }.getOrNull() }
        ?: agentExpressionFor(agentId)
