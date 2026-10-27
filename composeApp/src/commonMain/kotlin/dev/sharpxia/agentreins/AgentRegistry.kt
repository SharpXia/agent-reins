package dev.sharpxia.agentreins

import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.agentTintFor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3.1 Agent 本地注册表：agentId → 显示名 / 身份色，kv 持久化（"agentRegistry:v1"）。
 *
 * OpenClaw 协议没有 agent 配置写入面（agents 只读），名称与 tint 是纯本地个性化；
 * 未注册的 agent 用默认名（agentId 原文）+ seed 散列色（[agentTintFor]），首次在
 * Agent 域页改名/选色才落注册表。
 */
@Serializable
data class AgentProfile(
    val agentId: String,
    /** 显示名（默认 agentId 原文，可改别名）。 */
    val name: String,
    val tint: AgentTintName,
) {
    companion object {
        fun default(agentId: String) = AgentProfile(
            agentId = agentId,
            name = agentId,
            tint = agentTintFor(agentId),
        )
    }
}

/** Agents tab 分组摘要（由会话列表派生，纯函数便于测试）。 */
data class AgentSummary(
    val profile: AgentProfile,
    /** 未归档会话数。 */
    val sessionCount: Int,
    val unreadTotal: Int,
    val lastActiveAtMs: Long?,
    /** 该 agent 的主会话 key（agent:<id>:main 形态优先，供一键进入）。 */
    val mainSessionKey: String?,
)

object AgentRegistry {

    const val KV_KEY = "agentRegistry:v1"

    private val json = Json { ignoreUnknownKeys = true }

    fun serialize(profiles: Map<String, AgentProfile>): String {
        val arr = profiles.values.joinToString(",") { p ->
            buildJsonObject {
                put("agentId", p.agentId); put("name", p.name); put("tint", p.tint.name)
            }.toString()
        }
        return "[$arr]"
    }

    fun parse(raw: String?): Map<String, AgentProfile> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            json.decodeFromString<List<Entry>>(raw).associateBy { it.agentId }
                .mapValues { (_, e) -> AgentProfile(e.agentId, e.name, runCatching { AgentTintName.valueOf(e.tint) }.getOrDefault(AgentTintName.GRAY)) }
        }.getOrDefault(emptyMap())
    }

    @Serializable
    private data class Entry(val agentId: String, val name: String, val tint: String)

    /** 解析注册表 + 会话列表 → 分组摘要（未注册 agent 补默认 profile）。 */
    fun summarize(
        sessions: List<Session>,
        profiles: Map<String, AgentProfile>,
    ): List<AgentSummary> {
        val visible = sessions.filter { !it.archived }
        val byAgent = visible.groupBy { it.agentId ?: sessionKeyAgentId(it.id) ?: return@groupBy null }
        return byAgent.mapNotNull { (agentId, list) ->
            if (agentId == null) return@mapNotNull null
            AgentSummary(
                profile = profiles[agentId] ?: AgentProfile.default(agentId),
                sessionCount = list.size,
                unreadTotal = list.sumOf { it.unread },
                lastActiveAtMs = list.maxOfOrNull { it.lastActiveAtMs ?: 0L }?.takeIf { it > 0L },
                mainSessionKey = list.firstOrNull { it.id == "agent:$agentId:main" }?.id
                    ?: list.maxByOrNull { it.lastActiveAtMs ?: 0L }?.id,
            )
        }.sortedWith(
            compareByDescending<AgentSummary> { it.lastActiveAtMs ?: 0L }
                .thenBy { it.profile.agentId },
        )
    }

    /** 某 agent 名下的会话（未归档在前：pinned → 非归档 → 归档，段内按活跃时间倒序）。 */
    fun sessionsOf(agentId: String, sessions: List<Session>): List<Session> =
        sessions.filter { (it.agentId ?: sessionKeyAgentId(it.id)) == agentId }
            .sortedWith(
                compareByDescending<Session> { it.pinned }
                    .thenBy { it.archived }
                    .thenByDescending { it.lastActiveAtMs ?: 0L },
            )
}
