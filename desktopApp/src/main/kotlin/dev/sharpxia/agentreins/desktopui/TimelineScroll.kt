package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiMessage

/**
 * 桌面时间线滚动/翻页判定纯函数（wp-5 桌面时间线补齐；iOS wp-7① 滚动锚定 + wp-12 历史翻页
 * 同型）。全部无副作用、无 Compose 依赖，desktopTest 逐例回归；口径与 iOS HubStore /
 * HubViews 对应实现一一锚定，回归锚 = 桌面↔iOS parity 不漂移。
 */

// ── wp-12 历史翻页（earlierLoaderRow 三态 + 游标链） ─────────────────────────

/**
 * 「加载更早」入口乐观判据（iOS HubStore.refreshCanLoadEarlier 同口径）：翻到头锁存恒隐藏；
 * 空列表无游标不出口；否则「会话 lastSeq（实时行，随 run/消息事件推进）> 已载行数」——
 * 快照尾页 50 行 + 长会话（>50 行）即出按钮，首翻后由服务端 hasMore 接管（翻到头即锁存隐藏）。
 */
internal fun canLoadEarlier(
    exhausted: Boolean,
    lastSeq: Long,
    loadedCount: Int,
    listEmpty: Boolean,
): Boolean = !exhausted && !listEmpty && lastSeq > loadedCount

/** earlierLoaderRow 渲染三态（iOS HubViews.earlierLoaderRow 同序）：在途 > 可翻 > 收尾 > 隐藏。 */
internal enum class EarlierRowState { Hidden, InFlight, Loadable, Exhausted }

internal fun earlierRowState(loading: Boolean, canLoad: Boolean, exhausted: Boolean): EarlierRowState = when {
    loading -> EarlierRowState.InFlight
    canLoad -> EarlierRowState.Loadable
    exhausted -> EarlierRowState.Exhausted
    else -> EarlierRowState.Hidden
}

/**
 * 翻页游标（08 §4.3 keyset）：保存游标倒退（< 当前最老已载行 seq——会话期快照重建
 * HUB-SYNC-001/002 后旧游标低于重建后尾页首行）即弃用，回落当前最老已载行 seq，防以旧游标
 * 取数静默跳过缺口带；无保存游标（首翻/页未带 oldestSeq）同回落。
 */
internal fun nextBeforeCursor(savedCursor: Long?, oldestLoadedSeq: Long?): Long? {
    val cursor = if (savedCursor != null && oldestLoadedSeq != null && savedCursor < oldestLoadedSeq) {
        null
    } else {
        savedCursor
    }
    return cursor ?: oldestLoadedSeq
}

// ── wp-7① 滚动锚定（底部锚定态判定 + 相位门 + 贴底跟随条件） ─────────────────

/** 距底容差（px）：惯性/手势停在距底一屏内小段也算在底（iOS bottomSlack=80pt 同量级）。 */
internal const val BOTTOM_SLACK_PX = 96

/** LazyListLayoutInfo 投影出的最小几何采样（纯值，snapshotFlow 去重可比）。 */
internal data class ScrollSample(
    /** 滚动在途（含拖拽/惯性/程序化滚动）。 */
    val scrolling: Boolean,
    /** 尾行已布局且可见（空列表/尾行在视口外为 false——无意义几何不判锚定态）。 */
    val lastItemVisible: Boolean,
    /** 尾行内容末端坐标。 */
    val lastItemEnd: Int,
    /** 视口末端坐标（含 contentPadding）。 */
    val viewportEnd: Int,
    /** 全部行都在视口内（内容未超视口——无可滚空间，恒视为在底）。 */
    val contentFullyVisible: Boolean,
)

/**
 * 底部锚定态判定：尾行末端距视口端 ≤ 容差即在底；流式增长把尾行推出视口下沿时距离为负
 * （内容越过视口端），仍判在底——锚定态不被内容增长翻转。内容未超视口（短会话）恒在底。
 */
internal fun isAtBottom(sample: ScrollSample, slackPx: Int = BOTTOM_SLACK_PX): Boolean {
    if (!sample.lastItemVisible) return false
    if (sample.contentFullyVisible) return true
    return sample.viewportEnd - sample.lastItemEnd <= slackPx
}

/**
 * 用户驱动滚动相位门：滚动在途 且 无程序化跟随动画在途。程序化跟随（animating）期间几何
 * 变化不改锚定态——否则流式跟随动画中尾行短暂越界会解锚，下一帧起跟随永久停摆
 * （iOS onScrollPhaseChange userDriven 相位区分同纪律）。
 */
internal fun isUserDrivenScroll(scrolling: Boolean, programmaticFollowInFlight: Boolean): Boolean =
    scrolling && !programmaticFollowInFlight

/**
 * 锚定态更新裁决：用户驱动帧照判；或恰逢滚动结束沿（含程序滚动收尾）按最终几何补判一次
 * （惯性停在距底容差内也算在底）。内容增长帧（非用户驱动且无滚动沿）不改锚定态——Compose
 * 下 append 行/行内增长不产生滚动沿，锚定态原样保留，跟随不因增长解锚。
 */
internal fun shouldRecomputeAnchor(userDriven: Boolean, scrollJustSettled: Boolean): Boolean =
    userDriven || scrollJustSettled

/** wp-7① 贴底跟随条件：尾部签名已变化 且 用户在底部锚定态（上翻读史时不拉回）。 */
internal fun shouldFollowTail(tailChanged: Boolean, atBottom: Boolean): Boolean = tailChanged && atBottom

/**
 * wp-7① 尾部签名：行数 + 尾行身份/状态/内容量 + 尾行内容首尾切片，O(1) 构造（take/takeLast
 * 为有界子串）。比旧「只看条数」多覆盖流式行原位增长与占位行收敛（iOS tailSignature 同型；
 * 长度取 UTF-16 code unit，与 iOS utf8.count 同语义不同单位）。翻页 prepend 也变签（行数变），
 * 跟随由 atBottom 守卫拦住；极小概率漏检「等长且首尾切片相同的原地改写」，下一帧 append
 * 自愈，不追。
 */
internal fun tailSignature(messages: List<UiMessage>): String {
    val last = messages.lastOrNull() ?: return ""
    val c = last.content
    return "${messages.size}|${last.messageId}|${last.state}|${c.length}|${c.take(16)}|${c.takeLast(64)}"
}

// ── wp-12 翻页落地滚动恢复 + 时间线结构 ─────────────────────────────────────

/**
 * 翻页恢复锚：已武装且锚行（触发翻页前最老一行）仍在合并后列表中 → 返回其在 messages 里的
 * 下标（供 scrollToItem 置顶，保住阅读位置）；锚行随化身更替消失或未武装 → null（弃锚不滚）。
 */
internal fun restoreScrollTarget(
    armed: Boolean,
    anchorMessageId: String?,
    messageIds: List<String>,
): Int? {
    if (!armed || anchorMessageId.isNullOrEmpty()) return null
    return messageIds.indexOf(anchorMessageId).takeIf { it >= 0 }
}

/**
 * 时间线 LazyColumn 中消息行之前的常驻项数（顶部翻页行 + 翻页错误横幅在滚动区内占位），
 * 消息下标 → LazyColumn 项下标换算用：layoutIndex = messagesIndex + prefix。
 */
internal fun timelinePrefixItemCount(loaderVisible: Boolean, errorVisible: Boolean): Int =
    (if (loaderVisible) 1 else 0) + (if (errorVisible) 1 else 0)
