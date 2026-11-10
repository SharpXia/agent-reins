package dev.sharpxia.agentreins.desktopui

import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 多网关轮（v1.3.0）桌面 agent 分组偏好存储单测（desktopTest）：java.util.prefs 读写往返
 * （跨实例 = 重启后回落语义）+ 组员资格二元组纪律 + 改名不动成员/解散成员回未分组 + 覆盖换组
 * + 未知未来版本档只读不写。用独立 probe 节点（随机 UUID）隔离，finally removeNode 清理，
 * 不污染用户真实偏好（DesktopMutePreferencesTest 同款纪律）。
 */
class DesktopAgentGroupStoreTest {

    private fun probeNode(): Preferences =
        Preferences.userRoot().node("agentreins-test/agent-groups-probe/${UUID.randomUUID()}")

    @Test
    fun createAssignRoundTripsAcrossInstances() {
        val node = probeNode()
        try {
            val first = DesktopAgentGroupStore(node)
            assertEquals(emptyList(), first.groups, "初始空档")
            assertNull(first.groupIdOf("gw-a", "a1"), "初始未分组")

            val gid = first.createGroup("工作")
            first.assign("gw-a", "a1", gid)
            assertEquals(listOf("工作"), first.groups.map { it.name })
            assertEquals(gid, first.groupIdOf("gw-a", "a1"))

            // 新实例读同一节点 = 重启后回落语义（跨登出保留，投影库外偏好）
            val second = DesktopAgentGroupStore(node)
            assertEquals(listOf("工作"), second.groups.map { it.name })
            assertEquals(gid, second.groupIdOf("gw-a", "a1"))
            assertTrue(second.groups.single().id == gid, "组 id 稳定（UUID），改名不动成员的根据")
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun renameKeepsMembersAndDissolveReturnsThemToUngrouped() {
        val node = probeNode()
        try {
            val store = DesktopAgentGroupStore(node)
            val gid = store.createGroup("工作")
            store.assign("gw-a", "a1", gid)

            store.renameGroup(gid, "重命名后")
            assertEquals("重命名后", store.groups.single().name)
            assertEquals(gid, store.groupIdOf("gw-a", "a1"), "改名不动成员")

            store.dissolveGroup(gid)
            assertTrue(store.groups.isEmpty())
            assertNull(store.groupIdOf("gw-a", "a1"), "解散 = 成员回「未分组」，不级联删投影")
            // 解散结果持久化可见
            assertTrue(DesktopAgentGroupStore(node).groups.isEmpty())
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun assignSamePairAgainOverwritesGroupMembership() {
        val node = probeNode()
        try {
            val store = DesktopAgentGroupStore(node)
            val g1 = store.createGroup("一")
            val g2 = store.createGroup("二")
            store.assign("gw-a", "a1", g1)
            store.assign("gw-a", "a1", g2)
            assertEquals(g2, store.groupIdOf("gw-a", "a1"), "重复加入同一二元组 = 覆盖换组")
            assertEquals(1, store.members.size, "不产生重复组员行")

            // 二元组键纪律：同 agentId 跨网关互不影响
            store.assign("gw-b", "a1", g1)
            assertEquals(g2, store.groupIdOf("gw-a", "a1"))
            assertEquals(g1, store.groupIdOf("gw-b", "a1"))

            store.removeMember("gw-b", "a1")
            assertNull(store.groupIdOf("gw-b", "a1"))
            assertEquals(g2, store.groupIdOf("gw-a", "a1"))

            store.removeMember("gw-c", "zz") // 未分组成员为 no-op
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun nullNodeDegradesToMemoryOnlyWithoutThrowing() {
        val store = DesktopAgentGroupStore(node = null)
        val gid = store.createGroup("内存组")
        store.assign("gw", "a", gid)
        assertEquals(listOf("内存组"), store.groups.map { it.name })
        assertEquals(gid, store.groupIdOf("gw", "a"))
        store.renameGroup(gid, "改名")
        store.dissolveGroup(gid)
        assertTrue(store.groups.isEmpty() && store.members.isEmpty())
    }

    @Test
    fun unknownFutureVersionArchiveIsReadOnly() {
        val node = probeNode()
        try {
            // 预埋一个未来版本档（version=2）
            node.put(AGENT_GROUPS_STORAGE_KEY, """{"version":2,"groups":[],"members":[]}""")

            val store = DesktopAgentGroupStore(node)
            assertEquals(emptyList(), store.groups, "未知版本整档不读")
            store.createGroup("新组") // mutate 应被只读闸挡在持久化之外
            assertEquals(listOf("新组"), store.groups.map { it.name }, "进程内内存态仍生效")
            assertEquals(
                """{"version":2,"groups":[],"members":[]}""",
                node.get(AGENT_GROUPS_STORAGE_KEY, null),
                "存档未被回写（防旧 schema 覆盖新数据）",
            )
        } finally {
            node.removeNode()
        }
    }

    @Test
    fun corruptArchiveHealsAsWritableEmptyState() {
        val node = probeNode()
        try {
            node.put(AGENT_GROUPS_STORAGE_KEY, "not a json")
            val store = DesktopAgentGroupStore(node)
            assertTrue(store.groups.isEmpty(), "坏档降级空态")
            store.createGroup("愈合组")
            val reloaded = DesktopAgentGroupStore(node)
            assertEquals(listOf("愈合组"), reloaded.groups.map { it.name }, "坏档被新档覆盖（愈合非丢失）")
        } finally {
            node.removeNode()
        }
    }
}
