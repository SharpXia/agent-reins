package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 打字机节拍 + 交接账本单测（:desktopApp:test 锁行为不只锁存取——StreamingRowPresentationTest/
 * TimelineScrollTest 纯函数回归锚同纪律）。
 *
 * 桌面↔iOS parity 契约：iOS Chat/StreamingTypewriter.swift `TypewriterPacing` 同值同义
 * （baseTickNs=32ms / minTickNs=16ms / horizonMs=2000 / lagLimitChars=300 / catchUpTicks=75，
 * tick 同为毫秒整数除法）——iOS 无单测 target，本表兼作两端行为契约不漂移（单端改值须
 * 双端同步）。2026-11-28 流式逐字轮重写：旧「max(1, 积压/90) + 洪泛档 积压/3」废止，新口径
 * 恒逐字（步进恒 1，靠缩短 tick 提速；仅积压超滞后上限/final 续打才升档）。
 */
class StreamingTypewriterPacingTest {

    // ── ① tick 钳位：视界/积压，钳 [帧率档 16ms, 慢速档 32ms] ──

    @Test
    fun tickClampsBetweenMinAndBase() {
        assertEquals(32L, typewriterTickMs(0)) // 非正积压按 1 → 2000/1 → 钳慢速档
        assertEquals(32L, typewriterTickMs(1))
        assertEquals(32L, typewriterTickMs(62)) // 2000/62=32：慢速档顶
        assertEquals(16L, typewriterTickMs(125)) // 2000/125=16：帧率档起点
        assertEquals(16L, typewriterTickMs(8192))
        assertEquals(16L, typewriterTickMs(262144))
    }

    // ── ② 两档之间线性过渡，且对积压单调不增 ──

    @Test
    fun tickTransitionsLinearlyBetweenTiers() {
        assertEquals(20L, typewriterTickMs(100)) // 2000/100
        assertEquals(19L, typewriterTickMs(105)) // 2000/105=19.04 → 19（毫秒整数除，与 iOS 同式）
        assertEquals(17L, typewriterTickMs(117)) // 2000/117=17.09 → 17
        var prev = Long.MAX_VALUE
        for (backlog in 1..400 step 7) {
            val t = typewriterTickMs(backlog)
            assertTrue(t <= prev, "tick 随积压增大反而变长：$t > $prev @ backlog=$backlog")
            prev = t
        }
    }

    // ── ③ 步进恒 1：积压 ≤ 滞后上限（300 簇）全程逐字，超限才 ceil(积压/上限) 升档 ──

    @Test
    fun stepIsOneUntilLagLimit() {
        assertEquals(1, typewriterStep(0))
        assertEquals(1, typewriterStep(-5)) // 非正按 0 处理
        assertEquals(1, typewriterStep(300))
        assertEquals(2, typewriterStep(301)) // 301/300 向上取整
        assertEquals(2, typewriterStep(600))
        assertEquals(3, typewriterStep(601))
        assertEquals(4, typewriterStep(901))
        assertEquals(874, typewriterStep(262143)) // 桥合并缓冲硬帽量级：滞后仍有界
    }

    // ── ④ final 续打收敛：ceil(积压/75) 步进——尾 75 簇恒逐字，总 tick 对数有界 ──

    @Test
    fun catchUpStepBoundsTailTicks() {
        assertEquals(1, typewriterStep(75, catchUp = true))
        assertEquals(2, typewriterStep(76, catchUp = true))
        assertEquals(4, typewriterStep(300, catchUp = true))
        // 步进 = 积压/75 时积压按指数消退：总 tick ≈ 75×(1+ln(积压/75))，尾 75 簇回到逐字
        fun catchUpTicks(target: Int): Int {
            var shown = 0
            var ticks = 0
            while (shown < target) {
                val n = minOf(typewriterStep(target - shown, catchUp = true), target - shown)
                if (target - shown <= 75) assertEquals(1, n) // 尾段恒 1 簇收口，不整块跳出
                shown += n
                ticks++
            }
            return ticks
        }
        assertEquals(75, catchUpTicks(75))
        assertEquals(112, catchUpTicks(150)) // ≈1.8s
        assertEquals(156, catchUpTicks(300)) // ≈2.5s（帧率档 16ms/tick）
        for (target in listOf(600, 8192, 262144)) { // 桥合并缓冲硬帽量级仍有界
            val bound = kotlin.math.ceil(TYPEWRITER_CATCH_UP_TICKS * (1 + kotlin.math.ln(target.toDouble() / TYPEWRITER_CATCH_UP_TICKS))).toInt() + 1
            assertTrue(catchUpTicks(target) <= bound, "续打 $target 簇超对数上界 $bound")
        }
    }

