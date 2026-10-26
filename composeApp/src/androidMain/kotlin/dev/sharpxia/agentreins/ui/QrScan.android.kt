package dev.sharpxia.agentreins.ui

internal actual val qrScanSupported: Boolean = false

internal actual fun launchQrScanner(onResult: (String?) -> Unit) {
    // Android v1.1 出包时接 ML Kit / zxing 扫码；M1 用粘贴 setup code
}
