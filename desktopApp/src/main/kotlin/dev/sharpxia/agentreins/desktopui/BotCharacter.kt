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
import dev.sharpxia.agentreins.hub.avatar.botSeedFor
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
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * 会话角色「BotCharacter」桌面渲染器（角色系统批次1，2026-11-20；嘴部通道+幅度加大量
 * 2026-11-21）：单体圆身 + 一对大竖眼 + 一张嘴，无眉无四肢。零 UI 接入（批次3 会话列表 /
 * 批次4 聊天页接线）；iOS 对位 iosApp/iosApp/Shared/BotCharacterView.swift——本文件与
 * Swift 侧逐公式镜像，凡标「双端同表/同式」的常量与相位函数两侧一字不差（金标 3 ULID
 * JVM 单测 + Swift DEBUG 断言锁；嘴部/幅度新表另由 JVM BotMouthRulesTest 与 Swift
 * BotCharacterGoldLock.mouthAmplitudeLock DEBUG 锁同判据字面量表对锁——走查④修，单端
 * 改值 DEBUG 即断，不再只靠并排 diff 人工纪律）。
 *
 * 几何：100×100 逻辑坐标（s = size/100，RobotAvatar.swift:229 同规）；身体 = 既有 12 点
 * Catmull-Rom blob 管线原样复用（facePoint + 张力 1/6，AvatarRenderers.kt:303-324 同式，
 * 基半径 44 = FACE_BASE_RADIUS，AVATAR_PRESETS[bodyIndex].faceFactors 十二值表零新几何分支）；
 * 眼 = 竖圆角矩形 11×15（圆角=半宽）@ (39,45)/(61,45)，墨色 inkForFace 对比墨，眼窗可动域
 * x±4.5 / y±3.5（2026-11-21 扩容：旧 ±3.0/±2.5 下 compact ×1.6 幅度半周期被 clamp 钉边——
 * 32/36pt 真实挂载档上设计的绕圈/横扫被压扁；扩窗后全表零钉边，见幅度审计注释）。
 * 嘴 = 二次曲线唇线 @ 中心 (50,67)：全宽 16（compact 20）、全腔高 10（compact 13）、描边
 * 3（3.5）、曲度控制偏 ±5（±6，quad 中点实际位移半值）；嘴色 = 与眼同帧 inkForFace 对比墨
 * （一处计算两处消费，禁固定色）。安全域验算（FACE_MIN 0.70 界 80.8，AvatarProfile.kt:152）：
 * 保守包络 open=1 唇底 67+6.5+描边半宽 1.75=75.25 < 80.8 ✓（逐态实际最深处 DONE 大笑
 * compact 下唇中点 67+(0.7×6+0.85×13)/2=74.6 更浅）；compact 含嘴 dx±2.4 极值角点距心
 * √(13.5²+23.5²)≈27.1 < 30.8 ✓；嘴上缘逐态最坏帧（WAITING 叹气 compact 曲度 −0.45+开
 * 0.35）63.4 对眼极值下缘 57.5（满窗 dy+3.5+半高 9）间隙 ≈5.9 单位 ✓（评审保守口径
 * 「全开+下弯极端帧 58.75」亦留 1.25 间隙，两口径均不重叠）。
 * 渲染尺寸档 28/32/36/44/96；size < 40.dp 走 compact（眼放大 13×18、动效幅度 ×1.6、
 * 沿用 RobotAvatar.swift:267-268 分支机制；桌面此前无 compact 分支，本组件补齐）。
 *
 * 六态相位（botStateFor 编码；motionEnabled=false 全静态定格特征帧，A6 对齐——off 档
 * 不建帧循环、嘴出特征形不出动画，AvatarRenderers.kt:99-105 先例）。幅度表 2026-11-21
 * 按全幅口径（含 rest±0.8 与圈心项）重算：compact ×1.6 极值 逐通道 ≤ 窗 ±4.5/±3.5，零钉边。
 * 小尺寸可辨识度主载荷 =「形状（嘴）+缩放+旋转」，不再依赖亚像素平移：
 * - IDLE     呼吸 1250ms 关键帧 scale 1.00/1.035@313/0.98@625/1.00@1000（线性插值段；
 *            加幅有度——IDLE 是六态对比基线，过头反降相互对比度）+ 眨眼 blinkTempo 周期 +
 *            眼位 lissajous ±2.0@7s（dx 极值 (0.8+2.0)×1.6=4.48≤4.5 / dy 2.0×1.6=3.2≤3.5）+
 *            嘴 微张笑（开 0.10–0.22 随 1250ms 呼吸、曲度 +0.55）
 * - THINKING 眼位绕圈 R=(2.0,1.6) 圈心 −0.5、2400ms/圈（dx 4.48✓；dy [−3.36,+1.76]⊂±3.5
 *            真装进窗）+ 体倾 +4°（compact +6.4°）+ 嘴 小 o（开 0.45、窄 0.70、随眼圈
 *            dx ±1.5 漂移）
 * - RUNNING  眼位横扫 ±2.0 三角波 380ms/程（dx 4.48，靠扩窗消除旧钉边）+ 体 y-bob ±3.0@500ms
 *            （compact ±4.8 逻辑 ≈±1.7pt@36pt）+ 嘴 380ms 开合（开 0.30+0.45×升余弦 ∈[0.30,0.75]，
 *            同横扫半周期）
 * - WAITING  正视 + 双连眨（90/110ms 两组隔 160ms，每 2200ms）+ 体摇 ±5°@1600ms（compact
 *            ±8°）+ scale 脉冲 1.0→1.08 同相 1600ms（compact 1.128，醒目度最高）+
 *            嘴 抿扁（曲度 −0.15）+ 周期叹气 u∈[560,900]@2200ms（开 0.35、曲度垂至 −0.45）
 * - ERROR    眯眼 scaleY 0.45 定格 + 横抖 ±2.0×3@300ms 每 2s（dx 4.48✓）+ 体垂 scale 0.95 +
 *            嘴 下弯扁嘴（曲度 −0.5，抖窗内颤 ±0.25）
 * - DONE     开心弧（下半圆环 11×7）+ 弹跳 1→1.16→1 包络 450ms（spring(0.55) 双端时间纯
 *            函数落地：无过冲半正弦包络，峰值/时长照规格 1.16/450ms 不变）+
 *            嘴 开口大笑（开 0.85×同包络、宽 1.35 极值→compact 全宽 27、曲度 +0.7）
 *
 * 性能纪律：帧循环 = LaunchedEffect+withFrameNanos 仅 motionEnabled=true 分支创建；
 * 体态（scale/rotate/y-bob）施于 graphicsLayer 组合层（不重绘 Canvas path，iOS AvatarIdleMotion
 * 同纪律）；blob path remember(seed,s) 一次构建；嘴部只加 1–2 段 quad 小 path/弧（成本同级
 * 于既有逐帧眼 roundedRect，无逐帧 blob 重建）；LazyColumn 出屏 dispose 即停帧（批次3 核验）。
 * [interactive]/[gaze] 为批次4 眼神交互面（注视/下瞥/对焦眨眼三路触发；gaze 附加眼位恒
 * clamp 眼窗 ±4.5/±3.5（BOT_GAZE_AMP_X/Y 随窗同步——hover 注视到不了窗沿=批次4 语义回归），
 * 跳变 >4 单位触发对焦眨眼 90/110ms 冷却 250ms，off 档只 snap 不播眨眼）。
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
    // 档预览（批次5 设置页）：显式 12 值造型与色序号（seed bodyIndex/paletteIndex 让位；nil = seed 域）。
    faceFactorsOverride: List<Double>? = null,
    paletteIndexOverride: Int? = null,
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
    val paletteIndex = paletteIndexOverride ?: seed.paletteIndex
    val faceFill by animateColorAsState(
        targetValue = Color(
            AgentTint.entries[paletteIndex.coerceIn(0, AGENT_TINT_COUNT - 1)]
                .strongArgb(isDark),
        ),
        animationSpec = if (motionEnabled) tween(300, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)) else snap(),
        label = "bot-face-fill",
    )
    val ink = inkForFace(faceFill)

    // blob path（逻辑 12 点 → 像素空间）一次性构建：造型（档 override 或 seed 形表）+ 像素比
    // 变更才重建（设置页预览随草稿选型即时重建）
    val density = LocalDensity.current
    val unitPx = remember(size, density) { with(density) { size.toPx() } / 100f }
    val effectiveFactors = faceFactorsOverride
        ?: AVATAR_PRESETS[seed.bodyIndex.coerceIn(0, BODY_PRESET_COUNT - 1)].faceFactors
    val blobPath = remember(effectiveFactors, unitPx) {
        buildBotBlobPath(effectiveFactors, unitPx)
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
            // 嘴与眼同一帧 ink（上方 inkForFace 单点计算两处消费——深色 palette/暗色模式下
            // 固定深墨不可见，对比墨纪律）；dx/开合随嘴帧，体态（呼吸/摇/弹）已施于组合层
            drawMouth(botMouthFrame(state, phaseMs, compact), s, ink, compact)
        }
    }
}

