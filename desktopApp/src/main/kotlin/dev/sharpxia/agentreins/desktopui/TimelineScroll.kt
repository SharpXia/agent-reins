package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiMessage

/**
 * 桌面时间线滚动/翻页判定纯函数（wp-5 桌面时间线补齐；iOS wp-7① 滚动锚定 + wp-12 历史翻页
 * 同型）。全部无副作用、无 Compose 依赖，:desktopApp:test 逐例回归；口径与 iOS HubStore /
 * HubViews 对应实现一一锚定，回归锚 = 桌面↔iOS parity 不漂移。
 *
 * parity 知情差异两注（自检轮 2 R2-12/R2-13 落档，均非对齐遗漏）：
 * - 翻页恢复锚：iOS HubStore 对自动/手动两路翻页都武装并消费恢复锚（HubStore.swift:1806/
 *   :1833——SwiftUI LazyVStack 无 keyed 锚定需显式滚回）；桌面 LazyColumn 时间线 items 按
 *   messageId keyed 天然保位，[restoreScrollTarget] 只服务手动路径（自动续拉零触碰锚位，
 *   见 ConversationScreen.loadEarlier 注）。
 * - 键盘命令接线前置条件：命令由 ConversationScreen 根容器 onPreviewKeyEvent（preview 相
 *   沿焦点链下行）解析——composer 持焦点或焦点链内存在宿主才可达；composer 失焦后点非可
 *   聚焦消息行/滚动条若致窗口无焦点宿主，键事件可能不入 Compose 焦点派发（两种桌面行为
 *   未实机裁决）：实机走查硬项=「点消息列表（非 composer）后按 Ctrl+End/⌘↑ 是否滚动」，
 *   失效预案=根容器 focusRequester+focusable 或列表点击请求根焦点。
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

// ── 2026-11-23 批次二轮：近顶自动续拉 + 时间线键盘命令（补齐与 iOS 的交互缺口） ────────

/**
 * 近顶自动续拉近窗（行数）：首行下标 ≤ 阈值即「近顶」。iOS earlierAutoProximity 为像素、
 * 桌面为行数近似——parity 口径差异知情（翻页行自身占一行，8 行 ≈ 近一屏首屏行数）。
 */
internal const val AUTO_LOAD_EARLIER_PROXIMITY_ITEMS = 8

/**
 * 近顶自动续拉触发（iOS HubViews near-top auto requestEarlier 同型）：三条件 AND——
 * 用户停在近顶窗内（程序化跳转/贴底跟随帧不算）、翻页入口恰为 Loadable。loadEarlier 内
 * loadingEarlier/earlierExhausted/canLoadEarlierNow 守卫照旧幂等双保险（接线层不重复判）。
 */
internal fun shouldAutoLoadEarlier(
    firstVisibleItemIndex: Int,
    loaderState: EarlierRowState,
    isUserDrivenScroll: Boolean,
    threshold: Int = AUTO_LOAD_EARLIER_PROXIMITY_ITEMS,
): Boolean =
    firstVisibleItemIndex <= threshold && loaderState == EarlierRowState.Loadable && isUserDrivenScroll

/** 时间线键盘命令（⌘/Ctrl+↓ / Ctrl+End 回底、⌘/Ctrl+↑ / Ctrl+Home 回顶；iOS ⌘↓ 菜单回底同型）。 */
internal enum class TimelineScrollCommand { ScrollToBottom, ScrollToTop }

/**
 * 桌面 Key.keyCode.toInt() 的锚值 = AWT VK code（compose ui-desktop 1.12.2 实证：
 * Key 由 AWT KeyEvent.getKeyCode() + location 直接构造，keyCode 低 32 位即 VK code——
 * DirectionUp=38 / DirectionDown=40 / MoveHome(VK_HOME)=36 / MoveEnd(VK_END)=35）。
 * 纯函数不持 Compose 依赖（Key.keyCode→Int 映射在接线处做 .toInt()）。
 */
internal const val KEYCODE_DIRECTION_UP = 38
internal const val KEYCODE_DIRECTION_DOWN = 40
internal const val KEYCODE_MOVE_HOME = 36
internal const val KEYCODE_MOVE_END = 35

/**
 * 时间线键盘命令解析（闭集映射；无修饰键/未知键恒 null——无修饰的 Home/End 放行系统默认，
 * composer 聚焦态由接线层聚焦门先行放行文本编辑，两平台输入惯例差异知情）。
 */
internal fun resolveTimelineScrollKey(keyCode: Int, isCtrlOrMeta: Boolean): TimelineScrollCommand? {
    if (!isCtrlOrMeta) return null
    return when (keyCode) {
        KEYCODE_DIRECTION_DOWN, KEYCODE_MOVE_END -> TimelineScrollCommand.ScrollToBottom
        KEYCODE_DIRECTION_UP, KEYCODE_MOVE_HOME -> TimelineScrollCommand.ScrollToTop
        else -> null
    }
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
    /** 首个可见行下标（近顶自动续拉的近窗判定用；空列表为 0——2026-11-23 批次二轮增字段）。 */
    val firstVisibleItemIndex: Int,
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
