package dev.sharpxia.agentreins.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.agentTint

/** 固定常量色（规格 §5.4）：不随 agent/明暗变化，不进桥、不进存储。 */
private val VisorColor = Color(0xFF1F2329L)
private val EyeColor = Color(0xFFF5F7FAL)
private val BlushColor = Color(0xFFFFFFFFL).copy(alpha = 0.20f)
private val DropColor = Color(0xFF8FD3F4L)
private val HeartColor = Color(0xFFFF7BACL)

/**
 * Grokbot 机器人头像（规格 avatar-spec-20261029 §5）。
 *
 * 100×100 逻辑坐标（原点左上、y 向下）；s = 渲染尺寸/100，**每个坐标与每条线宽显式乘 s**
 * （不用 scale 变换，避免线宽/抗锯齿口径漂移）。仅 5 种基元：circle / 统一圆角 roundRect /
 * 多边形填充 / 直线与二三次贝塞尔圆帽描边；z 序固定（§5.2：天线杆→天线球→双耳→头→面罩→腮红→眼→眉→嘴→附加）。
 * 渲染尺寸 < 40 走 compact（§5.5，只画 头→面罩→眼→嘴）；**无 forceFull 类逃生口参数**。
 * headFill = 该 tint 的 strong（明暗双表取自 AgentTint）。
 */
@Composable
fun RobotAvatar(palette: AgentTintName, expression: AgentExpression, size: Int, isDark: Boolean) {
    val headFill = agentTint(palette, isDark).strong
    Canvas(Modifier.size(size.dp)) {
        val s = this.size.minDimension / 100f
        if (size < 40) drawCompact(expression, headFill, s) else drawFull(expression, headFill, s)
    }
}

// ---------- 基元（规格 §5.1，只允许这五种） ----------

private fun DrawScope.pCircle(cx: Float, cy: Float, r: Float, s: Float, color: Color) {
    drawCircle(color, radius = r * s, center = Offset(cx * s, cy * s))
}

