package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiConversation
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * wp-7 新建会话入口判定纯函数单测（desktopApp jvmTest 回归锚，SlashCommandRulesTest 同纪律）。
 * hasMainCard 门语义复刻 iOS HubStore.swift:162-175（hasMainCard / entryKey / createConversation
 * 守卫）：置灰（已有主线卡）优先于已提交/提交中瞬态；键 = gatewayId|agentId（同 agent 跨网关
 * 互不影响）；回执零解析——「已提交」瞬态只等校准成卡翻置灰，不本地发明卡片。
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

    // ── hasMainCard 门：置灰终态 ──

    @Test
    fun cardedAgentRowGrays() {
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        assertEquals(
            NewConversationRowState.GRAYED,
            newConversationRowState(conversations, "gw1", "agent-a", emptySet(), null),
        )
    }

    @Test
    fun grayedDominatesSubmittedAndCreatingTransients() {
        // iOS if hasMainCard 先判：成卡后瞬态一律被主线卡判据接管
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        val key = conversationEntryKey("gw1", "agent-a")
        assertEquals(
            NewConversationRowState.GRAYED,
            newConversationRowState(conversations, "gw1", "agent-a", setOf(key), key),
        )
    }

    @Test
    fun sameAgentAcrossGatewaysIsIndependent() {
        // 键含 gatewayId：gw1 已成卡不影响 gw2 同 agent 行可新建
        val conversations = listOf(conv("c1", "gw1", "agent-a"))
        assertEquals(
            NewConversationRowState.IDLE,
            newConversationRowState(conversations, "gw2", "agent-a", emptySet(), null),
        )
    }

    @Test
    fun differentAgentOnSameGatewayIsNotGrayed() {
        val conversations = listOf(conv("c1", "gw1", "agent-b"))
        assertEquals(
            NewConversationRowState.IDLE,
            newConversationRowState(conversations, "gw1", "agent-a", emptySet(), null),
        )
    }

    // ── 提交中/已提交瞬态（无卡期间） ──

    @Test
    fun creatingBeatsSubmittedForUncaredAgent() {
        val key = conversationEntryKey("gw1", "agent-a")
        assertEquals(
            NewConversationRowState.CREATING,
            newConversationRowState(emptyList(), "gw1", "agent-a", setOf(key), key),
        )
    }

    @Test
    fun submittedShowsWaitingStateWhenNoCardYet() {
        val key = conversationEntryKey("gw1", "agent-a")
        assertEquals(
            NewConversationRowState.SUBMITTED,
            newConversationRowState(emptyList(), "gw1", "agent-a", setOf(key), null),
        )
    }

    @Test
    fun unrelatedTransientsLeaveRowIdle() {
        assertEquals(
            NewConversationRowState.IDLE,
            newConversationRowState(emptyList(), "gw1", "agent-a", setOf("gw1|agent-b"), "gw1|agent-b"),
        )
    }
}
