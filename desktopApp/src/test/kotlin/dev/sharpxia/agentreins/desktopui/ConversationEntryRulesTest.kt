package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiConversation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * wp-7 新建会话入口判定纯函数单测（desktopApp jvmTest 回归锚，SlashCommandRulesTest 同纪律）。
 * 多主会话轮 v1.4.0（2026-11-16）：原 hasMainCard 置灰门拆除——入口常开，行态三态收敛
 * （SUBMITTED/CREATING/IDLE）；无卡 = 默认主线（mainSession=true adopt 不 fork）/ 有卡 =
 * 新开主线（newMainSession=true，服务端以请求幂等键确定性派生 main-<idempotencyKey>）分流由
 * [hasMainCard] 承接（iOS HubStore.hasMainCard/createConversation 同签名）；「已提交」瞬态由
 * [pruneSubmittedKeys] 成卡收敛清理（评审 H3：成卡即清瞬态——已有卡 agent 不再显示提示）；
 * 键 = gatewayId|agentId（同 agent 跨网关互不影响）；回执零解析——「已提交」瞬态只等校准成卡
 * 收敛，不本地发明卡片。
 */
class ConversationEntryRulesTest {

    private fun conv(id: String, gatewayId: String, agentId: String) = UiConversation(
        conversationId = id,
        gatewayId = gatewayId,
        label = id,
        agentId = agentId,
        preview = "",
        lastSeq = 0L,
        lastActiveAt = "",
        unread = 0,
        hasActiveRun = false,
        reconciling = false,
    )

    // ── 键形态（iOS HubStore.entryKey 同格式） ──

    @Test
    fun entryKeyIsGatewayPipeAgent() {
        assertEquals("gw1|agent-a", conversationEntryKey("gw1", "agent-a"))
    }

    // ── 主线卡判据分流（多主会话轮：置灰门降级为写路径分流判据） ──

    @Test
    fun cardedAgentHasMainCardTrue() {
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        assertTrue(hasMainCard(conversations, "gw1", "agent-a")) // 有卡 = 新开主线（newMainSession=true）
    }

    @Test
    fun cardlessAgentHasMainCardFalse() {
        assertFalse(hasMainCard(emptyList(), "gw1", "agent-a")) // 无卡 = 默认主线（mainSession=true）
    }

    @Test
    fun sameAgentAcrossGatewaysIsIndependent() {
        // 键含 gatewayId：gw1 已成卡不影响 gw2 同 agent 行判「无卡」
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        assertFalse(hasMainCard(conversations, "gw2", "agent-a"))
    }

    @Test
    fun differentAgentOnSameGatewayIsNotCarded() {
        val conversations = listOf(conv("c1", "gw1", "agent-b"))
        assertFalse(hasMainCard(conversations, "gw1", "agent-a"))
    }

    // ── 行态三态（置灰终态拆除后；瞬态只看提交中/已提交，不读卡） ──

    @Test
    fun cardedAgentRowStaysIdleWhenTransientCleared() {
        // 评审 H3 桌面口径：成卡即清瞬态（pruneSubmittedKeys）→ 已有卡 agent 行回 IDLE，
        // 不再有「已提交」提示、也不再置灰——入口常开可再建新主线
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        val pruned = pruneSubmittedKeys(setOf(conversationEntryKey("gw1", "agent-a")), conversations)
        assertEquals(
            NewConversationRowState.IDLE,
            newConversationRowState("gw1", "agent-a", pruned, null),
        )
    }

    @Test
    fun creatingBeatsSubmittedForUncaredAgent() {
        val key = conversationEntryKey("gw1", "agent-a")
        assertEquals(
            NewConversationRowState.CREATING,
            newConversationRowState("gw1", "agent-a", setOf(key), key),
        )
    }

    @Test
    fun submittedShowsWaitingStateWhenNoCardYet() {
        val key = conversationEntryKey("gw1", "agent-a")
        assertEquals(
            NewConversationRowState.SUBMITTED,
            newConversationRowState("gw1", "agent-a", setOf(key), null),
        )
    }

    @Test
    fun unrelatedTransientsLeaveRowIdle() {
        assertEquals(
            NewConversationRowState.IDLE,
            newConversationRowState("gw1", "agent-a", setOf("gw1|agent-b"), "gw1|agent-b"),
        )
    }

    // ── 成卡收敛（pruneSubmittedKeys，评审 H3：SUBMITTED 提示对已有卡 agent 不再显示） ──

    @Test
    fun pruneClearsSubmittedKeyOnceCardAppears() {
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        val submitted = setOf(conversationEntryKey("gw1", "agent-a"))
        assertTrue(pruneSubmittedKeys(submitted, conversations).isEmpty())
    }

    @Test
    fun pruneKeepsSubmittedKeyWhileCardAbsent() {
        // 无卡 agent 提交在途/待成卡期间瞬态保留
        val submitted = setOf(conversationEntryKey("gw1", "agent-a"))
        assertEquals(submitted, pruneSubmittedKeys(submitted, emptyList()))
    }

    @Test
    fun pruneOnlyClearsCardedKeys() {
        // gw1|agent-a 已成卡被清；gw2|agent-a 无卡瞬态保留（键含 gatewayId 互不影响）
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        val submitted = setOf(
            conversationEntryKey("gw1", "agent-a"),
            conversationEntryKey("gw2", "agent-a"),
        )
        assertEquals(setOf("gw2|agent-a"), pruneSubmittedKeys(submitted, conversations))
    }

    @Test
    fun pruneEmptySubmittedIsNoOp() {
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        assertEquals(emptySet(), pruneSubmittedKeys(emptySet(), conversations))
    }
}
