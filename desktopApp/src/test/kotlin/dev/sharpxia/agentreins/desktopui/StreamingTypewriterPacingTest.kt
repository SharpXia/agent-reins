package dev.sharpxia.agentreins.desktopui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 2026-11-21 流式单行归一轮：打字机步进纯函数单测（:desktopApp:test 锁行为不只锁存取——
 * StreamingRowPresentationTest/TimelineScrollTest 纯函数回归锚同纪律）。
 *
 * 桌面↔iOS parity 契约：iOS 侧 HubViews.swift StreamingTypewriter `revealStep` 双档 @ 16ms
 * ——本表锁 desktopui.typewriterRevealStep 同参数同单位；iOS 无单测 target，桌面回归锚兼作
 * 两端行为契约不漂移（单端改值须双端同步）。2026-11-21 走查②双端同扩洪泛档（积压>8192 转
 * 积压/3）；同日簇计量统一轮：积压/步长单位 UTF-16 码元 → 字素 Cluster
 * （typewriterClusterEnds = java.text.BreakIterator extended grapheme cluster，与 iOS
 * String.count 同义），双端常量 8192//12//3 @16ms 从此同义，非 BMP 漂移知情差异废止
 * ——单位语义锁在⑥⑦⑧，纯数值账（①②④⑤）不受单位改写影响。
 */
class StreamingTypewriterPacingTest {

    // ── ① 小积压恒 8（打字感下限：慢流 ≈500 簇/秒，8 簇/16ms） ──
    // 簇计量统一轮改写：单位口径 码元→簇，值表零变化——步进纯函数与单位无关（入参即积压
    // 簇数、返回值即步进簇数），簇口径由 typewriterClusterEnds/推进方落地，锁在⑥⑦⑧。

    @Test
    fun smallBacklogFloorsAt8() {
        assertEquals(8, typewriterRevealStep(0))
        assertEquals(8, typewriterRevealStep(1))
        assertEquals(8, typewriterRevealStep(12))
        assertEquals(8, typewriterRevealStep(95)) // 95/12=7（整数除）→ max(8, 7)=8，96/12=8 才越界
        assertEquals(8, typewriterRevealStep(96)) // 96/12=8，max 恒 8
    }

    // ── ② 大块积压比例步进（2s 合并窗整块到达：~12 步内追平，积压不跨下一块） ──
    // 簇计量统一轮改写：同①，单位=簇，值表零变化。

    @Test
    fun largeBacklogScalesProportionally() {
        assertEquals(10, typewriterRevealStep(120)) // 评审锚例：积压 120 簇 → 步 10 簇
        assertEquals(8, typewriterRevealStep(97))   // 97/12=8（除后仍是下限）
        assertEquals(100, typewriterRevealStep(1200))
        assertEquals(200, typewriterRevealStep(2400))
    }

    // ── ③ 追平敛零：整块积压按调用方 clamp 语义迭代收敛（步数 ≈ 12 有界、末段回落恒 8 步进） ──
    // 簇计量统一轮改写：模拟循环升格为簇口径实演——target 取自 typewriterClusterEnds(实串)
    // （同 StreamingTypewriterText 求值序），步进按簇数推进；BMP 文案簇数==码元数（2400 簇），
    // 步数账与旧码元口径一致（几何 ~38 + 恒 8 尾段 12 = 50 量级），故收敛界沿用。

    @Test
    fun backlogConvergesToZeroBoundedSteps() {
        val content = "字".repeat(2400)
        val clusterEnds = typewriterClusterEnds(content)
        assertEquals(2400, clusterEnds.size) // BMP：1 字 1 簇，锚定 target 口径
        val target = clusterEnds.size
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
    // 簇计量统一轮改写：阈值语义 码元→簇（8192 簇，与 iOS floodBacklogChars=String.count
    // 同义），纯数值账零变化（入参即簇数）；簇口径阈值边界实演在⑧。

    @Test
    fun floodTierActivatesAboveThreshold() {
        // 阈值边界：8192 簇本身仍常规档（> 才洪泛），8193 簇起洪泛
        assertEquals(682, typewriterRevealStep(8192))   // 8192/12=682
        assertEquals(2731, typewriterRevealStep(8193))  // 8193/3=2731
        // 桥合并缓冲硬帽量级（256KiB）：步长比例档，不再是几何档的 backlog/12
        assertEquals(65536 / 3, typewriterRevealStep(65536))
        assertEquals(262144 / 3, typewriterRevealStep(262144))
    }

    // ── ⑤ 洪泛相位有界 tick 收敛（2026-11-21 走查②：洪泛 ~2/3 几何递减 + 常规尾段封顶） ──
    // 簇计量统一轮改写：模拟积压数按簇计量，步数账零变化——同硬帽下簇数 ≤ 码元数，
    // 收敛只会更快，既有 tick 上界恒安全。

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

    // ── ⑥ 簇边界表值表（2026-11-21 簇计量统一轮；改写原因：旧⑥「typewriterSnapToCodePoint-
    //    Boundary 贴齐值表」随该函数退役——簇计数语义下劈半索引不可构造、贴齐职责被天然
    //    覆盖，值表改锁簇表本值；JDK21 BreakIterator 实测钉点，与 Swift Character 同语义） ──

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

    // ── ⑦ 揭示循环仿真（簇口径；改写原因：旧⑦断言「前缀不劈代理对」建立在码元推进+贴齐
    //    修复上——簇推进下断言升格为「切点恒取自簇表、前缀恒不出孤立高位代理尾」，严格强于
    //    不劈代理对：ZWJ/CRLF 等多码元簇同样不劈，旧码元口径对这些本无防护） ──

    @Test
    fun clusterRevealSimulationAlwaysCutsAtClusterBoundaries() {
        // 同旧⑦仿真串：码元 102 vs 簇 52——对比锁定「推进单位已换」且步数账减半量级
        val content = "流" + "😀".repeat(50) + "尾"
        assertEquals(102, content.length)
        val clusterEnds = typewriterClusterEnds(content)
        val target = clusterEnds.size
        assertEquals(52, target)
        var shown = 0
        while (shown < target) {
            // 同 StreamingTypewriterText 求值序：簇数 + 步进（簇）→ clamp → 查表取显示切点
            shown = minOf(target, shown + typewriterRevealStep(target - shown))
            assertTrue(shown <= target, "簇推进越界 @ shown=$shown")
            val cut = clusterEnds[shown - 1]
            assertFalse(
                content.substring(0, cut).last().isHighSurrogate(),
                "显示前缀尾落孤立高位代理（劈对/劈簇）@ cut=$cut",
            )
        }
        assertEquals(target, shown)
    }

    // ── ⑧ 洪泛阈值按簇（2026-11-21 簇计量统一轮扩例）：「8192」语义 = 8192 簇，非 8192 码元 ──

    @Test
    fun floodThresholdCountsClustersNotCodeUnits() {
        // 纯 BMP：簇数==码元数，阈值账与旧口径同值（8192 簇恰常规档顶、8193 簇起洪泛）
        assertEquals(682, typewriterRevealStep(typewriterClusterEnds("字".repeat(8192)).size))
        assertEquals(2731, typewriterRevealStep(typewriterClusterEnds("字".repeat(8193)).size))
        // 非 BMP 锁「簇」语义：8642 码元 > 8192 旧码元口径会误入洪泛档；簇口径 4321 簇恒常规
        assertEquals(4321, typewriterClusterEnds("😀".repeat(4321)).size)
        assertEquals(360, typewriterRevealStep(4321)) // 4321/12=360
    }
}