private fun DrawScope.pRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, s: Float, color: Color) {
    drawRoundRect(
        color,
        topLeft = Offset(x * s, y * s),
        size = Size(w * s, h * s),
        cornerRadius = CornerRadius(rx * s),
    )
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

// ---------- 骨架（规格 §5.2，所有表情共享） ----------

private fun DrawScope.drawSkeleton(headFill: Color, s: Float) {
    // 1 天线杆
    pStroke(4f, s, headFill) {
        moveTo(50f * s, 8f * s); lineTo(50f * s, 24f * s)
    }
    // 2 天线球
    pCircle(50f, 7f, 4.5f, s, headFill)
    // 3 左耳
    pRoundRect(4f, 42f, 10f, 16f, 5f, s, headFill)
    // 4 右耳
    pRoundRect(86f, 42f, 10f, 16f, 5f, s, headFill)
    // 5 头
    pRoundRect(12f, 22f, 76f, 64f, 22f, s, headFill)
    // 6 面罩
    pRoundRect(22f, 34f, 56f, 42f, 13f, s, VisorColor)
    // 7 腮红
    pCircle(30.5f, 66f, 4.5f, s, BlushColor)
    pCircle(69.5f, 66f, 4.5f, s, BlushColor)
}

// ---------- 完整版表情（规格 §5.3，逐条对齐；眼→眉→嘴→附加 z 序） ----------

private fun DrawScope.drawFull(expression: AgentExpression, headFill: Color, s: Float) {
    drawSkeleton(headFill, s)
    when (expression) {
        AgentExpression.CALM -> {
            // 眼
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 48f, 5f, s, EyeColor)
            // 嘴：平横线
            pStroke(4f, s, EyeColor) {
                moveTo(43f * s, 66f * s); lineTo(57f * s, 66f * s)
            }
        }

        AgentExpression.HAPPY -> {
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 48f, 5f, s, EyeColor)
            // 嘴：上弯笑弧（二次贝塞尔）
            pStroke(4f, s, EyeColor) {
                moveTo(39f * s, 63f * s); quadraticTo(50f * s, 74f * s, 61f * s, 63f * s)
            }
        }

        AgentExpression.EXCITED -> {
            pCircle(36f, 48f, 5.5f, s, EyeColor)
            pCircle(64f, 48f, 5.5f, s, EyeColor)
            pStroke(4f, s, EyeColor) {
                moveTo(39f * s, 62f * s); quadraticTo(50f * s, 76f * s, 61f * s, 62f * s)
            }
            // 附加：头顶两侧放射线（headFill）
            pStroke(3f, s, headFill) {
                moveTo(18f * s, 14f * s); lineTo(24f * s, 19f * s)
            }
            pStroke(3f, s, headFill) {
                moveTo(82f * s, 14f * s); lineTo(76f * s, 19f * s)
            }
        }

        AgentExpression.THINKING -> {
            // 眼：错位上瞟
            pCircle(37f, 45f, 5f, s, EyeColor)
            pCircle(63f, 47f, 5f, s, EyeColor)
            pStroke(4f, s, EyeColor) {
                moveTo(48f * s, 67f * s); lineTo(58f * s, 65f * s)
            }
            // 附加：思考泡泡点 ×2（渐大，headFill，漂浮在头部轮廓外）
            pCircle(72f, 18f, 2.5f, s, headFill)
            pCircle(79f, 11f, 3.5f, s, headFill)
        }

        AgentExpression.CONFUSED -> {
            // 眼：一大一小
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 49f, 4f, s, EyeColor)
            // 嘴：S 形波浪线（三次贝塞尔）
            pStroke(4f, s, EyeColor) {
                moveTo(42f * s, 66f * s); cubicTo(47f * s, 70f * s, 53f * s, 62f * s, 58f * s, 66f * s)
            }
            // 附加：汗滴（DROP：圆 + 三角拼合）
            pCircle(80f, 31f, 4f, s, DropColor)
            pPoly(listOf(80f to 22f, 76.2f to 29.5f, 83.8f to 29.5f), s, DropColor)
        }

        AgentExpression.SLEEPY -> {
            // 眼：闭眼横线
            pStroke(4f, s, EyeColor) {
                moveTo(31f * s, 48f * s); lineTo(41f * s, 48f * s)
            }
            pStroke(4f, s, EyeColor) {
                moveTo(59f * s, 48f * s); lineTo(69f * s, 48f * s)
            }
            // 嘴：小圆嘴
            pCircle(50f, 67f, 3f, s, EyeColor)
            // 附加：Z 字 ×2（headFill，圆帽折线）
            pStroke(3f, s, headFill) {
                moveTo(66f * s, 18f * s); lineTo(76f * s, 18f * s)
                lineTo(66f * s, 28f * s); lineTo(76f * s, 28f * s)
            }
            pStroke(2.5f, s, headFill) {
                moveTo(80f * s, 32f * s); lineTo(86f * s, 32f * s)
                lineTo(80f * s, 38f * s); lineTo(86f * s, 38f * s)
            }
        }

        AgentExpression.SAD -> {
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 48f, 5f, s, EyeColor)
            // 眉：内高外低（headFill）
            pStroke(3f, s, headFill) {
                moveTo(30f * s, 39f * s); lineTo(40f * s, 43f * s)
            }
            pStroke(3f, s, headFill) {
                moveTo(70f * s, 39f * s); lineTo(60f * s, 43f * s)
            }
            // 嘴：下弯哭弧
            pStroke(4f, s, EyeColor) {
                moveTo(41f * s, 70f * s); quadraticTo(50f * s, 61f * s, 59f * s, 70f * s)
            }
            // 附加：右眼下泪滴（DROP）
            pCircle(66f, 60f, 3f, s, DropColor)
            pPoly(listOf(66f to 52f, 63.2f to 57.5f, 68.8f to 57.5f), s, DropColor)
        }

        AgentExpression.SMUG -> {
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(63f, 47f, 5f, s, EyeColor)
            // 眉：单边挑眉（headFill）
            pStroke(3f, s, headFill) {
                moveTo(57f * s, 36f * s); lineTo(69f * s, 37f * s)
            }
            // 嘴：不对称歪嘴（三次贝塞尔）
            pStroke(4f, s, EyeColor) {
                moveTo(42f * s, 67f * s); cubicTo(48f * s, 71f * s, 54f * s, 63f * s, 59f * s, 63f * s)
            }
        }

        AgentExpression.SURPRISED -> {
            pCircle(36f, 47f, 6f, s, EyeColor)
            pCircle(64f, 47f, 6f, s, EyeColor)
            // 眉：上挑（headFill）
            pStroke(3f, s, headFill) {
                moveTo(31f * s, 36f * s); lineTo(41f * s, 34f * s)
            }
            pStroke(3f, s, headFill) {
                moveTo(59f * s, 34f * s); lineTo(69f * s, 36f * s)
            }
            // 嘴：大圆嘴
            pCircle(50f, 67f, 6f, s, EyeColor)
        }

        AgentExpression.LOVE -> {
            // 眼：两颗心（HEART：双圆 + 三角拼合）
            pCircle(32.5f, 45.5f, 4f, s, HeartColor)
            pCircle(39.5f, 45.5f, 4f, s, HeartColor)
            pPoly(listOf(28.8f to 47.5f, 43.2f to 47.5f, 36f to 55f), s, HeartColor)
            pCircle(60.5f, 45.5f, 4f, s, HeartColor)
            pCircle(67.5f, 45.5f, 4f, s, HeartColor)
            pPoly(listOf(56.8f to 47.5f, 71.2f to 47.5f, 64f to 55f), s, HeartColor)
            // 嘴：笑弧
            pStroke(4f, s, EyeColor) {
                moveTo(42f * s, 65f * s); quadraticTo(50f * s, 72f * s, 58f * s, 65f * s)
            }
            // 附加：头顶右上漂浮小爱心（HEART，整组抬高避开头部轮廓）
            pCircle(75f, 18f, 2f, s, HeartColor)
            pCircle(78f, 18f, 2f, s, HeartColor)
            pPoly(listOf(73.4f to 19.2f, 79.6f to 19.2f, 76.5f to 23.5f), s, HeartColor)
        }
    }
}

