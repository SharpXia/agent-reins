package dev.sharpxia.agentreins.hub.sync

import dev.sharpxia.agentreins.hub.HubServerFrame
import dev.sharpxia.agentreins.hub.HubWsClient
import dev.sharpxia.agentreins.hub.HubApi
import dev.sharpxia.agentreins.hub.auth.TokenManager
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import dev.sharpxia.agentreins.hub.projection.EventProjector
import dev.sharpxia.agentreins.hub.projection.SnapshotResponse
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/** 游标持久化落 hubSyncState 表（O-01 最小集；禁止存 Gateway offset/连接级 seq，O-12）。 */
class DbEventCursorStore(private val db: HubDatabase) : EventCursorStore {
    override fun eventCursor(): String? =
        db.hubQueries.selectSyncState(KEY_CURSOR).executeAsOneOrNull()

    override fun updateEventCursor(id: String) {
        db.hubQueries.upsertSyncState(KEY_CURSOR, id)
    }

    override fun snapshotWatermark(): String? =
        db.hubQueries.selectSyncState(KEY_WATERMARK).executeAsOneOrNull()

    override fun updateSnapshotWatermark(watermark: String) {
        db.hubQueries.upsertSyncState(KEY_WATERMARK, watermark)
    }

    override fun clear() {
        db.hubQueries.upsertSyncState(KEY_CURSOR, ZERO_ULID)
        db.hubQueries.upsertSyncState(KEY_WATERMARK, ZERO_ULID)
    }

    companion object {
        const val KEY_CURSOR = "eventCursor"
        const val KEY_WATERMARK = "snapshotWatermark"
        /** 零值 ULID（26 个 0，快照空系统约定；字典序最小）。 */
        const val ZERO_ULID = "00000000000000000000000000"
    }
}

/**
 * [SyncEngine] 实现：快照落定 / HTTP 增量串行分页 / 事件统一入口（幂等去重 + 游标推进）。
 *
 * 事件统一入口 [onEventArrived] 同时服务 WSS 推送与 HTTP 增量两通道（同构信封，R1/R2）：
 * `id ≤ 持久 cursor` 即丢弃（同时覆盖 INV-1 幂等与 05 §3.4 水位裁决——cursor 恒等于
 * 已应用最大事件 id，快照落定后被置为 snapshotWatermark，≤W 的在途推送天然过滤）。
 */
class DefaultSyncEngine(
    private val api: HubApi,
    private val auth: TokenManager,
    private val projector: EventProjector,
    private val cursors: EventCursorStore,
) : SyncEngine {

    /** 去重窗口：同 id 重复（两通道重叠）第二次出现跳过；cursor 单调已兜底大部分，此为重叠窗口内保险。 */
    private val seenMutex = Mutex()
    private val seen = LinkedHashSet<String>() // 插入序：命中即移除重插（LRU）；超容量裁最旧

    private suspend fun markSeen(id: String): Boolean = seenMutex.withLock {
        val fresh = seen.add(id)
        if (fresh && seen.size > 4096) seen.remove(seen.first())
        !fresh
    }

    override suspend fun bootstrap(): SnapshotResponse {
        val snap = auth.withBearer { api.snapshot() }
        projector.applySnapshot(snap) // 快照整体覆盖（hubCommands/hubSyncState/hubApprovals 保留）
        cursors.updateSnapshotWatermark(snap.snapshotWatermark)
        cursors.updateEventCursor(snap.snapshotWatermark)
        return snap
    }

    override suspend fun catchUp(): Boolean {
        var cursor = cursors.eventCursor() ?: return false
        var caughtUp = false
        while (true) {
            val page = auth.withBearer { api.events(cursor) }
            page.events.forEach { onEventArrived(it) }
            val next = page.nextCursor ?: break
            cursor = next
            cursors.updateEventCursor(next) // 应用后推进（INV-2；串行分页不并发拉取）
            caughtUp = !page.hasMore && next == page.currentWatermark
            if (!page.hasMore) break
        }
        if (caughtUp) runCatching { calibrateConversations() }
        return caughtUp
    }

    /**
     * 会话列表校准（08 §4.2 #7，R4 的卡片面）：v1.1 事件不带 conversationId、客户端不建占位卡，
     * 新会话卡/字段对齐全靠本路径——追平后拉一页列表 upsert 合并（新卡出现；孤儿映射顺带清理）。
     * 失败不阻塞同步循环（下一校准节拍重试）。
     */
    suspend fun calibrateConversations() {
        val page = auth.withBearer { api.conversations(limit = 200) }
        if (page.conversations.isNotEmpty()) projector.applyConversations(page.conversations)
    }

    suspend fun onEventArrived(e: EventEnvelope) {
        val c = cursors.eventCursor()
        if (c != null && e.id <= c) return // 幂等 + 水位裁决（ULID 字典序=时间序）
        if (markSeen(e.id)) return
        projector.applyEvent(e)
        if (c == null || e.id > c) cursors.updateEventCursor(e.id)
    }

    /** HUB-SYNC-001/002 后的全量重建（05 §3.6）：清游标 → 快照重建；发送队列不经此路径、原样保留。 */
    suspend fun rebuild() {
        cursors.clear()
        bootstrap()
    }
}

