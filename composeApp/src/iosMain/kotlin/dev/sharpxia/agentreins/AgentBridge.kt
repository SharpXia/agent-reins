package dev.sharpxia.agentreins

import androidx.compose.runtime.snapshotFlow
import dev.sharpxia.agentreins.model.ApprovalRequest
import dev.sharpxia.agentreins.model.ChatEntry
import dev.sharpxia.agentreins.model.GatewayStatus
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.ThinkingEntry
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import dev.sharpxia.agentreins.openclaw.Conversation
import dev.sharpxia.agentreins.openclaw.ConversationKind
import dev.sharpxia.agentreins.openclaw.Outbox
import dev.sharpxia.agentreins.openclaw.SearchHit
import dev.sharpxia.agentreins.openclaw.SessionCategory
import dev.sharpxia.agentreins.openclaw.SessionClassifier
import dev.sharpxia.agentreins.ui.AppSound
import dev.sharpxia.agentreins.ui.FeedbackPrefs
import dev.sharpxia.agentreins.ui.avatar.AgentExpression
import dev.sharpxia.agentreins.ui.avatar.avatarExpressionOf
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo

/**
 * SwiftUI 壳 ↔ AppModel 桥（iOS 前端重构）。
 *
 * AppModel 是 Compose snapshot state（mutableStateOf），Swift 侧无法直接订阅；
 * 本桥用 snapshotFlow 把状态切片成五路纯数据快照，经主线程回调推给 Swift。
 * composeApp（Compose UI 层）与 shared（数据层）一字不动——冻结约束下的唯一接线面。
 *
 * DTO 纪律（跨 Kotlin/Swift 边界）：
 * - 只用 String/Boolean/Int/Long/List/Map + 可空 String；数值一律非空，用哨兵
 *   （0=无时间、-1=无耗时/无 exitCode）——可空数值跨边界导出形态不稳，不赌。
 * - 多态（状态机/条目类型）折成 kind 字符串 + 平铺字段，Swift switch kind。
 */

// ---------- 快照 DTO ----------

/** 连接态：disconnected/connecting/pairing/connected/error。 */
data class BridgeConn(
    val kind: String,
    val message: String? = null,
    val requestId: String? = null,
    val gatewayName: String? = null,
    val connectedUrl: String? = null,
    val busy: Boolean = false,
    val lastError: String? = null,
    val hasPairedCredential: Boolean = false,
    val url: String = "",
    val apiKey: String = "",
    val alternateUrls: List<String> = emptyList(),
    val setupCode: String = "",
    val approvalsEnabled: Boolean = false,
    val onboardingDone: Boolean = false,
    val showOnboarding: Boolean = false,
    /** Dev 通道目标会话（SwiftUI 壳据此自动导航进会话页）。 */
    val devAutoSelectKey: String? = null,
)

/** conversation 行（首页列表）；run 状态已并入（running/derivedTasks/taskKeys）。 */
data class BridgeConversation(
    val id: String,
    val agentId: String,
    val kind: String,
    val channel: String? = null,
    val sessionKeys: List<String> = emptyList(),
    val preview: String? = null,
    val lastActiveAtMs: Long = 0,
    val unread: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val hidden: Boolean = false,
    val running: Boolean = false,
    val derivedTasks: Int = 0,
    val taskKeys: List<String> = emptyList(),
)

data class BridgeSession(
    val id: String,
    val title: String? = null,
    val preview: String? = null,
    val lastActiveAtMs: Long = 0,
    val unread: Int = 0,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val kind: String? = null,
    val agentId: String? = null,
    val hidden: Boolean = false,
)

data class BridgeAgent(
    val agentId: String,
    val name: String,
    val tint: String,
    /** 已解析的表情枚举名（恒非空：定制值或 agentId 散列派生值）。 */
    val expression: String = "",
    /** 黏土脸系数串（12 段两位小数逗号分隔）；"" = 正圆默认（face-sculpt §4）。 */
    val face: String = "",
    val sessionCount: Int = 0,
    val unreadTotal: Int = 0,
)

