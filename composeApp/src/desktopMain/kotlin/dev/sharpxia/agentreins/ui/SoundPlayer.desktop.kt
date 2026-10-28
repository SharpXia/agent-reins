package dev.sharpxia.agentreins.ui

/** desktop 无音频桥（M3.6 范围 iOS 优先）：设置页可切换但播放为 no-op。 */
actual fun createSoundPlayer(): SoundPlayer = SilentSoundPlayer
