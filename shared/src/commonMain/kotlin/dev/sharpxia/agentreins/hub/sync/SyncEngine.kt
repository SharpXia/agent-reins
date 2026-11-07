package dev.sharpxia.agentreins.hub.sync

import dev.sharpxia.agentreins.hub.projection.SnapshotResponse

/**
 * 快照→resume→增量→水位裁决→410 重建（05 §2/§3；P2 实现）。
 *
 * - 快照落定：整体覆盖本地投影，cursor = snapshotWatermark；快照行禁止 upsert 到已含更新事件的模型上
 * - 快照竞态五步裁决（05 §3.4）：丢弃 id ≤ W 的在途事件 → 按序重放 id > W 者 → cursor = W
 * - 追平判定：hasMore=false 且 nextCursor == currentWatermark；两通道重叠事件按事件 id 幂等去重（INV-1）
 * - 410 HUB-SYNC-001：停止增量 → 快照重建（保留未终态命令照常幂等重试）→ cursor=W → 先连后补
 */
interface SyncEngine {
    /** 首次初始化 / 游标过期重建：拉快照 → 覆盖投影 → cursor = snapshotWatermark。 */
    suspend fun bootstrap(): SnapshotResponse

    /** R3 先连后补：WSS sync.resume{cursor} 后发 HTTP 增量，串行分页至追平；返回是否追平。任何恢复场景都适用。 */
    suspend fun catchUp(): Boolean
}
