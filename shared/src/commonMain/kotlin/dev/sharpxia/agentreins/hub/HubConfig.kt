package dev.sharpxia.agentreins.hub

/**
 * Hub 接入配置。
 *
 * 地址纪律（2026-11-03 修订）：真实默认地址不进仓库（本仓库公开），由宿主平台构建期注入——
 * iOS 经 gitignored `Config.local.xcconfig`（键 `HUB_PUBLIC_BASE`）→ Info.plist
 * `HubDefaultBaseURL=$(HUB_PUBLIC_BASE)` → Swift 读 Bundle 后传给桥；桌面探针走环境变量。
 * shared 只承载「调用方给的地址」，不携带域名。
 */
data class HubConfig(
    /** REST base，含 /v1 前缀。 */
    val baseUrl: String,
    /** WS 端点，含 /v1/ws 路径（升级头 Bearer 鉴权，不支持 query 传 Token）。 */
    val wsUrl: String,
)
