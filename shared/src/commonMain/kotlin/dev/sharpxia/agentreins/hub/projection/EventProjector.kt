package dev.sharpxia.agentreins.hub.projection

/**
 * 21 类事件 → 会话/时间线/未读/运行态投影（05 §3.11 合并规则；P2 实现，归并层从 AgentEventParser 抽离）。
 *
 * 纪律速查：
 * - 合并键 = messageId upsert；delta 可跳过（best-effort）但必须推进游标；created/replaced 收敛终态
 * - 未知类型：忽略内容、照常推进游标、不报错不中断（06 §12.2 义务 2）
 * - 两通道（WSS 推送 / HTTP 增量）重叠按事件 id 幂等去重（INV-1）
 * - 化身更替（lifecycleRevision 变化）：旧代次封存只读、不混排两代 seq、新代次未读从零重算（05 §3.10）
 * - 未读以服务端投影值为准（read.updated 按 max 合并，INV-7 只进不退），禁止第二套计数
 * - 快照行禁止 upsert 到已含更新事件的模型上（快照整体覆盖）
 */
interface EventProjector {
    fun applySnapshot(snapshot: SnapshotResponse)

    /**
     * 会话列表校准（08 §4.2 #7；R4 周期校准的卡片面）：服务端列表行 upsert 合并——
     * 新会话卡出现、既有卡字段对齐；**不清表**（与快照整体覆盖不同：仅对账、不回滚事件已推进的本地态）。
     */
    fun applyConversations(rows: List<ConversationRow>)

    fun applyEvent(envelope: EventEnvelope)
}
