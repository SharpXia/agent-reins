package dev.sharpxia.agentreins.openclaw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 事件 seq 缺口检测（A10 简化）测试。 */
class SeqTrackerTest {

    @Test
    fun consecutiveSeqsReportNoGap() {
        val t = SeqTracker()
        assertEquals(0L, t.observe("s1", 10))
        assertEquals(0L, t.observe("s1", 11))
        assertEquals(0L, t.observe("s1", 12))
    }

    @Test
    fun gapReportsMissingCount() {
        val t = SeqTracker()
        assertEquals(0L, t.observe("s1", 10))
        assertEquals(3L, t.observe("s1", 14)) // 11/12/13 丢失
    }

    @Test
    fun firstFrameOfSessionNeverGaps() {
        val t = SeqTracker()
        assertEquals(0L, t.observe("s1", 42_000))
    }

    @Test
    fun duplicateAndOutOfOrderFramesDoNotGap() {
        // 快照式重放会重复推同一帧
        val t = SeqTracker()
        assertEquals(0L, t.observe("s1", 10))
        assertEquals(0L, t.observe("s1", 10))
        assertEquals(0L, t.observe("s1", 9))
        // 水位线不被回退帧拉低：后续正常帧不得误判缺口
        assertEquals(0L, t.observe("s1", 11))
        assertEquals(11L, t.lastSeqOf("s1"))
    }

    @Test
    fun nullAndNonPositiveSeqIgnored() {
        val t = SeqTracker()
        assertEquals(0L, t.observe("s1", null))
        assertEquals(0L, t.observe("s1", 0))
        assertEquals(0L, t.observe("s1", -5))
    }

    @Test
    fun sessionsAreIndependent() {
        val t = SeqTracker()
        assertEquals(0L, t.observe("s1", 100))
        assertEquals(0L, t.observe("s2", 5))
        assertEquals(0L, t.observe("s2", 6))
        assertEquals(2L, t.observe("s2", 9)) // 只看 s2 自己的序
    }

    @Test
    fun resetClearsForNewConnection() {
        val t = SeqTracker()
        assertEquals(0L, t.observe("s1", 100))
        t.reset() // 重连后 seq 空间归零
        assertEquals(0L, t.observe("s1", 1))
        assertTrue(t.lastSeqOf("s1") == 1L)
    }
}