/** 体态快照（scale/rotation/y-bob；坐标 100×100 逻辑系）。 */
private data class BotPose(val scale: Float, val rotationDeg: Float, val offsetY: Float)

/**
 * 深浅墨色判定（iOS inkForFace 逐字口径：R/G/B round 到 0-255，
 * 0.299R+0.587G+0.114B ≥ 153 → 深墨 0x1F2329，否则浅墨 0xF5F7FA）。
 * 自 AvatarRenderers.kt 迁入（批次5 旧渲染器整文件退役，本组件为其唯一消费面）。
 */
internal fun inkForFace(face: Color): Color {
    val r = (face.red * 255f).roundToInt()
    val g = (face.green * 255f).roundToInt()
    val b = (face.blue * 255f).roundToInt()
    return if (0.299f * r + 0.587f * g + 0.114f * b >= 153f) Color(0xFF1F2329) else Color(0xFFF5F7FA)
}

/** 眼帧快照（平移 dx/dy + 竖直 scale + 开心弧开关）。 */
private data class BotEyeFrame(val dx: Float, val dy: Float, val scaleY: Float, val arc: Boolean)

// ── 双端同表：几何与姿态常量（Swift BotCharacterView.swift 逐字对锁，勿单端改值） ──

/** compact 折算阈值（size < 40，RobotAvatar.swift:267 同规）；幅度倍率 1.6。 */
val BOT_COMPACT_THRESHOLD: Dp = 40.dp
const val BOT_COMPACT_AMP = 1.6f

