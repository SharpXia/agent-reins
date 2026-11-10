package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiAgentRef
import dev.sharpxia.agentreins.hub.UiGateway
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 多网关轮（v1.3.0）桌面 agent 分组纯规则层回归锚（设计稿 multi-gateway-design-20261110 r3
 * §4.2 数据模型 / §3.2 网关显示名 / §4.3 三段式呈现规则）：§4.2 JSON 形状与往返、坏档降级链、
 * 未知版本只读、组员键 = (gatewayId, agentId) 二元组、显示名空名回退、组员资格与目录取交集。
 */
class AgentGroupRulesTest {

    // ── §4.2 JSON 形状与往返 ──────────────────────────────────────────────

    @Test
    fun serializeEmitsExactSchema42Shape() {
        val json = agentGroupsSerialize(
            AgentGroupsState(
                groups = listOf(AgentGroupEntry("g-1", "工作", "2026-11-11T10:00:00+08:00")),
                members = listOf(AgentGroupMemberEntry("01GWULID", "main", "g-1")),
            ),
        )
        // 键形状与 iOS AgentGroupsBlob Codable 输出同形（§4.2 字面键名，无别名）
        assertTrue(json.contains("\"version\":1"), "version 键：$json")
        assertTrue(json.contains("\"groups\""), "groups 键：$json")
        assertTrue(json.contains("\"members\""), "members 键：$json")
        assertTrue(json.contains("\"id\":\"g-1\""), "groups.id：$json")
        assertTrue(json.contains("\"name\":\"工作\""), "groups.name：$json")
        assertTrue(json.contains("\"createdAt\""), "groups.createdAt：$json")
        assertTrue(json.contains("\"gatewayId\":\"01GWULID\""), "members.gatewayId：$json")
        assertTrue(json.contains("\"agentId\":\"main\""), "members.agentId：$json")
        assertTrue(json.contains("\"groupId\":\"g-1\""), "members.groupId：$json")
    }

    @Test
    fun parseSerializeRoundTrips() {
        val state = AgentGroupsState(
            groups = listOf(
                AgentGroupEntry("g-1", "工作", "2026-11-11T10:00:00+08:00"),
                AgentGroupEntry("g-2", "生活", "2026-11-11T11:00:00+08:00"),
            ),
            members = listOf(
                AgentGroupMemberEntry("gw-a", "agent-1", "g-1"),
                AgentGroupMemberEntry("gw-b", "agent-1", "g-2"), // 跨网关同名 agentId 是不同实体
            ),
        )
        val parsed = agentGroupsParse(agentGroupsSerialize(state))
        assertTrue(parsed.writable, "version=1 档可写")
        assertEquals(state, parsed.state, "往返无损（含跨网关同名 agentId 的两行组员）")
    }

    @Test
    fun parseDegradesCorruptArchiveToEmptyWritable() {
        for (bad in listOf("not json", "[1,2]", "{\"version\":1,\"groups\":", "")) {
            val parsed = agentGroupsParse(bad.ifBlank { null })
            assertTrue(parsed.writable, "坏档/缺档 = 可写空态（覆盖坏档是愈合）：$bad")
            assertTrue(parsed.state.groups.isEmpty() && parsed.state.members.isEmpty(), "$bad")
        }
    }

    @Test
    fun parseRejectsUnknownFutureVersionAsReadOnly() {
        val parsed = agentGroupsParse("""{"version":2,"groups":[],"members":[]}""")
        assertFalse(parsed.writable, "未知未来版本整档只读，防旧端写坏新数据")
        assertTrue(parsed.state.groups.isEmpty() && parsed.state.members.isEmpty())
    }

    @Test
    fun parseSkipsMalformedItemRowsButKeepsRest() {
        val parsed = agentGroupsParse(
            """
            {"version":1,
             "groups":[{"id":"g-1","name":"工作"},{"name":"缺id坏行"},[1]],
             "members":[{"gatewayId":"gw","agentId":"a","groupId":"g-1"},{"agentId":"缺两键"}]}
            """.trimIndent(),
        )
        assertTrue(parsed.writable)
        assertEquals(listOf("g-1"), parsed.state.groups.map { it.id }, "单条坏行跳过不炸整档")
        assertEquals(listOf("gw"), parsed.state.members.map { it.gatewayId })
    }

    // ── 组员键纪律：二元组，跨网关同名 agentId 互不影响 ──────────────────────

