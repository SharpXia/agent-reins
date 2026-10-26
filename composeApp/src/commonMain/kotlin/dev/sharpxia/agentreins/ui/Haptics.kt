package dev.sharpxia.agentreins.ui

import androidx.compose.runtime.Composable

/**
 * 触觉反馈（M2.6 iOS 体感补丁）。iOS = UIImpactFeedback/UINotificationFeedbackGenerator；
 * Android/Desktop 无此交互习惯，actual 为 no-op（Android 出包在 v1.1，届时补 Vibrator）。
 */
interface HapticPlayer {
    /** 轻击：发送、滑动操作提交、长按菜单弹出、回到底部。 */
    fun tap()

    /** 成功确认：归档/置顶等操作完成。 */
    fun success()
}

@Composable
expect fun rememberHapticPlayer(): HapticPlayer
