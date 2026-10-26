package dev.sharpxia.agentreins.openclaw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 重连退避策略（A11）测试。 */
class ReconnectPolicyTest {

    @Test
    fun exponentialBackoffCapped() {
        assertEquals(1_000L, ReconnectPolicy.decisionFor(1).delayMs)
        assertEquals(2_000L, ReconnectPolicy.decisionFor(2).delayMs)
        assertEquals(4_000L, ReconnectPolicy.decisionFor(3).delayMs)
        assertEquals(8_000L, ReconnectPolicy.decisionFor(4).delayMs)
        assertTrue(ReconnectPolicy.decisionFor(10).delayMs!! <= ReconnectPolicy.MAX_DELAY_MS)
        assertEquals(ReconnectPolicy.MAX_DELAY_MS, ReconnectPolicy.decisionFor(30).delayMs)
    }

    @Test
    fun serverRetryAfterWins() {
        // 服务器指令（retryable/retryAfterMs）优先于本地指数曲线
        assertEquals(7_500L, ReconnectPolicy.decisionFor(3, retryAfterMs = 7_500L).delayMs)
        assertEquals(500L, ReconnectPolicy.decisionFor(1, retryAfterMs = 500L).delayMs)
        // 非正数指令视同未给
        assertEquals(1_000L, ReconnectPolicy.decisionFor(1, retryAfterMs = 0L).delayMs)
        assertEquals(1_000L, ReconnectPolicy.decisionFor(1, retryAfterMs = -1L).delayMs)
    }

    @Test
    fun pauseReconnectStops() {
        val d = ReconnectPolicy.decisionFor(1, pauseReconnect = true)
        assertTrue(d.stop)
        assertNull(d.delayMs)
    }

    @Test
    fun normalDecisionDoesNotStop() {
        val d = ReconnectPolicy.decisionFor(5)
        assertFalse(d.stop)
        assertTrue(d.delayMs != null && d.delayMs > 0)
    }
}
