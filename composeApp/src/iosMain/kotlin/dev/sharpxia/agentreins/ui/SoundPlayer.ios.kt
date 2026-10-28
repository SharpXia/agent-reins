package dev.sharpxia.agentreins.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryAmbient
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import kotlin.concurrent.Volatile

/**
 * iOS 音效：AVAudioPlayer 播主 bundle 内 `sfx_<name>.wav`（Resources phase 平铺进
 * bundle 根）。会话类别 **ambient**（M3.6 反馈①）：与其他 App 的音乐/通话混播不抢占、
 * 随静音键静音；资源缺失与播放器未就绪都静默跳过——音效是增味不是功能。
 */
@OptIn(ExperimentalForeignApi::class)
private class IosSoundPlayer : SoundPlayer {
    /** 复制写快照（review R1-P2-2）：play 可被多个 Default 协程并发调用，
     *  不可变 Map 引用替换免锁；并发首播的读改写竞态最坏丢一个缓存条目（下次自愈重建），有意容忍。 */
    @Volatile
    private var players: Map<AppSound, AVAudioPlayer?> = emptyMap()

    @Volatile
    private var sessionConfigured = false

    override fun play(sound: AppSound) {
        if (!sessionConfigured) {
            // ambient：可混音（mixWithOthers 语义）、尊静音键。幂等配置，失败不影响播放
            runCatching {
                AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryAmbient, error = null)
            }
            sessionConfigured = true
        }
        if (players.containsKey(sound)) {
            players[sound]?.let { replay(it) }
            return
        }
        val player = NSBundle.mainBundle.pathForResource("sfx_${fileKey(sound)}", "wav")
            ?.let { path -> AVAudioPlayer(contentsOfURL = NSURL.fileURLWithPath(path), error = null) }
        players = players + (sound to player)
        player?.let { replay(it) }
    }

    private fun replay(player: AVAudioPlayer) {
        player.currentTime = 0.0
        player.play()
    }

    private fun fileKey(sound: AppSound) = when (sound) {
        AppSound.BLIP -> "blip"
        AppSound.TICK -> "tick"
        AppSound.DOUBLE_TICK -> "dbltick"
        AppSound.CHIME_A -> "chimea"
        AppSound.CHIME_B -> "chimeb"
    }
}

actual fun createSoundPlayer(): SoundPlayer = IosSoundPlayer()
