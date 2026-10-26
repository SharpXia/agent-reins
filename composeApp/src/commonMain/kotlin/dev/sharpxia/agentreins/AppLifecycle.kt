package dev.sharpxia.agentreins

import kotlinx.coroutines.flow.SharedFlow

/**
 * 应用回到前台的事件源（iOS 由 MainViewController 监听 didBecomeActive 注入）。
 * 用途：iOS 进后台 WS 必死，回前台自动用已存配置重连；Android/Desktop actual 为空流。
 */
internal expect val appForegroundEvents: SharedFlow<Unit>
