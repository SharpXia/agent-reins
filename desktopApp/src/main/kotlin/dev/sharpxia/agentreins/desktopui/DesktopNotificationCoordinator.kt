package dev.sharpxia.agentreins.desktopui

import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.projection.EventEnvelope
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * wp-18：macOS 本地通知面（iOS wp-13 NotificationCoordinator + HubStore 触发面的桌面同位）。
 *
 * 数据面（无第二协议面）：全部经 shared 事件流消费——
 * - 审批（approval.requested）与 run 终态（completed/failed）：[DefaultHubClient] eventTap
 *   旁路（wp-13 共用面，信封为已应用且已过幂等/水位裁决的事件；投影把 run 三种终态压成同一
 *   hasActiveRun=0，Flow 面无法还原事件类型——iOS AgentBridge.onBridgeEvent 同口径）。
 *   conversationId 经 hubConvKeys 直查（信封 gatewayId + sessionKey，run/approval 域必带），
 *   未映射 = 空串，按归属可得性显式降级，不编造归属。
 * - 回复（有新回复）：会话列表投影 Flow 的 unread 增量差异面（iOS noteConversationsPush
 *   同构：assistant final 才增 unread；「非当前打开」在本地判定）。
 *
 * 触发面清单（与 iOS wp-13 HubStore 同清单）：
 * - 审批：有归属 → 「会话名」有新的审批请求；未映射 → 无归属提醒（审批仍具行动性）。
 *   当前打开不抑制（审批是 run 前置门，出现即需注意，与 iOS 同口径）。wp-4：归属面放开两跳
 *   连结行（shared 投影分域）——他端/channel 会话的审批也带会话名。
 * - 回复：已知卡片 unread 增量 + 非当前打开；run.completed 后 3s 窗内的回复吞掉（防双响）。
 * - run 终态：仅 completed/failed（run.aborted 是用户主动 Stop 的自致结果，不提醒）；
 *   未映射会话不提醒。completed 同时置 3s 回复抑制窗。
 *   wp-4 修法二：[runOriginGate]（两端协调器同口径 = RunOriginRegistry.isRunLocal 消费）——
 *   仅「本端发起」（message.send 登记；compact 触发静默、他端无记录抑制）的 run 终态提醒。
 *
 * 静默降级纪律（验收硬要求）：权限拒绝（osascript 通知权限）/进程失败/超时任何路径不抛
 * 不崩不重试；通知内容最小化（标题 + 会话名，零正文/payload 摘要，iOS 同纪律）。
 *
 * wp-9：按会话静音补齐（桌面同位 iOS wp-13 HubStore.mutedConversations）——[mutedConversationIds]
 * 单点注入（conversationKeyResolver 同型，决策时实时读取，设置页/右键解除即时生效），
 * 口径 = 静音吞「回复 + run 终态」、**不吞审批**（审批是 run 前置门，出现即需注意，wp-9
 * scope/验收同口径；注：iOS HubStore.noteApprovalRequested 对已归属静音审批做了吞——桌面
 * 面按 wp-9 验收②「审批仍弹」为准，两端差异记录在案）。另有 [notificationsEnabled] 总开关
 * 单点门（[notify] 入口）：关闭 = 全部通知静默（含审批），生产 = [DesktopMutePreferences]。
 *
 * 线程模型：eventTap 在同步引擎协程线程回调（只做分类 + hubConvKeys 查询，毫秒级，不阻塞
 * 投影循环）；所有决策状态（unread 基线/抑制窗/会话快照）由单一消费协程独占（经 Channel
 * 序列化），osascript 子进程也在消费协程串行拉起。消费者必须永不抛出（消费循环 runCatching
 * 兜底），通知失败即静默丢弃。
 */
