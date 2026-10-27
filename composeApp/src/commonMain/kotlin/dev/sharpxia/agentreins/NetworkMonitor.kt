package dev.sharpxia.agentreins

import kotlinx.coroutines.flow.StateFlow

/**
 * 网络可用性状态（响应式重连触发源，真机反馈：定时盲重试时序不对，应条件满足再连）。
 * iOS = NWPathMonitor（StateFlow 回放当前值，冷启动即有初始判定）；
 * Android/Desktop actual 恒 true（桌面无此语义，Android v1.1 出包时接 ConnectivityManager）。
 */
internal expect val networkAvailable: StateFlow<Boolean>
