package dev.sharpxia.agentreins.desktopui

import java.util.prefs.Preferences

/**
 * wp-14：桌面关窗行为偏好存储（设置页「关闭行为」开关 = 关窗最小化到托盘 / 关窗退出进程）。
 * 生产消费位 = main.kt 窗口 onCloseRequest（决策时实时读 [minimizeToTrayOnClose]，改开关即时
 * 生效，无重建）+ SettingsScreen「关闭行为」开关（UI 镜像由 HubShell 持有，权威在此，
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
