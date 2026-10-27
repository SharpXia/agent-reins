package dev.sharpxia.agentreins.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.MutableSharedFlow

/** MainViewController 状态栏探测层 → Compose 的事件通道。 */
internal object StatusBarTapBus {
    val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}

@Composable
internal actual fun StatusBarTapEffect(onTap: () -> Unit) {
    // LaunchedEffect(Unit) 只捕获一次回调——后续重组传入的新 lambda（携带新状态）
    // 必须经 rememberUpdatedState 透传，否则回调读到首帧旧状态（对话页回顶失效实锤）
    val currentOnTap by rememberUpdatedState(onTap)
    LaunchedEffect(Unit) {
        StatusBarTapBus.taps.collect { currentOnTap() }
    }
}
