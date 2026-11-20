package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.avatar.AGENT_TINT_COUNT
import dev.sharpxia.agentreins.hub.avatar.AVATAR_PRESETS
import dev.sharpxia.agentreins.hub.avatar.AgentTint
import dev.sharpxia.agentreins.hub.avatar.BODY_PRESET_COUNT
import dev.sharpxia.agentreins.hub.avatar.BOT_BLINK_TEMPO_BASE_MS
import dev.sharpxia.agentreins.hub.avatar.BOT_BLINK_TEMPO_STEP_MS
import dev.sharpxia.agentreins.hub.avatar.BOT_SEED_GOLD_ULIDS
import dev.sharpxia.agentreins.hub.avatar.EYE_SHAPE_COUNT
import dev.sharpxia.agentreins.hub.avatar.POSE_PRESET_COUNT
import dev.sharpxia.agentreins.hub.avatar.botSeedFor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * botSeedFor 规则单测（角色系统批次1）：金标 3 枚真实 ULID 期望值表锁双端（Swift 侧
 * BotCharacterView.swift DEBUG 断言同表——任一侧派生漂移即断）/ 确定性（纯函数无随机源）/
 * 取值域（桶界 + 眨音周期格）/ 万级 Crockford ULID 分布（十色每桶≥4%、八身≥5%、三眼≥20%——
 * 哈希取位弱分布即破线）。ULID 母表 = Crockford 32 字符集，定种子随机生成、全流程可复现。
 */
class BotSeedRulesTest {

    private companion object {
        /** Crockford Base32（无 I/L/O/U）。 */
        val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray()
        const val SAMPLE_COUNT = 10_000

        /** 期望值表（2026-11-20 以 Kotlin Int 语义实算留痕；与 Swift DEBUG 断言同表勿改）。 */
        val GOLD_EXPECTED = listOf(
            // 01M6YV6ZSDDADH8J8KYRKWR0C5 → h=1736592439
            BotSeedRow("01M6YV6ZSDDADH8J8KYRKWR0C5", palette = 9, body = 4, eye = 2, pose = 2, blinkTempoMs = 4200),
            // 01M7FZXDT0550C25S5FPYRRTKZ → h=1403362355
            BotSeedRow("01M7FZXDT0550C25S5FPYRRTKZ", palette = 5, body = 4, eye = 2, pose = 2, blinkTempoMs = 5000),
            // 01M7FDA4YK22MP9YWE06PAW83X → h=−872450404（负 hash 桶位路径）
            BotSeedRow("01M7FDA4YK22MP9YWE06PAW83X", palette = 6, body = 6, eye = 1, pose = 3, blinkTempoMs = 2600),
        )

        data class BotSeedRow(
            val cid: String,
            val palette: Int,
            val body: Int,
            val eye: Int,
            val pose: Int,
            val blinkTempoMs: Int,
        )
    }

    @Test
    fun goldUlidsLockCrossPlatformDerivation() {
        assertEquals(3, BOT_SEED_GOLD_ULIDS.size, "金标常量恒 3 枚（双端对锁锚面）")
        for (row in GOLD_EXPECTED) {
            assertTrue(row.cid in BOT_SEED_GOLD_ULIDS, "${row.cid} 应在金标常量内")
            val seed = botSeedFor(row.cid)
            assertEquals(row.palette, seed.paletteIndex, "${row.cid} paletteIndex")
            assertEquals(row.body, seed.bodyIndex, "${row.cid} bodyIndex")
            assertEquals(row.eye, seed.eyeIndex, "${row.cid} eyeIndex")
            assertEquals(row.pose, seed.poseIndex, "${row.cid} poseIndex")
            assertEquals(row.blinkTempoMs, seed.blinkTempoMs, "${row.cid} blinkTempoMs")
        }
    }

