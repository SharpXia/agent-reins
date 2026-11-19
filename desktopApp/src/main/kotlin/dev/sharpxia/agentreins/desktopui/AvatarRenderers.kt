package dev.sharpxia.agentreins.desktopui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.hub.avatar.AgentExpression
import dev.sharpxia.agentreins.hub.avatar.AgentTint
import dev.sharpxia.agentreins.hub.avatar.FACE_POINT_COUNT
import dev.sharpxia.agentreins.hub.avatar.facePoint
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 桌面黏土 blob 机器人渲染器（v1.5.0 个性化形象轮**新建**——桌面无现成组件，勘材料 pitfall：
 * 无循环动画词汇，属新词汇引入非复用）。与 iOS RobotAvatar.swift 逐公式镜像：
 *
 * - blob = 过 12 点闭合均匀 Catmull-Rom（标准张力 1/6），控制点 = [facePoint]（0 号 12 点
 *   方向、顺时针、θ_i=i×30°）；相邻段共享点出/入切向共线等长 → C¹ 连续无尖角；全 1.0 = 正圆。
 * - 表情 = [AgentExpression] 十态，五官坐标表照抄 RobotAvatar.swift drawFull（一字不改）。
 * - 墨色 = 0.299R+0.587G+0.114B ≥ 153 → 深墨 0x1F2329，否则浅墨 0xF5F7FA（InkTheme 口径）。
 *
 * 动效（设计稿 §animationSpec 逐条）：
 * - A1 入场淡入：180ms tween（FastOutSlowInEasing），一次性非循环；off 档即时可见。
 * - A2 待机呼吸（motionLevel=standard 核心）：rememberInfiniteTransition keyframes 1250ms
 *   （0→1.00、313→1.02、625→0.99、1000→1.00，Restart），施于 graphicsLayer(scale/translationY/
 *   rotationZ) 组合层——**不重建 Canvas path**（iOS AvatarIdleMotion 同纪律）。
 * - A3 表情切换：AnimatedContent（scaleIn(0.82)+fadeIn(tween(180)) togetherWith
 *   scaleOut(1.08)+fadeOut(tween(180))）；off 档 snap 即时（功能性反馈非装饰）。
 * - A4 身形/配色切换：Animatable<Float> 0→1 捕获起终 12 值表，drawPath 时逐点 lerp +
 *   tween(300, CubicBezierEasing(0.22f,1f,0.36f,1f))（≈easeOutQuint）；配色
 *   animateColorAsState(tween(300))。切换 = 半径线性插值（GrokBot 机制同源）。
 * - A5 眨眼掩盖：形状切换用 0.2s 强制眨眼——眼睛屏面竖直压扁（宽保持），快闭前 45%（90ms）
 *   慢开后 55%（110ms）；打断=从当前合成帧出发（[factorsFrom] 取 morph 现值，不重播）。
 * - A6 档位：motionEnabled=false（off）→ A2/A4 静止 + A3 snap + A1 即时；桌面无系统级
 *   reduceMotion 对位（motionLevel 唯一门控，parity 已知差异记录不阻塞）。
 *
 * 尺寸约定：≥40dp 全量渲染（腮红/眉/附加元素），<40dp 桌面本轮无挂载点（预设缩略 44dp、
 * 预览 96dp 均 ≥40；compact 折算表为 iOS <40 专用，桌面挂会话行时再补）。
 */
