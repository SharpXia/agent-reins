package dev.sharpxia.agentreins.hub

import kotlinx.serialization.Serializable

/** Hub 统一错误形状（08-api-contracts.md §1.2）：客户端按 code+details 分支，message 仅人读。 */
@Serializable
data class HubErrorPayload(
    val code: String,
    val message: String,
    val details: String? = null,
    val retryable: Boolean? = null,
    val retryAfterMs: Long? = null,
)

/**
 * 20 条错误码（08 §7 唯一注册清单镜像，v1.0.0 冻结；新错误码只增不改义）。
 * HUB-AUTH-005 / HUB-GW-001 / HUB-GW-003 属 Bridge↔Hub 面，前端不会遇到，仍列出保持镜像完整。
 * 关键分支（P1/P2 落实）：AUTH-002 刷新后重试一次；AUTH-003 清凭证重登；AUTH-004 清全部本地数据停重试；
 * AUTH-006 先完成设备注册；BIZ-001 幂等键冲突禁止换键重试；SYNC-001 快照重建；GW-004 唯一允许换键重发。
 */
enum class HubErrorCode(val wire: String, val http: Int?) {
    AUTH_001("HUB-AUTH-001", 401),
    AUTH_002("HUB-AUTH-002", 401),
    AUTH_003("HUB-AUTH-003", 401),
    AUTH_004("HUB-AUTH-004", 403),
    AUTH_005("HUB-AUTH-005", 401),
    AUTH_006("HUB-AUTH-006", 403),
    BIZ_001("HUB-BIZ-001", 409),
    BIZ_002("HUB-BIZ-002", 404),
    BIZ_003("HUB-BIZ-003", 429),
    BIZ_004("HUB-BIZ-004", 400),
    BIZ_005("HUB-BIZ-005", 404),
    GW_001("HUB-GW-001", 409),
    GW_002("HUB-GW-002", 503),
    GW_003("HUB-GW-003", 503),
    /** 投递截止：仅出现在 command.failed 事件载荷，非 HTTP 状态码。 */
    GW_004("HUB-GW-004", null),
    SYNC_001("HUB-SYNC-001", 410),
    SYNC_002("HUB-SYNC-002", 409),
    /** 仅 WSS error 帧，下发后服务端关闭连接。 */
    SYNC_003("HUB-SYNC-003", null),
    SYS_001("HUB-SYS-001", 500),
    SYS_002("HUB-SYS-002", 503),
    ;

    companion object {
        private val byWire = entries.associateBy(HubErrorCode::wire)
        fun fromWire(code: String): HubErrorCode? = byWire[code]
    }
}