    @Test
    fun derivationIsDeterministicPerConversationId() {
        for (cid in BOT_SEED_GOLD_ULIDS) {
            assertEquals(botSeedFor(cid), botSeedFor(cid), "同 cid 逐参数恒定（纯函数无随机源）")
        }
        // 桶位对 cid 全串敏感（shr 分域取位，非单字符碰撞面）：金标两两互异
        val seeds = BOT_SEED_GOLD_ULIDS.map { botSeedFor(it) }
        assertEquals(3, seeds.toSet().size, "金标互异 cid 至少一组参数互异")
    }

    @Test
    fun derivedValuesStayInContractedRanges() {
        val random = Random(20261120)
        repeat(SAMPLE_COUNT) {
            val cid = randomUlid(random)
            val seed = botSeedFor(cid)
            assertTrue(seed.paletteIndex in 0 until AGENT_TINT_COUNT, "paletteIndex ∈ [0,10)：$cid")
            assertTrue(seed.bodyIndex in 0 until BODY_PRESET_COUNT, "bodyIndex ∈ [0,8)：$cid")
            assertTrue(seed.eyeIndex in 0 until EYE_SHAPE_COUNT, "eyeIndex ∈ [0,3)：$cid")
            assertTrue(seed.poseIndex in 0 until POSE_PRESET_COUNT, "poseIndex ∈ [0,4)：$cid")
            val offset = seed.blinkTempoMs - BOT_BLINK_TEMPO_BASE_MS
            assertTrue(offset >= 0 && offset % BOT_BLINK_TEMPO_STEP_MS == 0 && offset <= 6 * BOT_BLINK_TEMPO_STEP_MS,
                "blinkTempoMs ∈ [2600,5000] 步进 400：${seed.blinkTempoMs}（$cid）")
        }
    }

    @Test
    fun tenThousandUlidDistributionCoversAllBuckets() {
        val random = Random(20261120)
        val palette = IntArray(AGENT_TINT_COUNT)
        val body = IntArray(BODY_PRESET_COUNT)
        val eye = IntArray(EYE_SHAPE_COUNT)
        repeat(SAMPLE_COUNT) {
            val seed = botSeedFor(randomUlid(random))
            palette[seed.paletteIndex]++
            body[seed.bodyIndex]++
            eye[seed.eyeIndex]++
        }
        val n = SAMPLE_COUNT.toFloat()
        // 十色每桶 ≥4%（均匀期望 10%，破线 = 取位偏斜）；八身 ≥5%（期望 12.5%）；三眼 ≥20%（期望 33.3%）
        for (i in palette.indices) {
            assertTrue(palette[i] / n >= 0.04f, "十色桶 $i 占比 ${(palette[i] / n * 100)}% 应 ≥4%")
        }
        for (i in body.indices) {
            assertTrue(body[i] / n >= 0.05f, "八身桶 $i 占比 ${(body[i] / n * 100)}% 应 ≥5%")
        }
        for (i in eye.indices) {
            assertTrue(eye[i] / n >= 0.20f, "三眼桶 $i 占比 ${(eye[i] / n * 100)}% 应 ≥20%")
        }
    }

    @Test
    fun bucketModuliStayAlignedWithContractTables() {
        // 取位模数与双端契约表同宽（声明序即契约）：十色 = AgentTint 闭集、八身 = AVATAR_PRESETS
        assertEquals(AGENT_TINT_COUNT, AgentTint.entries.size, "十色锚 = AgentTint 闭集")
        assertEquals(BODY_PRESET_COUNT, AVATAR_PRESETS.size, "八身锚 = AVATAR_PRESETS 表")
        assertEquals(8, AVATAR_PRESETS.size)
    }

    /** 定种子生成 26 位 Crockford ULID 形串（01 前缀 + 24 位母表随机）。 */
    private fun randomUlid(random: Random): String =
        buildString {
            append("01")
            repeat(24) { append(CROCKFORD[random.nextInt(CROCKFORD.size)]) }
        }
}
