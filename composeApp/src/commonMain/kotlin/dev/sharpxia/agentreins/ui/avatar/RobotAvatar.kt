package dev.sharpxia.agentreins.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.agentTint
import kotlin.math.roundToInt

/**
 * 黏土 blob 头像（avatar-face-sculpt-20261030 §1–§3）：旧机器人的天线/天线球/双耳/
 * 圆角方形头部外框/暗色面罩全部移除，脸 = 一块 tint 纯色黏土 blob；眼、嘴、眉、腮红与
 * 各表情附加元素（汗滴/心/Z/放射线/思考泡）坐标沿用旧几何表一字不改。
 *
 * 100×100 逻辑坐标（原点左上、y 向下）；s = 渲染尺寸/100，**每个坐标与每条线宽显式乘 s**
 * （不用 scale 变换，避免线宽/抗锯齿口径漂移）。z 序：脸 blob→腮红→眼→眉→嘴→附加。
 * 渲染尺寸 < 40 走 compact（§5.5 折算表不变：blob→眼→嘴）。
 * 渲染器不做任何 face 串解析——调用点一律显式传已解析的 [faceFactors]（空/缺省 = 全 1.0 正圆）。
 */
@Composable
fun RobotAvatar(
    palette: AgentTintName,
    expression: AgentExpression,
    size: Int,
    isDark: Boolean,
    faceFactors: List<Double> = DefaultFaceFactors,
) {
    val faceFill = agentTint(palette, isDark).strong
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.minDimension / 100f
        val ink = inkForFace(faceFill)
        if (size < 40) drawCompact(expression, faceFill, ink, faceFactors, s) else drawFull(expression, faceFill, ink, faceFactors, s)
    }
}

// ---------- 固定色（face-sculpt §3 + 旧规格附加元素固定值，沿用不动） ----------

private val BlushColor = Color(0xFFFFFFFFL).copy(alpha = 0.20f)
private val DropColor = Color(0xFF8FD3F4L)
private val HeartColor = Color(0xFFFF7BACL)

/** 墨色两端点值（§3 逐字）：明度高的脸配深墨 #1F2329，否则浅墨 #F5F7FA。 */
private val InkDark = Color(0xFF1F2329L)
private val InkLight = Color(0xFFF5F7FAL)

/**
 * 眼/嘴/眉墨色（face-sculpt §3，两端逐字一致）：
 * 0.299R + 0.587G + 0.114B ≥ 153 → #1F2329，否则 → #F5F7FA（R/G/B 为 0-255 的 gamma 编码值）。
 */
internal fun inkForFace(face: Color): Color {
    val r = (face.red * 255f).roundToInt()
    val g = (face.green * 255f).roundToInt()
    val b = (face.blue * 255f).roundToInt()
    return if (0.299f * r + 0.587f * g + 0.114f * b >= 153f) InkDark else InkLight
}

// ---------- blob（face-sculpt §2：过 12 点的闭合均匀 Catmull-Rom，标准张力 1/6） ----------

/**
 * 对每段 P_i → P_{i+1}（索引 mod 12，前邻 P_{i−1}、后邻 P_{i+2}）逐段 cubicTo：
 * cp1 = P_i + (P_{i+1} − P_{i−1}) / 6；cp2 = P_{i+1} − (P_{i+2} − P_i) / 6。
 * 相邻段在共享点处的出/入切向共线且等长 → 整条 C¹ 连续，任何系数组合下无折线段/尖角；
 * 所有因子相等时为正圆（半径 44·f；段中点半径偏差 < 0.2%，百像素级渲染下远小于 1px）。
 */
internal fun faceBlobPath(factors: List<Double>, s: Float): Path {
    val pts = List(FACE_POINT_COUNT) { i -> facePoint(i, factors.getOrElse(i) { 1.0 }) }
    val path = Path()
    path.moveTo((pts[0].x * s).toFloat(), (pts[0].y * s).toFloat())
    for (i in 0 until FACE_POINT_COUNT) {
        val p0 = pts[(i + FACE_POINT_COUNT - 1) % FACE_POINT_COUNT]
        val p1 = pts[i]
        val p2 = pts[(i + 1) % FACE_POINT_COUNT]
        val p3 = pts[(i + 2) % FACE_POINT_COUNT]
        path.cubicTo(
            ((p1.x + (p2.x - p0.x) / 6.0) * s).toFloat(),
            ((p1.y + (p2.y - p0.y) / 6.0) * s).toFloat(),
            ((p2.x - (p3.x - p1.x) / 6.0) * s).toFloat(),
            ((p2.y - (p3.y - p1.y) / 6.0) * s).toFloat(),
            (p2.x * s).toFloat(),
            (p2.y * s).toFloat(),
        )
    }
    path.close()
    return path
}

// ---------- 基元（只保留 blob 渲染仍在用的三种） ----------

private fun DrawScope.pCircle(cx: Float, cy: Float, r: Float, s: Float, color: Color) {
    drawCircle(color, radius = r * s, center = Offset(cx * s, cy * s))
}

