package dev.sharpxia.agentreins.desktopui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.hub.avatar.AGENT_TINT_COUNT
import dev.sharpxia.agentreins.hub.avatar.AgentTint
import dev.sharpxia.agentreins.hub.avatar.AVATAR_PRESETS
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_DONE
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_ERROR
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_IDLE
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_RUNNING
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_THINKING
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_WAITING
import dev.sharpxia.agentreins.hub.avatar.BODY_PRESET_COUNT
import dev.sharpxia.agentreins.hub.avatar.BotSeed
import dev.sharpxia.agentreins.hub.avatar.EYE_SHAPE_COUNT
import dev.sharpxia.agentreins.hub.avatar.FACE_POINT_COUNT
import dev.sharpxia.agentreins.hub.avatar.POSE_PRESET_COUNT
import dev.sharpxia.agentreins.hub.avatar.facePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * 会话角色「BotCharacter」桌面渲染器（角色系统批次1，2026-11-20）：单体圆身 + 一对大竖眼，
 * 无嘴无眉无四肢。零 UI 接入（批次3 会话列表 / 批次4 聊天页接线）；iOS 对位
 * iosApp/iosApp/Shared/BotCharacterView.swift——本文件与 Swift 侧逐公式镜像，凡标
 * 「双端同表/同式」的常量与相位函数两侧一字不差（金标 3 ULID JVM 单测 + Swift DEBUG 断言锁）。
 *
 * 几何：100×100 逻辑坐标（s = size/100，RobotAvatar.swift:229 同规）；身体 = 既有 12 点
 * Catmull-Rom blob 管线原样复用（facePoint + 张力 1/6，AvatarRenderers.kt:303-324 同式，
 * 基半径 44 = FACE_BASE_RADIUS，AVATAR_PRESETS[bodyIndex].faceFactors 十二值表零新几何分支）；
 * 眼 = 竖圆角矩形 11×15（圆角=半宽）@ (39,45)/(61,45)，墨色 inkForFace 对比墨，眼窗可动域
 * x±3.0 / y±2.5（平移后恒 clamp，44 基半径下最远缘 < 最瘦体 30.8 安全域不变）。
 * 渲染尺寸档 28/32/36/44/96；size < 40.dp 走 compact（眼放大 13×18、动效幅度 ×1.6、
 * 沿用 RobotAvatar.swift:267-268 分支机制；桌面此前无 compact 分支，本组件补齐）。
 *
 * 六态相位（botStateFor 编码；motionEnabled=false 全静态定格特征帧，A6 对齐——off 档
 * 不建帧循环，AvatarRenderers.kt:99-105 先例）：
 * - IDLE     呼吸 1250ms 关键帧 scale 1.00/1.02@313/0.99@625/1.00@1000（AvatarRenderers.kt:111-117
 *            同帧，线性插值段）+ 眨眼 blinkTempo 周期 + 眼位 lissajous ±1.5@7s
 * - THINKING 眼位绕圈 r=(2.2,−1.8) 圈心上偏 1.2、2400ms/圈 + 体倾 +1.5°
 * - RUNNING  眼位横扫 ±2.0 三角波 380ms/程 + 体 y-bob ±0.8@500ms
 * - WAITING  正视 + 双连眨（90/110ms 两组隔 160ms，每 2200ms）+ 体摇 ±2.0°@1600ms +
 *            scale 脉冲 1.0→1.045 同相 1600ms（醒目度最高）
 * - ERROR    眯眼 scaleY 0.45 定格 + 横抖 ±1.2×3@300ms 每 2s + 体垂 scale 0.965
 * - DONE     开心弧（下半圆环 11×7）+ 弹跳 1→1.08→1 包络 450ms（spring(0.55) 双端时间纯
 *            函数落地：无过冲半正弦包络，峰值/时长照规格 1.08/450ms 不变）
 *
 * 性能纪律：帧循环 = LaunchedEffect+withFrameNanos 仅 motionEnabled=true 分支创建；
 * 体态（scale/rotate/y-bob）施于 graphicsLayer 组合层（不重绘 Canvas path，iOS AvatarIdleMotion
 * 同纪律）；blob path remember(seed,s) 一次构建；LazyColumn 出屏 dispose 即停帧（批次3 核验）。
 * [interactive]/[gaze] 为批次4 眼神交互面（注视/下瞥/对焦眨眼三路触发；gaze 附加眼位恒
 * clamp 眼窗，跳变 >4 单位触发对焦眨眼 90/110ms 冷却 250ms，off 档只 snap 不播眨眼）。
 */
