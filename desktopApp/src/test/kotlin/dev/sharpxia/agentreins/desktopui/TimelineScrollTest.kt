package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * wp-5 桌面时间线滚动/翻页判定纯函数单测（desktopApp jvmTest）。三验收例 + 配套口径：
 * ① 贴底跟随条件（含流式原地增长由尾签捕获）；② 上翻不拽回（锚定态几何判定 + 相位门，
 * 程序化跟随帧/内容增长帧不解锚）；③ 翻页锚恢复（合并后锚行置顶 + 化身更替弃锚 + 游标链）。
 * 口径与 iOS wp-7①（HubViews onScrollGeometryChange/onScrollPhaseChange/tailSignature/
 * setAtBottom）与 wp-12（HubStore refreshCanLoadEarlier/loadEarlier/takeRestoreScrollTarget）
 * 逐一对应，回归锚 = 桌面↔iOS parity 不漂移。
 */
class TimelineScrollTest {

    private fun msg(
        id: String = "m1",
        seq: Long = 1,
        role: String = "assistant",
        content: String = "正文",
        state: String = "final",
    ) = UiMessage(
        messageId = id, seq = seq, role = role, content = content,
        state = state, uiState = null, ts = null,
    )

    private fun sample(
        scrolling: Boolean = false,
        lastItemVisible: Boolean = true,
        lastItemEnd: Int = 1000,
        viewportEnd: Int = 1000,
        contentFullyVisible: Boolean = false,
    ) = ScrollSample(scrolling, lastItemVisible, lastItemEnd, viewportEnd, contentFullyVisible)

    // ── ① 贴底跟随条件：尾签变化 且 在底 ──

    @Test
    fun followOnlyWhenTailChangedAndAnchored() {
        // 尾部变化 + 在底 → 跟随（含流式行原位增长、新行 append）
        assertTrue(shouldFollowTail(tailChanged = true, atBottom = true))
        // 上翻读史（不在底）不拽回
        assertFalse(shouldFollowTail(tailChanged = true, atBottom = false))
        // 尾部无变化不跟随
        assertFalse(shouldFollowTail(tailChanged = false, atBottom = true))
    }

    @Test
    fun tailSignatureCoversStreamingInPlaceGrowth() {
        // 流式行原位增长（同一条行 content 变长）→ 尾签必须变化（旧「只看条数」口径漏检的场景）
        val streaming = listOf(msg(id = "m1", state = "streaming", content = "a"))
        val grown = listOf(msg(id = "m1", state = "streaming", content = "ab"))
        assertTrue(tailSignature(grown) != tailSignature(streaming))
        // 尾行状态翻转（streaming → final，同内容）→ 变化（占位行收敛）
        assertTrue(
            tailSignature(listOf(msg(id = "m1", state = "final", content = "a"))) !=
                tailSignature(streaming),
        )
        // 尾行身份变化（新行 append）→ 变化
        assertTrue(
            tailSignature(streaming + msg(id = "m2", seq = 2)) != tailSignature(streaming),
        )
        // 头部行增删（翻页 prepend）也变签（行数变）——跟随由 atBottom 守卫拦住
        assertTrue(
            tailSignature(listOf(msg(id = "head", seq = 0)) + streaming) != tailSignature(streaming),
        )
        // 尾部完全无变化 → 稳定（幂等）
        assertEquals(tailSignature(streaming), tailSignature(streaming))
        // 空列表 → 空签
        assertEquals("", tailSignature(emptyList()))
    }

    // ── ② 上翻不拽回：锚定态几何判定 + 相位门 ──

    @Test
    fun atBottomJudgedByGeometryWithSlack() {
        // 尾行末端距视口端在容差内（绝对贴底时余量仅为底 contentPadding 量级）→ 在底
        assertTrue(isAtBottom(sample(lastItemEnd = 1000, viewportEnd = 1012)))
        // 用户上翻超过容差 → 不在底（后续尾签变化不拽回）
        assertFalse(isAtBottom(sample(lastItemEnd = 800, viewportEnd = 1000)))
        // 尾行在视口外（上翻多屏）→ 不在底
        assertFalse(isAtBottom(sample(lastItemVisible = false, lastItemEnd = 3000, viewportEnd = 1000)))
        // 内容未超视口（短会话全部可见）→ 恒在底（无可滚空间）
        assertTrue(isAtBottom(sample(lastItemEnd = 200, viewportEnd = 1000, contentFullyVisible = true)))
    }

    @Test
    fun streamingGrowthKeepsAnchorInsteadOfUnanchoring() {
        // 流式 append/行内增长把尾行推出视口下沿：距离为负（内容越过视口端）→ 仍判在底，
        // 锚定态不被内容增长翻转（据增长解锚会让跟随永久停摆——iOS wp-7① 同纪律）
        assertTrue(isAtBottom(sample(lastItemEnd = 1240, viewportEnd = 1000)))
        // 且增长帧（无用户滚动、无滚动沿）不改锚定态
        assertFalse(shouldRecomputeAnchor(userDriven = false, scrollJustSettled = false))
    }