    // ── ⑤ 揭示循环仿真：窗积压逐字放完，耗时落在「两档上下界」内（不整块倾泻、不拖沓） ──

    @Test
    fun windowBacklogDrainsCharByCharWithinTierBounds() {
        // 与 StreamingTypewriterText 求值序同式：dt 记账（理想 tick 准点到达 → 每 tick 恒 1 簇）
        fun drainMs(backlog: Int): Pair<Long, Int> {
            var shown = 0
            var elapsed = 0L
            var maxStep = 0
            while (shown < backlog) {
                val step = minOf(typewriterStep(backlog - shown), backlog - shown)
                maxStep = maxOf(maxStep, step)
                shown += step
                elapsed += typewriterTickMs(backlog - shown + step)
            }
            return elapsed to maxStep
        }
        // 慢流：每窗 60 簇全程慢速档 32ms → 1.92s，全程逐字
        assertEquals(1_920L to 1, drainMs(60))
        // 中速：每窗 120 簇——帧率档起头、随积压消退放慢到慢速档收尾；耗时严格落在
        // 120×16ms 与 120×32ms 之间（两端都不贴：起头压到帧率档故 > 下界，尾段减速故 < 上界）
        val (midMs, midStep) = drainMs(120)
        assertEquals(1, midStep)
        assertTrue(midMs > 1_920L && midMs < 3_840L, "midMs=$midMs 越出两档上下界")
        // 滞后上限内的大窗仍逐字：300 簇——全程逐字，耗时落在 300×16ms 与 300×32ms 之间
        //（帧率档压满仍慢于 2s 窗=滞后开始累积，到 301 簇起才升档——有界滞后的来源）
        val (bigMs, bigStep) = drainMs(300)
        assertEquals(1, bigStep)
        assertTrue(bigMs > 4_800L && bigMs < 9_600L, "bigMs=$bigMs 越出两档上下界")
        // 升档后步进才 >1：600 簇积压起手 2 簇/tick
        assertEquals(2, drainMs(600).second)
    }

    // ── ⑥ 簇边界表值表（JDK21 BreakIterator 实测钉点，与 Swift Character 同语义） ──

    @Test
    fun clusterEndsValueTableLocksGraphemeUnits() {
        // 任务钉例 "a😀b" = 3 簇：😀 代理对收整簇，ends=[1,3,4]——码元 2 恒不出现为切点
        assertEquals(listOf(1, 3, 4), typewriterClusterEnds("a😀b").toList())
        // 常规中文流：1 字 1 簇 1 码元——簇口径与旧码元口径全同（零行为回归面）
        assertEquals(listOf(1, 2), typewriterClusterEnds("中文").toList())
        // 孤立高位代理归一：自成 1 簇不并邻（"a\uD83Db"=3 簇）——残缺内容不崩、不误并
        assertEquals(listOf(1, 2, 3), typewriterClusterEnds("a\uD83Db").toList())
        // 多码元簇同簇：CRLF（GB3，与 Swift Character 同）、ZWJ 家族、旗帜
        assertEquals(listOf(1, 3, 4), typewriterClusterEnds("x\r\ny").toList())
        assertEquals(1, typewriterClusterEnds("👨‍👩‍👦").size)
        assertEquals(listOf(4, 5), typewriterClusterEnds("🇺🇸x").toList())
        // 空串零簇；全簇揭示 = 码元全长（显示侧 substring 全长恒安全）
        assertEquals(0, typewriterClusterEnds("").size)
        assertEquals(4, typewriterClusterEnds("a😀b").last())
    }

    // ── ⑦ 揭示循环仿真：切点恒取自簇表、前缀恒不出孤立高位代理尾（含升档步进路径） ──