private fun DrawScope.pPoly(points: List<Pair<Float, Float>>, s: Float, color: Color) {
    val path = Path().apply {
        moveTo(points[0].first * s, points[0].second * s)
        points.drop(1).forEach { (x, y) -> lineTo(x * s, y * s) }
        close()
    }
    drawPath(path, color)
}

private inline fun DrawScope.pStroke(w: Float, s: Float, color: Color, build: Path.() -> Unit) {
    val path = Path().apply(build)
    drawPath(path, color, style = Stroke(width = w * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------- 骨架（blob 脸 + 腮红；外框/面罩/天线/双耳按 face-sculpt §1 移除） ----------

private fun DrawScope.drawFace(faceFactors: List<Double>, faceFill: Color, s: Float) {
    drawPath(faceBlobPath(faceFactors, s), faceFill)
    // 腮红（白 α0.20，坐标沿用）
    pCircle(30.5f, 66f, 4.5f, s, BlushColor)
    pCircle(69.5f, 66f, 4.5f, s, BlushColor)
}

// ---------- 完整版表情（五官坐标沿用旧几何表，一字不改；眼→眉→嘴→附加 z 序） ----------

private fun DrawScope.drawFull(expression: AgentExpression, faceFill: Color, ink: Color, faceFactors: List<Double>, s: Float) {
    drawFace(faceFactors, faceFill, s)
    when (expression) {
        AgentExpression.CALM -> {
            // 眼
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 48f, 5f, s, ink)
            // 嘴：平横线
            pStroke(4f, s, ink) {
                moveTo(43f * s, 66f * s); lineTo(57f * s, 66f * s)
            }
        }

        AgentExpression.HAPPY -> {
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 48f, 5f, s, ink)
            // 嘴：上弯笑弧（二次贝塞尔）
            pStroke(4f, s, ink) {
                moveTo(39f * s, 63f * s); quadraticTo(50f * s, 74f * s, 61f * s, 63f * s)
            }
        }

        AgentExpression.EXCITED -> {
            pCircle(36f, 48f, 5.5f, s, ink)
            pCircle(64f, 48f, 5.5f, s, ink)
            pStroke(4f, s, ink) {
                moveTo(39f * s, 62f * s); quadraticTo(50f * s, 76f * s, 61f * s, 62f * s)
            }
            // 附加：头顶两侧放射线（色值沿用旧几何表：faceFill）
            pStroke(3f, s, faceFill) {
                moveTo(18f * s, 14f * s); lineTo(24f * s, 19f * s)
            }
            pStroke(3f, s, faceFill) {
                moveTo(82f * s, 14f * s); lineTo(76f * s, 19f * s)
            }
        }

        AgentExpression.THINKING -> {
            // 眼：错位上瞟
            pCircle(37f, 45f, 5f, s, ink)
            pCircle(63f, 47f, 5f, s, ink)
            pStroke(4f, s, ink) {
                moveTo(48f * s, 67f * s); lineTo(58f * s, 65f * s)
            }
            // 附加：思考泡泡点 ×2（渐大，色值沿用旧几何表：faceFill）
            pCircle(72f, 18f, 2.5f, s, faceFill)
            pCircle(79f, 11f, 3.5f, s, faceFill)
        }

        AgentExpression.CONFUSED -> {
            // 眼：一大一小
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 49f, 4f, s, ink)
            // 嘴：S 形波浪线（三次贝塞尔）
            pStroke(4f, s, ink) {
                moveTo(42f * s, 66f * s); cubicTo(47f * s, 70f * s, 53f * s, 62f * s, 58f * s, 66f * s)
            }
            // 附加：汗滴（DROP：圆 + 三角拼合，固定色 #8FD3F4）
            pCircle(80f, 31f, 4f, s, DropColor)
            pPoly(listOf(80f to 22f, 76.2f to 29.5f, 83.8f to 29.5f), s, DropColor)
        }

        AgentExpression.SLEEPY -> {
            // 眼：闭眼横线
            pStroke(4f, s, ink) {
                moveTo(31f * s, 48f * s); lineTo(41f * s, 48f * s)
            }
            pStroke(4f, s, ink) {
                moveTo(59f * s, 48f * s); lineTo(69f * s, 48f * s)
            }
            // 嘴：小圆嘴
            pCircle(50f, 67f, 3f, s, ink)
            // 附加：Z 字 ×2（色值沿用旧几何表：faceFill，圆帽折线）
            pStroke(3f, s, faceFill) {
                moveTo(66f * s, 18f * s); lineTo(76f * s, 18f * s)
                lineTo(66f * s, 28f * s); lineTo(76f * s, 28f * s)
            }
            pStroke(2.5f, s, faceFill) {
                moveTo(80f * s, 32f * s); lineTo(86f * s, 32f * s)
                lineTo(80f * s, 38f * s); lineTo(86f * s, 38f * s)
            }
        }

        AgentExpression.SAD -> {
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 48f, 5f, s, ink)
            // 眉：内高外低
            pStroke(3f, s, ink) {
                moveTo(30f * s, 39f * s); lineTo(40f * s, 43f * s)
            }
            pStroke(3f, s, ink) {
                moveTo(70f * s, 39f * s); lineTo(60f * s, 43f * s)
            }
            // 嘴：下弯哭弧
            pStroke(4f, s, ink) {
                moveTo(41f * s, 70f * s); quadraticTo(50f * s, 61f * s, 59f * s, 70f * s)
            }
            // 附加：右眼下泪滴（DROP，固定色 #8FD3F4）
            pCircle(66f, 60f, 3f, s, DropColor)
            pPoly(listOf(66f to 52f, 63.2f to 57.5f, 68.8f to 57.5f), s, DropColor)
        }

        AgentExpression.SMUG -> {
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(63f, 47f, 5f, s, ink)
            // 眉：单边挑眉
            pStroke(3f, s, ink) {
                moveTo(57f * s, 36f * s); lineTo(69f * s, 37f * s)
            }
            // 嘴：不对称歪嘴（三次贝塞尔）
            pStroke(4f, s, ink) {
                moveTo(42f * s, 67f * s); cubicTo(48f * s, 71f * s, 54f * s, 63f * s, 59f * s, 63f * s)
            }
        }

        AgentExpression.SURPRISED -> {
            pCircle(36f, 47f, 6f, s, ink)
            pCircle(64f, 47f, 6f, s, ink)
            // 眉：上挑
            pStroke(3f, s, ink) {
                moveTo(31f * s, 36f * s); lineTo(41f * s, 34f * s)
            }
            pStroke(3f, s, ink) {
                moveTo(59f * s, 34f * s); lineTo(69f * s, 36f * s)
            }
            // 嘴：大圆嘴
            pCircle(50f, 67f, 6f, s, ink)
        }

        AgentExpression.LOVE -> {
            // 眼：两颗心（HEART：双圆 + 三角拼合，固定色 #FF7BAC）
            pCircle(32.5f, 45.5f, 4f, s, HeartColor)
            pCircle(39.5f, 45.5f, 4f, s, HeartColor)
            pPoly(listOf(28.8f to 47.5f, 43.2f to 47.5f, 36f to 55f), s, HeartColor)
            pCircle(60.5f, 45.5f, 4f, s, HeartColor)
            pCircle(67.5f, 45.5f, 4f, s, HeartColor)
            pPoly(listOf(56.8f to 47.5f, 71.2f to 47.5f, 64f to 55f), s, HeartColor)
            // 嘴：笑弧
            pStroke(4f, s, ink) {
                moveTo(42f * s, 65f * s); quadraticTo(50f * s, 72f * s, 58f * s, 65f * s)
            }
            // 附加：头顶右上漂浮小爱心（HEART，整组抬高避开头部轮廓）
            pCircle(75f, 18f, 2f, s, HeartColor)
            pCircle(78f, 18f, 2f, s, HeartColor)
            pPoly(listOf(73.4f to 19.2f, 79.6f to 19.2f, 76.5f to 23.5f), s, HeartColor)
        }
    }
}

