package dev.sharpxia.agentreins.ui

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter

internal object QrScanBus {
    val results = MutableSharedFlow<String?>(extraBufferCapacity = 4)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}

/** iosApp 壳工程扫码页回传（QR 原文或 null=取消）。K/N 导出，Swift 侧 QrScanBridgeKt 调用。 */
fun onQrScanResult(payload: String?) {
    QrScanBus.results.tryEmit(payload)
}

internal actual val qrScanSupported: Boolean = true

@OptIn(ExperimentalForeignApi::class)
internal actual fun launchQrScanner(onResult: (String?) -> Unit) {
    // 扫码页由 SwiftUI 壳工程承载（AVFoundation），经通知拉起、经 onQrScanResult 回传
    NSNotificationCenter.defaultCenter.postNotificationName("agentreins.qrscan.request", null)
    QrScanBus.scope.launch { onResult(QrScanBus.results.first()) }
}