/**
 * 眼：全量 11×15（圆角=半宽 5.5）、compact 放大 13×18；中心 (39,45)/(61,45)。
 * 眼窗 ±4.5/±3.5（2026-11-21 扩容，旧 ±3.0/±2.5）：compact ×1.6 新幅度表逐通道极值
 * IDLE/THINKING/RUNNING/ERROR dx (0.8+2.0)×1.6=4.48≤4.5、IDLE dy 2.0×1.6=3.2≤3.5、
 * THINKING dy [−3.36,+1.76]⊂±3.5——全表零钉边（改幅度必按全幅口径含 rest±0.8/圈心项
 * 重算，漏加即算错；BOT_GAZE_AMP_X/Y 必须随窗同步，否则 hover 注视到不了窗沿=批次4 回归）。
 */
const val BOT_EYE_W = 11f
const val BOT_EYE_H = 15f
const val BOT_EYE_W_COMPACT = 13f
const val BOT_EYE_H_COMPACT = 18f
const val BOT_EYE_CX_L = 39f
const val BOT_EYE_CX_R = 61f
const val BOT_EYE_CY = 45f
const val BOT_EYE_WINDOW_X = 4.5f
const val BOT_EYE_WINDOW_Y = 3.5f

/**
 * 嘴（嘴部通道 2026-11-21）：二次曲线唇线，中心 (50,67)。定稿口径全腔高制（勿混全高/半高）：
 * 全宽 16（compact 20）、全腔高 10（compact 13，open=1 时上下唇中点距）、描边 3（3.5，仅
 * 闭/抿唇线）、曲度控制偏 ±5（±6；quad 控制点 y 偏移 = curve×此值，中点实际位移半值）、
 * DONE wide 极值 1.35（→compact 全宽 20×1.35=27）。open/curve 值域 [0,1]/[−1,1]（frame 内
 * coerce 兜底）；闭/抿嘴 open<0.04 走圆帽唇线，≥0.04 上下唇 quad 围腔填充。逐态嘴形与
 * 幅度表同相位周期（mouthFrame 纯时间函数，Swift BotCharacterMotion 逐公式镜像勿单端改）。
 */
