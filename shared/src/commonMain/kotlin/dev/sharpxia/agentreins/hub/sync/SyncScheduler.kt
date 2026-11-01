package dev.sharpxia.agentreins.hub.sync

/**
 * 恢复触发 / 心跳巡检 / R4 周期校准 / 退化轮询（05 §3.3；P2 实现）。
 *
 * - R4 校准：每 5 分钟或每 500 条推送，先到为准
 * - 退化轮询（R5）：WSS 不可达时兜底，间隔采纳 sync.ready.pollIntervalMs（注册默认 30000，advisory）
 * - 断线恢复统一流程（R3/R6）：刷 Token → WSS sync.resume → HTTP 增量至追平 → 队列未终态命令幂等重试
 */
interface SyncScheduler {
    fun start()

    fun stop()

    /** 回前台 / 网络切换 / 重连等恢复场景统一入口，reason 仅入日志。 */
    fun triggerRecovery(reason: String)
}
