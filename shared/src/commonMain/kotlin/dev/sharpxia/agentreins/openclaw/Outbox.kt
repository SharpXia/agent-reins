package dev.sharpxia.agentreins.openclaw

/**
 * 发送队列 outbox（A4 文本简化版）——纯状态机，单测不依赖 DB。
 *
 * 精髓（照抄官方语义）：
 * ① 发送「结果不明」（超时/断线/异常）→ 保持 PENDING 不置失败（消息不静默丢失）；
 * ② 补发复用同一 idempotencyKey（sessions.send 幂等）；
 * ③ 只有该 turn 出现在已保存 history 里才退休——不靠 ack 单方面判定成功；
 * ④ 每网关限额 50 条/48h（官方 50 条/48MB/48h 的文本简化）。
 */
object Outbox {

    /** 队列条目（DB 行的内存态）。 */
    data class Command(
        val idempotencyKey: String,
        val sessionKey: String,
        val text: String,
        val status: Status = Status.PENDING,
        val retryCount: Int = 0,
        val lastError: String? = null,
        val createdAtMs: Long,
    ) {
        enum class Status { PENDING, SENT }
    }

    /** 限额：50 条 / 48 小时（按入队时间窗口计数）。 */
    const val LIMIT_COUNT = 50
    const val LIMIT_WINDOW_MS = 48 * 60 * 60 * 1_000L

    /** idempotencyKey 生成（带随机后缀防碰撞）。 */
    fun newKey(nowMs: Long, random: String): String = "out-$nowMs-$random"

    /**
     * 入队前校验：同窗口内已有 [existing] 条目时是否允许再入队。
     * @return null = 允许；非 null = 拒绝原因（UI 显式提示，消息不静默丢弃）。
     */
    fun checkLimit(existing: List<Command>, nowMs: Long): String? {
        val windowStart = nowMs - LIMIT_WINDOW_MS
        val inWindow = existing.count { it.createdAtMs >= windowStart }
        return if (inWindow >= LIMIT_COUNT) {
            "发送队列已满（$LIMIT_COUNT 条/48小时），请稍后再试"
        } else {
            null
        }
    }

    /**
     * 发送结果归并。成功受理（拿到 runId）→ SENT；结果不明/失败 → PENDING + 重试计数
     * 与错误记录（[error]）。retire 不在此处——见 [shouldRetire]。
     */
    fun afterSendAttempt(cmd: Command, ok: Boolean, error: String?): Command = when {
        ok -> cmd.copy(status = Command.Status.SENT, lastError = null)
        else -> cmd.copy(
            status = Command.Status.PENDING,
            retryCount = cmd.retryCount + 1,
            lastError = error?.take(200),
        )
    }

    /**
     * 退休判定：chat.history 里该 user 消息的 idempotencyKey 以本命令的 key 开头
     * （实测报文 `<idempotencyKey>:user`，见 protocol-notes §5）→ 该 turn 已落 transcript。
     */
    fun shouldRetire(cmd: Command, historyIdempotencyKeys: Collection<String>): Boolean =
        historyIdempotencyKeys.any { it == cmd.idempotencyKey || it.startsWith("${cmd.idempotencyKey}:") }

    /** 重连补发顺序：PENDING 优先于 SENT（未发出的先走），同状态按入队先后（FIFO）。 */
    fun flushOrder(cmds: List<Command>): List<Command> = cmds
        .filter { it.status == Command.Status.PENDING || it.status == Command.Status.SENT }
        .sortedWith(compareBy({ if (it.status == Command.Status.PENDING) 0 else 1 }, { it.createdAtMs }, { it.idempotencyKey }))

    /**
     * 一轮 flush 应尝试的命令：flushOrder 过滤掉本轮已尝试过的幂等键（M2.6 尾随窗口
     * 修复的核心语义——flush 进行中新入队的命令下一轮被捞到立即补发；已失败的条目
     * 不再原地自旋，等下次 send/reconnect 触发新一轮）。
     */
    fun pendingForRound(all: List<Command>, attempted: Set<String>): List<Command> =
        flushOrder(all).filter { it.idempotencyKey !in attempted }
}
