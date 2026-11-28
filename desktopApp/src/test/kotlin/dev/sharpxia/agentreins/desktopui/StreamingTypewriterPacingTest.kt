package dev.sharpxia.agentreins.desktopui

import kotlin.math.ceil
import kotlin.math.ln
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
 * （baseTickMs=32 / minTickMs=16 / horizonMs=2000 / lagLimitChars=750 / carrySingleLimit=750 /
 * carryDrainTicks=60 / firstChunkTypingLimit=1200，tick 同为毫秒整数除法）——iOS 无单测
 * target，本表兼作两端行为契约不漂移（单端改值须双端同步）。
 *
 * 2026-11-28 流式逐字轮：旧「max(1, 积压/90) + 洪泛档 积压/3」废止，新口径恒逐字（步进恒 1，
 * 靠缩短 tick 提速）。探针实测定参：快 agent 一条 810 字回复在首个 2s 合并窗内整段到达并
 * 紧接 final——首窗必须从 0 逐字、final 续打对中等积压必须仍逐字，否则一次性回复全程零逐字。
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

    // ── ③ 流式期步进恒 1：积压 ≤ 滞后上限（750 簇）全程逐字，超限才 ceil(积压/上限) 升档 ──

    @Test
    fun stepIsOneUntilLagLimit() {
        assertEquals(1, typewriterStep(0))
        assertEquals(1, typewriterStep(-5)) // 非正按 0 处理
        assertEquals(1, typewriterStep(750))
        assertEquals(2, typewriterStep(751)) // 751/750 向上取整
        assertEquals(2, typewriterStep(1500))
        assertEquals(3, typewriterStep(1501))
        assertEquals(4, typewriterStep(2251))
        assertEquals(350, typewriterStep(262143)) // 桥合并缓冲硬帽量级：滞后仍有界
    }

    // ── ④ final 续打：≤750 恒逐字（一次性回复的续打即正文本体），超出部分有界追平后回到逐字 ──

    @Test
    fun carryStepStaysSingleUntilLimitThenDrainsExcess() {
        assertEquals(1, typewriterCarryStep(0))
        assertEquals(1, typewriterCarryStep(-1))
        assertEquals(1, typewriterCarryStep(750))
        assertEquals(1, typewriterCarryStep(751)) // 超出 1：ceil(1/60)=1
        assertEquals(1, typewriterCarryStep(810)) // 超出 60：ceil(60/60)=1——探针实测的一次性 810 字回复全程逐字
        assertEquals(2, typewriterCarryStep(811))
        assertEquals(10, typewriterCarryStep(1350)) // 超出 600：10 簇/tick
        // 超出部分 ~60×(1+ln) tick 内追平，其后恒逐字收口
        fun carryTicks(target: Int): Int {
            var shown = 0
            var ticks = 0
            while (shown < target) {
                val backlog = target - shown
                val n = minOf(typewriterCarryStep(backlog), backlog)
                if (backlog <= TYPEWRITER_CARRY_SINGLE_LIMIT) assertEquals(1, n) // 上限内恒 1 簇收口
                shown += n
                ticks++
            }
            return ticks
        }
        assertEquals(750, carryTicks(750))
        assertEquals(810, carryTicks(810)) // 一次性回复：恰好 810 tick，全程逐字
        for (target in listOf(1350, 3600, 262144)) { // 长回复尾包有界（桥合并缓冲硬帽量级仍收敛）
            val excess = target - TYPEWRITER_CARRY_SINGLE_LIMIT
            val bound = TYPEWRITER_CARRY_SINGLE_LIMIT +
                ceil(TYPEWRITER_CARRY_DRAIN_TICKS * (1 + ln(excess.toDouble() / TYPEWRITER_CARRY_DRAIN_TICKS))).toInt() + 2
            assertTrue(carryTicks(target) <= bound, "续打 $target 簇超对数上界 $bound")
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
        // 滞后上限内的大窗（探针实测的 750 簇量级）全程逐字：耗时落在 750×16ms 与 750×32ms 之间
        val (bigMs, bigStep) = drainMs(750)
        assertEquals(1, bigStep)
        assertTrue(bigMs > 12_000L && bigMs < 24_000L, "bigMs=$bigMs 越出两档上下界")
        // 超限才升档：1200 簇起手 2 簇/tick（持续快流下滞后有界）
        assertEquals(2, drainMs(1200).second)
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
            // 续打步进（超限多簇一步）仍恒落簇边界
            shown = minOf(target, shown + typewriterCarryStep(target - shown))
            assertTrue(shown <= target, "簇推进越界 @ shown=$shown")
            val cut = clusterEnds[shown - 1]
            assertFalse(
                content.substring(0, cut).last().isHighSurrogate(),
                "显示前缀尾落孤立高位代理（劈对/劈簇）@ cut=$cut",
            )
        }
        assertEquals(target, shown)
    }

    // ── ⑧ 簇口径：步进入参是簇数非码元——非 BMP 大串不误触升档；首窗上限按簇 ──

    @Test
    fun limitsCountClustersNotCodeUnits() {
        // 750 个 emoji = 1500 码元 > 750，簇数 750 仍恒逐字；751 簇（1502 码元）才升档
        assertEquals(1, typewriterStep(typewriterClusterEnds("😀".repeat(750)).size))
        assertEquals(2, typewriterStep(typewriterClusterEnds("😀".repeat(751)).size))
        assertEquals(1, typewriterCarryStep(typewriterClusterEnds("😀".repeat(810)).size))
        // 首窗逐字上限按簇：1200 emoji 首窗可从 0 逐字，1201 不行
        assertTrue(typewriterClusterEnds("😀".repeat(1200)).size <= TYPEWRITER_FIRST_CHUNK_LIMIT)
        assertFalse(typewriterClusterEnds("😀".repeat(1201)).size <= TYPEWRITER_FIRST_CHUNK_LIMIT)
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
        var now = 1_764_243_200_000L
        val ledger = TypewriterHandoffLedger(now = { now })
        ledger.record("c1", "dstream:run-1", 2, "abcd")
        ledger.observeMessages("c1", listOf(TypewriterHandoffLedger.ArrivalCandidate("first", "assistant", "final", null, 1)))
        ledger.observeMessages(
            "c1",
            listOf(
                TypewriterHandoffLedger.ArrivalCandidate("first", "assistant", "final", null, 1),
                TypewriterHandoffLedger.ArrivalCandidate("final-1", "assistant", "final", java.time.Instant.ofEpochMilli(now).toString(), 10),
            ),
        )
        assertEquals(0, ledger.freshFinalStart("final-1"))
        ledger.reset()
        assertTrue(ledger.isNewStream("c1", "dstream:run-1"))
        assertNull(ledger.carryStart("c1", "abcdef"))
        assertNull(ledger.freshFinalStart("final-1")) // 登记随登出清空
    }

    // ── ⑪ 交接账本：实时新到的 final 行逐字——探针实证的主路径：OpenClaw 网关回复整条经
    //    message.created 落库、无 delta，流式行从未浮现。判定在消息流入口（首帧=历史不算，
    //    其后首次出现的助手 final 行登记），行组合只读登记、打字机起跑才消耗 ──

    @Test
    fun ledgerFreshFinalTypesOnlyUnseenRecentRows() {
        var now = 1_764_243_200_000L // 固定 epoch 毫秒
        val ledger = TypewriterHandoffLedger(now = { now })
        fun tsAgo(ms: Long) = java.time.Instant.ofEpochMilli(now - ms).toString()
        fun cand(
            id: String,
            role: String = "assistant",
            state: String = "final",
            ts: String? = tsAgo(3_000),
            len: Int = 810,
        ) = TypewriterHandoffLedger.ArrivalCandidate(id, role, state, ts, len)

        // 首帧 = 打开会话的历史加载：不算新到
        ledger.observeMessages("c1", listOf(cand("h1"), cand("h2")))
        assertNull(ledger.freshFinalStart("h1"))

        // 其后首次出现的助手 final 行：登记实时新到——非消耗读取，行组合重入恒得 0
        ledger.observeMessages("c1", listOf(cand("h1"), cand("h2"), cand("m1")))
        assertEquals(0, ledger.freshFinalStart("m1"))
        assertEquals(0, ledger.freshFinalStart("m1"))
        // 打字机起跑即消耗：此后重物化不从头重打；同一行再现于后续帧不重复登记
        ledger.consumeFreshFinal("m1")
        assertNull(ledger.freshFinalStart("m1"))
        ledger.observeMessages("c1", listOf(cand("h1"), cand("h2"), cand("m1")))
        assertNull(ledger.freshFinalStart("m1"))

        // 不登记的形态：过旧（翻页回灌）/未来超窗/无时间戳/坏时间戳/空正文/超长/流式行/用户行
        ledger.observeMessages(
            "c1",
            listOf(
                cand("old", ts = tsAgo(121_000)),
                cand("far-future", ts = tsAgo(-121_000)),
                cand("nots", ts = null),
                cand("bad", ts = "not-a-date"),
                cand("empty", len = 0),
                cand("huge", len = 20_001),
                cand("streaming-row", state = "streaming"),
                cand("user-row", role = "user"),
            ),
        )
        for (id in listOf("old", "far-future", "nots", "bad", "empty", "huge", "streaming-row", "user-row")) {
            assertNull(ledger.freshFinalStart(id), id)
        }

        // 登记的形态：窗内边界内侧（119s 前）/设备时钟落后（未来 60s，按绝对差）/内容上限内侧
        now += 1_000
        ledger.observeMessages("c1", listOf(cand("edge", ts = tsAgo(119_000)), cand("skew", ts = tsAgo(-60_000)), cand("max", len = 20_000)))
        assertEquals(0, ledger.freshFinalStart("edge"))
        assertEquals(0, ledger.freshFinalStart("skew"))
        assertEquals(0, ledger.freshFinalStart("max"))

        // 登记过期：30s 未起跑即贴齐（迟到物化不冷不丁重打旧消息）
        now += 31_000
        assertNull(ledger.freshFinalStart("edge"))

        // 切会话：观测状态复位，首帧又是历史
        ledger.observeMessages("c2", listOf(cand("other")))
        assertNull(ledger.freshFinalStart("other"))

        // 流式续打账本优先：有记录且前缀吻合时接续记录进度，不从头重打
        ledger.record("c1", "final-2", 40, "x".repeat(50))
        assertEquals(40, ledger.carryStart("c1", "x".repeat(50) + "y".repeat(50)))

        // 空会话键不观测
        ledger.observeMessages("", listOf(cand("anon")))
        assertNull(ledger.freshFinalStart("anon"))
    }
}
