package dev.sharpxia.agentreins.hub

/**
 * Hub 接入配置。
 *
 * 地址纪律：开发期明文 HTTP/WS（TLS 属 P4 部署环节，docs/plans/hub-migration-plan.md §6），
 * 真实地址一律经运行时配置注入（.env / dev.json / 登录页输入），不写死进仓库（本仓库公开）。
 * 占位示例：REST `http://<HUB-IP>:8080/v1`、WS `ws://<HUB-IP>:8080/v1/ws`。
 */
data class HubConfig(
    /** REST base，含 /v1 前缀。 */
    val baseUrl: String,
    /** WS 端点，含 /v1/ws 路径（升级头 Bearer 鉴权，不支持 query 传 Token）。 */
    val wsUrl: String,
)