    @Test
    fun pairKeyUsesPipeSeparatorWithBothParts() {
        assertEquals("gw-01|agent-main", agentGroupsPairKey("gw-01", "agent-main"))
        // 同名 agentId 跨网关 = 不同键（agents_native_uq 只在网关内唯一，ADR-14）
        assertTrue(agentGroupsPairKey("gw-a", "x") != agentGroupsPairKey("gw-b", "x"))
    }

    @Test
    fun membershipIsPerGatewayAgentPair() {
        val state = AgentGroupsState(
            groups = listOf(AgentGroupEntry("g-1", "工作", "t")),
            members = listOf(AgentGroupMemberEntry("gw-a", "x", "g-1")),
        )
        assertEquals("g-1", agentGroupMembership(state, "gw-a", "x"))
        assertNull(agentGroupMembership(state, "gw-b", "x"), "跨网关同名 agentId 未分组")
        assertNull(agentGroupMembership(state, "gw-a", "y"))
    }

    // ── 网关显示名（§3.2 会话行 caption 数据面） ─────────────────────────────

    @Test
    fun gatewayDisplayNameFallsBackToIdSuffix8OnBlankOrUnknown() {
        assertEquals("工作网关", gatewayDisplayName("工作网关", "01GWULID00"))
        // 空名回退 gatewayId 尾段（末 8 位，iOS suffix(8) 同构）
        assertEquals("TAIL8ID9", gatewayDisplayName("", "01GWULIDTAIL8ID9"))
        assertEquals("TAIL8ID9", gatewayDisplayName("   ", "01GWULIDTAIL8ID9"))
        // 未知 gatewayId（目录缺行）同口径
        assertEquals("TAIL8ID9", gatewayNameFor(emptyList(), "01GWULIDTAIL8ID9"))
    }

    @Test
    fun gatewayNameForResolvesByNameLookup() {
        val gateways = listOf(UiGateway("gw-1", "厨房", true, null, emptyList()))
        assertEquals("厨房", gatewayNameFor(gateways, "gw-1"))
        assertEquals("w-2TAIL9", gatewayNameFor(gateways, "gw-2TAIL9"), "未知 id 回尾段")
    }

    // ── 三段式视图：组员资格与目录取交集 ────────────────────────────────────

    @Test
    fun viewIntersectsWithDirectoryAndKeepsCrossGatewayGroups() {
        val state = AgentGroupsState(
            groups = listOf(
                AgentGroupEntry("g-1", "工作", "t"),
                AgentGroupEntry("g-2", "生活", "t"),
            ),
            members = listOf(
                AgentGroupMemberEntry("gw-a", "a1", "g-1"),
                AgentGroupMemberEntry("gw-b", "b1", "g-1"), // 跨网关混组（一等能力）
                AgentGroupMemberEntry("gw-a", "ghost", "g-2"), // 已下线 agent（目录无行）
                AgentGroupMemberEntry("gw-a", "orphan", "g-missing"), // 指向已解散组（孤儿资格）
            ),
        )
        val gateways = listOf(
            UiGateway("gw-a", "A", true, null, listOf(UiAgentRef("a1", "助手一"), UiAgentRef("a2", "助手二"))),
            UiGateway("gw-b", "B", false, null, listOf(UiAgentRef("b1", "助手三"))),
        )
        val view = agentGroupsView(gateways, state)
        // 组按存储序；组内 = 组员资格 ∩ 目录
        assertEquals(2, view.grouped.size)
        assertEquals(
            listOf("a1" to "gw-a", "b1" to "gw-b"),
            view.grouped[0].second.map { it.agent.agentId to it.gateway.gatewayId },
            "g-1 含跨网关两行",
        )
        assertTrue(view.grouped[1].second.isEmpty(), "ghost 成员（目录无行）不渲染，不编造")
        // 未分组 = 目录内组员资格为 nil 的行；孤儿资格（组已不存在）不计入任一段
        assertEquals(
            listOf("a2"),
            view.ungrouped.map { it.agent.agentId },
        )
    }

    @Test
    fun viewEmptyDirectoryYieldsEmptySections() {
        val view = agentGroupsView(
            emptyList(),
            AgentGroupsState(listOf(AgentGroupEntry("g-1", "工作", "t")), emptyList()),
        )
        assertEquals(1, view.grouped.size)
        assertTrue(view.grouped[0].second.isEmpty())
        assertTrue(view.ungrouped.isEmpty())
    }
}