    @Test
    fun clusterRevealSimulationAlwaysCutsAtClusterBoundaries() {
        val content = "流" + "😀".repeat(50) + "尾"
        assertEquals(102, content.length)
        val clusterEnds = typewriterClusterEnds(content)
        val target = clusterEnds.size
        assertEquals(52, target)
        var shown = 0
        while (shown < target) {
            // 同 StreamingTypewriterText 求值序：簇数 + 步进（簇）→ clamp → 查表取显示切点；
            // catchUp=true 走升档步进（多簇一步）仍恒落簇边界
            shown = minOf(target, shown + typewriterStep(target - shown, catchUp = true))
            assertTrue(shown <= target, "簇推进越界 @ shown=$shown")
            val cut = clusterEnds[shown - 1]
            assertFalse(
                content.substring(0, cut).last().isHighSurrogate(),
                "显示前缀尾落孤立高位代理（劈对/劈簇）@ cut=$cut",
            )
        }
        assertEquals(target, shown)
    }

    // ── ⑧ 簇口径：步进入参是簇数非码元——非 BMP 大串不误触滞后升档 ──

    @Test
    fun lagLimitCountsClustersNotCodeUnits() {
        // 300 个 emoji = 600 码元 > 300，簇数 300 仍恒逐字；301 簇（602 码元）才升档
        assertEquals(1, typewriterStep(typewriterClusterEnds("😀".repeat(300)).size))
        assertEquals(2, typewriterStep(typewriterClusterEnds("😀".repeat(301)).size))
        // 首窗逐字上限按簇：300 emoji 首窗可从 0 逐字
        assertTrue(typewriterClusterEnds("😀".repeat(300)).size <= TYPEWRITER_FIRST_CHUNK_LIMIT)
        assertFalse(typewriterClusterEnds("😀".repeat(301)).size <= TYPEWRITER_FIRST_CHUNK_LIMIT)
    }

    // ── ⑨ 交接账本：首窗逐字 / 重物化不重打 ──

    @Test
    fun ledgerTreatsUnseenMessageAsNewStream() {
        val ledger = TypewriterHandoffLedger(now = { 0L })
        assertTrue(ledger.isNewStream("c1", "dstream:run-1")) // 首次出现的流式行
        assertFalse(ledger.isNewStream("", "m")) // 空会话键：不记账不触发首窗逐字
        assertFalse(ledger.isNewStream("c1", "")) // 空行键同理
        ledger.record("c1", "dstream:run-1", 10, "0123456789")
        assertFalse(ledger.isNewStream("c1", "dstream:run-1")) // 滚动往返重物化：已见=贴齐不重打
        assertTrue(ledger.isNewStream("c1", "dstream:run-2")) // 下一 run 的新流式行：重新逐字
        // 空会话键不落账：记了也读不到（恒不新流、不续打）
        ledger.record("", "m", 1, "x")
        assertFalse(ledger.isNewStream("", "m"))
        assertNull(ledger.carryStart("", "x"))
    }

    // ── ⑩ 交接账本：final 续打起点校验（前缀吻合 + 确有剩余 + TTL） ──

    @Test
    fun ledgerCarryStartValidatesPrefixBacklogAndTtl() {
        var now = 1_000L
        val ledger = TypewriterHandoffLedger(now = { now })
        assertNull(ledger.carryStart("c1", "abc")) // 无记录
        assertNull(ledger.carryStart("", "abc")) // 空会话键恒不续打
        ledger.record("c1", "dstream:run-1", 2, "abcd")
        assertEquals(2, ledger.carryStart("c1", "abcdef")) // 前缀吻合 + 有剩余积压
        assertNull(ledger.carryStart("c1", "ab")) // 内容收缩：已揭示不小于全文
        assertNull(ledger.carryStart("c1", "abXXXX")) // 非前缀改写（replace 语义）：贴齐
        assertNull(ledger.carryStart("c2", "abcdef")) // 他人会话不串账
        ledger.record("c1", "dstream:run-1", 4, "abcd")
        assertNull(ledger.carryStart("c1", "abcd")) // 流式期已打完：无剩余，final 直接整段渲染
        ledger.record("c1", "dstream:run-2", 1, "ab")
        now += 4_999L
        assertEquals(1, ledger.carryStart("c1", "abc")) // TTL 内有效
        now += 1L
        assertNull(ledger.carryStart("c1", "abc")) // TTL 外：迟到物化贴齐
        assertNull(ledger.carryStart("c1", "abc")) // 过期即清：账本无残留
    }

    @Test
    fun ledgerResetClearsEverything() {
        val ledger = TypewriterHandoffLedger(now = { 0L })
        ledger.record("c1", "dstream:run-1", 2, "abcd")
        ledger.reset()
        assertTrue(ledger.isNewStream("c1", "dstream:run-1"))
        assertNull(ledger.carryStart("c1", "abcdef"))
    }
}
