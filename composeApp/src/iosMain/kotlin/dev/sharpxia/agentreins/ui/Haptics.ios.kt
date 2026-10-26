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
        impact.prepare()
        impact.impactOccurred()
    }

    override fun success() {
        notify.prepare()
        notify.notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
    }
}

@Composable
actual fun rememberHapticPlayer(): HapticPlayer = remember { IosHapticPlayer() }