@Composable
internal fun HubRobotAvatar(
    palette: AgentTint,
    expression: AgentExpression,
    size: Dp,
    faceFactors: List<Double>,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()

    // ── A1 入场淡入（一次性非循环；off 档即时可见——iOS hasAppeared 同语义） ──
    val entrance = remember(motionEnabled) { Animatable(if (motionEnabled) 0f else 1f) }
    LaunchedEffect(motionEnabled) {
        if (motionEnabled && entrance.value < 1f) {
            entrance.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        } else if (!motionEnabled) {
            entrance.snapTo(1f)
        }
    }

    // ── A2 待机呼吸（常驻循环，组合层 transform 非重绘 Path；motionEnabled=false 不施用） ──
    val breath = rememberInfiniteTransition(label = "avatar-idle")
    val breathScale by breath.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1250
                1.00f at 0 with FastOutSlowInEasing
                1.02f at 313 with FastOutSlowInEasing
                0.99f at 625 with FastOutSlowInEasing
                1.00f at 1000 with FastOutSlowInEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "breath-scale",
    )
    val breathOffsetY by breath.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1250
                0f at 0 with FastOutSlowInEasing
                (-0.7f) at 313 with FastOutSlowInEasing
                0.5f at 625 with FastOutSlowInEasing
                0f at 1000 with FastOutSlowInEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "breath-offset-y",
    )
    val breathRotation by breath.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1250
                0f at 0 with FastOutSlowInEasing
                0.6f at 313 with FastOutSlowInEasing
                (-0.45f) at 625 with FastOutSlowInEasing
                0f at 1000 with FastOutSlowInEasing
            },
            repeatMode = RepeatMode.Restart,
        ),
        label = "breath-rotation",
    )

    // ── A4 身形插值状态（起终 12 值表 + 进度；打断 = 从当前合成帧出发冻结混合，A5②） ──
    var factorsFrom by remember { mutableStateOf(faceFactors) }
    var factorsTo by remember { mutableStateOf(faceFactors) }
    val morph = remember { Animatable(1f) }
    val blink = remember { Animatable(0f) }
    LaunchedEffect(faceFactors, motionEnabled) {
        val current = (0 until FACE_POINT_COUNT).map { i ->
            val f = if (i < factorsFrom.size) factorsFrom[i] else 1.0
            val t = if (i < factorsTo.size) factorsTo[i] else 1.0
            f + (t - f) * morph.value // 当前合成帧（打断冻结起点）
        }
        factorsFrom = current
        factorsTo = faceFactors
        if (!motionEnabled) {
            morph.snapTo(1f) // off 档 A4 静止（A6）
            blink.snapTo(0f)
            return@LaunchedEffect
        }
        if (current == factorsTo) return@LaunchedEffect // 同形重渲染：不重播
        blink.snapTo(0f)
        morph.snapTo(0f)
        // A5 眨眼掩盖与 A4 插值并行：眨眼总长 200ms（快闭前 45% 90ms / 慢开后 55% 110ms）
        val blinkJob = launch {
            blink.animateTo(1f, tween(90, easing = LinearEasing))
            blink.animateTo(0f, tween(110, easing = LinearEasing))
        }
        morph.animateTo(1f, tween(300, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)))
        blinkJob.join()
        blink.snapTo(0f)
    }

    // ── A4 配色随值动画（300ms 同曲线；off 档 snap） ──
    val faceFill by animateColorAsState(
        targetValue = Color(palette.strongArgb(isDark)),
        animationSpec = if (motionEnabled) tween(300, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)) else snap(),
        label = "avatar-face-fill",
    )
    val ink = inkForFace(faceFill)

    AnimatedContent(
        targetState = expression,
        // A3 表情切换：180ms easeInOut 交叉淡变（iOS Canvas 不插值故换身份交叉淡变，桌面同语义）；
        // off 档 snap 即时（A6：表情切换是功能性反馈非装饰）
        transitionSpec = {
            if (motionEnabled) {
                (scaleIn(initialScale = 0.82f) + fadeIn(tween(180, easing = FastOutSlowInEasing))) togetherWith
                    (scaleOut(targetScale = 1.08f) + fadeOut(tween(180, easing = FastOutSlowInEasing)))
            } else {
                (fadeIn(snap())) togetherWith (fadeOut(snap()))
            }
        },
        label = "avatar-expression",
        // A2 施用面 = 组合层 graphicsLayer（scale/offsetY/rotationZ 同相位 1250ms，Canvas path
        // 不随呼吸重绘——iOS AvatarIdleMotion 同纪律）；motionEnabled=false 分支不施用（循环值
        // 推进零消费零绘制）。A1 入场 alpha 同层。
        modifier = modifier
            .alpha(entrance.value)
            .graphicsLayer {
                if (motionEnabled) {
                    scaleX = breathScale
                    scaleY = breathScale
                    translationY = breathOffsetY
                    rotationZ = breathRotation
                }
            },
    ) { expr ->
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size.minDimension / 100f
            val factors = (0 until FACE_POINT_COUNT).map { i ->
                val f = if (i < factorsFrom.size) factorsFrom[i] else 1.0
                val t = if (i < factorsTo.size) factorsTo[i] else 1.0
                f + (t - f) * morph.value
            }
            drawBlob(factors, s, faceFill)
            // 腮红（白 α0.20，坐标沿用）
            pCircle(30.5f, 66f, 4.5f, s, Color.White.copy(alpha = 0.20f))
            pCircle(69.5f, 66f, 4.5f, s, Color.White.copy(alpha = 0.20f))
            drawExpression(expr, faceFill, ink, s, blink.value)
        }
    }
}

