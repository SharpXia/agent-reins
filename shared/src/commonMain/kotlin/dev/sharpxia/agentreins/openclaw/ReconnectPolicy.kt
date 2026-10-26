package dev.sharpxia.agentreins.openclaw

import kotlin.math.pow

/**
 * 重连退避策略（纯函数，A11）：指数退避 + 服务器错误帧 retryAfterMs 驱动 + pauseReconnect 尊重。
 *
 * 设计对齐官方 App：连接意外断开后按 attempt 指数退避重试（1s → 2s → 4s → … 封顶 60s）；
 * 服务器在 error 帧里明确给出 retryable/retryAfterMs 时优先用服务器指令；
 * `pauseReconnect`（hello/shutdown 携带，网关维护窗口）为 true 时停止自动重连。
 */
object ReconnectPolicy {

    /** 退避基数（首次断线后等待）。 */
    const val BASE_DELAY_MS = 1_000L
    /** 退避封顶。 */
    const val MAX_DELAY_MS = 60_000L
    const val FACTOR = 2.0

    /** 一次重连决策：等多久后重试；stop=true 表示不再自动重连。 */
    data class Decision(val delayMs: Long?, val stop: Boolean) {
        companion object {
            val STOP = Decision(null, true)
        }
    }

    /**
     * 计算第 [attempt] 次重试（从 1 起）的退避。
     * [retryAfterMs]：服务器 error 帧显式指令（>0 时优先）；[pauseReconnect]：网关维护标志。
     */
    fun decisionFor(attempt: Int, retryAfterMs: Long? = null, pauseReconnect: Boolean = false): Decision {
        if (pauseReconnect) return Decision.STOP
        if (attempt < 1) return Decision(BASE_DELAY_MS, false)
        retryAfterMs?.takeIf { it > 0 }?.let { return Decision(it, false) }
        val backoff = BASE_DELAY_MS * FACTOR.pow((attempt - 1).toDouble()).toLong()
        return Decision(minOf(backoff, MAX_DELAY_MS), false)
    }

    /** 成功连接即重置（回到 attempt=1 的基数）。 */
    const val RESET_ATTEMPT = 0
}
