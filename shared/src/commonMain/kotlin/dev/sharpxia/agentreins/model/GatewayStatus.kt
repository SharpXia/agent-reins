package dev.sharpxia.agentreins.model

/** 网关连接状态。UI 据此渲染状态徽标。 */
sealed interface GatewayStatus {
    data object Disconnected : GatewayStatus

    data object Connecting : GatewayStatus

    /** 扫码配对等待 owner 批准（客户端自动重试，批准后自动续连）。 */
    data class PairingPending(val requestId: String? = null) : GatewayStatus

    data class Connected(val gatewayName: String? = null) : GatewayStatus

    data class Error(val message: String? = null) : GatewayStatus
}