/**
 * [SyncScheduler] 实现：单主循环承载 连接→帧消费→看门狗→校准→断线退避重连→410 重建。
 *
 * - R3 先连后补：sync.ready 收到即触发一次 catchUp（幂等），与后续推送重叠按 id 去重
 * - R4 周期校准：在线期间每 [calibrationIntervalMs] 触发 catchUp（先到为准的 500 条计数从简取时间维）
 * - R5 轮询退化：WSS 建连失败/反复断开时按 max(pollIntervalMs, 30s) 节拍 catchUp 兜底
 * - R6 服务端断开不是错误：一律重连恢复；仅 1000+superseded 停轮询等 [triggerRecovery]
 * - 活性看门狗：90s 无任何帧判死主动重连（库级 ping 在 Darwin 不可用时兜底，R6）
 */
class DefaultSyncScheduler(
    private val scope: CoroutineScope,
    private val engine: DefaultSyncEngine,
    private val ws: HubWsClient,
    private val cursors: EventCursorStore,
    private val calibrationIntervalMs: Long = 300_000,
    private val watchdogTimeoutMs: Long = 90_000,
) : SyncScheduler {

    private var main: Job? = null
    private val recovery = Channel<Unit>(Channel.CONFLATED)
    private val bootMark = TimeSource.Monotonic.markNow()

    @Volatile private var lastFrameAt = 0L

    @Volatile private var running = false
    @Volatile private var pollIntervalMs: Long = 30_000

    override fun start() {
        if (running) return
        running = true
        main = scope.launch { runLoop() }
    }

    override fun stop() {
        running = false
        main?.cancel()
        main = null
    }

    override fun triggerRecovery(reason: String) {
        recovery.trySend(Unit)
    }

    private suspend fun runLoop() {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive && running) {
            var superseded = false
            try {
                if (cursors.eventCursor() == null) engine.bootstrap()
                // 帧收集先于 connect 启动：建连即发 resume，ready/事件可能在订阅建立前到达
                val collector = scope.launch {
                    ws.frames().collect { onFrame(it) }
                }
                try {
                    ws.connect(cursors.eventCursor() ?: DbEventCursorStore.ZERO_ULID)
                    backoffMs = 1_000L
                    consumeUntilClosed()
                } finally {
                    collector.cancel()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 建连/帧循环失败：走下方统一恢复节拍（R5 轮询退化）
            }
            val closed = ws.closed.value
            if (closed?.superseded == true) {
                // 同设备新连接接管（08 §5.5）：本进程不重连，等显式恢复信号
                superseded = true
            } else {
                runCatching { engine.catchUp() } // 断开窗口先 HTTP 补一轮（完整性凭证通道）
            }
            if (superseded) {
                recovery.receive()
                continue
            }
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(pollIntervalMs.coerceAtLeast(30_000))
        }
    }

    /** 连接在途巡检：看门狗 + 周期校准（帧分发已在 runLoop 的 collector 上）。返回即连接已断。 */
    private suspend fun consumeUntilClosed() {
        while (currentCoroutineContext().isActive) {
            delay(5_000)
            if (ws.closed.value != null) break
            val now = nowMs()
            val last = lastFrameAt
            if (last != 0L && now - last > watchdogTimeoutMs) {
                // 90s 无帧：连接判死（中间件静默丢弃/对端假活），主动关闭走重连
                ws.close()
                break
            }
            if (last != 0L && now - last >= calibrationIntervalMs) {
                runCatching { engine.catchUp() } // R4 周期校准（不阻塞帧消费循环节奏）
            }
        }
    }

    private suspend fun onFrame(frame: HubServerFrame) {
        lastFrameAt = nowMs()
        when (frame) {
            is HubServerFrame.Ready -> {
                frame.pollIntervalMs?.let { if (it > 0) pollIntervalMs = it }
                runCatching { engine.catchUp() } // R3 先连后补（ready 后立即对账一次）
            }
            is HubServerFrame.Event -> engine.onEventArrived(frame.event)
            is HubServerFrame.Error -> when (frame.code) {
                "HUB-SYNC-001", "HUB-SYNC-002" -> {
                    // cursor 过期/非法 → 全量重建（保留发送队列），重建后外层循环重连
                    runCatching { engine.rebuild() }
                }
                else -> Unit // HUB-BIZ-003 限流帧等：依赖下一轮校准/重连收敛
            }
        }
    }

    private fun nowMs(): Long = bootMark.elapsedNow().inWholeMilliseconds
}
