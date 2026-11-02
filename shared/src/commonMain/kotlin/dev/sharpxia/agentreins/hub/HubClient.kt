package dev.sharpxia.agentreins.hub

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.sharpxia.agentreins.hub.auth.CredentialStore
import dev.sharpxia.agentreins.hub.auth.platformCredentialStore
import dev.sharpxia.agentreins.hub.command.CommandClient
import dev.sharpxia.agentreins.hub.command.DefaultCommandClient
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
    val gatewayId: String,
    val sessionKey: String,
    /** REST 句柄；事件流单独建的占位行此值为 null（对齐前不可进详情/发消息）。 */
    val conversationId: String?,
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

    // ── UI 查询（投影库 Flow；写路径由 projector/commands 承担） ──

    fun observeConversations(): Flow<List<UiConversation>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectConversations().asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiConversation(
                    gatewayId = it.gatewayId, sessionKey = it.sessionKey,
                    conversationId = it.conversationId, label = it.label, agentId = it.agentId,
                    preview = it.lastMessagePreview, lastSeq = it.lastSeq, lastActiveAt = it.lastActiveAt,
                    unread = it.unreadCount.toInt(), hasActiveRun = it.hasActiveRun == 1L,
                    reconciling = it.reconciling == 1L,
                )
            }
        }
    }

    fun observeMessages(gatewayId: String, sessionKey: String): Flow<List<UiMessage>> {
        val db = db ?: return flowOf(emptyList())
        return db.hubQueries.selectMessages(gatewayId, sessionKey).asFlow().mapToList(scope.coroutineContext).map { rows ->
            rows.map {
                UiMessage(
                    messageId = it.messageId, seq = it.seq, role = it.role, content = it.content,
                    state = it.state, uiState = it.uiState, ts = it.ts,
                )
            }
        }
    }

    /** 会话完整渲染后上报已读（readUpTo = 已渲染最大 seq）。 */
    suspend fun markRead(conversationId: String, gatewayId: String, sessionKey: String, readUpTo: Long) {
        commands?.markRead(conversationId, gatewayId, sessionKey, readUpTo)
    }

    companion object {
        /** 组装入口：库 + 运行时；存储不可用时 db=null（UI 呈现不可用态，认证面保留）。 */
        fun create(scope: CoroutineScope, credentialStore: CredentialStore = platformCredentialStore()): DefaultHubClient =
            DefaultHubClient(scope, HubRuntime(scope, credentialStore), openHubDatabase(createHubDriver()))
    }
}
