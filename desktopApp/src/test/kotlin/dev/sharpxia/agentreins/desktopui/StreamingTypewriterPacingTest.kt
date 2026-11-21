package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 2026-11-21 流式单行归一轮：打字机步进纯函数单测（:desktopApp:test 锁行为不只锁存取——
 * StreamingRowPresentationTest/TimelineScrollTest 纯函数回归锚同纪律）。
 *
 * 桌面↔iOS parity 契约：iOS 侧 HubViews.swift StreamingTypewriter `revealStep` 双档 @ 16ms
 * ——本表锁 desktopui.typewriterRevealStep 同参数；iOS 无单测 target，桌面回归锚兼作两端
 * 行为契约不漂移（单端改值须双端同步）。2026-11-21 走查②双端同扩洪泛档（积压>8192 转
 * 积压/3：特大块几何档累计重解析 ~45×积压字符掉帧，洪泛档 ~5 tick 累计 ~2×积压）。
 */
class StreamingTypewriterPacingTest {

    // ── ① 小积压恒 8（打字感下限：慢流 ≈500 字/秒，8 字/16ms） ──

    @Test
    fun smallBacklogFloorsAt8() {
        assertEquals(8, typewriterRevealStep(0))
        assertEquals(8, typewriterRevealStep(1))
        assertEquals(8, typewriterRevealStep(12))
        assertEquals(8, typewriterRevealStep(95)) // 95/12=7（整数除）→ max(8, 7)=8，96/12=8 才越界
        assertEquals(8, typewriterRevealStep(96)) // 96/12=8，max 恒 8
    }

    // ── ② 大块积压比例步进（2s 合并窗整块到达：~12 步内追平，积压不跨下一块） ──

    @Test
    fun largeBacklogScalesProportionally() {
        assertEquals(10, typewriterRevealStep(120)) // 评审锚例：积压 120 → 步 10
        assertEquals(8, typewriterRevealStep(97))   // 97/12=8（除后仍是下限）
        assertEquals(100, typewriterRevealStep(1200))
        assertEquals(200, typewriterRevealStep(2400))
    }

    // ── ③ 追平敛零：整块积压按调用方 clamp 语义迭代收敛（步数 ≈ 12 有界、末段回落恒 8 步进） ──

    @Test
    fun backlogConvergesToZeroBoundedSteps() {
        // 模拟 StreamingTypewriterText 循环：shown += step(积压)，clamp 到 target
        val target = 2400
        var shown = 0
        var steps = 0
        while (shown < target) {
            val backlog = target - shown
            val step = typewriterRevealStep(backlog)
            assertTrue(step >= 8, "step=$step < 下限 8 @ backlog=$backlog")
            assertTrue(step <= backlog + 7, "step=$step 显著超积压 @ backlog=$backlog") // clamp 前上界
            shown = minOf(target, shown + step)
            steps++
            assertTrue(steps < 200, "200 步未收敛：shown=$shown")
        }
        assertEquals(target, shown)
        // 几何相位（积压 ≈11/12 递减）2400→96 约 38 步 + 恒 8 尾段 12 步 = 实算 50 步；
        // 上界 60 防步进公式被改成更慢档（更快的 /6、/12→/24 由①②值表另锁）。
        assertTrue(steps in 40..60, "收敛步数 $steps 越界（几何 ~38 + 尾段 12 = 50 量级）")
        assertEquals(0, target - shown)
    }

    // ── ④ 洪泛档（2026-11-21 走查②）：积压 >8192 转 积压/3 快速追平，特大块不逐字拖帧 ──

    @Test
    fun floodTierActivatesAboveThreshold() {
        // 阈值边界：8192 本身仍常规档（> 才洪泛），8193 起洪泛
        assertEquals(682, typewriterRevealStep(8192))   // 8192/12=682
        assertEquals(2731, typewriterRevealStep(8193))  // 8193/3=2731
        // 桥合并缓冲硬帽量级（256KiB）：步长比例档，不再是几何档的 backlog/12
        assertEquals(65536 / 3, typewriterRevealStep(65536))
        assertEquals(262144 / 3, typewriterRevealStep(262144))
    }

    @Test
    fun floodBacklogConvergesBoundedTicks() {
        // 洪泛相位 ~2/3 递减：256KiB → 8.2k 约 7 tick；余量 ≤8192 回常规档（既有最坏情形封顶）
        var shown = 0
        val target = 262144
        var floodTicks = 0
        while (target - shown > TYPEWRITER_FLOOD_BACKLOG_CHARS) {
            shown = minOf(target, shown + typewriterRevealStep(target - shown))
            floodTicks++
            assertTrue(floodTicks < 20, "洪泛相位 $floodTicks tick 未收口（~9 tick 量级）")
        }
        // 全程（洪泛 + 常规尾段）以有界 tick 收敛：洪泛 ~9 + 常规几何 ~49 + 恒 8 尾段 12 ≈ 70
        var total = floodTicks
        while (shown < target) {
            shown = minOf(target, shown + typewriterRevealStep(target - shown))
            total++
            assertTrue(total < 120, "$total tick 未收敛")
        }
        assertEquals(target, shown)
    }
}