@Composable
internal fun BotCharacter(
    seed: BotSeed,
    state: Int,
    size: Dp,
    motionEnabled: Boolean,
    interactive: Boolean,
    modifier: Modifier = Modifier,
    // 眼神交互覆盖（批次4）：interactive=true 时叠加到相位眼位（恒 clamp 眼窗）；null = 无交互。
    gaze: BotGaze? = null,
) {
    val isDark = isSystemInDarkTheme()
    val compact = size < BOT_COMPACT_THRESHOLD

    // ── 相位驱动（ms since 态进入；off 档钉特征帧、不建循环——A6）──
    var phaseMs by remember { mutableStateOf(0f) }
    LaunchedEffect(state, motionEnabled) {
        if (!motionEnabled) {
            phaseMs = botStaticPhaseMs(state).toFloat()
            return@LaunchedEffect
        }
        var start = -1L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                phaseMs = (now - start) / 1_000_000f
            }
        }
    }

    // 对焦眨眼（批次4）：gaze 跳变 >4 单位触发一次 90/110ms 包络，冷却 250ms（off 档只 snap
    // 不播，A3「功能性反馈非装饰」同口径）。眨眼动画挂 rememberCoroutineScope——LaunchedEffect
    // 随 gaze 变化重启会取消在途动画，scope 只随组合离场取消。
    val focusBlink = remember { Animatable(0f) }
    var lastGazeValue by remember { mutableStateOf<BotGaze?>(null) }
    var lastFocusBlinkAtMs by remember { mutableStateOf(0L) }
    val interactionScope = rememberCoroutineScope()
    LaunchedEffect(gaze, interactive, motionEnabled) {
        val current = gaze ?: BotGaze(0f, 0f)
        val previous = lastGazeValue ?: BotGaze(0f, 0f)
        lastGazeValue = gaze
        if (!interactive || !motionEnabled) return@LaunchedEffect
        val jump = hypot(current.dx - previous.dx, current.dy - previous.dy)
        val now = System.currentTimeMillis()
        if (jump > BOT_FOCUS_BLINK_THRESHOLD &&
            now - lastFocusBlinkAtMs >= BOT_FOCUS_BLINK_COOLDOWN_MS &&
            !focusBlink.isRunning
        ) {
            lastFocusBlinkAtMs = now
            focusBlink.snapTo(0f)
            interactionScope.launch {
                focusBlink.animateTo(1f, tween(BOT_BLINK_CLOSE_MS.toInt(), easing = LinearEasing))
                focusBlink.animateTo(0f, tween(BOT_BLINK_OPEN_MS.toInt(), easing = LinearEasing))
            }
        }
    }
    // 配色随值动画（300ms 同 A4 曲线；off 档 snap——AvatarRenderers.kt:193-197 同式）
    val faceFill by animateColorAsState(
        targetValue = Color(
            AgentTint.entries[seed.paletteIndex.coerceIn(0, AGENT_TINT_COUNT - 1)]
                .strongArgb(isDark),
        ),
        animationSpec = if (motionEnabled) tween(300, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)) else snap(),
        label = "bot-face-fill",
    )
    val ink = inkForFace(faceFill)

    // blob path（逻辑 12 点 → 像素空间）一次性构建：seed 形表 + 像素比变更才重建
    val density = LocalDensity.current
    val unitPx = remember(size, density) { with(density) { size.toPx() } / 100f }
    val blobPath = remember(seed, unitPx) {
        buildBotBlobPath(
            AVATAR_PRESETS[seed.bodyIndex.coerceIn(0, BODY_PRESET_COUNT - 1)].faceFactors,
            unitPx,
        )
    }

    // 体态施于组合层（graphicsLayer 块内读相位 = per-frame 层属性更新，不重绘 path、不重组——
    // 呼吸/摇/弹跳全部走这里，iOS AvatarIdleMotion 同纪律）
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val pose = botPose(seed, state, phaseMs, compact)
                scaleX = pose.scale
                scaleY = pose.scale
                rotationZ = pose.rotationDeg
                translationY = pose.offsetY * unitPx // offsetY 为 100 逻辑单位，translationY 取像素
            },
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val s = this.size.minDimension / 100f
            drawPath(blobPath, faceFill)
            val eye = botEyeFrame(seed, state, phaseMs, compact, gaze = if (interactive) gaze else null)
            // 对焦眨眼叠加：min 取更闭的包络（DONE U 弧不吃 scaleY 无碍；off 档 focusBlink 恒 0）
            val blinked = eye.copy(scaleY = min(eye.scaleY, 1f - focusBlink.value))
            drawBotEyes(seed, blinked, s, ink, compact)
        }
    }
}