const val BOT_MOUTH_CX = 50f
const val BOT_MOUTH_CY = 67f
const val BOT_MOUTH_W = 16f
const val BOT_MOUTH_W_COMPACT = 20f
const val BOT_MOUTH_CAVITY_H = 10f
const val BOT_MOUTH_CAVITY_H_COMPACT = 13f
const val BOT_MOUTH_STROKE = 3f
const val BOT_MOUTH_STROKE_COMPACT = 3.5f
const val BOT_MOUTH_CURVE_CTRL = 5f
const val BOT_MOUTH_CURVE_CTRL_COMPACT = 6f
const val BOT_MOUTH_WIDE_MAX = 1.35f

/** 开/抿分界：open 低于此值呈唇线（圆帽描边），不低于呈腔体填充。 */
const val BOT_MOUTH_OPEN_FILL_MIN = 0.04f

/** 嘴部逐态定值（mouthFrame 消费；纯数值锚，双端同表）：IDLE 微张笑/THINKING 小 o/
 *  RUNNING 开合/WAITING 抿扁+叹气/ERROR 下弯+颤/DONE 大笑——见 mouthFrame 注释。 */
const val BOT_MOUTH_IDLE_OPEN_BASE = 0.10f
const val BOT_MOUTH_IDLE_OPEN_AMP = 0.12f
const val BOT_MOUTH_IDLE_CURVE = 0.55f
const val BOT_MOUTH_THINK_OPEN = 0.45f
const val BOT_MOUTH_THINK_WIDE = 0.70f
const val BOT_MOUTH_THINK_DX = 1.5f
const val BOT_MOUTH_RUN_OPEN_BASE = 0.30f
const val BOT_MOUTH_RUN_OPEN_AMP = 0.45f
const val BOT_MOUTH_WAIT_CURVE = -0.15f
const val BOT_MOUTH_SIGH_OPEN = 0.35f
const val BOT_MOUTH_SIGH_CURVE = -0.45f
const val BOT_MOUTH_ERROR_CURVE = -0.5f
const val BOT_MOUTH_ERROR_TREMBLE = 0.25f
const val BOT_MOUTH_DONE_OPEN = 0.85f
const val BOT_MOUTH_DONE_CURVE = 0.7f
const val BOT_MOUTH_DONE_WIDE_AMP = 0.35f

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
 *  悬停容器内的近似点、视野半径 120（饱和后按窗幅比例取单位方向）；对焦眨眼阈值/冷却。
 *  窗幅 2026-11-21 随眼窗扩容同步 4.5×3.5（=BOT_EYE_WINDOW_X/Y，hover 注视可达窗沿）。 */
const val BOT_GAZE_ANCHOR_X = 16f
const val BOT_GAZE_ANCHOR_Y = 14f
const val BOT_GAZE_RADIUS = 120f
const val BOT_GAZE_AMP_X = 4.5f
const val BOT_GAZE_AMP_Y = 3.5f
const val BOT_FOCUS_BLINK_THRESHOLD = 4f
const val BOT_FOCUS_BLINK_COOLDOWN_MS = 250L

/** 设置页档预览的中性种子（批次5）：botSeedFor("") = h=0 → 眼形圆角竖方/姿态 0/眨音 2600ms
 *  ——档（12 值+色）经 override 注入，seed 只余档外身份维度（眼形/姿态/眨音），设置页取
 *  中性值不携会话身份（Swift botSettingsPreviewSeed 同值镜像）。 */
val botSettingsPreviewSeed: BotSeed = botSeedFor("")

// ── 相位函数（六态；t = ms since 态进入，纯时间函数双端同值） ──

/** compact 动效幅度倍率下的一帧体态（幅度表 2026-11-21 加大：32/36pt 挂载档亚像素级
 *  位移不可辨 → 主载荷换缩放/旋转/嘴形，逐通道极值审计见头注释）。 */
