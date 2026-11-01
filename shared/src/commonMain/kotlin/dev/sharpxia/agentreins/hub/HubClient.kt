package dev.sharpxia.agentreins.hub

import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.command.CommandClient
import dev.sharpxia.agentreins.hub.projection.EventProjector
import dev.sharpxia.agentreins.hub.sync.SyncEngine

/**
 * UI 门面：对标直连时代的 AgentClient 接缝，收窄到 Hub 语义（账号/连接/消息/权限全归后端）。
 * P0 仅聚合占位；P1–P3 随各模块实现逐步填充与收窄（docs/plans/hub-migration-plan.md §3）。
 */
interface HubClient {
    val auth: TokenManager
    val api: HubApi
    val ws: HubWsClient
    val sync: SyncEngine
    val commands: CommandClient
    val projection: EventProjector
}