internal class DesktopNotificationCoordinator(
    private val notifier: DesktopNotifier = OsascriptNotifier(),
    /** hubConvKeys 解析（信封 gatewayId + sessionKey → conversationId；未映射 = ""）。生产实现见 DesktopHubApp。 */
    private val conversationKeyResolver: (gatewayId: String, sessionKey: String) -> String = { _, _ -> "" },
    /**
     * wp-9：按会话静音集注入（conversationKeyResolver 同型的单点注入位；决策时实时读取，
     * 右键/设置页解除静音即时生效）。生产 = DesktopMutePreferences.mutedConversationIds。
     * 口径：吞回复 + run 终态，不吞审批（审批是 run 前置门，iOS 同纪律）。未映射会话
     * （conversationId = ""）不在静音面内，维持既有降级路径。
     */
    private val mutedConversationIds: () -> Set<String> = { emptySet() },
    /** wp-9：通知总开关（false = [notify] 单点门放空全部 kind 含审批；生产 = DesktopMutePreferences）。 */
    private val notificationsEnabled: () -> Boolean = { true },
    /** 单调时钟注入点（测试控制 3s 抑制窗推进；生产 = System.nanoTime）。 */
    private val nowNanos: () -> Long = System::nanoTime,
) {
    /** 单条已定形的本地通知（发送面 = [DesktopNotifier]）。 */
    internal data class Request(
        val kind: Kind,
        /** null = 无归属（审批未映射场景）；reply/run 必有归属（未映射已丢弃）。 */
        val conversationId: String?,
        val title: String,
        val body: String,
    )

    internal enum class Kind { APPROVAL, REPLY, RUN_COMPLETED, RUN_FAILED }

    /** 通知发送面：实现必须自带静默降级（任何失败不抛）。 */
    internal fun interface DesktopNotifier {
        fun notify(request: Request)
    }

    // ── 事件旁路入口（同步引擎协程线程；永不抛、不阻塞） ──────────────────────

    private sealed interface Msg {
        data class Event(val classified: Classified) : Msg
        data class Conversations(val list: List<UiConversation>) : Msg
        data object Reset : Msg
    }

    internal sealed interface Classified {
        data class ApprovalRequested(val conversationId: String) : Classified

        /** wp-4：runId 随分类透出（发起方门按 (gatewayId, runId) 消费）；gatewayId 同理。 */
        data class RunTerminal(
            val gatewayId: String,
            val conversationId: String,
            val completed: Boolean,
            val runId: String?,
        ) : Classified
    }

    private val channel = Channel<Msg>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val started = AtomicBoolean(false)

    /** 当前打开会话（壳层写 / 消费协程读，回复「非当前打开」判定；null = 无打开会话）。 */
    @Volatile
    var openConversationId: String? = null

    /**
     * wp-4 修法二：run 终态「本端发起」门（生产 = RunOriginRegistry.isRunLocal；壳层在
     * DefaultHubClient 组装后注入 lambda，解析经 client.runOriginRegistry 保证重登重建后仍活）。
     * null = 不设门（单测与旧装配兼容，行为同旧：只按归属降级）。false = 抑制提醒（他端发起 /
     * compact 触发），true = 放行。
     */
    @Volatile
    var runOriginGate: ((gatewayId: String, runId: String?) -> Boolean)? = null

    /** eventTap 入口（DefaultHubClient 组装时注入；投影应用后回调）。 */
    fun onEventEnvelope(envelope: EventEnvelope) {
        runCatching {
            val classified = classify(envelope) ?: return
            channel.trySend(Msg.Event(classified))
        }
    }

    /**
     * 信封分类（纯函数，单测覆盖）：sessionKey 缺失即不出通知（run/approval 域必带——
     * 缺失说明信封不属通知触发面）；conversationId 经 hubConvKeys 解析，未映射 = 空串。
     */
    internal fun classify(envelope: EventEnvelope): Classified? {
        val sk = envelope.sessionKey ?: return null
        val conversationId = conversationKeyResolver(envelope.gatewayId, sk)
        return when (envelope.type) {
            EventEnvelope.APPROVAL_REQUESTED -> Classified.ApprovalRequested(conversationId)
            EventEnvelope.RUN_COMPLETED -> Classified.RunTerminal(
                gatewayId = envelope.gatewayId, conversationId = conversationId, completed = true, runId = envelope.runId,
            )
            EventEnvelope.RUN_FAILED -> Classified.RunTerminal(
                gatewayId = envelope.gatewayId, conversationId = conversationId, completed = false, runId = envelope.runId,
            )
            else -> null // run.aborted 与其余事件不走通知面（回复走会话列表 Flow 差异）
        }
    }

    /** 会话列表投影 Flow 推送入口（壳层 LaunchedEffect 喂入）。 */
    fun pushConversations(list: List<UiConversation>) {
        channel.trySend(Msg.Conversations(list))
    }

    /** 登录态离场（登出/凭据失效）重置：unread 基线与抑制窗不得跨会话复用。 */
    fun reset() {
        channel.trySend(Msg.Reset)
    }

    /** 起消费协程（幂等；DesktopHubApp scope，与窗口同生命周期）。 */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            for (msg in channel) {
                runCatching {
                    when (msg) {
                        is Msg.Event -> handleEvent(msg.classified)
                        is Msg.Conversations -> handleConversationsPush(msg.list)
                        Msg.Reset -> handleReset()
                    }
                }
            }
        }
    }

    // ── 决策层（消费协程独占；internal 供单测直调） ──────────────────────────

    /** 登录后首个推送只记基线（bootstrap 快照积压不算「到达」，iOS conversationsPrimed 同构）。 */
    private var primed = false
    private val lastUnread = HashMap<String, Int>()
    private val replySuppressUntilNanos = HashMap<String, Long>()
    private var latestConversations: List<UiConversation> = emptyList()

    internal fun handleEvent(classified: Classified) {
        when (classified) {
            is Classified.ApprovalRequested -> notifyApproval(classified.conversationId)
            is Classified.RunTerminal -> notifyRunTerminal(
                gatewayId = classified.gatewayId,
                conversationId = classified.conversationId,
                completed = classified.completed,
                runId = classified.runId,
            )
        }
    }

    internal fun handleConversationsPush(list: List<UiConversation>) {
        latestConversations = list
        // wp-9：单次推送读一次静音集（避免每卡片重复读注入 lambda）
        val mutedIds = mutedConversationIds()
        if (list.isEmpty()) {
            // 全空推送 = 登出清库后的空窗：基线清零（重登快照从零重记，不误报「到达」）
            primed = false
            lastUnread.clear()
            replySuppressUntilNanos.clear()
            return
        }
        if (!primed) {
            primed = true
            list.forEach { lastUnread[it.conversationId] = it.unread }
            return
        }
        list.forEach { c ->
            val prev = lastUnread[c.conversationId]
            lastUnread[c.conversationId] = c.unread
            val now = c.unread
            // 只对已知卡片判增量（登录/快照重建后新入列的卡片带 unread 只记录不算「到达」）
            if (prev == null || now <= prev) return@forEach
            if (c.conversationId == openConversationId) return@forEach // 当前打开不提醒
            // wp-9：按会话静音（iOS HubStore.noteConversationsPush 同位 guard）——位置在「当前
            // 打开」之后、抑制窗之前，与 iOS 同序
            if (c.conversationId in mutedIds) return@forEach
            // run.completed 抑制窗内（同一 run 结束，完成通知已含「回来看」信号）吞掉防双响
            val until = replySuppressUntilNanos[c.conversationId]
            if (until != null && nowNanos() < until) return@forEach
            notify(
                Request(
                    kind = Kind.REPLY,
                    conversationId = c.conversationId,
                    title = "有新回复",
                    body = "「${displayLabel(c)}」有新的回复",
                ),
            )
        }
    }

    internal fun handleReset() {
        primed = false
        lastUnread.clear()
        replySuppressUntilNanos.clear()
        latestConversations = emptyList()
    }

    private fun notifyApproval(conversationId: String) {
        val attributed = conversationId.isNotEmpty()
        // 审批不受回复抑制窗影响、也不受「当前打开」抑制：审批是 run 前置门，出现即需注意（iOS 同口径）。
        // wp-9：审批也不受按会话静音影响（吞审批 = 用户可能错过 run 前置门，wp-9 验收②「审批仍弹」）；
        // 已归属静音审批在 iOS HubStore:377 有吞的变体，桌面按 wp-9 scope 口径为准（差异记录在案）。
        val label = if (attributed) labelOf(conversationId) else null
        notify(
            Request(
                kind = Kind.APPROVAL,
                conversationId = conversationId.takeIf { attributed },
                title = "有待审批",
                body = if (label != null) "「$label」有新的审批请求" else "有新的审批请求",
            ),
        )
    }

    private fun notifyRunTerminal(gatewayId: String, conversationId: String, completed: Boolean, runId: String?) {
        // 未映射会话：无归属、无法跳转，显式降级不提醒（不编造归属，iOS noteRunTerminal 同纪律）
        if (conversationId.isEmpty()) return
        // wp-9：按会话静音（iOS HubStore.noteRunTerminal 同位 guard）——吞回复/终态口径的终态侧；
        // 早于 3s 抑制窗置位（iOS 同序：静音即 return，窗不设——静音期间回复本就被吞，窗无意义）
        if (conversationId in mutedConversationIds()) return
        // wp-4 修法二发起方门（两端协调器同口径）：他端发起 / compact 触发的 run 终态静默
        // （未读增量仍由会话列表承载）；null 门 = 旧装配/单测兼容（放行）
        val gate = runOriginGate
        if (gate != null && !gate(gatewayId, runId)) return
        val label = labelOf(conversationId)
        if (completed) {
            replySuppressUntilNanos[conversationId] = nowNanos() + REPLY_SUPPRESS_WINDOW_NANOS
            notify(
                Request(
                    kind = Kind.RUN_COMPLETED,
                    conversationId = conversationId,
                    title = "运行完成",
                    body = "「$label」的任务已运行完成",
                ),
            )
        } else {
            notify(
                Request(
                    kind = Kind.RUN_FAILED,
                    conversationId = conversationId,
                    title = "运行失败",
                    body = "「$label」的任务运行失败",
                ),
            )
        }
    }

    private fun notify(request: Request) {
        // wp-9 总开关单点门：关闭 = 全部通知静默（含审批；打开后下一条通知即时恢复，无缓存态）
        if (!notificationsEnabled()) return
        runCatching { notifier.notify(request) } // 通知发送任何失败静默（不重试不崩）
    }

    /** 卡片显示名（iOS displayLabel 同式：label 空 → agentId → 「会话」兜底）。 */
    private fun labelOf(conversationId: String): String =
        latestConversations.firstOrNull { it.conversationId == conversationId }?.let(::displayLabel) ?: "会话"

    private fun displayLabel(c: UiConversation): String =
        if (c.label.isNotEmpty()) c.label else c.agentId.ifEmpty { "会话" }

    internal companion object {
        /** run.completed 后的回复抑制窗（iOS noteRunTerminal 同值 3s）。 */
        internal const val REPLY_SUPPRESS_WINDOW_NANOS: Long = 3_000_000_000L
    }
}

