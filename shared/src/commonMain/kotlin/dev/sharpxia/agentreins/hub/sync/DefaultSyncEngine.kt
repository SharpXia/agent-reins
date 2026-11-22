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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * 事件应用串行锁（2026-11-22 FLOW-3）：onEventArrived 的游标读改写（读 c → 应用 → 判大
     * 回写）与 catchUp 的分页游标推进是两条协程（WSS 帧收集 vs R4 周期校准/断线补拉；桌面
     * scope = Dispatchers.Default 真并行），交错可把持久游标拉回到已应用事件 id 之下——resume/
     * catchUp 即重投旧事件，流式 delta 追加非幂等（重复防护责任在引擎，INV-1/INV-2）→ 重复
     * 追加正文。锁只围游标敏感段（事件 RMW、页界判大回写），网络请求在锁外（长时间分页不
     * 阻塞帧消费）；bootstrap/rebuild 的快照落定写不并发于事件应用（runLoop 引导先于帧收集、
     * rebuild 与帧消费同协程），不入锁。
     */
    private val applyMutex = Mutex()

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
            // 应用后推进（INV-2；串行分页不并发拉取）。页界回写经 applyMutex 与并发
            // onEventArrived 串行，且重读判大再写：本轮分页期间 WSS 事件把游标推得比 next
            // 更远时，本页不得把持久游标拉回（回退窗→旧事件重投，delta 重追加）
            applyMutex.withLock {
                val latest = cursors.eventCursor()
                if (latest == null || next > latest) cursors.updateEventCursor(next)
            }
            cursor = next
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

    suspend fun onEventArrived(e: EventEnvelope) = applyMutex.withLock {
        // 游标读改写整段串行（applyMutex）：入口读 c → 落库 → 判大回写，期间无让位点也不可
        // 被并发入口插入——真并行线程下「陈旧 c 条件回写」正是游标回退根因（FLOW-3）
        val c = cursors.eventCursor()
        if (c != null && e.id <= c) return@withLock // 幂等 + 水位裁决（ULID 字典序=时间序）
        if (markSeen(e.id)) return@withLock
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
 * - 活性看门狗：90s 无传输层活性（transportActivity tick）判死主动重连（2026-11-22 FLOW-5；
 *   当前 Ktor 栈下 tick 只随数据帧走、与旧等价，机制就位待栈面变化——见 lastTransportTick 注）
 *
 * wp-10（纯观察面）：相位 [phase] 只在上述既有分支落点翻转（循环头 catching / Ready+追平
 * linked / 断开窗口 reconnecting / HUB-SYNC-001/002 rebuilding / superseded 等待），
 * 不改任何恢复语义。
 */
class DefaultSyncScheduler(
    private val scope: CoroutineScope,
    private val engine: DefaultSyncEngine,
    private val ws: HubWsClient,
    private val cursors: EventCursorStore,
    private val calibrationIntervalMs: Long = 300_000,
    private val watchdogTimeoutMs: Long = 90_000,
    /**
     * wp-10 相位落点（additive，可选）：缺省自持内部流（单测/直连组装场景）。DefaultHubClient
     * 组装时传入自有稳定流（phaseSink）——scheduler 每次 start/stop 会重建，UI 订阅面若直接
     * 指向 scheduler 自持流会随重建换源，注入客户端级稳定流后 collect 永不换源。
     */
    private val phaseSink: MutableStateFlow<SyncPhase> = MutableStateFlow(SyncPhase.Catching),
    /**
     * v1.5.0 形象档回拉钩子（additive，可选）：每次 WSS 建连收到 sync.ready 即触发一次——
     * 离线窗口错过的 avatar.updated 控制帧不可重放（控制帧无 seq 无重放），每次重连建立
     * 成功后主动 GET 一次（端点 #30，权威 = REST 读面；设计稿 §syncMechanism 读路径③）。
     * 缺省 null = 钩子关闭（旧装配/直连单测零行为变化）；实现方须自吞失败（best-effort，
     * 不阻塞同步循环）。
     */
    private val onReady: (() -> Unit)? = null,
    /**
     * v1.5.0 形象档推送钩子（additive，可选）：avatar.updated 控制帧到达即回调（收敛比较
     * updatedAt 大者胜由实现方承担——同值幂等跳过防广播回环）。缺省 null = 钩子关闭。
     */
    private val onAvatarUpdated: ((dev.sharpxia.agentreins.hub.avatar.AvatarProfile) -> Unit)? = null,
    /**
     * v1.5.2 会话覆盖档推送钩子（additive，可选）：conversation.avatar.updated 控制帧到达即
     * 回调（conversationId, profile）——[dev.sharpxia.agentreins.hub.avatar.AvatarProfile] 非
     * null = 覆盖档帧（per-key updatedAt 大者胜/同值幂等跳过由实现方承担）；**null = 合法清除
     * 帧**（端点 #33 广播，按键移除，重复应用无副作用）。缺省 null = 钩子关闭。
     */
    private val onConversationAvatarUpdated: ((String, dev.sharpxia.agentreins.hub.avatar.AvatarProfile?) -> Unit)? = null,
) : SyncScheduler {

    override val phase: StateFlow<SyncPhase> get() = phaseSink

    private var main: Job? = null
    private val recovery = Channel<Unit>(Channel.CONFLATED)
    private val bootMark = TimeSource.Monotonic.markNow()

    /** 应用帧时刻（数据面）：R4 周期校准的静默判据（传输层 ping 不刷此戳——无数据静默期正是校准目标）。 */
    @Volatile private var lastFrameAt = 0L

    /**
     * 传输层活性快照（2026-11-22 FLOW-5）：tick = [HubWsClient.transportActivity] 现值，at =
     * tick 变化时刻。判据设计 =「90s 无任意到达帧（含协议层 ping/pong）才判死」；**当前 Ktor
     * 3.6.0 栈下 Ping/Pong 被 DefaultWebSocketSession 包装拦截、不达用户 incoming（见
     * DefaultHubWsClient pingIntervalMillis 注），tick 实际只随数据帧走——行为与旧（按应用帧
     * 判死）等价**，机制就位待栈面变化（raw 会话/启用 pinger，后者 R6 记录 desktop CIO
     * ready 帧卡死，列 defer 待联调轮）即生效。
     */
    @Volatile private var lastTransportTick = 0L
    @Volatile private var lastTransportAt = 0L

    @Volatile private var running = false
    @Volatile private var pollIntervalMs: Long = 30_000

    /** 相位翻转（wp-10 观察面）：StateFlow 同值合流，重复写零副作用。 */
    private fun setPhase(p: SyncPhase) {
        phaseSink.value = p
    }

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
            setPhase(SyncPhase.Catching) // wp-10：连接段在途（引导/建连/追平）
            try {
                if (cursors.eventCursor() == null) engine.bootstrap()
                // 帧收集先于 connect 启动：建连即发 resume，ready/事件可能在订阅建立前到达
                val collector = scope.launch {
                    ws.frames().collect { onFrame(it) }
                }
                try {
                    ws.connect(cursors.eventCursor() ?: DbEventCursorStore.ZERO_ULID)
                    backoffMs = 1_000L
                    // 新连接活性基线（FLOW-5）：传输戳从建连时刻起算，上一连接的静默账不继承
                    lastTransportTick = ws.transportActivity.value
                    lastTransportAt = nowMs()
                    consumeUntilClosed()
                } finally {
                    collector.cancel()
                }
            } catch (e: CancellationException) {
                // stop()（含 iOS 后台暂停）取消路径必须收尾 WS 会话：此前会话泄漏存续，
                // 挂起竞态下底层 URLSession 错误回调落在半终结会话上（Ktor Darwin
                // didComplete 异常无人消费）→ 进程 abort（0.5.4 真机锁屏解锁 SIGABRT）。
                runCatching { ws.close() }
                throw e
            } catch (_: Exception) {
                // 建连/帧循环失败：走下方统一恢复节拍（R5 轮询退化）
            }
            // 断开（含建连失败）窗口：HTTP 补拉 + 退避重连——wp-10「重连中」提示条
            val closed = ws.closed.value
            if (closed?.superseded == true) {
                // 同设备新连接接管（08 §5.5）：本进程不重连，等显式恢复信号
                superseded = true
            } else {
                setPhase(SyncPhase.Reconnecting)
                runCatching { engine.catchUp() } // 断开窗口先 HTTP 补一轮（完整性凭证通道）
            }
            if (superseded) {
                setPhase(SyncPhase.Superseded)
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
            // 看门狗（FLOW-5）：判据 = 传输层活性 tick 90s 无变化才判死（中间件静默丢弃/对端
            // 假活）。当前 Ktor 栈下 tick 只随数据帧走（Ping/Pong 被 DefaultWebSocketSession
            // 拦截，见 DefaultHubWsClient pingIntervalMillis 注）——行为与旧等价；机制就位待
            // 栈面变化即回归「真死」判据（defer：启用 pinger 需活 Hub 复验）
            val tick = ws.transportActivity.value
            if (tick != lastTransportTick) {
                lastTransportTick = tick
                lastTransportAt = now
            }
            val last = lastTransportAt
            if (last != 0L && now - last > watchdogTimeoutMs) {
                // 90s 无活性变化：连接判死，主动关闭走重连
                ws.close()
                break
            }
            if (lastFrameAt != 0L && now - lastFrameAt >= calibrationIntervalMs) {
                // R4 周期校准（应用帧静默即对账；不阻塞帧消费循环节奏）；追平成功即收敛 linked
                //（wp-10 提示条消失相位兜底）
                if (runCatching { engine.catchUp() }.getOrDefault(false)) setPhase(SyncPhase.Linked)
            }
        }
    }

    private suspend fun onFrame(frame: HubServerFrame) {
        lastFrameAt = nowMs()
        when (frame) {
            is HubServerFrame.Ready -> {
                frame.pollIntervalMs?.let { if (it > 0) pollIntervalMs = it }
                val ok = runCatching { engine.catchUp() } // R3 先连后补（ready 后立即对账一次）
                // wp-10：追平成功 → linked（提示条消失相位）；未追平/失败保持 catching（帧仍在流）
                if (ok.getOrDefault(false)) setPhase(SyncPhase.Linked)
                // v1.5.0：重连回拉钩子（形象档；离线窗口广播不可重放，ready 后主动 GET 一次）。
                // 回调自吞失败（best-effort），不阻塞同步循环与相位收敛。
                onReady?.invoke()
            }
            is HubServerFrame.Event -> engine.onEventArrived(frame.event)
            is HubServerFrame.Error -> when (frame.code) {
                "HUB-SYNC-001", "HUB-SYNC-002" -> {
                    // cursor 过期/非法 → 全量重建（保留发送队列），重建后外层循环重连
                    setPhase(SyncPhase.Rebuilding) // wp-10：重建态提示条（验收③ 410 重建）
                    runCatching { engine.rebuild() }
                }
                else -> Unit // HUB-BIZ-003 限流帧等：依赖下一轮校准/重连收敛
            }
            // v1.5.0/v1.5.2：avatar.updated / conversation.avatar.updated 控制帧——非事件域，
            // 不进投影/游标，直达形象档收敛钩子（会话帧 profile=null = 合法清除帧）
            is HubServerFrame.AvatarUpdated -> onAvatarUpdated?.invoke(frame.profile)
            is HubServerFrame.ConversationAvatarUpdated ->
                onConversationAvatarUpdated?.invoke(frame.conversationId, frame.profile)
        }
    }

    private fun nowMs(): Long = bootMark.elapsedNow().inWholeMilliseconds
}