/** 深浅墨色判定（iOS inkForFace 逐字口径：0.299R+0.587G+0.114B ≥ 153 → 深墨）。 */
internal fun inkForFace(face: Color): Color {
    val r = (face.red * 255f).roundToInt()
    val g = (face.green * 255f).roundToInt()
    val b = (face.blue * 255f).roundToInt()
    return if (0.299f * r + 0.587f * g + 0.114f * b >= 153f) Color(0xFF1F2329) else Color(0xFFF5F7FA)
}

// ── 绘制基元（RobotAvatar.swift pCircle/pPoly/pStroke 逐式镜像，坐标显式乘 s） ──

private fun DrawScope.pCircle(cx: Float, cy: Float, r: Float, s: Float, color: Color) {
    drawCircle(color, r * s, Offset(cx * s, cy * s))
}

/** 眼（眨眼版）：竖直压扁宽保持（A5）；blink=1 全闭（高 0）。圆眼专用，闭眼线/心眼不 squash。 */
private fun DrawScope.pEye(cx: Float, cy: Float, r: Float, s: Float, color: Color, blink: Float) {
    val h = r * (1f - blink)
    if (h <= 0.01f) return
    drawOval(
        color = color,
        topLeft = Offset((cx - r) * s, (cy - h) * s),
        size = Size(2f * r * s, 2f * h * s),
    )
}

private fun DrawScope.pPoly(points: List<Pair<Float, Float>>, s: Float, color: Color) {
    if (points.size < 3) return
    val path = Path()
    path.moveTo(points[0].first * s, points[0].second * s)
    points.drop(1).forEach { (x, y) -> path.lineTo(x * s, y * s) }
    path.close()
    drawPath(path, color)
}