/**
 * wp-18：macOS 本地通知 osascript 封装（`display notification` AppleScript 派发）。
 *
 * 选型：Compose Desktop JVM 进程无 bundle id，JNA 直调 UNUserNotificationCenter 需要
 * NSApplication 运行环与签名 bundle，不可行；osascript 是无签名依赖的既定路径。
 * 通知归属系统「脚本编辑器」（osascript 载体）：首次派发时系统可能弹授权询问；用户在
 * 系统设置拒绝后，命令仍以退出码 0 返回但横幅被系统抑制（或 -1743 拒绝非零退出）——
 * 两种形态本封装都按「静默降级」处理：不重试、不提示、不抛出（验收：通知权限拒绝静默降级）。
 */
internal class OsascriptNotifier(
    private val osascriptPath: String = "/usr/bin/osascript",
    private val timeoutSeconds: Long = 5,
) : DesktopNotificationCoordinator.DesktopNotifier {
    override fun notify(request: DesktopNotificationCoordinator.Request) {
        runCatching {
            if (!isMacOs()) return // 非 macOS 平台静默不派发（桌面面只在 mac 出通知）
            val process = ProcessBuilder(osascriptPath, "-e", buildAppleScript(request.title, request.body))
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        // 启动失败/超时/权限拒绝：吞掉（验收硬要求，任何路径不抛）
    }

    internal companion object {
        /** AppleScript 派发串（title + body；声音 default，与 iOS .default 同口径）。internal 供单测。 */
        internal fun buildAppleScript(title: String, body: String): String =
            "display notification \"${appleScriptEscape(body)}\" with title \"${appleScriptEscape(title)}\" sound name \"default\""

        /** AppleScript 字符串字面量转义：反斜杠与双引号（最小集，通知内容不含控制字符）。 */
        internal fun appleScriptEscape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun isMacOs(): Boolean =
            System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)
    }
}