/** 体态快照（scale/rotation/y-bob；坐标 100×100 逻辑系）。 */
private data class BotPose(val scale: Float, val rotationDeg: Float, val offsetY: Float)

/** 眼帧快照（平移 dx/dy + 竖直 scale + 开心弧开关）。 */
private data class BotEyeFrame(val dx: Float, val dy: Float, val scaleY: Float, val arc: Boolean)

// ── 双端同表：几何与姿态常量（Swift BotCharacterView.swift 逐字对锁，勿单端改值） ──

/** compact 折算阈值（size < 40，RobotAvatar.swift:267 同规）；幅度倍率 1.6。 */
val BOT_COMPACT_THRESHOLD: Dp = 40.dp
const val BOT_COMPACT_AMP = 1.6f

/** 眼：全量 11×15（圆角=半宽 5.5）、compact 放大 13×18；中心 (39,45)/(61,45)；眼窗 ±3.0/±2.5。 */
const val BOT_EYE_W = 11f
const val BOT_EYE_H = 15f
const val BOT_EYE_W_COMPACT = 13f
const val BOT_EYE_H_COMPACT = 18f
const val BOT_EYE_CX_L = 39f
const val BOT_EYE_CX_R = 61f
const val BOT_EYE_CY = 45f
const val BOT_EYE_WINDOW_X = 3f
const val BOT_EYE_WINDOW_Y = 2.5f

/** 开心弧（下半圆环）：全量 11×7 / compact 13×8，圆帽描边 3。 */
const val BOT_ARC_W = 11f
const val BOT_ARC_H = 7f
const val BOT_ARC_W_COMPACT = 13f
const val BOT_ARC_H_COMPACT = 8f
const val BOT_ARC_STROKE = 3f

/** 静息姿态四档（poseIndex → 倾角° × 静息眼位 x；规格 −1.2°/0°/+1.2° × ∓0.8）。 */
val BOT_REST_TILT = floatArrayOf(-1.2f, 0f, 1.2f, -1.2f)
val BOT_REST_EYE_X = floatArrayOf(-0.8f, 0.8f, -0.8f, 0.8f)

/** 眯眼定格 scaleY（ERROR）。 */
const val BOT_SQUINT_SCALE = 0.45f

/** 眨眼包络（RobotAvatar.swift:327-334 同式）：0–90ms 线性闭、90–200ms 线性开。 */
const val BOT_BLINK_CLOSE_MS = 90f
const val BOT_BLINK_OPEN_MS = 110f

/**
 * 眼神交互附加眼位（批次4；Swift BotGaze 同形镜像，100 逻辑单位，组件内恒 clamp 眼窗）。
 */
data class BotGaze(val dx: Float, val dy: Float) {
    companion object {
        /** 下视窗底（输入聚焦/新消息下瞥共用值）。 */
        val DOWN = BotGaze(0f, BOT_EYE_WINDOW_Y)
    }
}

/** 眼神交互常量（批次4；Swift BotCharacterMotion 双端同表勿单端改值）：注视锚=角色中心在
 *  悬停容器内的近似点、视野半径 120（饱和后按窗幅比例取单位方向）；对焦眨眼阈值/冷却。 */
const val BOT_GAZE_ANCHOR_X = 16f
const val BOT_GAZE_ANCHOR_Y = 14f
const val BOT_GAZE_RADIUS = 120f
const val BOT_GAZE_AMP_X = 3f
const val BOT_GAZE_AMP_Y = 2.5f
const val BOT_FOCUS_BLINK_THRESHOLD = 4f
const val BOT_FOCUS_BLINK_COOLDOWN_MS = 250L

// ── 相位函数（六态；t = ms since 态进入，纯时间函数双端同值） ──

