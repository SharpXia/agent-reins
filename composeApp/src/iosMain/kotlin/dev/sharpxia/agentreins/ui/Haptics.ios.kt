package dev.sharpxia.agentreins.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType

private class IosHapticPlayer : HapticPlayer {
    private val impact = UIImpactFeedbackGenerator(style = UIImpactFeedbackStyle.UIImpactFeedbackStyleLight)
    private val notify = UINotificationFeedbackGenerator()

    override fun tap() {
        if (!FeedbackPrefs.hapticsEnabled.value) return // M3.6 设置页开关门控
        impact.prepare()
        impact.impactOccurred()
    }

    override fun success() {
        if (!FeedbackPrefs.hapticsEnabled.value) return
        notify.prepare()
        notify.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
    }
}

@Composable
actual fun rememberHapticPlayer(): HapticPlayer = remember { IosHapticPlayer() }
