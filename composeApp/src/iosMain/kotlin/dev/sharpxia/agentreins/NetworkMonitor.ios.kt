package dev.sharpxia.agentreins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_monitor_t
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_t
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue

internal object NetworkBus {
    // 起始乐观 true：monitor 启动即回调一次当前路径，随后纠正
    val available = MutableStateFlow(true)
}

internal actual val networkAvailable: StateFlow<Boolean> = NetworkBus.available

private var pathMonitor: nw_path_monitor_t? = null

/**
 * MainViewController 启动时装载。用 Network.framework 的 **C 层 API**——K/N 2.4 的
 * platform.Network 不导出 NWPathMonitor ObjC 类（实测 klib 无此符号），C 函数族齐全。
 * update handler 的块桥接把 nw_path_t 呈现为 NSObject?，运行时同指针，as-cast 还原。
 * 路径每次变化（Wi-Fi/蜂窝/Tailscale 起停）都会推新值。
 */
@OptIn(ExperimentalForeignApi::class)
fun installNetworkMonitor() {
    if (pathMonitor != null) return
    val monitor = nw_path_monitor_create()
    nw_path_monitor_set_update_handler(monitor) { path ->
        @Suppress("UNCHECKED_CAST")
        val p = path as? nw_path_t
        NetworkBus.available.value = p != null && nw_path_get_status(p) == nw_path_status_satisfied
    }
    nw_path_monitor_set_queue(
        monitor,
        dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
    )
    nw_path_monitor_start(monitor)
    pathMonitor = monitor
}