    @Test
    fun userDrivenGateDistinguishesProgrammaticFollow() {
        // 用户拖拽/惯性帧照判锚定态
        assertTrue(isUserDrivenScroll(scrolling = true, programmaticFollowInFlight = false))
        // 程序化跟随动画帧不改锚定态（跟随时尾行短暂越界不解锚）
        assertFalse(isUserDrivenScroll(scrolling = true, programmaticFollowInFlight = true))
        // 滚动结束沿补判一次（惯性停在距底容差内也算在底——iOS bottomSlack=80 同语义）
        assertTrue(shouldRecomputeAnchor(userDriven = false, scrollJustSettled = true))
        assertTrue(isAtBottom(sample(lastItemEnd = 990, viewportEnd = 1000))) // 10px ≤ 96 容差
        // 上翻后惯性停在半途 → 不在底
        assertFalse(isAtBottom(sample(lastItemEnd = 700, viewportEnd = 1000)))
        // 非用户驱动且非结束沿（程序跟随在途）不补判
        assertFalse(shouldRecomputeAnchor(userDriven = false, scrollJustSettled = false))
    }

    // ── ③ 翻页锚恢复 ──

    @Test
    fun restoreAnchorTargetsOldestRowAfterMerge() {
        val mergedIds = listOf("e1", "e2", "a1", "a2", "a3") // a1 = 触发翻页前最老行，新页 e1/e2 合入其上方
        // 武装且锚行在合并后列表 → 目标 = 锚行新下标（滚回后视口顶 = 原最老行，阅读位置不跳）
        assertEquals(2, restoreScrollTarget(armed = true, anchorMessageId = "a1", messageIds = mergedIds))
        // 未武装（在途新尾消息到达）→ 不滚
        assertNull(restoreScrollTarget(armed = false, anchorMessageId = "a1", messageIds = mergedIds))
        // 锚行随化身更替消失 → 弃锚不滚
        assertNull(restoreScrollTarget(armed = true, anchorMessageId = "gone", messageIds = mergedIds))
        assertNull(restoreScrollTarget(armed = true, anchorMessageId = null, messageIds = mergedIds))
    }

    @Test
    fun cursorAdvancesWithRegressionGuard() {
        // 首翻无保存游标 → 回落当前最老已载行 seq（快照尾页 seq 101..150，before=101 取 seq<101）
        assertEquals(101L, nextBeforeCursor(savedCursor = null, oldestLoadedSeq = 101L))
        // 正常推进：保存游标与当前最老已载行一致（上一页 oldestSeq=51 已合入为新最老行）→ 用保存游标
        assertEquals(51L, nextBeforeCursor(savedCursor = 51L, oldestLoadedSeq = 51L))
        // 游标倒退（会话期快照重建，旧游标 30 低于重建后尾页首行 51）→ 弃用回落，防静默跳过缺口带
        assertEquals(51L, nextBeforeCursor(savedCursor = 30L, oldestLoadedSeq = 51L))
        // 空列表无游标 → 不取数
        assertNull(nextBeforeCursor(savedCursor = null, oldestLoadedSeq = null))
    }

    // ── earlierLoaderRow 三态 + 入口判据 + 滚动区结构 ──

    @Test
    fun earlierRowStatesMapInOrder() {
        assertEquals(EarlierRowState.InFlight, earlierRowState(loading = true, canLoad = true, exhausted = false))
        assertEquals(EarlierRowState.Loadable, earlierRowState(loading = false, canLoad = true, exhausted = false))
        assertEquals(EarlierRowState.Exhausted, earlierRowState(loading = false, canLoad = false, exhausted = true))
        assertEquals(EarlierRowState.Hidden, earlierRowState(loading = false, canLoad = false, exhausted = false))
    }

    @Test
    fun canLoadEarlierOptimisticCriterion() {
        // >50 行会话：快照尾页 50 行 + lastSeq 80 → 出按钮
        assertTrue(canLoadEarlier(exhausted = false, lastSeq = 80, loadedCount = 50, listEmpty = false))
        // 短会话（快照即全量）不出按钮——界面零噪声
        assertFalse(canLoadEarlier(exhausted = false, lastSeq = 30, loadedCount = 30, listEmpty = false))
        // 翻到头锁存 → 恒隐藏（验收：按钮消失）
        assertFalse(canLoadEarlier(exhausted = true, lastSeq = 80, loadedCount = 50, listEmpty = false))
        // 空列表无游标不出口
        assertFalse(canLoadEarlier(exhausted = false, lastSeq = 80, loadedCount = 0, listEmpty = true))
    }

    @Test
    fun timelinePrefixItemMath() {
        assertEquals(1, timelinePrefixItemCount(loaderVisible = true, errorVisible = false))
        assertEquals(2, timelinePrefixItemCount(loaderVisible = true, errorVisible = true))
        assertEquals(0, timelinePrefixItemCount(loaderVisible = false, errorVisible = false))
    }
}
