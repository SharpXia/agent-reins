package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_DONE
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_ERROR
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_IDLE
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_RUNNING
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_THINKING
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_WAITING
import dev.sharpxia.agentreins.hub.avatar.FACE_BASE_RADIUS
import dev.sharpxia.agentreins.hub.avatar.FACE_MIN
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 嘴部通道纯函数回归（嘴部+幅度轮 2026-11-21）：open/curve/wide 值域全域扫描、六态逐态
 * 特征帧互异、motionEnabled=false 静态钉点嘴形可辨（WAITING t=1000 抿嘴非叹气 / RUNNING
 * t=125 开口中程 ≈0.63 / DONE t=225 大笑）、幅度新表全幅口径零钉边审计、嘴部几何安全域。
 * 判据与实现（BotCharacter.kt botMouthFrame/常量表）同口径，勿单侧改；Swift 侧 mouthFrame
 * 为逐公式镜像无 JVM 测试，改值必须双端同改并排核对。
 */
class BotMouthRulesTest {

    private companion object {
        val ALL_STATES = intArrayOf(
            BOT_STATE_IDLE,
            BOT_STATE_RUNNING,
            BOT_STATE_THINKING,
            BOT_STATE_WAITING,
            BOT_STATE_ERROR,
            BOT_STATE_DONE,
        )
    }

    @Test
    fun mouthFrameValueDomainHoldsAcrossAllStatesAndSizes() {
        // 全域扫描（6 态 × t∈[0,6000) 步 7ms × compact 两档）：open∈[0,1]/curve∈[−1,1]/
        // wide∈[0.5,1.35]/|dx|≤THINK 漂移 compact 极值 2.4
        for (state in ALL_STATES) {
            var t = 0f
            while (t < 6000f) {
                for (compact in booleanArrayOf(false, true)) {
                    val f = botMouthFrame(state, t, compact)
                    assertTrue(f.open in 0f..1f, "open 越域 $f（state=$state t=$t）")
                    assertTrue(f.curve in -1f..1f, "curve 越域 $f（state=$state t=$t）")
                    assertTrue(f.wide in 0.5f..BOT_MOUTH_WIDE_MAX, "wide 越域 $f（state=$state t=$t）")
                    assertTrue(abs(f.dx) <= BOT_MOUTH_THINK_DX * BOT_COMPACT_AMP + 1e-4f, "dx 越域 $f")
                }
                t += 7f
            }
        }
    }

    @Test
    fun perStateMouthCharacteristicFramesAreDistinct() {
        // 六态同一时刻帧两两互异（t=1000：微张笑/开合/小 o 漂移/抿嘴/下弯/闭口深笑）
        val frames = ALL_STATES.map { botMouthFrame(it, 1000f, false) }
        for (i in frames.indices) {
            for (j in i + 1 until frames.size) {
                assertTrue(frames[i] != frames[j], "state[$i] 与 state[$j] 特征帧相同：${frames[i]}")
            }
        }
    }

    @Test
    fun staticPinsShowRecognizableMouthShapes() {
        // WAITING 钉点 1000（2026-11-21 800→1000 避开叹气窗 [560,900]）：抿嘴非叹气
        val waiting = botMouthFrame(BOT_STATE_WAITING, 1000f, false)
        assertEquals(0f, waiting.open, "钉点应呈抿嘴（开=0）")
        assertEquals(BOT_MOUTH_WAIT_CURVE, waiting.curve, 1e-4f)
        // 叹气窗中点 u=730（半正弦包络峰值）：开 0.35、曲度垂至 −0.45——与钉点互证窗生效
        val sigh = botMouthFrame(BOT_STATE_WAITING, 730f, false)
        assertEquals(BOT_MOUTH_SIGH_OPEN, sigh.open, 1e-3f)
        assertEquals(BOT_MOUTH_SIGH_CURVE, sigh.curve, 1e-3f)

        // RUNNING 钉点 125：开合中程（升余弦振荡 0.30+0.45×(0.5−0.5cos(τ·125/380)) ≈0.632）
        val running = botMouthFrame(BOT_STATE_RUNNING, 125f, false)
        assertEquals(0.632f, running.open, 0.01f)

        // DONE 钉点 225（450ms 半正弦包络峰值）：大笑 open=0.85、wide=1.35 极值
        val done = botMouthFrame(BOT_STATE_DONE, 225f, false)
        assertEquals(BOT_MOUTH_DONE_OPEN, done.open, 1e-3f)
        assertEquals(BOT_MOUTH_WIDE_MAX, done.wide, 1e-3f)
        assertEquals(BOT_MOUTH_DONE_CURVE, done.curve, 1e-3f)

        // ERROR 钉点 500（抖窗外）：下弯扁嘴定格；抖窗内 t=37.5（半正弦峰）曲度颤上探 −0.25
        val error = botMouthFrame(BOT_STATE_ERROR, 500f, false)
        assertEquals(0f, error.open)
        assertEquals(BOT_MOUTH_ERROR_CURVE, error.curve, 1e-4f)
        val trembling = botMouthFrame(BOT_STATE_ERROR, 37.5f, false)
        assertEquals(BOT_MOUTH_ERROR_CURVE + BOT_MOUTH_ERROR_TREMBLE, trembling.curve, 1e-3f)

        // IDLE/THINKING 钉点 0：微张笑（开=base、曲度 +0.55）/ 小 o（开 0.45、窄 0.70、随圈右漂）
        val idle = botMouthFrame(BOT_STATE_IDLE, 0f, false)
        assertEquals(BOT_MOUTH_IDLE_OPEN_BASE, idle.open, 1e-4f)
        assertEquals(BOT_MOUTH_IDLE_CURVE, idle.curve, 1e-4f)
        val thinking = botMouthFrame(BOT_STATE_THINKING, 0f, false)
        assertEquals(BOT_MOUTH_THINK_OPEN, thinking.open, 1e-4f)
        assertEquals(BOT_MOUTH_THINK_WIDE, thinking.wide, 1e-4f)
        assertEquals(BOT_MOUTH_THINK_DX, thinking.dx, 1e-4f)
    }