/** compact 动效幅度倍率下的一帧体态。 */
private fun botPose(seed: BotSeed, state: Int, t: Float, compact: Boolean): BotPose {
    val amp = if (compact) BOT_COMPACT_AMP else 1f
    val rest = BOT_REST_TILT[seed.poseIndex.coerceIn(0, POSE_PRESET_COUNT - 1)] * amp
    val tau = (2f * PI).toFloat()
    return when (state) {
        BOT_STATE_IDLE -> BotPose(breathScale(t), rest, 0f)
        BOT_STATE_THINKING -> BotPose(1f, rest + 1.5f * amp, 0f)
        BOT_STATE_RUNNING -> BotPose(1f, rest, -0.8f * amp * sin(tau * t / 500f))
        BOT_STATE_WAITING -> BotPose(
            1f + 0.045f * amp * (0.5f - 0.5f * cos(tau * t / 1600f)),
            rest + 2.0f * amp * sin(tau * t / 1600f),
            0f,
        )
        BOT_STATE_ERROR -> BotPose(0.965f, rest, 0f)
        BOT_STATE_DONE -> BotPose(
            1f + 0.08f * amp * sin(PI.toFloat() * min(t, 450f) / 450f),
            rest,
            0f,
        )
        else -> BotPose(1f, rest, 0f)
    }
}

/** IDLE 呼吸关键帧（1250ms：1.00@0 → 1.02@313 → 0.99@625 → 1.00@1000 → 持住，线性段）。 */
private fun breathScale(t: Float): Float {
    val p = t % 1250f
    return when {
        p < 313f -> lerp(1.00f, 1.02f, p / 313f)
        p < 625f -> lerp(1.02f, 0.99f, (p - 313f) / 312f)
        p < 1000f -> lerp(0.99f, 1.00f, (p - 625f) / 375f)
        else -> 1.00f
    }
}

private fun lerp(a: Float, b: Float, x: Float): Float = a + (b - a) * x

/** 一帧眼位/眼皮（平移恒 clamp 眼窗；ERROR 眯眼定格；DONE 开心弧替代眼形；[gaze] 附加眼位先叠加后 clamp）。 */
private fun botEyeFrame(seed: BotSeed, state: Int, t: Float, compact: Boolean, gaze: BotGaze? = null): BotEyeFrame {
    val amp = if (compact) BOT_COMPACT_AMP else 1f
    val tau = (2f * PI).toFloat()
    var dx = BOT_REST_EYE_X[seed.poseIndex.coerceIn(0, POSE_PRESET_COUNT - 1)] * amp
    var dy = 0f
    var scaleY = 1f
    var arc = false
    when (state) {
        BOT_STATE_IDLE -> {
            dx += 1.5f * amp * sin(tau * t / 7000f)
            dy += 1.5f * amp * sin(2f * tau * t / 7000f)
            scaleY = 1f - blinkEnvelope(t % seed.blinkTempoMs.coerceAtLeast(1))
        }
        BOT_STATE_THINKING -> {
            // 绕圈 r=(2.2,−1.8)、圈心上偏 1.2（「上偏」落点）、2400ms/圈
            dx += 2.2f * amp * cos(tau * t / 2400f)
            dy += -1.2f * amp + 1.8f * amp * sin(tau * t / 2400f)
        }
        BOT_STATE_RUNNING -> dx += 2.0f * amp * sweepTriangle(t)
        BOT_STATE_WAITING -> {
            val u = t % 2200f
            scaleY = 1f - when {
                u < 200f -> blinkEnvelope(u)                       // 组1：90 闭 + 110 开
                u >= 360f && u < 560f -> blinkEnvelope(u - 360f)   // 组2：隔 160ms
                else -> 0f
            }
        }
        BOT_STATE_ERROR -> {
            scaleY = BOT_SQUINT_SCALE
            val u = t % 2000f
            if (u < 300f) dx += 1.2f * amp * sin(2f * tau * u / 300f) // ±1.2 ×3 圈@300ms
        }
        BOT_STATE_DONE -> arc = true
    }
    if (gaze != null) {
        dx += gaze.dx // 附加眼位恒过眼窗 clamp（±3.0/±2.5 安全域不变）
        dy += gaze.dy
    }
    return BotEyeFrame(
        dx = dx.coerceIn(-BOT_EYE_WINDOW_X, BOT_EYE_WINDOW_X),
        dy = dy.coerceIn(-BOT_EYE_WINDOW_Y, BOT_EYE_WINDOW_Y),
        scaleY = scaleY,
        arc = arc,
    )
}

