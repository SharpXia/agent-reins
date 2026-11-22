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
 * String.count 同义）；2026-11-22 pacing 对齐轮（B2）：常规档 max(8, 积压/12) →
 * max(1, 积压/90)——步长与桥 2s 合并窗时长对齐（≈90 tick×16ms≈1.4s 抽干一个窗、慢流
 * 1 簇/16ms 逐字），旧档 ≤~12 tick 抽干+500 簇/秒下限是数据节拍的 30-100 倍、打字体感
 * 在数学上不存在，双端同轮同改，双端常量 8192//90//3 @16ms 从此同义——单位语义锁在⑥⑦⑧，
 * 纯数值账（①②③④⑤）随档改写。
 */
class StreamingTypewriterPacingTest {

    // ── ① 小积压恒 1（2026-11-22 pacing 对齐轮：慢流逐字档 1 簇/16ms≈62.5 簇/秒，贴数据节拍） ──
    // 簇计量统一轮改写：单位口径 码元→簇，值表零变化——步进纯函数与单位无关（入参即积压
    // 簇数、返回值即步进簇数），簇口径由 typewriterClusterEnds/推进方落地，锁在⑥⑦⑧。

    @Test
    fun smallBacklogFloorsAt1() {
        assertEquals(1, typewriterRevealStep(0))
        assertEquals(1, typewriterRevealStep(1))
        assertEquals(1, typewriterRevealStep(89))  // 89/90=0（整数除）→ max(1, 0)=1
        assertEquals(1, typewriterRevealStep(90))  // 90/90=1，恒 1
        assertEquals(1, typewriterRevealStep(179)) // 179/90=1，180/90=2 才越界（见②）
    }

    // ── ② 大块积压比例步进（2s 合并窗整块到达：~90 步（≈1.4s）内抽干，贴数据节拍） ──
    // 簇计量统一轮改写：同①，单位=簇。2026-11-22 pacing 对齐轮：比例 1/12→1/90。

    @Test
    fun largeBacklogScalesProportionally() {
        assertEquals(2, typewriterRevealStep(180))   // 180/90=2（恒 1 档出界点）
        assertEquals(10, typewriterRevealStep(900))  // 锚例：积压 900 簇 → 步 10 簇
        assertEquals(20, typewriterRevealStep(1800))
        assertEquals(50, typewriterRevealStep(4500))
        assertEquals(91, typewriterRevealStep(8192)) // 常规档顶：8192/90=91（8193 起洪泛，见④）
    }

    // ── ③ 追平敛零：整块积压按调用方 clamp 语义迭代收敛（几何 ≈89/90 递减 + 恒 1 尾段） ──
    // 簇计量统一轮改写：模拟循环升格为簇口径实演——target 取自 typewriterClusterEnds(实串)
    // （同 StreamingTypewriterText 求值序），步进按簇数推进；BMP 文案簇数==码元数（2400 簇）。
    // 2026-11-22 pacing 对齐轮：比例 1/12→1/90，收敛步数账随之拉长（实算 434 步≈7s 追平
    // 一块 2400 簇——贴 2s 数据节拍的逐字揭示即本意，洪泛档兜底特大块）。

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
            assertTrue(step >= 1, "step=$step < 下限 1 @ backlog=$backlog")
            assertTrue(step <= backlog, "step=$step 超积压 @ backlog=$backlog") // clamp 前上界
            shown = minOf(target, shown + step)
            steps++
            assertTrue(steps < 1000, "1000 步未收敛：shown=$shown")
        }
        assertEquals(target, shown)
        // 几何相位（积压 ≈89/90 递减）2400→~90 约 344 步 + 恒 1 尾段 ~90 步 = 实算 434 步；
        // 上界 480 防步进公式被改成更慢档（更快的 /12、/90→/180 由①②值表另锁）。
        assertTrue(steps in 400..480, "收敛步数 $steps 越界（几何 ~344 + 尾段 ~90 = 434 量级）")
        assertEquals(0, target - shown)
    }

    // ── ④ 洪泛档（2026-11-21 走查②）：积压 >8192 转 积压/3 快速追平，特大块不逐字拖帧 ──
    // 簇计量统一轮改写：阈值语义 码元→簇（8192 簇，与 iOS floodBacklogChars=String.count
    // 同义）；簇口径阈值边界实演在⑧。2026-11-22 pacing 对齐轮：8192 簇常规档步长随 /90 改。

    @Test
    fun floodTierActivatesAboveThreshold() {
        // 阈值边界：8192 簇本身仍常规档（> 才洪泛），8193 簇起洪泛
        assertEquals(91, typewriterRevealStep(8192))    // 8192/90=91（常规档顶）
        assertEquals(2731, typewriterRevealStep(8193))  // 8193/3=2731
        // 桥合并缓冲硬帽量级（256KiB）：步长比例档，不再是常规档的 backlog/90
        assertEquals(65536 / 3, typewriterRevealStep(65536))
        assertEquals(262144 / 3, typewriterRevealStep(262144))
    }

    // ── ⑤ 洪泛相位有界 tick 收敛（2026-11-21 走查②：洪泛 ~2/3 几何递减 + 常规尾段封顶） ──
    // 簇计量统一轮改写：模拟积压数按簇计量——同硬帽下簇数 ≤ 码元数，收敛只会更快。
    // 2026-11-22 pacing 对齐轮：常规档改 /90 后尾段拉长（8192 簇起实算 ~545 tick≈8.7s
    // 逐字收尾，即新档的贴节拍语义），tick 上界随之改。

    @Test
    fun floodBacklogConvergesBoundedTicks() {
        // 洪泛相位 ~2/3 递减：256KiB → ≤8.2k 约 9 tick（走查②行为不变）
        var shown = 0
        val target = 262144
        var floodTicks = 0
        while (target - shown > TYPEWRITER_FLOOD_BACKLOG_CHARS) {
            shown = minOf(target, shown + typewriterRevealStep(target - shown))
            floodTicks++
            assertTrue(floodTicks < 20, "洪泛相位 $floodTicks tick 未收口（~9 tick 量级）")
        }
        // 全程（洪泛 + 常规尾段）以有界 tick 收敛：洪泛 ~9 + 常规档尾段（≤8192 簇 ≈89/90
        // 递减 + 恒 1 段）~528 = 实算 537；上界 640 防步进公式被改成更慢档（①②④值表另锁）。
        var total = floodTicks
        while (shown < target) {
            shown = minOf(target, shown + typewriterRevealStep(target - shown))
            total++
            assertTrue(total < 640, "$total tick 未收敛")
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
        // 纯 BMP：簇数==码元数，阈值账与旧口径同值（8192 簇恰常规档顶、8193 簇起洪泛；
        // 常规档步长随 2026-11-22 pacing 对齐轮改 /90）
        assertEquals(91, typewriterRevealStep(typewriterClusterEnds("字".repeat(8192)).size))
        assertEquals(2731, typewriterRevealStep(typewriterClusterEnds("字".repeat(8193)).size))
        // 非 BMP 锁「簇」语义：8642 码元 > 8192 旧码元口径会误入洪泛档；簇口径 4321 簇恒常规
        assertEquals(4321, typewriterClusterEnds("😀".repeat(4321)).size)
        assertEquals(48, typewriterRevealStep(4321)) // 4321/90=48
    }
}
