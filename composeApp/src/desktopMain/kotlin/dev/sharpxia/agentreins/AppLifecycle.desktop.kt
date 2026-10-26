package dev.sharpxia.agentreins

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

// 桌面窗口常驻前台，无后台断连问题
internal actual val appForegroundEvents: SharedFlow<Unit> = MutableSharedFlow()