/** 眨眼包络（快闭前 90ms 线性 → 慢开后 110ms 线性；RobotAvatar.swift:327-334 同式）。 */
private fun blinkEnvelope(u: Float): Float = when {
    u <= 0f -> 0f
    u < BOT_BLINK_CLOSE_MS -> u / BOT_BLINK_CLOSE_MS
    u < BOT_BLINK_CLOSE_MS + BOT_BLINK_OPEN_MS ->
        (1f - (u - BOT_BLINK_CLOSE_MS) / BOT_BLINK_OPEN_MS).coerceIn(0f, 1f)
    else -> 0f
}

/** 横扫三角波 ±1（760ms 全程 = 380ms/程）。 */
private fun sweepTriangle(t: Float): Float {
    val ph = t / 760f - floor(t / 760f)
    return 1f - 4f * abs(ph - 0.5f)
}

/** motionEnabled=false 的逐态特征帧钉点（ms）：峰值/上偏/眯眼可辨帧。 */
private fun botStaticPhaseMs(state: Int): Int = when (state) {
    BOT_STATE_RUNNING -> 125   // y-bob 峰值 −0.8
    BOT_STATE_WAITING -> 800   // 脉冲峰值 1.045（compact 1.072）、正视不眨
    BOT_STATE_ERROR -> 500     // 横抖窗（300ms）之外、眯眼定格
    BOT_STATE_DONE -> 225      // 弹跳峰值 1.08 + U 弧
    else -> 0                  // IDLE/THINKING：静息 / 绕圈起点（上偏+右视）
}

// ── 绘制 ──

/** 身体 blob：过 12 点闭合均匀 Catmull-Rom（张力 1/6），AvatarRenderers.kt:303-324 同式（像素空间）。 */
private fun buildBotBlobPath(factors: List<Double>, s: Float): Path {
    val pts = (0 until FACE_POINT_COUNT).map { i ->
        facePoint(i, if (i < factors.size) factors[i] else 1.0)
    }
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
    return path
}

/** 眼对绘制：eyeIndex 0 圆角竖方（圆角=半宽）/ 1 椭圆 / 2 正圆；眨/眯压 scaleY 宽保持；DONE 双 U 弧。 */
private fun DrawScope.drawBotEyes(seed: BotSeed, frame: BotEyeFrame, s: Float, ink: Color, compact: Boolean) {
    val w = if (compact) BOT_EYE_W_COMPACT else BOT_EYE_W
    val hFull = if (compact) BOT_EYE_H_COMPACT else BOT_EYE_H
    for (cx in floatArrayOf(BOT_EYE_CX_L, BOT_EYE_CX_R)) {
        val ecx = cx + frame.dx
        val ecy = BOT_EYE_CY + frame.dy
        if (frame.arc) {
            val rw = (if (compact) BOT_ARC_W_COMPACT else BOT_ARC_W) / 2f
            val rh = (if (compact) BOT_ARC_H_COMPACT else BOT_ARC_H) / 2f
            drawArc(
                color = ink,
                startAngle = 0f,
                sweepAngle = 180f, // 0°=3 点钟、顺时针过 90°（6 点钟）= 下半环（Compose y 向下）
                useCenter = false,
                topLeft = Offset((ecx - rw) * s, ecy * s),
                size = Size(2f * rw * s, 2f * rh * s),
                style = Stroke(BOT_ARC_STROKE * s, cap = StrokeCap.Round),
            )
            continue
        }
        val h = hFull * frame.scaleY
        if (h <= 0.05f) continue // 眨眼全闭不画（pEye 同纪律）
        val topLeft = Offset((ecx - w / 2f) * s, (ecy - h / 2f) * s)
        val size = Size(w * s, h * s)
        when (seed.eyeIndex.coerceIn(0, EYE_SHAPE_COUNT - 1)) {
            0 -> drawRoundRect(ink, topLeft, size, CornerRadius(min(w, h) / 2f * s))
            2 -> {
                val d = min(w, h)
                drawOval(ink, Offset((ecx - d / 2f) * s, (ecy - d / 2f) * s), Size(d * s, d * s))
            }
            else -> drawOval(ink, topLeft, size)
        }
    }
}