private fun botPose(seed: BotSeed, state: Int, t: Float, compact: Boolean): BotPose {
    val amp = if (compact) BOT_COMPACT_AMP else 1f
    val rest = BOT_REST_TILT[seed.poseIndex.coerceIn(0, POSE_PRESET_COUNT - 1)] * amp
    val tau = (2f * PI).toFloat()
    return when (state) {
        BOT_STATE_IDLE -> BotPose(breathScale(t), rest, 0f)
        BOT_STATE_THINKING -> BotPose(1f, rest + 4.0f * amp, 0f)
        BOT_STATE_RUNNING -> BotPose(1f, rest, -3.0f * amp * sin(tau * t / 500f))
        BOT_STATE_WAITING -> BotPose(
            1f + 0.08f * amp * (0.5f - 0.5f * cos(tau * t / 1600f)),
            rest + 5.0f * amp * sin(tau * t / 1600f),
            0f,
        )
        BOT_STATE_ERROR -> BotPose(0.95f, rest, 0f)
        BOT_STATE_DONE -> BotPose(
            1f + 0.16f * amp * sin(PI.toFloat() * min(t, 450f) / 450f),
            rest,
            0f,
        )
        else -> BotPose(1f, rest, 0f)
    }
}

/** IDLE 呼吸关键帧（1250ms：1.000@0 → 1.035@313 → 0.980@625 → 1.000@1000 → 持住，线性段；
 *  加幅有度：IDLE 是六态对比基线，呼吸过头相互对比度反降）。 */
private fun breathScale(t: Float): Float {
    val p = t % 1250f
    return when {
        p < 313f -> lerp(1.000f, 1.035f, p / 313f)
        p < 625f -> lerp(1.035f, 0.980f, (p - 313f) / 312f)
        p < 1000f -> lerp(0.980f, 1.000f, (p - 625f) / 375f)
        else -> 1.000f
    }
}

private fun lerp(a: Float, b: Float, x: Float): Float = a + (b - a) * x

/** 一帧眼位/眼皮（平移恒 clamp 眼窗 ±4.5/±3.5；ERROR 眯眼定格；DONE 开心弧替代眼形；
 *  [gaze] 附加眼位先叠加后 clamp。新表按全幅口径零钉边，审计见常量区注释）。 */
private fun botEyeFrame(seed: BotSeed, state: Int, t: Float, compact: Boolean, gaze: BotGaze? = null): BotEyeFrame {
    val amp = if (compact) BOT_COMPACT_AMP else 1f
    val tau = (2f * PI).toFloat()
    var dx = BOT_REST_EYE_X[seed.poseIndex.coerceIn(0, POSE_PRESET_COUNT - 1)] * amp
    var dy = 0f
    var scaleY = 1f
    var arc = false
    when (state) {
        BOT_STATE_IDLE -> {
            dx += 2.0f * amp * sin(tau * t / 7000f)   // lissajous ±2.0@7s（compact 极值 4.48≤4.5）
            dy += 2.0f * amp * sin(2f * tau * t / 7000f) // 3.2≤3.5
            scaleY = 1f - blinkEnvelope(t % seed.blinkTempoMs.coerceAtLeast(1))
        }
        BOT_STATE_THINKING -> {
            // 绕圈 R=(2.0,1.6)、圈心 −0.5（「上偏」余量收窄换 dy 全程进窗 [−3.36,+1.76]）、2400ms/圈
            dx += 2.0f * amp * cos(tau * t / 2400f)
            dy += -0.5f * amp + 1.6f * amp * sin(tau * t / 2400f)
        }
        BOT_STATE_RUNNING -> dx += 2.0f * amp * sweepTriangle(t) // 横扫 ±2.0（compact 4.48，扩窗零钉边）
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
            if (u < 300f) dx += 2.0f * amp * sin(2f * tau * u / 300f) // ±2.0 ×3 圈@300ms（compact 4.48）
        }
        BOT_STATE_DONE -> arc = true
    }
    if (gaze != null) {
        dx += gaze.dx // 附加眼位恒过眼窗 clamp（±4.5/±3.5；AMP_X/Y=窗，注视可达窗沿）
        dy += gaze.dy
    }
    return BotEyeFrame(
        dx = dx.coerceIn(-BOT_EYE_WINDOW_X, BOT_EYE_WINDOW_X),
        dy = dy.coerceIn(-BOT_EYE_WINDOW_Y, BOT_EYE_WINDOW_Y),
        scaleY = scaleY,
        arc = arc,
    )
}

