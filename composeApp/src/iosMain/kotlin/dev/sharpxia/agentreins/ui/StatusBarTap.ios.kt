package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableSharedFlow
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSLog
import platform.UIKit.UIScrollView
import platform.UIKit.UIScrollViewDelegateProtocol
import platform.darwin.NSObject

/** 探针 delegate → Compose 页面（StatusBarTapEffect 消费端）的事件通道。 */
internal object StatusBarTapBus {
    val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}

/** 探针初始 offset（非 0 是系统选中候选的前提之一：已在顶部就没有可回顶的量）。 */
private const val PROBE_OFFSET = 100.0

/**
 * 系统 scroll-to-top 探针，随调用屏进出组合（导航栈/Tab 切换/销毁即自动装卸，
 * 同窗同时只有一个活屏探针=唯一的 scrollsToTop 候选）。KotlinConf App 同款形态：
 * UIKitView 内嵌 Compose 层级、alpha(0f)（≤0.01 的视图 UIKit 命中测试直接跳过，
 * 不挡列表触摸）、contentSize 远大于可视区（无内容可滚的系统不选它）、
 * 初始 offset=100（点按状态栏时系统把探针动画滚到 0，didScroll 检出向上即转发）。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun StatusBarTapEffect(onTap: () -> Unit) {
    // LaunchedEffect(Unit) 只捕获一次回调——后续重组传入的新 lambda（携带新状态）
    // 必须经 rememberUpdatedState 透传，否则回调读到首帧旧状态（对话页回顶失效实锤）
    val currentOnTap by rememberUpdatedState(onTap)
    val delegate = remember { StatusTapDelegate() }
    UIKitView(
        factory = {
            UIScrollView(CGRectMake(0.0, 0.0, 100.0, 1.0)).apply {
                scrollsToTop = true
                showsVerticalScrollIndicator = false
                showsHorizontalScrollIndicator = false
                setDelegate(delegate)
                setContentSize(CGSizeMake(100.0, 10000.0))
                setContentOffset(CGPointMake(0.0, PROBE_OFFSET))
            }
        },
        modifier = Modifier.alpha(0f).height(1.dp).fillMaxWidth(),
    )
    LaunchedEffect(Unit) {
        StatusBarTapBus.taps.collect { currentOnTap() }
    }
}

/** 探针 delegate：强引用由组合侧 remember 持有（UIKit delegate 是弱引用）。 */
@OptIn(ExperimentalForeignApi::class)
private class StatusTapDelegate : NSObject(), UIScrollViewDelegateProtocol {
    private var firstScroll = true
    private var scrollingToTop = false
    private var lastKnownOffset = PROBE_OFFSET

    override fun scrollViewShouldScrollToTop(scrollView: UIScrollView): Boolean {
        NSLog("AgentReins[STATUS_BAR] scrollViewShouldScrollToTop -> true")
        return true
    }

    override fun scrollViewDidScroll(scrollView: UIScrollView) {
        if (firstScroll) { // factory 里自己 setContentOffset 触发的首帧回调，不是点按
            firstScroll = false
            return
        }
        if (scrollingToTop) return
        val newOffset = scrollView.contentOffset.useContents { y } ?: return
        val scrollingUp = newOffset < lastKnownOffset
        lastKnownOffset = newOffset
        if (scrollingUp) {
            scrollingToTop = true
            NSLog("AgentReins[STATUS_BAR_TAPPED] probe offset -> %.1f, forward to bus", newOffset)
            StatusBarTapBus.taps.tryEmit(Unit)
        }
    }

    override fun scrollViewDidScrollToTop(scrollView: UIScrollView) {
        NSLog("AgentReins[STATUS_BAR_TAPPED] scrollViewDidScrollToTop -> reset probe")
        scrollingToTop = false
        lastKnownOffset = PROBE_OFFSET
        scrollView.setContentOffset(CGPointMake(0.0, PROBE_OFFSET))
    }
}
