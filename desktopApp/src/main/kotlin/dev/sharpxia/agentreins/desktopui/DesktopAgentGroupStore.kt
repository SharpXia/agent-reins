package dev.sharpxia.agentreins.desktopui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.prefs.Preferences

/**
 * 多网关轮（v1.3.0，设计稿 §4.1/§4.2/§4.3）桌面 agent 分组偏好存储（iOS HubStore.AgentGroupStore
 * 同构、java.util.prefs 承载，DesktopNotificationCoordinator/DesktopMutePreferences 同款节点纪律）：
 *
 * - **本地承载**（§4.1 承载裁定）：投影库外偏好（不进 Hub.sq——全表可整库丢弃重建，登出
 *   wipeLocalData 不触碰），跨登出/重启保留，与静音集同形态；两端各自持久、互不同步。
 * - **数据形状一处定义**（§4.2）：单 JSON blob，key = "agentGroups.v1"（编码/解码规则在
 *   [agentGroupsParse]/[agentGroupsSerialize]，:desktopApp:test 回归锚 AgentGroupRulesTest）。
 * - 响应面：groups/members 为 Compose State（内存权威）——GatewaysPanel 订阅即随写随刷，
 *   与静音偏好的「lambda 实时读」形态不同（分组是面板主呈现面，状态订阅更直接）。
 *
 * 存储纪律（DesktopMutePreferences 同面延伸）：进程内内存态为权威，持久化 best-effort——任何
 * Preferences I/O 失败静默降级为纯内存态（进程内仍生效，重启回落最近一次成功持久化的值）；
 * node = null（Preferences 体系不可用）全程内存态，不抛不崩。
 * 未知未来版本存档（version != 1）**只读不写**（AgentGroupsArchive.writable=false 挡 persist），
 * 防旧端用旧 schema 覆盖新数据；坏档/缺档 = 可写空态（覆盖坏档是愈合）。
 */
internal class DesktopAgentGroupStore(node: Preferences? = null) {

    /** 用户级偏好节点（与静音/窗口偏好同族节点；macOS = ~/Library/Preferences plist 面）。 */
    private val node: Preferences? = node ?: runCatching { Preferences.userRoot().node(NODE_NAME) }.getOrNull()

    private val lock = Any()

    /** 档面可写性（init 时一次性判定：未知未来版本整档只读；此后 mutate 静默 no-op 持久化）。 */
    private val writable: Boolean

    private var groupsState by mutableStateOf<List<AgentGroupEntry>>(emptyList())
    private var membersState by mutableStateOf<List<AgentGroupMemberEntry>>(emptyList())

    init {
        val raw = node?.let { n -> runCatching { n.get(AGENT_GROUPS_STORAGE_KEY, null) }.getOrNull() }
        val archive = agentGroupsParse(raw)
        writable = archive.writable
        groupsState = archive.state.groups
        membersState = archive.state.members
    }

    /** 当前分组清单快照（Compose State——GatewaysPanel 订阅刷新）。 */
    val groups: List<AgentGroupEntry> get() = groupsState

    /** 当前组员清单快照（canonical 形态，读侧按 (gatewayId, agentId) 索引）。 */
    val members: List<AgentGroupMemberEntry> get() = membersState

    /** 该 agent 的组 id（nil = 未分组）。 */
    fun groupIdOf(gatewayId: String, agentId: String): String? = synchronized(lock) {
        agentGroupMembership(AgentGroupsState(groupsState, membersState), gatewayId, agentId)
    }

    /** 新建组，返回组 id（稳定 UUID；改名不动成员——id 与成员资格解耦的根据）。 */
    fun createGroup(name: String): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        groupsState = groupsState + AgentGroupEntry(id, name, nowRfc3339())
        persist()
        id
    }

    /** 改名（只动 groups 行，members 原样；未知组 id 为 no-op）。 */
    fun renameGroup(id: String, name: String) = synchronized(lock) {
        groupsState = groupsState.map { if (it.id == id) it.copy(name = name) else it }
        persist()
    }

    /** 解散组（成员回「未分组」；不级联删任何投影——组只是本地展示偏好）。 */
    fun dissolveGroup(id: String) = synchronized(lock) {
        groupsState = groupsState.filterNot { it.id == id }
        membersState = membersState.filterNot { it.groupId == id }
        persist()
    }

    /** 加入分组（重复加入同一二元组 = 覆盖换组；同 agent 跨网关互不影响——键是二元组）。 */
    fun assign(gatewayId: String, agentId: String, groupId: String) = synchronized(lock) {
        val key = agentGroupsPairKey(gatewayId, agentId)
        membersState = membersState.filterNot {
            agentGroupsPairKey(it.gatewayId, it.agentId) == key
        } + AgentGroupMemberEntry(gatewayId, agentId, groupId)
        persist()
    }

    /** 移出分组（未分组成员为 no-op）。 */
    fun removeMember(gatewayId: String, agentId: String) = synchronized(lock) {
        val key = agentGroupsPairKey(gatewayId, agentId)
        membersState = membersState.filterNot {
            agentGroupsPairKey(it.gatewayId, it.agentId) == key
        }
        persist()
    }

    /** 整 blob 重写（个人规模组/成员个位~十位量级，无增量需求）；只读档/无 node 静默跳过。 */
    private fun persist() {
        if (!writable) return
        node?.let { n ->
            runCatching {
                n.put(AGENT_GROUPS_STORAGE_KEY, agentGroupsSerialize(AgentGroupsState(groupsState, membersState)))
                n.flush()
            } // 持久化失败 = 内存态降级（进程内生效，重启回落上次成功持久化值）
        }
    }

    private fun nowRfc3339(): String =
        OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    internal companion object {
        /** 用户级偏好节点（与 DesktopMutePreferences 同节点族——§4.2 草写 "agentreins" 的实码化）。 */
        private const val NODE_NAME = "dev/sharpxia/agentreins/hub"
    }
}
