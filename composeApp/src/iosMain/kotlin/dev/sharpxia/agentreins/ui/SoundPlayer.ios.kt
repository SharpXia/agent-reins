package dev.sharpxia.agentreins.ui

import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFAudio.AVAudioPlayer
import platform.Foundation.NSBundle
import platform.Foundation.NSURL
import kotlin.concurrent.Volatile

/** iOS 音效：AVAudioPlayer 播主 bundle 内 `sfx_<name>.wav`（Resources phase 平铺进
 *  bundle 根，非 Sounds/ 子目录）。资源缺失与播放器未就绪都静默跳过——音效是增味不是功能。
 *  会话类别保持默认 SoloAmbient：随静音键、与其他 App 音频互斥（产品决策，M3.6 知情保留）。 */
@OptIn(ExperimentalForeignApi::class)
private class IosSoundPlayer : SoundPlayer {
    /** 复制写快照（review R1-P2-2）：play 可被多个 Default 协程并发调用，
     *  不可变 Map 引用替换免锁；并发首播的读改写竞态最坏丢一个缓存条目（下次自愈重建），有意容忍。 */
    @Volatile
    private var players: Map<AppSound, AVAudioPlayer?> = emptyMap()

    override fun play(sound: AppSound) {
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