/**
 * wp-9：按会话静音集 + 通知总开关的本地偏好存储（生产注入位 = [DesktopNotificationCoordinator]
 * 的 mutedConversationIds / notificationsEnabled）。
 *
 * 口径（与 iOS HubStore 同位）：静音是**本地用户偏好，非账号数据**——跨登出保留（iOS
 * UserDefaults `hub.muted.<id>` 前缀同式，此处 java.util.prefs 用户级节点，跨重启保留；
 * HubStore:672 登出清库路径不动静音偏好，桌面 [SettingsScreen] 登出同样不清）。
 *
 * 存储纪律：进程内内存镜像为权威（读 O(1) 且免 I/O 阻塞决策线程），持久化 best-effort——
 * 任何 Preferences I/O 失败（BackingStoreException/权限）静默降级为纯内存态：进程内仍生效，
 * 下次启动回落到最近一次成功持久化的值；Preferences 体系本身不可用时 node = null，全程内存态。
 * 不抛不崩（桌面静默降级纪律同面延伸到偏好面）。
 */
internal class DesktopMutePreferences(node: Preferences? = null) {

    private val node: Preferences? = node ?: runCatching { Preferences.userRoot().node(NODE_NAME) }.getOrNull()

    private val lock = Any()
    private val mutedInMemory: MutableSet<String>
    private var enabledInMemory: Boolean

