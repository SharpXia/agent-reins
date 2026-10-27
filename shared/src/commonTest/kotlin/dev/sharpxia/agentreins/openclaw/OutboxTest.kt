package dev.sharpxia.agentreins.openclaw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 发送队列状态机（A4 文本简化版）语义测试。 */
class OutboxTest {

    private fun cmd(
        key: String = "out-1",
        status: Outbox.Command.Status = Outbox.Command.Status.PENDING,
        createdAt: Long = 1_000L,
        retryCount: Int = 0,
    ) = Outbox.Command(
        idempotencyKey = key,
        sessionKey = "agent:main:main",
        text = "hello",
        status = status,
        createdAtMs = createdAt,
        retryCount = retryCount,
    )

    // ---- 限额（50 条 / 48h） ----

    @Test
    fun limitAllowsBelowThreshold() {
        val existing = (1 until Outbox.LIMIT_COUNT).map { cmd(key = "k$it") }
        assertNull(Outbox.checkLimit(existing, nowMs = 10_000L))
    }

    @Test
    fun limitBlocksAtThreshold() {
        val existing = (1..Outbox.LIMIT_COUNT).map { cmd(key = "k$it", createdAt = 5_000L) }
        val reason = Outbox.checkLimit(existing, nowMs = 10_000L)
        assertTrue(reason != null && reason.contains("50"))
    }

    @Test
    fun limitWindowExpires() {
        // 48h 前的条目不计入窗口
        val existing = (1..Outbox.LIMIT_COUNT).map {
            cmd(key = "k$it", createdAt = 10_000L - Outbox.LIMIT_WINDOW_MS - 1)
        }
        assertNull(Outbox.checkLimit(existing, nowMs = 10_000L))
    }

    // ---- 发送结果归并 ----

    @Test
    fun successMarksSent() {
        val after = Outbox.afterSendAttempt(cmd(), ok = true, error = null)
        assertEquals(Outbox.Command.Status.SENT, after.status)
        assertNull(after.lastError)
    }

    @Test
    fun failureStaysPendingAndCountsRetry() {
        // 精髓①：结果不明不置失败，保持 PENDING（消息不静默丢失）
        val after = Outbox.afterSendAttempt(cmd(), ok = false, error = "timeout")
        assertEquals(Outbox.Command.Status.PENDING, after.status)
        assertEquals(1, after.retryCount)
        assertEquals("timeout", after.lastError)
    }

    // ---- 退休判定（history 出现才退休） ----

    @Test
    fun retireOnExactKey() {
        assertTrue(Outbox.shouldRetire(cmd(key = "out-9"), listOf("out-9")))
    }

    @Test
    fun retireOnSuffixedKey() {
        // 实测报文 idempotencyKey 形态 "<key>:user"
        assertTrue(Outbox.shouldRetire(cmd(key = "out-9"), listOf("out-9:user", "other:user")))
    }

    @Test
    fun noRetireOnUnrelatedKeys() {
        assertFalse(Outbox.shouldRetire(cmd(key = "out-9"), listOf("out-90:user", "out-9x:user")))
        assertFalse(Outbox.shouldRetire(cmd(key = "out-9"), emptyList()))
    }

    // ---- 补发顺序 ----

    @Test
    fun flushPutsPendingBeforeSentFifo() {
        val list = listOf(
            cmd(key = "a", status = Outbox.Command.Status.SENT, createdAt = 100),
            cmd(key = "b", status = Outbox.Command.Status.PENDING, createdAt = 300),
            cmd(key = "c", status = Outbox.Command.Status.PENDING, createdAt = 200),
            cmd(key = "d", status = Outbox.Command.Status.PENDING, createdAt = 200), // 同刻按键序稳定
        )
        val order = Outbox.flushOrder(list).map { it.idempotencyKey }
        assertEquals(listOf("c", "d", "b", "a"), order)
    }

    // ---- 尾随窗口（M2.6 P3 修复语义） ----

    @Test
    fun pendingForRoundExcludesAlreadyAttempted() {
        val list = listOf(cmd(key = "a"), cmd(key = "b"), cmd(key = "c", status = Outbox.Command.Status.SENT))
        val round = Outbox.pendingForRound(list, attempted = setOf("a"))
        assertEquals(listOf("b", "c"), round.map { it.idempotencyKey })
    }

    @Test
    fun pendingForRoundPicksUpTailArrivalsNextRound() {
        // 模拟：flush 进行中（a/b 已尝试）新入队 d → 下一轮 reload 捞到 d 立即补发
        val midFlush = listOf(cmd(key = "a"), cmd(key = "b"))
        val afterTailArrival = midFlush + cmd(key = "d", createdAt = 900)
        val attempted = setOf("a", "b")
        val secondRound = Outbox.pendingForRound(afterTailArrival, attempted)
        assertEquals(listOf("d"), secondRound.map { it.idempotencyKey })
    }

    @Test
    fun pendingForRoundStopsWhenAllAttempted() {
        val list = listOf(cmd(key = "a"), cmd(key = "b"))
        assertTrue(Outbox.pendingForRound(list, attempted = setOf("a", "b")).isEmpty())
    }
}
