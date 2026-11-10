package dev.sharpxia.agentreins.desktopui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.sharpxia.agentreins.hub.UiAgentRef
import dev.sharpxia.agentreins.hub.UiGateway

/**
 * 多网关轮（v1.3.0，设计稿 multi-gateway-design-20261110 r3）桌面 agent 分组的**纯规则层**：
 * §4.2 数据模型（单 JSON blob，键 = Preferences "agentGroups.v1"，形状两端一处定义、解析各自
 * 实现——iOS AgentGroupsBlob Codable 同形）+ §3.2/§4.3 呈现规则（网关显示名、组员资格与目录
 * 取交集）。无 Compose 无 Preferences 依赖，desktopTest 回归锚 [AgentGroupRulesTest]。
 *
 * 键纪律（§4.2）：组员键 = **(gatewayId, agentId) 二元组**——agentId 只在网关内唯一
 * （agents_native_uq），跨网关同名 agentId 是不同实体（ADR-14）；跨网关混组是一等能力。
 * 组用稳定 UUID：改名不动成员；删组 = 解散（成员回「未分组」），不级联删任何投影。
 */

/** 当前档面版本（§4.2 version 演进预留位；未知未来版本整档只读不写，防旧端写坏新数据）。 */
internal const val AGENT_GROUPS_VERSION: Int = 1

/** 存储 key（§4.2 字面：桌面 Preferences key "agentGroups.v1"；iOS UserDefaults 同名 key）。 */
internal const val AGENT_GROUPS_STORAGE_KEY: String = "agentGroups.v1"

/** 分组行（§4.2 groups 项）：稳定 UUID id + 展示名 + 创建时刻（RFC3339，仅呈现/排序用）。 */
internal data class AgentGroupEntry(
    val id: String,
    val name: String,
    val createdAt: String,
)

/** 组员行（§4.2 members 项）：canonical 形态原样持有；读侧按 (gatewayId, agentId) 索引。 */
internal data class AgentGroupMemberEntry(
    val gatewayId: String,
    val agentId: String,
    val groupId: String,
)

/** 整档内存态（groups 保存储顺序 = 建组顺序；members 保追加序，语义无序）。 */
internal data class AgentGroupsState(
    val groups: List<AgentGroupEntry> = emptyList(),
    val members: List<AgentGroupMemberEntry> = emptyList(),
)

/**
 * 档面解析结果：[state] 为可用档面（坏档/缺档 = 空态；未知未来版本 = 空态）；[writable] =
 * 版本已知（==1）可回写。坏档/缺档降级为可写空态（覆盖坏档是愈合不是丢失）；未知版本只读
 * ——应用内不会有该版本，回写等于用旧 schema 覆盖新数据。
 */
internal class AgentGroupsArchive(val state: AgentGroupsState, val writable: Boolean)

/**
 * §4.2 blob 解析（降级链纪律，镜像 DefaultEventProjector 的坏载荷降级——单条坏行跳过不炸整档）：
 * - null/空串 = 无档 → 空态可写；
 * - JSON 不可解 / 根非对象 → 坏档 → 空态可写（覆盖坏档）；
 * - version 缺失或 != 1 → 未知档 → 空态只读（[AgentGroupsArchive.writable] = false）；
 * - groups/members 非数组 → 按空清单；单条目坏形状（缺字段/非对象）跳过，其余保留。
 */
internal fun agentGroupsParse(raw: String?): AgentGroupsArchive {
    val empty = AgentGroupsArchive(AgentGroupsState(), writable = true)
    if (raw.isNullOrBlank()) return empty
    val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return empty
    val version = runCatching { (root["version"] as? JsonPrimitive)?.intOrNull }.getOrNull()
        ?: return empty
    if (version != AGENT_GROUPS_VERSION) return AgentGroupsArchive(AgentGroupsState(), writable = false)

    fun entries(key: String, read: (kotlinx.serialization.json.JsonObject) -> AgentGroupMemberEntry?) =
        runCatching {
            root[key]?.jsonArray?.mapNotNull { el ->
                runCatching { el.jsonObject }.getOrNull()?.let(read)
            }.orEmpty()
        }.getOrDefault(emptyList())

    val groups = runCatching {
        root["groups"]?.jsonArray?.mapNotNull { el ->
            runCatching { el.jsonObject }.getOrNull()?.let { g ->
                val id = (g["id"] as? JsonPrimitive)?.content
                val name = (g["name"] as? JsonPrimitive)?.content
                val createdAt = (g["createdAt"] as? JsonPrimitive)?.content
                if (id.isNullOrEmpty() || name == null) null else AgentGroupEntry(id, name, createdAt.orEmpty())
            }
        }.orEmpty()
    }.getOrDefault(emptyList())

    val members = entries("members") { m ->
        val gatewayId = (m["gatewayId"] as? JsonPrimitive)?.content
        val agentId = (m["agentId"] as? JsonPrimitive)?.content
        val groupId = (m["groupId"] as? JsonPrimitive)?.content
        if (gatewayId.isNullOrEmpty() || agentId.isNullOrEmpty() || groupId.isNullOrEmpty()) null
        else AgentGroupMemberEntry(gatewayId, agentId, groupId)
    }

    return AgentGroupsArchive(AgentGroupsState(groups, members), writable = true)
}