/** 会话/对话列表域（首行 tab 与 agent 页的数据源）。 */
data class BridgeLists(
    val conversations: List<BridgeConversation> = emptyList(),
    val sessions: List<BridgeSession> = emptyList(),
    val agents: List<BridgeAgent> = emptyList(),
)

/** 对话流条目：kind = message/thinking/tool。 */
data class BridgeEntry(
    val kind: String,
    val id: String,
    val sessionId: String? = null,
    val role: String = "",
    val content: String = "",
    val timestampMs: Long = 0,
    val streaming: Boolean = false,
    val entryId: String? = null,
    val text: String = "",
    val toolName: String = "",
    val title: String? = null,
    val argumentsJson: String? = null,
    val resultJson: String? = null,
    val exitCode: Int = -1,
    val durationMs: Long = -1,
    val state: String = "running",
)

/** 当前会话对话流域。 */
data class BridgeConv(
    val sessionKey: String,
    val entries: List<BridgeEntry> = emptyList(),
    val hasMoreHistory: Boolean = false,
    val historyLoaded: Boolean = false,
    val runActive: Boolean = false,
    val loadingOlder: Boolean = false,
    val queuedCount: Int = 0,
    val pendingOutbox: List<BridgeOutboxCmd> = emptyList(),
    val sendSignal: Int = 0,
    val readOnly: Boolean = false,
)

data class BridgeOutboxCmd(
    val idempotencyKey: String,
    val text: String,
    val createdAtMs: Long = 0,
    val unconfirmed: Boolean = false,
)

/** 时间线分区（conversation 路由）。 */
data class BridgeSection(
    val key: String,
    val label: String? = null,
    val entries: List<BridgeEntry> = emptyList(),
    val hasMoreHistory: Boolean = false,
    val historyLoaded: Boolean = false,
)

data class BridgeTimeline(
    val agentId: String? = null,
    val sections: List<BridgeSection> = emptyList(),
    val sectionLoading: String? = null,
    val compactMarks: Map<String, Long> = emptyMap(),
)

data class BridgeApproval(
    val id: String,
    val summary: String,
    val detailJson: String? = null,
)

data class BridgeSearchHit(
    val sessionKey: String,
    val snippet: String,
    val timestampMs: Long = 0,
)

/** 杂项域：审批/导航锚/草稿/偏好/搜索。 */
data class BridgeMisc(
    val pendingApproval: BridgeApproval? = null,
    val autoOpenKey: String? = null,
    val draft: String = "",
    val quotedText: String? = null,
    val creatingSession: Boolean = false,
    val themeMode: String = "SYSTEM",
    val soundEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val notificationSound: String = "DOUBLE_TICK",
    val searchHits: List<BridgeSearchHit> = emptyList(),
    val searching: Boolean = false,
)

// ---------- 桥本体 ----------

class AgentBridge(internal val model: AppModel) {

