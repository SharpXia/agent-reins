package dev.sharpxia.agentreins.ui

internal actual val qrScanSupported: Boolean = false

internal actual fun launchQrScanner(onResult: (String?) -> Unit) {
    // 桌面端无摄像头扫码场景，用粘贴 setup code
}