/** §4.2 blob 编码：键形状与 iOS AgentGroupsBlob Codable 输出同形（version/groups/members）。 */
internal fun agentGroupsSerialize(state: AgentGroupsState): String = buildJsonObject {
    put("version", AGENT_GROUPS_VERSION)
    put(
        "groups",
        buildJsonArray {
            state.groups.forEach { g ->
                add(
                    buildJsonObject {
                        put("id", g.id)
                        put("name", g.name)
                        put("createdAt", g.createdAt)
                    },
                )
            }
        },
    )
    put(
        "members",
        buildJsonArray {
            state.members.forEach { m ->
                add(
                    buildJsonObject {
                        put("gatewayId", m.gatewayId)
                        put("agentId", m.agentId)
                        put("groupId", m.groupId)
                    },
                )
            }
        },
    )
}.toString()

// ── 呈现规则（§3.2 会话行/§4.3 三段式共用；iOS HubStore.gatewayDisplayName/groupAgents 同构） ──

/** 组员 canonical 键：`gatewayId|agentId`（iOS HubStore.pairKey 同格式；与 conversationEntryKey 同族）。 */
internal fun agentGroupsPairKey(gatewayId: String, agentId: String): String = "$gatewayId|$agentId"

/** 组员资格查询（nil = 未分组；同二元组只认最后一条——store.assign 覆盖换组保证不重复）。 */
internal fun agentGroupMembership(state: AgentGroupsState, gatewayId: String, agentId: String): String? =
    state.members.lastOrNull {
        it.gatewayId == gatewayId && it.agentId == agentId
    }?.groupId

/**
 * 网关显示名（会话行 caption 数据面，设计稿 §3.2）：hub.gateways 按 gatewayId 查名；空名回退
 * gatewayId 尾段（末 8 位——gatewayId 为 ULID 无分隔段，取尾 8 位作可区分短名；未知 gatewayId
 * 同口径，iOS HubStore.gatewayDisplayName suffix(8) 同构）。
 */
internal fun gatewayDisplayName(name: String, gatewayId: String): String =
    if (name.isNotBlank()) name else gatewayId.takeLast(8)

/** 会话行便捷口径：按 gatewayId 在网关目录查名后走 [gatewayDisplayName]。 */
internal fun gatewayNameFor(gateways: List<UiGateway>, gatewayId: String): String =
    gatewayDisplayName(
        gateways.firstOrNull { it.gatewayId == gatewayId }?.name.orEmpty(),
        gatewayId,
    )

/** 目录内 agent 行（三段式「分组/未分组」的渲染单元）：组员资格与目录取交集的产物。 */
internal data class GatewayAgentRef(
    val gateway: UiGateway,
    val agent: UiAgentRef,
)

/** 三段式前两段视图（§4.3）：每组一段（组内 agent 为组员资格 ∩ 目录）+ 未分组一段。 */
internal data class AgentGroupsView(
    /** 组按存储序（建组序）展开；组内行按目录序（网关序 × agent 序）——iOS groupAgents 同序。 */
    val grouped: List<Pair<AgentGroupEntry, List<GatewayAgentRef>>>,
    val ungrouped: List<GatewayAgentRef>,
)

/**
 * 分组视图解析（iOS HubStore.groupAgents/ungroupedAgents 同构）：组员资格独立于目录存活，
 * 呈现与 hub.gateways 取交集——已下线/未出现的 agent 不渲染、不编造；指向不存在组的成员行
 * （孤儿资格）既不入组也不算未分组（等下一次 assign/目录收敛，呈现层不发明组）。
 */
internal fun agentGroupsView(gateways: List<UiGateway>, state: AgentGroupsState): AgentGroupsView {
    val rows = gateways.flatMap { gw ->
        gw.agents.map { GatewayAgentRef(gw, it) }
    }
    val grouped = state.groups.map { g ->
        g to rows.filter { agentGroupMembership(state, it.gateway.gatewayId, it.agent.agentId) == g.id }
    }
    val ungrouped = rows.filter {
        agentGroupMembership(state, it.gateway.gatewayId, it.agent.agentId) == null
    }
    return AgentGroupsView(grouped, ungrouped)
}
