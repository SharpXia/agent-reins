package dev.sharpxia.agentreins.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.MutableSharedFlow

/** MainViewController 状态栏探测层 → Compose 的事件通道。 */
internal object StatusBarTapBus {
    val taps = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
}

@Composable
internal actual fun StatusBarTapEffect(onTap: () -> Unit) {
    LaunchedEffect(Unit) {
        StatusBarTapBus.taps.collect { onTap() }
    }
}
