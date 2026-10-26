package dev.sharpxia.agentreins.ui

/** 平台是否内置扫码入口（iOS 相机扫描；Android/Desktop 用粘贴 setup code 代替）。 */
internal expect val qrScanSupported: Boolean

/** 拉起系统相机扫码；结果（QR 原文或 null=取消）经 [onResult] 回传。 */
internal expect fun launchQrScanner(onResult: (String?) -> Unit)
