package dev.sharpxia.agentreins.desktopui

import java.util.prefs.Preferences

/**
 * wp-14：桌面窗口/显示偏好存储——本文件挂两班偏好（同 DesktopMutePreferences 挂协调器
 * 文件的先例）：[DesktopWindowPreferences]（设置页「关闭行为」开关 = 关窗最小化到托盘 /
 * 关窗退出进程）与 [DesktopDisplayPreferences]（2026-11-19 纯显示偏好轮：设置页「流式输出」
 * /「思考中指示」两开关）。
 *
 * 关窗行为：生产消费位 = main.kt 窗口 onCloseRequest（决策时实时读 [minimizeToTrayOnClose]，
 * 改开关即时生效，无重建）+ SettingsScreen「关闭行为」开关（UI 镜像由 HubShell 持有，权威在此，
 * DesktopMutePreferences 同式纪律）。
 *
 * 口径（与 DesktopMutePreferences 同面）：关窗行为是**本地用户偏好，非账号数据**——跨登出
 * 保留（不在设置页登出清库面）。缺省 = 关窗最小化到托盘（wp-14 验收「默认关窗最小化」；
 * 降级方向同「保住进程」：Preferences 不可用/读到缺省回落 true）。
 *
 * 存储纪律：进程内内存镜像为权威，持久化 best-effort——任何 Preferences I/O 失败静默降级
 * 为纯内存态：进程内仍生效，下次启动回落最近一次成功持久化的值；Preferences 体系不可用时
 * node = null 全程内存态。不抛不崩（桌面静默降级纪律同面延伸到偏好面）。
 */
internal class DesktopWindowPreferences(node: Preferences? = null) {

    private val node: Preferences? = node ?: runCatching { Preferences.userRoot().node(NODE_NAME) }.getOrNull()

    private val lock = Any()
    private var minimizeToTrayInMemory: Boolean

    init {
        var loaded = true
        node?.let { n ->
            runCatching { loaded = n.getBoolean(KEY_MINIMIZE_TO_TRAY_ON_CLOSE, true) }
        }
        minimizeToTrayInMemory = loaded
    }

    /** true = 关窗最小化到托盘（进程与同步面保持运行，托盘「显示主窗」可唤回）；false = 关窗即退出。缺省 true。 */
    val minimizeToTrayOnClose: Boolean
        get() = synchronized(lock) { minimizeToTrayInMemory }

    fun setMinimizeToTrayOnClose(enabled: Boolean) {
        synchronized(lock) { minimizeToTrayInMemory = enabled }
        node?.let { n ->
            runCatching { n.putBoolean(KEY_MINIMIZE_TO_TRAY_ON_CLOSE, enabled) }
        } // 持久化失败 = 内存态降级（进程内生效，重启后回落上次持久化值）
    }

    internal companion object {
        /** 用户级偏好节点（java.util.prefs；与 DesktopMutePreferences 同节点，键族不重叠）。 */
        private const val NODE_NAME = "dev/sharpxia/agentreins/hub"

        /** 关窗行为键（缺省 true = 关窗最小化到托盘）。 */
        private const val KEY_MINIMIZE_TO_TRAY_ON_CLOSE = "hub.minimizeToTrayOnClose"
    }
}

/**
 * 2026-11-19 纯显示偏好轮：桌面显示偏好存储（设置页「显示偏好」卡两开关）——「流式输出」
 * （开 = 流式行逐字揭示 + ▌ 光标 + 「流式生成中」标签；关 = 流式增量不渲染、AI 回复等 final
 * 权威行落库时整段呈现；决策纯函数 streamingRowPresentation）与「思考中指示」（开 = 回复
 * 占位文案「正在思考…/<agent> 正在处理中…」；关 = 信号撤显示，Stop 键/「结束中」旁注等
 * 功能面不动）。**数据面不动**：delta 照常入投影库、已读水位照常推进，开关只裁渲染分支
 * （iOS HubStore.streamingPlayback/thinkingIndicator 同口径 parity，两端各自持久互不同步）。
 * 生产消费位 = HubShell（UI 镜像只喂会话页渲染与设置页开关，权威在此，改开关即时生效）。
 *
 * 口径（DesktopWindowPreferences 同面）：本地用户偏好非账号数据——跨登出保留（不在登出
 * 清库面）；缺省 = 两开关均开（保持既有行为，老用户升级视觉零变化；缺键回落 true 覆盖
 * 首装/旧档无键/持久化损坏）。同节点 "dev/sharpxia/agentreins/hub"（键族与
 * hub.minimizeToTrayOnClose 等不重叠）。存储纪律完全同 [DesktopWindowPreferences]：
 * 内存镜像权威 + best-effort persist + node = null 纯内存态，不抛不崩。
 */
internal class DesktopDisplayPreferences(node: Preferences? = null) {

    private val node: Preferences? = node ?: runCatching { Preferences.userRoot().node(NODE_NAME) }.getOrNull()

    private val lock = Any()
    private var streamingPlaybackInMemory: Boolean
    private var thinkingIndicatorInMemory: Boolean

    init {
        var loadedStreaming = true
        var loadedThinking = true
        node?.let { n ->
            runCatching { loadedStreaming = n.getBoolean(KEY_STREAMING_PLAYBACK, true) }
            runCatching { loadedThinking = n.getBoolean(KEY_THINKING_INDICATOR, true) }
        }
        streamingPlaybackInMemory = loadedStreaming
        thinkingIndicatorInMemory = loadedThinking
    }

    /** true = 流式逐字揭示（现状）；false = 流式增量不渲染、final 落库整段呈现。缺省 true。 */
    val streamingPlayback: Boolean
        get() = synchronized(lock) { streamingPlaybackInMemory }

    /** true = 思考中信号显示（现状）；false = 信号撤显示（Stop 等功能面不动）。缺省 true。 */
    val thinkingIndicator: Boolean
        get() = synchronized(lock) { thinkingIndicatorInMemory }

    fun setStreamingPlayback(enabled: Boolean) {
        synchronized(lock) { streamingPlaybackInMemory = enabled }
        node?.let { n ->
            runCatching { n.putBoolean(KEY_STREAMING_PLAYBACK, enabled) }
        } // 持久化失败 = 内存态降级（进程内生效，重启后回落上次持久化值）
    }

    fun setThinkingIndicator(enabled: Boolean) {
        synchronized(lock) { thinkingIndicatorInMemory = enabled }
        node?.let { n ->
            runCatching { n.putBoolean(KEY_THINKING_INDICATOR, enabled) }
        } // 持久化失败 = 内存态降级（进程内生效，重启后回落上次持久化值）
    }

    internal companion object {
        /** 用户级偏好节点（java.util.prefs；与 DesktopWindowPreferences/DesktopMutePreferences 同节点，键族不重叠）。 */
        private const val NODE_NAME = "dev/sharpxia/agentreins/hub"

        /** 「流式输出」键（缺省 true = 逐字揭示，保持现状）。 */
        private const val KEY_STREAMING_PLAYBACK = "hub.streamingPlayback"

        /** 「思考中指示」键（缺省 true = 信号显示，保持现状）。 */
        private const val KEY_THINKING_INDICATOR = "hub.thinkingIndicator"
    }
}
