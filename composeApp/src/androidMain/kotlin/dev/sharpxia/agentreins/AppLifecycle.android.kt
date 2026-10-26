package dev.sharpxia.agentreins

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

// Android 侧生命周期回连随 v1.1 Android 出包一并实现；v1.0 移动端验收设备为 iOS
internal actual val appForegroundEvents: SharedFlow<Unit> = MutableSharedFlow()
