package dev.sharpxia.agentreins.hub

import kotlin.concurrent.atomics.AtomicReference

/**
 * wp-4 修法二（发起方）：run 终态「本端发起」本地判定登记表（内存态，随 [DefaultHubClient.start]
 * 重建，登出 wipeLocalData 重置）。
 *
 * 背景：run.started/终态事件按账号事件流全量下发到每台设备（信封无发起方字段——
 * hub-wss-frames-v1.schema.json DataRunStarted 只有 runId/agentId），他端/dashboard 触发的
 * run 终态此前也在本端弹「运行完成」。客户端唯一可靠的「本端发起」证据 = **本设备命令提交面**：
 * 只经 message.send（会话模型中 run 由用户消息发起的主通道）登记会话；conversation.compact
 * 明确登记为静默通道（compact 触发的 run 终态不提醒，wp-4 scope）——read.mark/rename/create
 * 与 run 无关不登记，run.cancel/approval.decide 不发起 run 不登记（中止面自致结果本就不提醒；
 * 未读增量仍由会话列表承载）。ADR-14：conversationId 是 Hub REST 句柄，登记与判定都不解析
 * sessionKey 内部结构。
 *
 * 判定链（两端协调器同口径消费）：
 * 1. 命令提交面（DefaultCommandClient enqueueAndSubmit / compactConversation）→
 *    [markSubmission]：记录该会话最近一次本端动作（SEND 准入 / COMPACT 静默覆盖）。SEND 只在
 *    submit 取得 PERSISTED 回执后登记（回归修复 2026-11-06：没发出的消息不开准入；compact
 *    维持提交前登记——静默通道宁默勿响）。
 * 2. eventTap 透传 run.started（DefaultHubClient 内建喂帧，解析 (gatewayId, sessionKey) →
 *    conversationId，**只喂 twoHop==0 自学习行**——两跳连结行（同 agent 的 TG/channel/任务键）
 *    与未映射键都不喂，回归修复 2026-11-06：SEND 登记是会话级，若两跳行也解析出主线卡，主线卡
 *    的 SEND 标记会把 channel 会话的 run 误判本端）→ [noteRunStarted]：会话准入（最近动作为
 *    SEND）即把该 run 标记为本端发起。
 * 3. run 终态（completed/failed）→ [isRunLocal]：run 键标记存在 = 本端发起（消费即清除，
 *    防陈旧标记跨 run 复用）；无记录（他端发起 / compact 触发 / run.started 丢失面）= 抑制。
 *
 * 已知残余（事件契约无发起方字段，客户端不可判，登记在案）：同会话、同自学习主键下**他设备**
 * message.send 触发的 run 仍会被判本端（本端此前 SEND 过即有会话级标记）——收口需事件面加
 * 发起方字段（服务端 additive，超本批范围）。
 *
 * 线程安全：命令提交协程（Dispatchers.Default）与同步引擎 eventTap 线程并发访问，内部状态用
 * CAS 整体替换（copy-on-write 快照），读侧无锁。
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class RunOriginRegistry {

    /** 本端动作类别：SEND = 会话准入（后续 run 终态提醒）；COMPACT = 静默通道（覆盖准入）。 */
    enum class Kind { SEND, COMPACT }

    private data class State(
        /** conversationId → 最近一次本端动作（last-wins；SEND 准入、COMPACT 静默覆盖）。 */
        val origins: Map<String, Kind> = emptyMap(),
        /** run.started 时刻判为本端发起的在途 run 键（终态消费即清除，防无界增长）。 */
        val localRuns: Set<String> = emptySet(),
    )

    private val state = AtomicReference(State())

    /** CAS 整体替换（copy-on-write 快照读侧无锁；手写循环保跨版本稳定，不依赖原子扩展函数）。 */
    private inline fun mutate(transform: (State) -> State) {
        while (true) {
            val cur = state.load()
            if (state.compareAndSet(cur, transform(cur))) return
        }
    }

    /**
     * 本设备命令提交面登记（DefaultCommandClient 调用；conversationId 为 Hub REST 句柄）。
     * SEND 由调用方保证在 submit 取得 PERSISTED 回执后才调（提交失败不登记，见类注）；
     * COMPACT 为静默覆盖，提交前登记。
     */
    fun markSubmission(conversationId: String, kind: Kind) {
        if (conversationId.isEmpty()) return
        mutate { s -> State(s.origins + (conversationId to kind), s.localRuns) }
    }

    /**
     * eventTap run.started 喂帧（DefaultHubClient 内建）：[conversationId] 为信封
     * (gatewayId, sessionKey) 经 hubConvKeys 的解析成果——**喂帧侧（feedRunOrigin）已把关：
     * 只喂 twoHop==0 自学习行**，未映射/两跳行 = 不调用本方法。会话最近动作 = SEND 才标记
     * 该 run 为本端发起；否则不标记（终态即抑制）。
     */
    fun noteRunStarted(gatewayId: String, runId: String, conversationId: String?) {
        if (runId.isEmpty()) return
        val admitted = conversationId != null && state.load().origins[conversationId] == Kind.SEND
        if (!admitted) return
        val key = runKey(gatewayId, runId)
        mutate { s -> State(s.origins, s.localRuns + key) }
    }

    /**
     * run 终态消费（两端协调器同口径）：该 run 在 run.started 时刻被判定为本端发起 = true
     * （消费即清除）；无记录（他端发起 / compact 触发 / started 丢失）= false = 抑制提醒。
     */
    fun isRunLocal(gatewayId: String, runId: String?): Boolean {
        if (runId.isNullOrEmpty()) return false
        val key = runKey(gatewayId, runId)
        var consumed = false
        mutate { s ->
            if (key in s.localRuns) {
                consumed = true
                State(s.origins, s.localRuns - key)
            } else {
                s
            }
        }
        return consumed
    }

    /** 登出/重登重置（DefaultHubClient.wipeLocalData）：登记面不跨账号复用。 */
    fun reset() {
        state.store(State())
    }

    private fun runKey(gatewayId: String, runId: String): String = gatewayId + " " + runId
}
