package dev.sharpxia.agentreins.ui

import androidx.compose.runtime.Composable

private val NoopHaptics = object : HapticPlayer {
    override fun tap() = Unit
    override fun success() = Unit
}

@Composable
actual fun rememberHapticPlayer(): HapticPlayer = NoopHaptics