    init {
        val loadedMuted = mutableSetOf<String>()
        var loadedEnabled = true
        node?.let { n ->
            // 静音键按前缀过滤（前缀内 conversationId 还原；启用开关键不混入静音集——iOS
            // loadMutedConversations 同式 prefix 过滤）
            runCatching {
                for (key in n.keys()) {
                    if (!key.startsWith(MUTED_KEY_PREFIX)) continue
                    runCatching {
                        if (n.getBoolean(key, false)) loadedMuted += key.removePrefix(MUTED_KEY_PREFIX)
                    }
                }
            }
            runCatching { loadedEnabled = n.getBoolean(KEY_NOTIFICATIONS_ENABLED, true) }
        }
        mutedInMemory = loadedMuted
        enabledInMemory = loadedEnabled
    }

    /** 当前静音集快照（线程安全；决策线程调用，O(n) 拷贝可忽略——静音集量级是个位数会话）。 */
    fun mutedConversationIds(): Set<String> = synchronized(lock) { mutedInMemory.toSet() }

    fun isMuted(conversationId: String): Boolean = synchronized(lock) { conversationId in mutedInMemory }

    /** 通知总开关（true = 开；Preferences 不可用/读到缺省 = 开——降级方向为「多提醒」不吞用户信息）。 */
    val notificationsEnabled: Boolean
        get() = synchronized(lock) { enabledInMemory }

    fun setMuted(muted: Boolean, conversationId: String) {
        synchronized(lock) {
            if (muted) mutedInMemory += conversationId else mutedInMemory -= conversationId
        }
        node?.let { n ->
            runCatching {
                if (muted) n.putBoolean(MUTED_KEY_PREFIX + conversationId, true) else n.remove(MUTED_KEY_PREFIX + conversationId)
            } // 持久化失败 = 内存态降级（进程内生效，重启后回落上次持久化值）
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        synchronized(lock) { enabledInMemory = enabled }
        node?.let { n ->
            runCatching { n.putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled) }
        }
    }

    internal companion object {
        /** 用户级偏好节点（java.util.prefs；macOS = ~/Library/Preferences plist 面）。 */
        private const val NODE_NAME = "dev/sharpxia/agentreins/hub"

        /** 静音键前缀（iOS HubStore.mutedKeyPrefix 同值 `hub.muted.`，两端偏好语义同源）。 */
        private const val MUTED_KEY_PREFIX = "hub.muted."

        /** 总开关键（非静音前缀族，互不混入）。 */
        private const val KEY_NOTIFICATIONS_ENABLED = "hub.notificationsEnabled"
    }
}
