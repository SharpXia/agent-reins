package dev.sharpxia.agentreins

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

internal object AppForegroundBus {
    val events = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 4)
}

internal actual val appForegroundEvents: SharedFlow<Unit> = AppForegroundBus.events

// observer token 必须持有引用，否则 K/N GC 回收后通知失效
private var foregroundObserver: NSObjectProtocol? = null

/** MainViewController 启动时装载；didBecomeActive 在回前台（以及冷启动首次激活）时触发。 */
@OptIn(ExperimentalForeignApi::class)
fun installAppForegroundObserver() {
    // K/N 绑定的 addObserverForName name 参数为 String（NSNotificationName 桥接），用系统通知名原文
    foregroundObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        name = "UIApplicationDidBecomeActiveNotification",
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _: NSNotification? ->
        AppForegroundBus.events.tryEmit(Unit)
    }
}