private fun DrawScope.pStroke(w: Float, s: Float, color: Color, build: (Path) -> Unit) {
    val path = Path()
    build(path)
    drawPath(path, color, style = Stroke(width = w * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun pathLine(p: Path, x1: Float, y1: Float, x2: Float, y2: Float, s: Float) {
    p.moveTo(x1 * s, y1 * s)
    p.lineTo(x2 * s, y2 * s)
}

private fun pathQuad(p: Path, x1: Float, y1: Float, cx: Float, cy: Float, x2: Float, y2: Float, s: Float) {
    p.moveTo(x1 * s, y1 * s)
    p.quadraticBezierTo(cx * s, cy * s, x2 * s, y2 * s)
}

private fun pathCubic(
    p: Path, x1: Float, y1: Float,
    c1x: Float, c1y: Float, c2x: Float, c2y: Float,
    x2: Float, y2: Float, s: Float,
) {
    p.moveTo(x1 * s, y1 * s)
    p.cubicTo(c1x * s, c1y * s, c2x * s, c2y * s, x2 * s, y2 * s)
}

/** blob：过 12 点闭合均匀 Catmull-Rom（张力 1/6），cp1 = P_i+(P_{i+1}−P_{i−1})/6、cp2 = P_{i+1}−(P_{i+2}−P_i)/6。 */
private fun DrawScope.drawBlob(factors: List<Double>, s: Float, color: Color) {
    val pts = (0 until FACE_POINT_COUNT).map { i -> facePoint(i, if (i < factors.size) factors[i] else 1.0) }
    val path = Path()
    path.moveTo((pts[0].first * s).toFloat(), (pts[0].second * s).toFloat())
    for (i in 0 until FACE_POINT_COUNT) {
        val p0 = pts[(i + FACE_POINT_COUNT - 1) % FACE_POINT_COUNT]
        val p1 = pts[i]
        val p2 = pts[(i + 1) % FACE_POINT_COUNT]
        val p3 = pts[(i + 2) % FACE_POINT_COUNT]
        val cp1x = p1.first + (p2.first - p0.first) / 6.0
        val cp1y = p1.second + (p2.second - p0.second) / 6.0
        val cp2x = p2.first - (p3.first - p1.first) / 6.0
        val cp2y = p2.second - (p3.second - p1.second) / 6.0
        path.cubicTo(
            (cp1x * s).toFloat(), (cp1y * s).toFloat(),
            (cp2x * s).toFloat(), (cp2y * s).toFloat(),
            (p2.first * s).toFloat(), (p2.second * s).toFloat(),
        )
    }
    path.close()
    drawPath(path, color)
}

/**
 * 完整版表情（≥40dp；五官坐标照抄 RobotAvatar.swift drawFull 一字不改；眼→眉→嘴→附加 z 序）。
 * [blink] ∈ [0,1] 只压扁圆眼（宽保持）；闭眼线/心眼不参与。
 */
private fun DrawScope.drawExpression(
    expression: AgentExpression,
    faceFill: Color,
    ink: Color,
    s: Float,
    blink: Float,
) {
    // 附加元素固定色（face-sculpt §3：汗滴 #8FD3F4 / 心 #FF7BAC）
    val drop = Color(0xFF8FD3F4)
    val heart = Color(0xFFFF7BAC)

    when (expression) {
        AgentExpression.CALM -> {
            pEye(36f, 48f, 5f, s, ink, blink)
            pEye(64f, 48f, 5f, s, ink, blink)
            pStroke(4f, s, ink) { pathLine(it, 43f, 66f, 57f, 66f, s) }
        }

        AgentExpression.HAPPY -> {
            pEye(36f, 48f, 5f, s, ink, blink)
            pEye(64f, 48f, 5f, s, ink, blink)
            pStroke(4f, s, ink) { pathQuad(it, 39f, 63f, 50f, 74f, 61f, 63f, s) }
        }

        AgentExpression.EXCITED -> {
            pEye(36f, 48f, 5.5f, s, ink, blink)
            pEye(64f, 48f, 5.5f, s, ink, blink)
            pStroke(4f, s, ink) { pathQuad(it, 39f, 62f, 50f, 76f, 61f, 62f, s) }
            // 头顶两侧放射线（faceFill）
            pStroke(3f, s, faceFill) { pathLine(it, 18f, 14f, 24f, 19f, s) }
            pStroke(3f, s, faceFill) { pathLine(it, 82f, 14f, 76f, 19f, s) }
        }

        AgentExpression.THINKING -> {
            // 眼：错位上瞟
            pEye(37f, 45f, 5f, s, ink, blink)
            pEye(63f, 47f, 5f, s, ink, blink)
            pStroke(4f, s, ink) { pathLine(it, 48f, 67f, 58f, 65f, s) }
            // 思考泡泡点 ×2（渐大，faceFill，头部轮廓外）
            pCircle(72f, 18f, 2.5f, s, faceFill)
            pCircle(79f, 11f, 3.5f, s, faceFill)
        }

        AgentExpression.CONFUSED -> {
            // 眼：一大一小
            pEye(36f, 48f, 5f, s, ink, blink)
            pEye(64f, 49f, 4f, s, ink, blink)
            // 嘴：S 形波浪线（三次贝塞尔）
            pStroke(4f, s, ink) {
                pathCubic(it, 42f, 66f, 47f, 70f, 53f, 62f, 58f, 66f, s)
            }
            // 汗滴（圆 + 三角拼合，固定 #8FD3F4）
            pCircle(80f, 31f, 4f, s, drop)
            pPoly(listOf(80f to 22f, 76.2f to 29.5f, 83.8f to 29.5f), s, drop)
        }

        AgentExpression.SLEEPY -> {
            // 眼：闭眼横线（不参与眨眼压扁）
            pStroke(4f, s, ink) { pathLine(it, 31f, 48f, 41f, 48f, s) }
            pStroke(4f, s, ink) { pathLine(it, 59f, 48f, 69f, 48f, s) }
            pCircle(50f, 67f, 3f, s, ink) // 小圆嘴
            // Z 字 ×2（faceFill，圆帽折线）
            pStroke(3f, s, faceFill) {
                it.moveTo(66f * s, 18f * s); it.lineTo(76f * s, 18f * s)
                it.lineTo(66f * s, 28f * s); it.lineTo(76f * s, 28f * s)
            }
            pStroke(2.5f, s, faceFill) {
                it.moveTo(80f * s, 32f * s); it.lineTo(86f * s, 32f * s)
                it.lineTo(80f * s, 38f * s); it.lineTo(86f * s, 38f * s)
            }
        }

        AgentExpression.SAD -> {
            pEye(36f, 48f, 5f, s, ink, blink)
            pEye(64f, 48f, 5f, s, ink, blink)
            // 眉：内高外低
            pStroke(3f, s, ink) { pathLine(it, 30f, 39f, 40f, 43f, s) }
            pStroke(3f, s, ink) { pathLine(it, 70f, 39f, 60f, 43f, s) }
            // 嘴：下弯哭弧
            pStroke(4f, s, ink) { pathQuad(it, 41f, 70f, 50f, 61f, 59f, 70f, s) }
            // 右眼下泪滴（固定 #8FD3F4）
            pCircle(66f, 60f, 3f, s, drop)
            pPoly(listOf(66f to 52f, 63.2f to 57.5f, 68.8f to 57.5f), s, drop)
        }

        AgentExpression.SMUG -> {
            pEye(36f, 48f, 5f, s, ink, blink)
            pEye(63f, 47f, 5f, s, ink, blink)
            // 眉：单边挑眉
            pStroke(3f, s, ink) { pathLine(it, 57f, 36f, 69f, 37f, s) }
            // 嘴：不对称歪嘴（三次贝塞尔）
            pStroke(4f, s, ink) {
                pathCubic(it, 42f, 67f, 48f, 71f, 54f, 63f, 59f, 63f, s)
            }
        }

        AgentExpression.SURPRISED -> {
            pEye(36f, 47f, 6f, s, ink, blink)
            pEye(64f, 47f, 6f, s, ink, blink)
            // 眉：上挑
            pStroke(3f, s, ink) { pathLine(it, 31f, 36f, 41f, 34f, s) }
            pStroke(3f, s, ink) { pathLine(it, 59f, 34f, 69f, 36f, s) }
            pCircle(50f, 67f, 6f, s, ink) // 大圆嘴
        }

        AgentExpression.LOVE -> {
            // 眼：两颗心（双圆 + 三角拼合，固定 #FF7BAC；不参与眨眼压扁）
            pCircle(32.5f, 45.5f, 4f, s, heart)
            pCircle(39.5f, 45.5f, 4f, s, heart)
            pPoly(listOf(28.8f to 47.5f, 43.2f to 47.5f, 36f to 55f), s, heart)
            pCircle(60.5f, 45.5f, 4f, s, heart)
            pCircle(67.5f, 45.5f, 4f, s, heart)
            pPoly(listOf(56.8f to 47.5f, 71.2f to 47.5f, 64f to 55f), s, heart)
            // 嘴：笑弧
            pStroke(4f, s, ink) { pathQuad(it, 42f, 65f, 50f, 72f, 58f, 65f, s) }
            // 头顶右上漂浮小爱心（整组抬高避开头部轮廓）
            pCircle(75f, 18f, 2f, s, heart)
            pCircle(78f, 18f, 2f, s, heart)
            pPoly(listOf(73.4f to 19.2f, 79.6f to 19.2f, 76.5f to 23.5f), s, heart)
        }
    }
}