// ---------- compact（规格 §5.5，size < 40：只画 头→面罩→眼→嘴） ----------

private fun DrawScope.drawCompact(expression: AgentExpression, headFill: Color, s: Float) {
    pRoundRect(12f, 22f, 76f, 64f, 22f, s, headFill) // 5 头
    pRoundRect(22f, 34f, 56f, 42f, 13f, s, VisorColor) // 6 面罩
    when (expression) {
        // 眼嘴折算表（§5.5）：省掉天线、双耳、腮红、眉、附加元素
        AgentExpression.SLEEPY -> {
            pStroke(4f, s, EyeColor) {
                moveTo(31f * s, 48f * s); lineTo(41f * s, 48f * s)
            }
            pStroke(4f, s, EyeColor) {
                moveTo(59f * s, 48f * s); lineTo(69f * s, 48f * s)
            }
            pCircle(50f, 67f, 3f, s, EyeColor)
        }

        AgentExpression.SURPRISED -> {
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 48f, 5f, s, EyeColor)
            pCircle(50f, 67f, 4f, s, EyeColor)
        }

        AgentExpression.THINKING, AgentExpression.CONFUSED -> {
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 48f, 5f, s, EyeColor)
            pStroke(4f, s, EyeColor) {
                moveTo(43f * s, 66f * s); lineTo(57f * s, 66f * s)
            }
        }

        AgentExpression.SAD -> {
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 48f, 5f, s, EyeColor)
            pStroke(4f, s, EyeColor) {
                moveTo(41f * s, 70f * s); quadraticTo(50f * s, 61f * s, 59f * s, 70f * s)
            }
        }

        // CALM / HAPPY / EXCITED / SMUG / LOVE：圆点眼 + HAPPY 笑弧
        else -> {
            pCircle(36f, 48f, 5f, s, EyeColor)
            pCircle(64f, 48f, 5f, s, EyeColor)
            pStroke(4f, s, EyeColor) {
                moveTo(39f * s, 63f * s); quadraticTo(50f * s, 74f * s, 61f * s, 63f * s)
            }
        }
    }
}