// ── 嘴部通道（嘴部+幅度轮 2026-11-21）：一帧嘴形（纯时间相位，与眼/体同周期，双端同式）──

/**
 * 嘴帧快照：open 开合 [0,1]（0=唇线、1=全腔高）、curve 曲度 [−1,1]（>0 笑=中点下垂 +y、
 * <0 撇=中点上挑）、wide 宽度倍率（1.0 定宽，DONE 峰值 [BOT_MOUTH_WIDE_MAX]）、dx 水平漂移
 * （THINKING 随眼圈）。internal 供 JVM BotMouthRulesTest 逐态特征帧回归（绘制 drawMouth 为
 * private）；值域 coerce 兜底（DONE 包络 sin(π) 浮点尾差负开在此归零）。
 */
internal data class BotMouthFrame(val open: Float, val curve: Float, val wide: Float, val dx: Float)

/**
 * 逐态嘴形（纯时间函数；相位周期与既有六态一一同源，Swift BotCharacterMotion.mouthFrame
 * 逐公式镜像勿单端改）：
 * - IDLE     呼吸微张笑：开 0.10+0.12×升余弦@1250ms（随呼吸）、曲度 +0.55 定笑。
 * - THINKING 小 o：开 0.45、窄 0.70、曲度 0，dx ±1.5 随眼圈 cos@2400ms 漂移。
 * - RUNNING  开合：开 0.30+0.45×升余弦@380ms（与横扫同半周期；振荡项落 [0,1] 勿做
 *            base±amp 直加——负开=腔体翻转）、曲度 0。
 * - WAITING  抿扁：曲度 −0.15、开 0；周期叹气 u=t%2200∈[560,900] 半正弦包络 → 开 0.35、
 *            曲度垂至 −0.45（静态钉点 1000 在窗外呈抿嘴）。
 * - ERROR    下弯扁嘴：曲度 −0.5、开 0；抖窗 u<300 内曲度颤 ±0.25（与眼横抖同窗同频）。
 * - DONE     开口大笑：450ms 半正弦包络（与弹跳同式）→ 开 0.85、宽 1+0.35×env（峰值 1.35）、
 *            曲度 +0.7（闭口端仍呈深笑）。
 */
