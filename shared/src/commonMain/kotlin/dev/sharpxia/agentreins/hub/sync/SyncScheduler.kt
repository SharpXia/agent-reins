package dev.sharpxia.agentreins.hub.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * 同步连接相位（wp-10 连接状态面）：客户端 ↔ Hub 连接/同步管线的**纯观察态**，供两端 UI
 * 挂连接提示条；不参与恢复控制——同步语义与恢复流程（R3/R4/R5/R6）零变化。
 *
 * - [Linked]：WSS 已连且追平（Ready 后 R3 catchUp 成功 / R4 校准追平）——提示条唯一消失相位
 * - [Catching]：连接段在途（bootstrap 引导 / 建连 / 追平中），提示条弱提示
 * - [Reconnecting]：连接已断，HTTP 补拉 + 退避重连窗口（含 R5 轮询退化），提示条警示
 * - [Rebuilding]：HUB-SYNC-001/002 cursor 过期，全量快照重建在途，提示条警示
 * - [Superseded]：同设备新连接接管（08 §5.5），本进程停轮询等显式恢复信号，提示条警示
 */
enum class SyncPhase {
    Catching,
    Linked,
    Reconnecting,
    Rebuilding,
    Superseded,
}

/**
 * 恢复触发 / 心跳巡检 / R4 周期校准 / 退化轮询（05 §3.3；P2 实现）。
 *
 * - R4 校准：每 5 分钟或每 500 条推送，先到为准
 * - 退化轮询（R5）：WSS 不可达时兜底，间隔采纳 sync.ready.pollIntervalMs（注册默认 30000，advisory）
 * - 断线恢复统一流程（R3/R6）：刷 Token → WSS sync.resume → HTTP 增量至追平 → 队列未终态命令幂等重试
 *
 * wp-10（additive）：[phase] 只读暴露连接相位（[SyncPhase]），UI 据此挂连接提示条；
 * 断连/重连/410 重建期间可见，追平（Linked）消失。纯观察面，写入仅发生在既有恢复
 * 流程的既有分支（runLoop 循环头 / Ready 追平成功 / 断开窗口 / 重建分支 / superseded 等待）。
 */
interface SyncScheduler {
    /** 连接相位观察面（wp-10）：StateFlow 同值合流，UI collectAsState 直接订阅。 */
    val phase: StateFlow<SyncPhase>

    fun start()

    fun stop()

    /** 回前台 / 网络切换 / 重连等恢复场景统一入口，reason 仅入日志。 */
    fun triggerRecovery(reason: String)
}
