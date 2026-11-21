package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.avatar.AVATAR_PRESETS
import dev.sharpxia.agentreins.hub.avatar.AgentTint
import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
import dev.sharpxia.agentreins.hub.avatar.BOT_SEED_GOLD_ULIDS
import dev.sharpxia.agentreins.hub.avatar.UPDATED_AT_EPOCH
import dev.sharpxia.agentreins.hub.avatar.botSeedFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * conversationSeedDerivedProfile 镜像锁单测（会话级换形象轮 2026-11-21）：会话换装 dialog 的
 * 「无覆盖当前形象」预览/随机排除键必须与 BotCharacter 无覆盖渲染选表逐参数同源——本侧锁
 * 镜像（paletteIndex/bodyIndex 派生公式本体由 BotSeedRulesTest 金标锁），任一侧漂移即断。
 * 预览档与实渲染一旦错位，用户在 dialog 里看到的就不是侧栏/聊天页那只。
 */
class ConversationAvatarRulesTest {

    /** 金标 3 ULID 期望值（palette/body 取 BotSeedRulesTest GOLD_EXPECTED 实算留痕，勿单侧改；
     *  AvatarPalette 声明序下标：0 GRAY / 1 BLUE / 2 RED / 3 ORANGE / 4 YELLOW / 5 GREEN /
     *  6 CYAN / 7 PURPLE / 8 MAGENTA / 9 BROWN）。 */
    private val goldExpected = listOf(
        // 01M6YV6ZSDDADH8J8KYRKWR0C5 → palette 9 (BROWN) / body 4 (P5)
        Triple("01M6YV6ZSDDADH8J8KYRKWR0C5", AvatarPalette.BROWN, "P5"),
        // 01M7FZXDT0550C25S5FPYRRTKZ → palette 5 (GREEN) / body 4 (P5)
        Triple("01M7FZXDT0550C25S5FPYRRTKZ", AvatarPalette.GREEN, "P5"),
        // 01M7FDA4YK22MP9YWE06PAW83X → palette 6 (CYAN) / body 6 (P7)（负 hash 桶位路径）
        Triple("01M7FDA4YK22MP9YWE06PAW83X", AvatarPalette.CYAN, "P7"),
    )

    @Test
    fun goldUlidsMirrorRendererSelection() {
        for ((cid, palette, presetId) in goldExpected) {
            val derived = conversationSeedDerivedProfile(cid, AvatarMotion.STANDARD)
            assertEquals(palette, derived.paletteId, "$cid paletteId")
            val preset = AVATAR_PRESETS.first { it.id == presetId }
            assertEquals(preset.faceFactors, derived.faceFactors, "$cid faceFactors = $presetId 十二值表")
            // 双锁：镜像与 BotCharacter 无覆盖渲染同式取表（bodyIndex 直查 AVATAR_PRESETS）
            assertEquals(AVATAR_PRESETS[botSeedFor(cid).bodyIndex].faceFactors, derived.faceFactors, "$cid 镜像 vs 渲染取表")
        }
    }

    @Test
    fun paletteIndexContractMatchesRenderColorPath() {
        // 渲染取色走 AgentTint.entries[paletteIndex]，镜像走 AvatarPalette.entries 同下标——
        // 声明序 = 跨端契约（两枚举同序），漂移即预览色 ≠ 挂载色
        for (cid in BOT_SEED_GOLD_ULIDS) {
            val derived = conversationSeedDerivedProfile(cid, AvatarMotion.STANDARD)
            assertEquals(
                AgentTint.entries[botSeedFor(cid).paletteIndex],
                AgentTint.of(derived.paletteId),
                "$cid 取色路径对齐",
            )
        }
    }

    @Test
    fun motionPassesThroughAndUpdatedAtStaysEpochSentinel() {
        for (motion in listOf(AvatarMotion.OFF, AvatarMotion.STANDARD)) {
            val derived = conversationSeedDerivedProfile(BOT_SEED_GOLD_ULIDS.first(), motion)
            assertEquals(motion, derived.motionLevel, "motion 只填契约形状原样透传")
            assertEquals(UPDATED_AT_EPOCH, derived.updatedAt, "派生档恒 epoch 哨兵（不入收敛比较）")
        }
    }

    @Test
    fun derivationIsDeterministicPerConversationId() {
        for (cid in BOT_SEED_GOLD_ULIDS) {
            assertEquals(
                conversationSeedDerivedProfile(cid, AvatarMotion.STANDARD),
                conversationSeedDerivedProfile(cid, AvatarMotion.STANDARD),
                "$cid 镜像恒定（纯函数无随机源）",
            )
        }
    }
}