    init {
        // 根因修复（模拟器 A/B 实证,详见 diagnostic/stall-trace 分支）：
        // AppModel 的 mutableStateOf 写发生在 Dispatchers.Default 线程，而本壳没有
        // ComposeUIViewController——CMP 壳由其内部自动启动全局快照通知泵，SwiftUI 壳
        // 没人启动 → 桥的 snapshotFlow 只发首帧，后续状态更新对 Swift 侧间歇性/永久
        // 不可见（「已连接但会话内容加载不出」的真凶）。此处手动接上通知泵。
        androidx.compose.runtime.snapshots.Snapshot.registerGlobalWriteObserver {
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false

    /** Swift 端启动观测；幂等（重复调用忽略）。 */
    fun start(
        onConn: (BridgeConn) -> Unit,
        onLists: (BridgeLists) -> Unit,
        onConv: (BridgeConv) -> Unit,
        onTimeline: (BridgeTimeline) -> Unit,
        onMisc: (BridgeMisc) -> Unit,
        onEvent: (String, String) -> Unit,
    ) {
        if (started) return
        started = true
        scope.launch {
            snapshotFlow {
                val st = model.status
                println("AgentReins[connsnap] status=${st::class.simpleName} busy=${model.busy} lastError=${model.lastError}")
                BridgeConn(
                    kind = when (st) {
                        is GatewayStatus.Connected -> "connected"
                        is GatewayStatus.Connecting -> "connecting"
                        is GatewayStatus.PairingPending -> "pairing"
                        is GatewayStatus.Error -> "error"
                        GatewayStatus.Disconnected -> "disconnected"
                    },
                    message = (st as? GatewayStatus.Error)?.message,
                    requestId = (st as? GatewayStatus.PairingPending)?.requestId,
                    gatewayName = (st as? GatewayStatus.Connected)?.gatewayName,
                    connectedUrl = model.connectedUrl,
                    busy = model.busy,
                    lastError = model.lastError,
                    hasPairedCredential = model.hasPairedCredential,
                    url = model.url,
                    apiKey = model.apiKey,
                    alternateUrls = model.alternateUrls,
                    setupCode = model.setupCode,
                    approvalsEnabled = model.approvalsEnabled,
                    onboardingDone = model.onboardingDone,
                    showOnboarding = model.showOnboarding,
                    devAutoSelectKey = model.devAutoSelectKey,
                )
            }.collect { onConn(it) }
        }
        scope.launch {
            snapshotFlow {
                BridgeLists(
                    conversations = model.conversations.map { it.toBridge(model) },
                    sessions = model.sessions.map { it.toBridge() },
                    agents = AgentRegistry.summarize(model.sessions, model.agentProfiles).map {
                        BridgeAgent(
                            agentId = it.profile.agentId,
                            name = it.profile.name,
                            tint = it.profile.tint.name,
                            expression = avatarExpressionOf(it.profile.agentId, model.agentProfiles).name,
                            face = it.profile.face,
                            sessionCount = it.sessionCount,
                            unreadTotal = it.unreadTotal,
                        )
                    },
                )
            }.collect { onLists(it) }
        }
        // Dev 预览注入（AGENTREINS_DEV_PREVIEW_MARKDOWN=1）：网关不可用时的渲染走查通道，
        // 只改桥快照、不写 AppModel——验证 SwiftUI 侧 Markdown/shimmer/工具卡渲染与钉底。
        val previewMarkdown = AutoPromptHolderPreview.value
        scope.launch {
            snapshotFlow {
                val conv = model.conversation
                var entries = conv?.entries?.map { it.toBridge() } ?: emptyList()
                if (previewMarkdown && conv == null) {
                    entries = previewEntries()
                }
                BridgeConv(
                    sessionKey = model.selectedSession ?: (if (previewMarkdown) "preview" else ""),
                    entries = entries,
                    hasMoreHistory = conv?.hasMoreHistory ?: false,
                    historyLoaded = previewMarkdown || (conv?.historyLoaded ?: false),
                    runActive = conv?.activeRunId != null,
                    loadingOlder = model.loadingOlder,
                    queuedCount = model.queuedCount,
                    pendingOutbox = model.pendingOutbox.map { cmd ->
                        BridgeOutboxCmd(
                            idempotencyKey = cmd.idempotencyKey,
                            text = cmd.text,
                            createdAtMs = cmd.createdAtMs,
                            unconfirmed = cmd.lastError?.contains("结果未确认") == true,
                        )
                    },
                    sendSignal = model.sendSignal,
                )
            }.collect { onConv(it) }
        }
        scope.launch {
            snapshotFlow {
                BridgeTimeline(
                    agentId = model.convAgentId,
                    sections = model.convSections.map { s ->
                        BridgeSection(
                            key = s.key,
                            label = s.label,
                            entries = s.state?.entries?.map { it.toBridge() } ?: emptyList(),
                            hasMoreHistory = s.state?.hasMoreHistory ?: false,
                            historyLoaded = s.state?.historyLoaded ?: false,
                        )
                    },
                    sectionLoading = model.convSectionLoading,
                    compactMarks = model.compactMarks,
                )
            }.collect { onTimeline(it) }
        }
        scope.launch {
            snapshotFlow {
                BridgeMisc(
                    pendingApproval = model.pendingApproval?.toBridge(),
                    autoOpenKey = model.autoOpenKey ?: (if (previewMarkdown) "preview" else null),
                    draft = model.draft,
                    quotedText = model.quotedText,
                    creatingSession = model.creatingSession,
                    themeMode = model.themeMode.name,
                    soundEnabled = FeedbackPrefs.soundEnabled.value,
                    hapticsEnabled = FeedbackPrefs.hapticsEnabled.value,
                    notificationSound = FeedbackPrefs.notificationSound.value?.name ?: "none",
                    searchHits = model.searchHits.map { it.toBridge() },
                    searching = model.searching,
                )
            }.collect { onMisc(it) }
        }
        // createNewSession 完成直达（Compose 版 Nav 层消费同一事件流）
        scope.launch {
            model.navigationEvents.collect { key -> onEvent("openSession", key) }
        }
        // Dev 自动发消息（模拟器闭环验证）：连接成功 + 目标会话历史就绪后发一次
        val autoPrompt = AutoPromptHolder.value
        if (autoPrompt != null) {
            val prompt: String = autoPrompt
            scope.launch {
                snapshotFlow { Triple(
                    model.status is GatewayStatus.Connected,
                    model.selectedSession,
                    model.conversation?.historyLoaded == true,
                ) }.collect { (connected, key, loaded) ->
                    if (!autoPromptSent && connected && loaded && key != null) {
                        autoPromptSent = true
                        model.draft = prompt
                        model.send()
                    }
                }
            }
        }
    }

    private var autoPromptSent = false

    /** Dev 预览条目：Markdown 全要素 + 流式尾 + 工具卡三态 + 思考卡 + 待发气泡。 */
    private fun previewEntries(): List<BridgeEntry> = listOf(
        BridgeEntry(kind = "message", id = "pv-1", role = "user", content = "帮我总结一下 KMP 的优缺点，用 Markdown",
            timestampMs = SystemTimeHolder.nowMs()),
        BridgeEntry(kind = "thinking", id = "pv-t1", text = "用户想要 Markdown 格式的对比总结\n整理优势和劣势两大部分"),
        BridgeEntry(kind = "tool", id = "pv-tool1", toolName = "exec", title = "收集资料",
            state = "succeeded", durationMs = 482,
            argumentsJson = "{\"cmd\": \"ls -la\"}", resultJson = "total 64\ndrwxr-xr-x  12 user  staff   384B"),
        BridgeEntry(kind = "message", id = "pv-2", role = "assistant", content = """## KMP 优缺点总结

Kotlin Multiplatform 让 **业务逻辑** 一份代码多端复用：

| 维度 | 优点 | 代价 |
|---|---|---|
| 代码复用 | 数据层/协议层 100% 共享 | UI 仍需各端实现 |
| 性能 | 编译为原生二进制 | 与原生仍有差距 |
| 生态 | 可调用平台 API | 互操作样板代码 |

细节见 `shared` 模块，注意 `expect/actual` 的使用成本。""",
            timestampMs = SystemTimeHolder.nowMs()),
        BridgeEntry(kind = "tool", id = "pv-tool2", toolName = "browser", title = "正在打开文档页",
            state = "running", durationMs = -1),
        BridgeEntry(kind = "message", id = "pv-3", role = "assistant", content = "正在补充流式输出的一段话，这条处于",
            timestampMs = SystemTimeHolder.nowMs(), streaming = true),
    )

    // ---- 动作（AppModel 全量动作面 1:1 透传，Swift 侧不触碰 Kotlin 内部状态） ----

    fun connect() = model.connect()
    fun disconnect() = model.disconnect()
    fun setUrl(url: String) { model.url = url }
    fun setApiKey(apiKey: String) { model.apiKey = apiKey }
    fun setAlternateUrls(rawList: String) {
        model.alternateUrls = rawList.split(",", "，").map { it.trim() }.filter { it.isNotBlank() }
    }
    fun setSetupCode(code: String) { model.setupCode = code }
    fun onQrScan(payload: String?) = model.onQrScan(payload)
    fun clearError() { model.lastError = null }

    fun selectSession(key: String?) {
        model.select(key?.let { k -> model.sessions.firstOrNull { it.id == k } })
    }
    fun openSessionByKey(key: String) = model.openSessionByKey(key)

    fun setDraft(text: String) { model.draft = text }
    fun sendDraft() = model.send()
    fun resendText(text: String) = model.resendText(text)
    fun stopRun() = model.stopRun()
    fun stopByCommand() = model.stopByCommand()
    fun quoteMessage(text: String) = model.quoteMessage(text)
    fun clearQuote() = model.clearQuote()

    /** fork 锚点判定与 Kotlin 版同语义：entryId 优先，缺锚点（user- 前缀合成 id）不可分支。 */
    fun fork(entryId: String?, id: String) {
        val anchor = entryId ?: id.takeIf { !it.startsWith("user-") }
        if (anchor == null) {
            model.lastError = "该消息不支持分支（缺 transcript 锚点）"
            return
        }
        model.forkFrom(Message(id = id, sessionId = "", role = Message.Role.USER, content = "", entryId = entryId))
    }

    fun loadOlder(): Boolean = model.loadOlderMessages()
    fun retryQueued() = model.retryQueuedNow()
    fun deleteQueued(idempotencyKey: String) {
        model.pendingOutbox.firstOrNull { it.idempotencyKey == idempotencyKey }?.let { model.deleteQueued(it) }
    }

    fun setConvPinned(id: String, pinned: Boolean) {
        model.conversations.firstOrNull { it.id == id }?.let { model.setConversationPinned(it, pinned) }
    }
    fun setConvArchived(id: String, archived: Boolean) {
        model.conversations.firstOrNull { it.id == id }?.let { model.setConversationArchived(it, archived) }
    }
    fun setConvHidden(id: String, hidden: Boolean) {
        model.conversations.firstOrNull { it.id == id }?.let { model.setConversationHidden(it, hidden) }
    }
    fun setSessionArchived(key: String, archived: Boolean) {
        model.sessions.firstOrNull { it.id == key }?.let { model.setSessionArchived(it, archived) }
    }
    fun setSessionPinned(key: String, pinned: Boolean) {
        model.sessions.firstOrNull { it.id == key }?.let { model.setSessionPinned(it, pinned) }
    }
    fun setSessionHidden(key: String, hidden: Boolean) {
        model.sessions.firstOrNull { it.id == key }?.let { model.setSessionHidden(it, hidden) }
    }
    fun markAllRead() = model.markAllRead()

    fun conversationIdForAgent(agentId: String): String? = model.conversationIdForAgent(agentId)
    fun createNewSession(agentId: String) = model.createNewSession(agentId)
    fun setAgentName(agentId: String, name: String) = model.setAgentName(agentId, name)
    fun setAgentTint(agentId: String, tint: String) {
        val name = runCatching { AgentTintName.valueOf(tint) }.getOrNull() ?: return
        model.setAgentTint(agentId, name)
    }
    fun setAgentExpression(agentId: String, expression: String) {
        val e = runCatching { AgentExpression.valueOf(expression) }.getOrNull() ?: return
        model.setAgentExpression(agentId, e)
    }
    /** 黏土脸系数串透传（非法串由 AppModel.setAgentFace 静默忽略；"" = 回默认圆）。 */
    fun setAgentFace(agentId: String, face: String) = model.setAgentFace(agentId, face)

    fun openTimeline(agentId: String) = model.openConversationTimeline(agentId)
    fun closeTimeline() = model.closeConversationTimeline()
    fun loadSection(key: String): Boolean = model.loadSectionHistoryIfNeeded(key)
    fun createSessionInConversation() = model.createSessionInConversation()
    fun compactContext() = model.compactContext()
    fun sendAgentStatus() = model.sendAgentStatusPrompt()
    /** 从头开始（AppModel.startFresh）：当前 agent 开全新 surface 键。 */
    fun startFresh() = model.startFresh()
    fun consumeAutoOpen() = model.consumeAutoOpen()

    fun respondApproval(approved: Boolean) = model.respondApproval(approved)
    fun toggleApprovals(enabled: Boolean) = model.toggleApprovals(enabled)
    fun applyTheme(mode: String) {
        val m = runCatching { ThemeMode.valueOf(mode) }.getOrNull() ?: return
        model.applyThemeMode(m)
    }
    fun toggleSound(enabled: Boolean) = model.toggleSound(enabled)
    fun toggleHaptics(enabled: Boolean) = model.toggleHaptics(enabled)
    fun setNotificationSound(name: String) {
        val sound = if (name == "none") null else AppSound.entries.firstOrNull { it.name == name }
        model.setNotificationSound(sound)
    }
    fun searchServer(query: String) = model.searchServer(query)
}

/** Dev 预览开关（AGENTREINS_DEV_PREVIEW_MARKDOWN=1）。 */
internal object AutoPromptHolderPreview {
    val value: Boolean = NSProcessInfo.processInfo.environment["AGENTREINS_DEV_PREVIEW_MARKDOWN"] == "1"
}

internal object SystemTimeHolder {
    fun nowMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
}

// ---------- 映射 ----------

private fun Session.toBridge() = BridgeSession(
    id = id,
    title = title,
    preview = preview,
    lastActiveAtMs = lastActiveAtMs ?: 0,
    unread = unread,
    pinned = pinned,
    archived = archived,
    kind = kind,
    agentId = agentId ?: sessionKeyAgentId(id),
    hidden = hidden,
)

/** run 状态派生与 conversationRunStatus/TaskDetailSheet 同语义，Swift 侧免复刻。 */
private fun Conversation.toBridge(model: AppModel): BridgeConversation {
    val agentKeys = model.sessions.asSequence()
        .filter { (it.agentId ?: sessionKeyAgentId(it.id)) == agentId }
        .mapTo(mutableSetOf()) { it.id }
    val active = model.activeRunKeys.filterTo(mutableSetOf()) { it in agentKeys }
    val derived = active.filter { SessionClassifier.classify(it) == SessionCategory.DERIVED }.sorted()
    return BridgeConversation(
        id = id,
        agentId = agentId,
        kind = if (kind == ConversationKind.CHANNEL) "channel" else "mainline",
        channel = channel,
        sessionKeys = sessionKeys,
        preview = preview,
        lastActiveAtMs = lastActiveAtMs ?: 0,
        unread = unread,
        pinned = pinned,
        archived = archived,
        hidden = hidden,
        running = active.isNotEmpty(),
        derivedTasks = derived.size,
        taskKeys = derived,
    )
}

private fun ApprovalRequest.toBridge() = BridgeApproval(
    id = id,
    summary = summary,
    detailJson = detailJson,
)

private fun SearchHit.toBridge() = BridgeSearchHit(
    sessionKey = sessionKey,
    snippet = snippet,
    timestampMs = timestampMs,
)

private fun ChatEntry.toBridge(): BridgeEntry = when (this) {
    is Message -> BridgeEntry(
        kind = "message",
        id = id,
        sessionId = sessionId,
        role = when (role) {
            Message.Role.USER -> "user"
            Message.Role.ASSISTANT -> "assistant"
            Message.Role.SYSTEM -> "system"
        },
        content = content,
        timestampMs = timestampMs ?: 0,
        streaming = streaming,
        entryId = entryId,
    )
    is ThinkingEntry -> BridgeEntry(kind = "thinking", id = id, text = text)
    is ToolCallEvent -> BridgeEntry(
        kind = "tool",
        id = id,
        sessionId = sessionId,
        toolName = toolName,
        title = title,
        argumentsJson = argumentsJson,
        resultJson = resultJson,
        exitCode = exitCode ?: -1,
        durationMs = durationMs ?: -1,
        state = when (state) {
            ToolCallEvent.State.RUNNING -> "running"
            ToolCallEvent.State.SUCCEEDED -> "succeeded"
            ToolCallEvent.State.FAILED -> "failed"
        },
    )
    else -> BridgeEntry(kind = "unknown", id = id)
}
