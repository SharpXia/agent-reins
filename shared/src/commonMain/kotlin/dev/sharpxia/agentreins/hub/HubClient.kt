package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.platformCredentialStore
import dev.sharpxia.agentreins.hub.command.CommandClient
import dev.sharpxia.agentreins.hub.command.DefaultCommandClient
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.projection.CommandReceipt
import dev.sharpxia.agentreins.hub.projection.DefaultEventProjector
import dev.sharpxia.agentreins.hub.projection.EventProjector
import dev.sharpxia.agentreins.hub.db.createHubDriver
import dev.sharpxia.agentreins.hub.db.openHubDatabase
import dev.sharpxia.agentreins.hub.sync.DbEventCursorStore
import dev.sharpxia.agentreins.hub.sync.DefaultSyncEngine
import dev.sharpxia.agentreins.hub.sync.DefaultSyncScheduler
import dev.sharpxia.agentreins.hubdb.HubDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** UI 只读模型：隔离 sqldelight 生成行类型（iOS 桥按此投影）。 */
data class UiConversation(
    /** Hub ULID 句柄；化身更替后不变。卡片唯一来源 = 快照/列表校准（v1.1：UI 只见 conversation）。 */
    val conversationId: String,
    val label: String,
    val agentId: String,
    val preview: String,
    val lastSeq: Long,
    val lastActiveAt: String,
    val unread: Int,
    val hasActiveRun: Boolean,
    val reconciling: Boolean,
)

/** UI 消息行：state ∈ final/streaming/failed；uiState 仅用户行（sending/delivered/read/failed）。 */
data class UiMessage(
    val messageId: String,
    val seq: Long,
    val role: String,
    val content: String,
    val state: String,
    val uiState: String?,
    val ts: String?,
)

/**
 * Hub 客户端门面（P3 定稿）：认证栈 + 投影库 + 同步引擎 + WSS + 命令队列一站式组装。
 * 时序：create（库 + runtime）→ login/restore 成功（runtime 进入 LoggedIn、authStack 就绪）→
 * [start] 组装 engine/scheduler 并起同步循环 → [stop] 停止。UI 经 observeXxx Flow 订阅投影库，
 * 写路径全走 commands（幂等队列）。db=null（存储不可用）时只保留认证面，同步/命令不可用。
 */
class DefaultHubClient(
    private val scope: CoroutineScope,
    val runtime: HubRuntime,
    private val db: HubDatabase?,
) {
    var projection: EventProjector? = null
        private set
    var engine: DefaultSyncEngine? = null
        private set
    var commands: CommandClient? = null
        private set

    private var scheduler: DefaultSyncScheduler? = null

    /** 登录态就绪后组装同步栈并启动（幂等：重复调用忽略；登出后 stop 再 login 需重新 start）。 */
    fun start() {
        val stack = runtime.authStack ?: return
        if (scheduler != null || db == null) return
        projection = DefaultEventProjector(db)
        val cursors = DbEventCursorStore(db)
        engine = DefaultSyncEngine(stack.api, stack.auth, projection!!, cursors)
        commands = DefaultCommandClient(stack.api, stack.auth, db)
        val base = runtime.baseUrl ?: return
        val ws = DefaultHubWsClient(
            config = HubConfig(base, runtime.wsUrlOf(base)),
            accessTokenProvider = { stack.auth.currentAccessToken() },
            scope = scope,
        )
        scheduler = DefaultSyncScheduler(scope, engine!!, ws, cursors)
        scheduler?.start()
        // 断线恢复：未终态命令按同键幂等重发（05 §3.5 恢复流程收尾步）
        scope.launch { runCatching { commands?.retryUnfinished() } }
    }

    fun stop() {
        scheduler?.stop()
        scheduler = null
    }

    /**
     * 清全部本地数据（登出闭环，方案 §6-P4 验收⑤）：七表事务内清空，含 O-01 最小集
     * （命令队列/游标）与审批累积态——设备已在服务端撤销，本地不残留任何账号数据；
     * 重登后从空库 bootstrap。须在 [stop] 之后调用（同步循环已停，无在途投影写入）。
     */
    fun wipeLocalData() {
        val db = db ?: return
        db.hubQueries.transaction {
            db.hubQueries.clearGateways()
            db.hubQueries.clearConversations()
            db.hubQueries.clearConvKeys()
            db.hubQueries.clearMessages()
            db.hubQueries.clearReadCursors()
            db.hubQueries.clearCommands()
            db.hubQueries.clearApprovals()
            db.hubQueries.clearSyncState()
        }
    }

    // ── UI 查询（投影库 Flow；写路径由 projector/commands 承担） ──

    fun observeConversations(): Flow<List<UiConversation>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectConversations().asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiConversation(
                    conversationId = it.conversationId, label = it.label, agentId = it.agentId,
                    preview = it.lastMessagePreview, lastSeq = it.lastSeq, lastActiveAt = it.lastActiveAt,
                    unread = it.unreadCount.toInt(), hasActiveRun = it.hasActiveRun == 1L,
                    reconciling = it.reconciling == 1L,
                )
            }
        }
    }

    fun observeMessages(conversationId: String): Flow<List<UiMessage>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectMessages(conversationId).asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiMessage(
                    messageId = it.messageId, seq = it.seq, role = it.role, content = it.content,
                    state = it.state, uiState = it.uiState, ts = it.ts,
                )
            }
        }
    }

    /** 会话完整渲染后上报已读（readUpTo = 已渲染最大 seq）。 */
    suspend fun markRead(conversationId: String, readUpTo: Long) {
        commands?.markRead(conversationId, readUpTo)
    }

    // ── v1.1 新端点门面（#16/#17/#18/#19；不走幂等队列，直调） ──────────────

    /** 新开会话（openclaw 原生指令）；新会话经 roster 同步 + R4 校准自动出现在列表。 */
    suspend fun createConversation(gatewayId: String, agentId: String, label: String?): CommandReceipt =
        commands!!.createConversation(gatewayId, agentId, label)

    suspend fun compactConversation(conversationId: String): CommandReceipt =
        commands!!.compactConversation(conversationId)

    suspend fun renameConversation(conversationId: String, label: String): CommandReceipt =
        commands!!.renameConversation(conversationId, label)

    suspend fun agentStatus(gatewayId: String, agentId: String): AgentStatusResponse {
        val stack = runtime.authStack ?: throw IllegalStateException("未登录")
        return stack.auth.withBearer { stack.api.agentStatus(gatewayId, agentId) }
    }

    companion object {
        /** 组装入口：库 + 运行时；存储不可用时 db=null（UI 呈现不可用态，认证面保留）。 */
        fun create(scope: CoroutineScope, credentialStore: CredentialStore = platformCredentialStore()): DefaultHubClient =
            DefaultHubClient(scope, HubRuntime(scope, credentialStore), openHubDatabase(createHubDriver()))
    }
}
