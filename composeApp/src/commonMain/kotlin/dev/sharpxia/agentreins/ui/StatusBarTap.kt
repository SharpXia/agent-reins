package dev.sharpxia.agentreins.ui

import androidx.compose.runtime.Composable

/**
 * 状态栏点击 → 回顶（iOS 原生交互习惯）。CMP 未内置该手势（YouTrack CMP-4187
 * 仍在 Backlog），iOS 侧由 MainViewController 的探测层发事件；Android/Desktop
 * 无此交互习惯，actual 为 no-op。
 */
@Composable
internal expect fun StatusBarTapEffect(onTap: () -> Unit)
