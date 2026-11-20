package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_DONE
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_ERROR
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_IDLE
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_RUNNING
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_THINKING
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_WAITING
import dev.sharpxia.agentreins.hub.avatar.botStateFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * botStateFor 优先级矩阵单测（角色系统批次1）：WAITING > RUNNING(thinkingNow?THINKING:RUNNING)
 * > ERROR > DONE(瞬时) > IDLE 全 2^5 组合扫描 + 编码值闭集校验。DONE 为视图层 900ms 瞬时窗
 * （hasActiveRun 真→假且终态非 failed 时置 doneTransient），纯函数只认入参。
 */
class BotStateRulesTest {

    @Test
    fun stateCodesFormDistinctClosedSet() {
        val codes = listOf(BOT_STATE_IDLE, BOT_STATE_RUNNING, BOT_STATE_THINKING, BOT_STATE_WAITING, BOT_STATE_ERROR, BOT_STATE_DONE)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), codes, "六态稳定编码 0..5（跨端 Int 过界契约）")
        assertEquals(6, codes.toSet().size)
    }

    @Test
    fun idleWhenNothingSignals() {
        assertEquals(BOT_STATE_IDLE, botStateFor(hasActiveRun = false, thinkingNow = false, awaitingApproval = false, lastMessageFailed = false))
        assertEquals(BOT_STATE_IDLE, botStateFor(false, false, false, false, doneTransient = false))
    }

    @Test
    fun waitingWinsOverEverything() {
        // 全真极值：审批挂起压过 run/思考/失败/DONE 瞬时窗
        assertEquals(BOT_STATE_WAITING, botStateFor(hasActiveRun = true, thinkingNow = true, awaitingApproval = true, lastMessageFailed = true, doneTransient = true))
        // 与任一单信号并存同样 WAITING
        assertEquals(BOT_STATE_WAITING, botStateFor(true, false, true, false))
        assertEquals(BOT_STATE_WAITING, botStateFor(false, false, true, true, doneTransient = true))
    }

    @Test
    fun runningBeatsErrorAndDoneThinkingSplits() {
        // 有 run 压过末条失败与 DONE 瞬时窗
        assertEquals(BOT_STATE_RUNNING, botStateFor(true, false, false, true, doneTransient = true))
        assertEquals(BOT_STATE_RUNNING, botStateFor(true, false, false, false))
        assertEquals(BOT_STATE_THINKING, botStateFor(true, true, false, false))
        // thinkingNow 细分不影响 WAITING（审批优先）
        assertEquals(BOT_STATE_WAITING, botStateFor(true, true, true, false))
    }

    @Test
    fun errorBeatsDoneTransient() {
        assertEquals(BOT_STATE_ERROR, botStateFor(false, false, false, true))
        // 弹跳窗内出现失败：ERROR 压过 DONE
        assertEquals(BOT_STATE_ERROR, botStateFor(false, false, false, true, doneTransient = true))
    }

    @Test
    fun doneTransientYieldsDoneOnlyWhenNothingHigher() {
        assertEquals(BOT_STATE_DONE, botStateFor(false, false, false, false, doneTransient = true))
        // 瞬时窗结束回落 IDLE（视图层 900ms 计时后 doneTransient=false）
        assertEquals(BOT_STATE_IDLE, botStateFor(false, false, false, false))
    }

    @Test
    fun fullMatrixIsCoveredByPriorityChain() {
        // 2^5 全组合扫描：结果恒落在六态闭集内，且与逐优先级复算一致
        val expected = listOf(BOT_STATE_WAITING, BOT_STATE_THINKING, BOT_STATE_RUNNING, BOT_STATE_ERROR, BOT_STATE_DONE, BOT_STATE_IDLE)
        for (mask in 0 until 32) {
            val run = mask and 1 != 0
            val thinking = mask and 2 != 0
            val waiting = mask and 4 != 0
            val failed = mask and 8 != 0
            val transient = mask and 16 != 0
            val got = botStateFor(run, thinking, waiting, failed, transient)
            assertTrue(got in expected, "mask=$mask → $got 不在六态闭集")
            val want = when {
                waiting -> BOT_STATE_WAITING
                run && thinking -> BOT_STATE_THINKING
                run -> BOT_STATE_RUNNING
                failed -> BOT_STATE_ERROR
                transient -> BOT_STATE_DONE
                else -> BOT_STATE_IDLE
            }
            assertEquals(want, got, "mask=$mask 优先级链复算不一致")
        }
    }
}
