package dev.sharpxia.agentreins.ui.avatar

import dev.sharpxia.agentreins.AgentProfile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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

// ---------- 黏土捏脸（face-sculpt §2/§4：两端逐公式一致，Kotlin 只此一份） ----------

/** 控制点数 / 基准半径（100×100 坐标系，圆心 (50,50)）/ 半径系数上下限。 */
const val FACE_POINT_COUNT = 12
const val FACE_R = 44.0
const val FACE_MIN = 0.70
const val FACE_MAX = 1.10

/** 脸控制点坐标（100×100 坐标系；0 号在 12 点方向、顺时针，θ_i = i×30°）。 */
data class FacePoint(val x: Double, val y: Double)

private fun Double.clampFace(): Double = coerceIn(FACE_MIN, FACE_MAX)

/** 控制点坐标：x = 50 + 44·f·sin(θ_i)，y = 50 − 44·f·cos(θ_i)（弧度制）。 */
fun facePoint(i: Int, f: Double): FacePoint {
    val theta = (i % FACE_POINT_COUNT) * (2.0 * PI / FACE_POINT_COUNT)
    val r = FACE_R * f.clampFace()
    return FacePoint(50.0 + r * sin(theta), 50.0 - r * cos(theta))
}

/**
 * 解析黏土脸系数串：12 段逗号分隔小数，逐值 clamp 到 [0.70,1.10]。
 * 段数 ≠ 12 或任一段解析失败 → null（调用方按正圆处理）。
 */
fun parseFaceFactors(raw: String): List<Double>? {
    val parts = raw.split(",")
    if (parts.size != FACE_POINT_COUNT) return null
    return parts.map { it.trim().toDoubleOrNull()?.clampFace() ?: return null }
}

/**
 * 编码黏土脸系数串：恒 12 段、各两位小数（如 `1.00,0.92,…`）。
 * 手写 cents 格式化避免 String.format 的 locale 依赖（德语区会输出「1,00」砸坏逗号分隔）；
 * 不足 12 段按 1.0 补齐、超出截断，逐值 clamp，保证与 [parseFaceFactors] 往返稳定。
 */
fun encodeFaceFactors(fs: List<Double>): String =
    List(FACE_POINT_COUNT) { i -> (fs.getOrElse(i) { 1.0 }.clampFace() * 100).roundToInt().coerceIn(70, 110) }
        .joinToString(",") { c ->
            if (c < 100) "0.${c.toString().padStart(2, '0')}" else "1.${(c - 100).toString().padStart(2, '0')}"
        }
