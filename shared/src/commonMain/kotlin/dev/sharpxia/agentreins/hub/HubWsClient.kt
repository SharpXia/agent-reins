package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import kotlinx.coroutines.flow.Flow

/** Hub→客户端的 WSS 帧（四帧之一；sync.resume 是唯一客户端→Hub 上行帧，由 [HubWsClient.connect] 内部发送）。 */
sealed interface HubServerFrame {
    /** 就绪确认：cursor 校验通过；watermark=服务端当前水位；pollIntervalMs 为退化轮询建议值（advisory，可采纳）。 */
    data class Ready(val watermark: String, val pollIntervalMs: Long? = null) : HubServerFrame

    /** 事件推送；信封与 GET /v1/sync/events 完全同构（同一 events 表行）。 */
    data class Event(val event: EventEnvelope) : HubServerFrame

    /** 错误帧；HUB-SYNC-001/002/003 下发后服务端关闭连接。 */
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean? = null,
        val retryAfterMs: Long? = null,
    ) : HubServerFrame
}

/**
 * WSS 客户端（`GET /v1/ws` 升级；Hub 自有帧协议，与 Gateway wire v4 独立。P2 实现）。
 *
 * 契约要点（08 §5）：
 * - 升级请求头 `Authorization: Bearer <accessToken>`，不支持 query 传 Token；401=AUTH-002 / 403=AUTH-004
 * - 建连后首帧 `sync.resume{protocolVersion:1, cursor}`；protocolVersion 支持集 = {1}
 * - 服务端先发 sync.ready 再推 id > cursor 的新事件；单连接按 id 升序、不静默丢帧
 * - 同一 ClientDevice 至多一条活跃 WSS（新连接认证成功即关闭旧连接，close 1000 superseded）
 * - 心跳：WS 协议层 ping/pong；客户端启用库级 ping（25–30s，Darwin 引擎支持性 P2 实测，§7-R6）
 */
interface HubWsClient {
    /**
     * 建连 + 鉴权 + 发首帧 `sync.resume{cursor}`，返回前应已收到或即将收到 sync.ready。
     * close 语义（08 §5.5）：1000 且 reason=superseded → 不重连（同设备新连接已接管）；
     * 其余一切服务端断开 → 一律走断线恢复流程重连（先建 WSS 后发 HTTP 增量）。
     */
    suspend fun connect(cursor: String)

    fun frames(): Flow<HubServerFrame>

    suspend fun close()
}