// ---------- compact（size < 40：只画 blob→眼→嘴，折算表沿用 §5.5） ----------

private fun DrawScope.drawCompact(expression: AgentExpression, faceFill: Color, ink: Color, faceFactors: List<Double>, s: Float) {
    drawPath(faceBlobPath(faceFactors, s), faceFill)
    when (expression) {
        // 眼嘴折算表（§5.5）：省掉腮红、眉、附加元素
        AgentExpression.SLEEPY -> {
            pStroke(4f, s, ink) {
                moveTo(31f * s, 48f * s); lineTo(41f * s, 48f * s)
            }
            pStroke(4f, s, ink) {
                moveTo(59f * s, 48f * s); lineTo(69f * s, 48f * s)
            }
            pCircle(50f, 67f, 3f, s, ink)
        }

        AgentExpression.SURPRISED -> {
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 48f, 5f, s, ink)
            pCircle(50f, 67f, 4f, s, ink)
        }

        AgentExpression.THINKING, AgentExpression.CONFUSED -> {
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 48f, 5f, s, ink)
            pStroke(4f, s, ink) {
                moveTo(43f * s, 66f * s); lineTo(57f * s, 66f * s)
            }
        }

        AgentExpression.SAD -> {
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 48f, 5f, s, ink)
            pStroke(4f, s, ink) {
                moveTo(41f * s, 70f * s); quadraticTo(50f * s, 61f * s, 59f * s, 70f * s)
            }
        }

        // CALM / HAPPY / EXCITED / SMUG / LOVE：圆点眼 + HAPPY 笑弧
        else -> {
            pCircle(36f, 48f, 5f, s, ink)
            pCircle(64f, 48f, 5f, s, ink)
            pStroke(4f, s, ink) {
                moveTo(39f * s, 63f * s); quadraticTo(50f * s, 74f * s, 61f * s, 63f * s)
            }
        }
    }
}
