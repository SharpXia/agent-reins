package dev.sharpxia.agentreins.openclaw

/**
 * 事件 seq 单调序号缺口检测（纯函数，A10 简化版）。
 *
 * 协议事实（protocol-notes §2/§6）：operator 广播的 agent 事件带全局单调 seq；
 * WS 断线期间的帧不会回放——重连后 seq 归零重启。缺口 = 丢帧风险 → 该会话
 * schedule history 回填（与 run 终局回填同一机制）。
 *
 * per-session 记 lastSeq；重连（[reset]）后首帧不判缺口。
 */
class SeqTracker {

    private val lastSeqBySession = mutableMapOf<String, Long>()

    /**
     * 喂入一个会话的下一帧 seq。
     * @return 缺口丢失的帧数（>0 应触发 history 回填）；0 = 连续。
     * 回退/重复帧（seq ≤ last，快照式重放常见）不算缺口且**不拉低水位线**——
     * 否则后续正常帧会被误判缺口。
     */
    fun observe(sessionKey: String, seq: Long?): Long {
        if (seq == null || seq <= 0) return 0
        val last = lastSeqBySession[sessionKey]
        if (last != null && seq <= last) return 0
        lastSeqBySession[sessionKey] = seq
        return if (last == null) 0 else seq - last - 1
    }

    /** 新连接开始（seq 空间归零），清空全部跟踪。 */
    fun reset() = lastSeqBySession.clear()

    fun lastSeqOf(sessionKey: String): Long? = lastSeqBySession[sessionKey]
}
