package dev.sharpxia.agentreins.ui

/** Android v1.1 补 SoundPool；当前 no-op。 */
actual fun createSoundPlayer(): SoundPlayer = SilentSoundPlayer
