package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.projection.SessionSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v1.4.0 会话管理面板（AgentSessionsManagerDialog）纯函数单测（desktopApp jvmTest 回归锚，
 * ConversationEntryRulesTest 同纪律）：
 * ① rename 门（评审 M7，同 K2 iOS SessionManagerSheet）：#19 conversation.rename 服务端经
 *    lookupActiveConversation 只在卡行集内解析，task/channel 行必 404 HUB-BIZ-002——仅主线族
 *    行（isMainSession=true）开放重命名，非主线行只给删除；
 * ② delete 乐观移除（验收 L6）：清单行即撤且幂等——乐观删后事件级联（session.removed）再删
 *    不告警不重复（事件面幂等在 DefaultEventProjector.onSessionRemoved，shared desktopTest
 *    GatewaySurfaceTest 覆盖门面 REST 面，此处锚 UI 清单面的同款幂等语义）；
 * ③ rename 乐观翻新（PERSISTED 回执后行 label 即时翻新，幂等）；
 * ④ 网关名称预校验（#25 契约：必填非空、≤64 字符；服务端 400 HUB-BIZ-001 为权威裁决，
 *    客户端预校验只为 UX）。
 */
class SessionManagerRulesTest {

    private fun summary(
        id: String,
        label: String = "",
        isMain: Boolean = false,
        lastActivityAt: String = "2026-11-16T08:00:00.000Z",
    ) = SessionSummary(
        conversationId = id,
        label = label,
        lastActivityAt = lastActivityAt,
        isMainSession = isMain,
    )

    // ── ① rename 门（M7） ──

    @Test
    fun mainSessionRowAllowsRename() {
        assertTrue(sessionRowRenameAllowed(isMainSession = true))
    }

    @Test
    fun nonMainSessionRowDeniesRename() {
        // task/channel/subagent/cron 行（isMainSession=false）不给重命名——服务端必 404
        assertFalse(sessionRowRenameAllowed(isMainSession = false))
    }

    // ── ② delete 乐观移除（L6 幂等） ──

    @Test
    fun removeSessionRowDeletesMatchingRow() {
        val sessions = listOf(
            summary("main-1", label = "主线", isMain = true),
            summary("task-1", label = "任务", isMain = false),
        )
        val removed = removeSessionRow(sessions, "task-1")
        assertEquals(1, removed.size)
        assertEquals("main-1", removed.single().conversationId)
    }

    @Test
    fun removeSessionRowIsIdempotentOnMissingRow() {
        // 乐观删后事件级联再删（无匹配行）原样返回——双路径幂等
        val sessions = listOf(summary("main-1", label = "主线", isMain = true))
        assertEquals(sessions, removeSessionRow(sessions, "already-gone"))
    }

    // ── ③ rename 乐观翻新 ──

    @Test
    fun renameSessionRowUpdatesLabelInPlace() {
        val sessions = listOf(
            summary("main-1", label = "旧名", isMain = true),
            summary("main-2", label = "另一主线", isMain = true),
        )
        val renamed = renameSessionRow(sessions, "main-1", "新名")
        assertEquals("新名", renamed.first { it.conversationId == "main-1" }.label)
        assertEquals("另一主线", renamed.first { it.conversationId == "main-2" }.label) // 别行不受影响
        assertTrue(renamed.first { it.conversationId == "main-1" }.isMainSession) // 其余字段保持
    }

    @Test
    fun renameSessionRowIsIdempotentOnMissingRow() {
        val sessions = listOf(summary("main-1", label = "主线", isMain = true))
        assertEquals(sessions, renameSessionRow(sessions, "already-gone", "无关名"))
    }

    // ── ④ 网关名称预校验（#25） ──

    @Test
    fun gatewayNameAcceptsNonEmptyWithin64() {
        assertTrue(gatewayNameValid("客厅网关"))
        assertTrue(gatewayNameValid("  客厅网关  ")) // trim 后非空即可
        assertTrue(gatewayNameValid("g".repeat(64))) // 恰 64 上界合法
    }

    @Test
    fun gatewayNameRejectsBlankAndOverlong() {
        assertFalse(gatewayNameValid(""))
        assertFalse(gatewayNameValid("   ")) // trim 后空串 = 必填非空不满足
        assertFalse(gatewayNameValid("g".repeat(65))) // 超 64：服务端 400 HUB-BIZ-001 预防线
    }
}