internal fun botMouthFrame(state: Int, t: Float, compact: Boolean): BotMouthFrame {
    val amp = if (compact) BOT_COMPACT_AMP else 1f
    val tau = (2f * PI).toFloat()
    var open = 0f
    var curve = 0f
    var wide = 1f
    var dx = 0f
    when (state) {
        BOT_STATE_IDLE -> {
            open = BOT_MOUTH_IDLE_OPEN_BASE +
                BOT_MOUTH_IDLE_OPEN_AMP * (0.5f - 0.5f * cos(tau * t / 1250f))
            curve = BOT_MOUTH_IDLE_CURVE
        }
        BOT_STATE_THINKING -> {
            open = BOT_MOUTH_THINK_OPEN
            wide = BOT_MOUTH_THINK_WIDE
            dx = BOT_MOUTH_THINK_DX * amp * cos(tau * t / 2400f)
        }
        BOT_STATE_RUNNING -> {
            open = BOT_MOUTH_RUN_OPEN_BASE +
                BOT_MOUTH_RUN_OPEN_AMP * (0.5f - 0.5f * cos(tau * t / 380f))
        }
        BOT_STATE_WAITING -> {
            curve = BOT_MOUTH_WAIT_CURVE
            val u = t % 2200f
            if (u >= 560f && u < 900f) {
                val sigh = sin(PI.toFloat() * (u - 560f) / 340f) // 叹气半正弦包络 ∈[0,1]
                open = BOT_MOUTH_SIGH_OPEN * sigh
                curve = lerp(BOT_MOUTH_WAIT_CURVE, BOT_MOUTH_SIGH_CURVE, sigh)
            }
        }
        BOT_STATE_ERROR -> {
            curve = BOT_MOUTH_ERROR_CURVE
            val u = t % 2000f
            if (u < 300f) curve += BOT_MOUTH_ERROR_TREMBLE * sin(2f * tau * u / 300f)
        }
        BOT_STATE_DONE -> {
            val env = sin(PI.toFloat() * min(t, 450f) / 450f)
            open = BOT_MOUTH_DONE_OPEN * env
            wide = 1f + BOT_MOUTH_DONE_WIDE_AMP * env
            curve = BOT_MOUTH_DONE_CURVE
        }
    }
    return BotMouthFrame(
        open = open.coerceIn(0f, 1f),
        curve = curve.coerceIn(-1f, 1f),
        wide = wide.coerceIn(0.5f, BOT_MOUTH_WIDE_MAX),
        dx = dx,
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

/** motionEnabled=false 的逐态特征帧钉点（ms）：峰值/上偏/眯眼可辨帧（嘴部同步出特征形，
 *  A6「off 档静止」语义 = 嘴出特征形不出动画）。 */
private fun botStaticPhaseMs(state: Int): Int = when (state) {
    BOT_STATE_RUNNING -> 125   // y-bob 峰值 −3.0 + 嘴开合中程 open≈0.63
    BOT_STATE_WAITING -> 1000  // 2026-11-21 800→1000：避开嘴部叹气窗 [560,900] 呈抿嘴 + 脉冲 1.068（compact 1.109）正视不眨
    BOT_STATE_ERROR -> 500     // 横抖窗（300ms）之外、眯眼 + 下弯扁嘴（曲度 −0.5）定格
    BOT_STATE_DONE -> 225      // 弹跳峰值 1.16 + U 弧 + 开口大笑（开 0.85/宽 1.35）
    else -> 0                  // IDLE/THINKING：静息微张笑 / 小 o + 绕圈起点（圈心 −0.5+右视）
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

/**
 * 嘴绘制（嘴部轮 2026-11-21）：open < [BOT_MOUTH_OPEN_FILL_MIN] 闭/抿嘴 = 圆帽二次唇线
 * （控制点 y = 中心 + curve×曲度偏移，quad 中点实际位移半值）；否则张嘴 = 上下唇两条二次
 * 曲线围腔填充（上唇控制 −open×腔高、下唇 +open×腔高 → 腔中点全高 = open×全腔高）。唇线
 * 段数 ≤2、无逐帧 blob 重建，成本同级于既有逐帧眼 roundedRect；嘴色 = 与眼同帧对比墨
 * （入参 [ink]，禁在此固定色）。
 */
private fun DrawScope.drawMouth(frame: BotMouthFrame, s: Float, ink: Color, compact: Boolean) {
    val halfW = (if (compact) BOT_MOUTH_W_COMPACT else BOT_MOUTH_W) * frame.wide / 2f
    val ctrl = if (compact) BOT_MOUTH_CURVE_CTRL_COMPACT else BOT_MOUTH_CURVE_CTRL
    val cavity = if (compact) BOT_MOUTH_CAVITY_H_COMPACT else BOT_MOUTH_CAVITY_H
    val cx = BOT_MOUTH_CX + frame.dx
    val left = Offset((cx - halfW) * s, BOT_MOUTH_CY * s)
    val right = Offset((cx + halfW) * s, BOT_MOUTH_CY * s)
    val midX = cx * s
    val ctrlY = BOT_MOUTH_CY + frame.curve * ctrl
    if (frame.open < BOT_MOUTH_OPEN_FILL_MIN) {
        val lip = Path()
        lip.moveTo(left.x, left.y)
        lip.quadraticTo(midX, ctrlY * s, right.x, right.y)
        drawPath(
            lip,
            ink,
            style = Stroke(
                (if (compact) BOT_MOUTH_STROKE_COMPACT else BOT_MOUTH_STROKE) * s,
                cap = StrokeCap.Round,
            ),
        )
    } else {
        val cavityPath = Path()
        cavityPath.moveTo(left.x, left.y)
        cavityPath.quadraticTo(midX, (ctrlY - frame.open * cavity) * s, right.x, right.y)
        cavityPath.quadraticTo(midX, (ctrlY + frame.open * cavity) * s, left.x, left.y)
        cavityPath.close()
        drawPath(cavityPath, ink)
    }
}
