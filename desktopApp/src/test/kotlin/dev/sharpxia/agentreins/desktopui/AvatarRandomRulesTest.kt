package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.avatar.AVATAR_PRESETS
import dev.sharpxia.agentreins.hub.avatar.randomPresetExcluding
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 随机换形抽取规则单测（形象随机轮 2026-11-21）：必排除传入 faceKey（「连摇必换形」判据，
 * 排除键 = shown 档键——draft 感知由调用方传 shown 实现同口径）/ 结果恒在目录闭集内 /
 * Random(种子) 可重复 / 多次摇全目录可达（排除一键后其余 7 款均可达）。
 * 判据与实现（AvatarSpec.kt randomPresetExcluding）同口径，勿单侧改。
 */
class AvatarRandomRulesTest {

    @Test
    fun drawAlwaysExcludesPassedFaceKey() {
        // 每款预设作排除键各摇 500 次（共 8×500）：结果键恒 ≠ 传入键——「连摇必换形」判据
        for (preset in AVATAR_PRESETS) {
            val random = Random(20261121)
            repeat(500) {
                val drawn = randomPresetExcluding(preset.faceKey, random)
                assertNotEquals(preset.faceKey, drawn.faceKey, "排除键 ${preset.id} 被摇中")
            }
        }
    }

    @Test
    fun drawAlwaysStaysInCatalogClosure() {
        val keys = AVATAR_PRESETS.map { it.faceKey }.toSet()
        val random = Random(7)
        repeat(1_000) { i ->
            val current = AVATAR_PRESETS[i % AVATAR_PRESETS.size].faceKey
            assertTrue(randomPresetExcluding(current, random) in AVATAR_PRESETS, "第 $i 摇出闭集")
        }
    }

    @Test
    fun seededRandomIsReproducible() {
        // 同种子同排除键 → 抽取序列逐项相等（可重复性；UI 侧用 Random.Default 不受影响）
        val a = Random(42)
        val b = Random(42)
        val key = AVATAR_PRESETS[2].faceKey
        repeat(50) { i ->
            assertEquals(randomPresetExcluding(key, a).id, randomPresetExcluding(key, b).id, "第 $i 摇不同")
        }
    }

    @Test
    fun repeatedDrawsReachEveryRemainingPreset() {
        // 排除 P1 后连摇 2_000 次：其余 7 款全可达（单次命中概率 1/7，全不可达概率≈0）
        val excluded = AVATAR_PRESETS.first().faceKey
        val remaining = AVATAR_PRESETS.drop(1).map { it.id }.toSet()
        val random = Random(2026)
        val seen = HashSet<String>()
        repeat(2_000) {
            seen += randomPresetExcluding(excluded, random).id
        }
        assertEquals(remaining, seen, "排除后目录应全可达，缺：${remaining - seen}")
    }

    @Test
    fun nullExclusionKeyDrawsFromFullCatalog() {
        // 传 null（无当前形/防御路径）：全目录 8 款均可摇中（回退分支账面锁）
        val random = Random(99)
        val seen = HashSet<String>()
        repeat(2_000) { seen += randomPresetExcluding(null, random).id }
        assertEquals(AVATAR_PRESETS.map { it.id }.toSet(), seen)
    }
}
