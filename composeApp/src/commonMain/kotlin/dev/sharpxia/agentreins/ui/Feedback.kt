package dev.sharpxia.agentreins.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/**
 * 声音/触感反馈层（M3.6）。音效为自写合成 wav（Grok 原声有版权，不复用）：
 * 事件挂点集中在 AppModel，平台 actual 决定是否真发声（iOS AVAudioPlayer，其余 no-op）。
 */

/** 5 件套音效（自合成，资源名见 SoundPlayer actual 的映射）。 */
enum class AppSound(val label: String) {
    /** 短促上行 blip：连接成功。 */
    BLIP("Blip"),

    /** 单滴答：轻交互。 */
    TICK("Tick"),

    /** 双滴答：默认通知音。 */
    DOUBLE_TICK("Double Tick"),

    /** 双音上行风铃 A：审批请求。 */
    CHIME_A("Chime A"),

    /** 双音下行风铃 B：通知音备选。 */
    CHIME_B("Chime B"),
}

/** 全局反馈偏好（AppModel 从 kv 加载并写入；平台 actual 门控时读取）。 */
object FeedbackPrefs {
    /** 触感开关（iOS UIImpactFeedback/UINotificationFeedbackGenerator 是否生效）。 */
    var hapticsEnabled = mutableStateOf(true)

    /** 音效总开关：关闭后所有事件音（含固定的连接/审批音）静默（M3.6 反馈①）。 */
    var soundEnabled = mutableStateOf(true)

    /** 通知音（新回复）：null = 静音。审批/连接音为固定音，不受此项控制。 */
    var notificationSound = mutableStateOf<AppSound?>(AppSound.DOUBLE_TICK)
}

interface SoundPlayer {
    fun play(sound: AppSound)
}

/** 无声实现（desktop/android + iOS 资源缺失兜底）。 */
object SilentSoundPlayer : SoundPlayer {
    override fun play(sound: AppSound) {}
}

@Composable
fun rememberSoundPlayer(): SoundPlayer = remember { createSoundPlayer() }

expect fun createSoundPlayer(): SoundPlayer

/**
 * 触觉反馈（M2.6 iOS 体感补丁）。iOS = UIImpactFeedback/UINotificationFeedbackGenerator；
 * Android/Desktop 无此交互习惯，actual 为 no-op（Android 出包在 v1.1，届时补 Vibrator）。
 * M3.6：实际触发受 [FeedbackPrefs.hapticsEnabled] 门控（设置页开关）。
 */
interface HapticPlayer {
    /** 轻击：发送、滑动操作提交、长按菜单弹出、回到底部。 */
    fun tap()

    /** 成功确认：归档/置顶等操作完成。 */
    fun success()
}

@Composable
expect fun rememberHapticPlayer(): HapticPlayer