    @Test
    fun runningMouthOpenCycleStaysInContractedBand() {
        // RUNNING 开合带 [0.30, 0.75]（升余弦振荡项落 [0,1]——base±amp 直加会产生负开，
        // 评审非阻塞备注 (b) 的回归锚）：半周期采样扫描
        var t = 0f
        while (t < 1520f) { // 4 个 380ms 半周期
            val open = botMouthFrame(BOT_STATE_RUNNING, t, false).open
            assertTrue(open in (BOT_MOUTH_RUN_OPEN_BASE - 1e-4f)..(BOT_MOUTH_RUN_OPEN_BASE + BOT_MOUTH_RUN_OPEN_AMP + 1e-4f), "t=$t open=$open 越带")
            t += 19f
        }
        assertEquals(BOT_MOUTH_RUN_OPEN_BASE, botMouthFrame(BOT_STATE_RUNNING, 0f, false).open, 1e-4f)
        assertEquals(BOT_MOUTH_RUN_OPEN_BASE + BOT_MOUTH_RUN_OPEN_AMP, botMouthFrame(BOT_STATE_RUNNING, 190f, false).open, 1e-3f)
    }

    @Test
    fun compactAmplitudeTableStaysInsideWidenedEyeWindow() {
        // 幅度新表全幅口径审计（含 rest±0.8 与圈心项；compact ×1.6）——漏加 rest/圈心即算错：
        // dx 极值通道 IDLE lissajous / THINKING 绕圈 / RUNNING 横扫 / ERROR 抖 全部 (0.8+2.0)×1.6=4.48
        assertEquals(4.48f, (0.8f + 2.0f) * BOT_COMPACT_AMP, 1e-3f)
        assertTrue((0.8f + 2.0f) * BOT_COMPACT_AMP <= BOT_EYE_WINDOW_X, "dx 极值超窗→钉边回归")
        assertTrue(2.0f * BOT_COMPACT_AMP <= BOT_EYE_WINDOW_Y, "IDLE lissajous dy 3.2 超窗")
        assertTrue((-0.5f - 1.6f) * BOT_COMPACT_AMP >= -BOT_EYE_WINDOW_Y, "THINKING 绕圈 dy 下探 −3.36 超窗")
        assertTrue((-0.5f + 1.6f) * BOT_COMPACT_AMP <= BOT_EYE_WINDOW_Y, "THINKING 绕圈 dy 上探 1.76 超窗")
        // gaze 幅随窗（批次4 hover 注视可达窗沿语义；双端同表）
        assertEquals(BOT_EYE_WINDOW_X, BOT_GAZE_AMP_X)
        assertEquals(BOT_EYE_WINDOW_Y, BOT_GAZE_AMP_Y)
        // 下视窗底 = 窗底（BotGaze.DOWN 引窗常量）
        assertEquals(BOT_EYE_WINDOW_Y, BotGaze.DOWN.dy)
    }

    @Test
    fun mouthGeometryStaysInsideSlimmestFaceBoundary() {
        // 最瘦脸界（FACE_MIN 0.70×44 基半径 + 圆心 50，AvatarProfile.kt facePoint 口径）= 80.8
        val boundary = (50.0 + FACE_BASE_RADIUS * FACE_MIN).toFloat()
        // 保守包络 open=1 compact：唇底 67+6.5 + 描边半宽 1.75 = 75.25 < 80.8
        val bottom = BOT_MOUTH_CY + BOT_MOUTH_CAVITY_H_COMPACT / 2f + BOT_MOUTH_STROKE_COMPACT / 2f
        assertTrue(bottom < boundary, "唇底 $bottom 触界 $boundary")
        // DONE wide compact 极值角点（含嘴 dx 漂移极值 ±2.4）距心 < 内切安全半径 30.8
        val corner = hypot(
            BOT_MOUTH_W_COMPACT * BOT_MOUTH_WIDE_MAX / 2f + BOT_MOUTH_THINK_DX * BOT_COMPACT_AMP,
            BOT_MOUTH_CY - 50f,
        )
        assertTrue(corner < FACE_BASE_RADIUS.toFloat() * FACE_MIN, "角点距心 $corner 触安全域")
    }

    @Test
    fun trembleLowerBoundAndDoneWideMidEnvelope() {
        // 走查①修补第 7 例（与 Swift DEBUG 锁同表，使 commit message「×7」账实一致）：
        // ERROR 颤动下探界 −0.75（u=262.5 → sin(3.5π)=−1）
        val errLow = botMouthFrame(BOT_STATE_ERROR, 262.5f, false)
        assertEquals(BOT_MOUTH_ERROR_CURVE - BOT_MOUTH_ERROR_TREMBLE, errLow.curve, 1e-3f)
        assertEquals(0f, errLow.open)
        // DONE 450ms 包络中程（t=112.5 → env=sin(π/4)≈0.7071）：open≈0.601、wide≈1.2475
        val doneMid = botMouthFrame(BOT_STATE_DONE, 112.5f, false)
        assertEquals(0.601f, doneMid.open, 1e-3f)
        assertEquals(1.2475f, doneMid.wide, 1e-3f)
        assertEquals(BOT_MOUTH_DONE_CURVE, doneMid.curve, 1e-3f)
    }
}
