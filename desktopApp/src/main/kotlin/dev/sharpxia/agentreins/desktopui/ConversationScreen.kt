package dev.sharpxia.agentreins.desktopui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.avatar.botSeedFor
import dev.sharpxia.agentreins.hub.avatar.botStateFor
import dev.sharpxia.agentreins.hub.projection.AttachmentRef
import dev.sharpxia.agentreins.hub.projection.CommandChoice
import dev.sharpxia.agentreins.hub.projection.ModelOptionItem
import dev.sharpxia.agentreins.hub.projection.ModelsList
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiMessage
import dev.sharpxia.agentreins.hub.UiWorkActivity
import dev.sharpxia.agentreins.hub.projection.CommandsList
import java.io.File
import java.text.BreakIterator
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OPTIMISTIC_SEQ_SENTINEL: Long = Long.MAX_VALUE

/** 末条消息失败判定（角色系统批次4 聊天页 ERROR 态信号；state/uiState 口径与消息行失败徽标同源）。 */
private fun UiMessage?.isFailedMessage(): Boolean {
    val m = this ?: return false
    return m.state == "failed" || m.uiState == "failed"
}

/** 动态斜杠命令目录内存缓存 TTL（2026-11-07 动态目录轮）：5 分钟，过期即重拉。 */
private const val SLASH_COMMANDS_TTL_MS: Long = 5 * 60 * 1000L

/**
 * 动态斜杠命令目录内存缓存（桌面轻量单例，会话键 → (目录, 取数时刻)）：进程级共享、不落盘
 * ——端点 #21 是纯读目录（服务端解析 conversationId，响应体无 sessionKey 外泄面），缓存
 * 丢失的代价只是一次重拉。限流纪律：TTL + 仅 "/" 草稿触发拉取，远低于列表类 60 次/分/设备。
 */
private object SlashCommandsCache {
    private val entries = mutableMapOf<String, Pair<CommandsList, Long>>()

    /** 未过期返回目录，否则 null（过期与缺失同口径，调用方重拉）。 */
    @Synchronized
    fun valid(conversationId: String, nowMs: Long = System.currentTimeMillis()): CommandsList? =
        entries[conversationId]
            ?.takeIf { nowMs - it.second < SLASH_COMMANDS_TTL_MS }
            ?.first

    @Synchronized
    fun put(conversationId: String, list: CommandsList, nowMs: Long = System.currentTimeMillis()) {
        entries[conversationId] = list to nowMs
    }
}

/**
 * 端点 #21 拉取（纯读直调 REST，不走命令队列）：经 runtime.authStack 复用 bearer 刷新栈调
 * HubApi.commands(conversationId)，与 loadEarlierMessages 同款通道纪律。返回 null = 通道
 * 不可用（未登录）或 REST 未成功，调用方保留静态六降级；Cancel 不吞（屏幕销毁即放弃）。
 */
private suspend fun fetchSlashCommands(client: DefaultHubClient, conversationId: String): CommandsList? {
    val stack = client.runtime.authStack ?: return null
    return try {
        stack.auth.withBearer { stack.api.commands(conversationId) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * 会话模型目录内存缓存（#22，v1.1 additive 2026-11-08；桌面轻量单例，会话键 → (目录, 取数
 * 时刻)）：进程级共享、不落盘，纪律与 [SlashCommandsCache] 全同（TTL 5 分钟、纯读目录、
 * 限流远低于列表类 60 次/分/设备）。
 */
private object ModelsCache {
    private val entries = mutableMapOf<String, Pair<ModelsList, Long>>()

    /** 拉取失败标记（2026-11-18 hermes 模型卡挂死修复轮）：卡显式降级不再永转圈；成功即清除。
     *  快照状态 map——失败落标记即触发重组（否则纯 map 变更无重组信号，卡停在进度态）。 */
    private val failures = mutableStateMapOf<String, Long>()

    /** 未过期返回目录，否则 null（过期与缺失同口径，调用方重拉）。 */
    @Synchronized
    fun valid(conversationId: String, nowMs: Long = System.currentTimeMillis()): ModelsList? =
        entries[conversationId]
            ?.takeIf { nowMs - it.second < SLASH_COMMANDS_TTL_MS }
            ?.first

    @Synchronized
    fun put(conversationId: String, list: ModelsList, nowMs: Long = System.currentTimeMillis()) {
        entries[conversationId] = list to nowMs
        failures.remove(conversationId)
    }

    @Synchronized
    fun markFailed(conversationId: String, nowMs: Long = System.currentTimeMillis()) {
        failures[conversationId] = nowMs
    }

    @Synchronized
    fun failed(conversationId: String): Boolean = failures.containsKey(conversationId)
}

/**
 * 端点 #22 拉取（#22，v1.1 additive 2026-11-08；纯读直调 REST，不走命令队列）：经
 * runtime.authStack 复用 bearer 刷新栈调 HubApi.models(conversationId)，与 fetchSlashCommands
 * 同款通道纪律。返回 null = 通道不可用（未登录）或 REST 未成功，调用方不渲染交互卡
 * （静默降级，与命令目录同纪律）；Cancel 不吞（屏幕销毁即放弃）。
 */
private suspend fun fetchModels(client: DefaultHubClient, conversationId: String): ModelsList? {
    val stack = client.runtime.authStack ?: return null
    return try {
        stack.auth.withBearer { stack.api.models(conversationId) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}

/**
 * /think 卡数据面（2026-11-08 自检轮；iOS HubViews.thinkCommand 桌面同构）：Gateway
 * /think.level 为函数型动态闭集（按当前模型解析，commands.list 不下发值仅标
 * args[].dynamic=true）——结构化数据出口 = REST #22 当前模型条目的 thinkingLevels；卡
 * 选项 = default 前置 + levels（剔除 value=="default"），镜像 OpenClaw
 * listThinkingLevelChoices 形状。当前模型缺失（非投影路径/缓存未到）或无等级 → null
 * （无卡退化，不编造值集，ADR-14）。
 */
internal fun thinkCommand(modelsList: ModelsList?): SlashCommand? {
    val current = modelsList?.models?.firstOrNull { it.current == true } ?: return null
    val levels = current.thinkingLevels.orEmpty()
    if (levels.isEmpty()) return null
    val choices = buildList {
        add(CommandChoice(value = "default", label = "默认"))
        levels.forEach { if (it.value != "default") add(it) }
    }
    return SlashCommand(
        name = "/think",
        summary = "切换思考等级（当前模型：${current.name}）",
        takesArgument = true,
        choices = choices,
        argName = "level",
    )
}

/**
 * 会话页（wp-6 验收④ + wp-10 交互 parity + wp-18 渲染）：消息时间线（乐观行 + 流式行 +
 * 合成行分隔条）+ 发送 + 已读上报。
 * 数据源 = 投影库 Flow（observeMessages，seq 升序）；写路径只走 commands 幂等队列。
 *
 * wp-10（对齐 iOS wp-8/wp-9）：
 * - 消息操作：右键菜单——复制全文（非合成行非空正文）/ 失败行重发（新意图新键，ADR-07）；
 * - Stop：活跃 run 期间 composer 常驻 Stop（grokbot 式，替代发送键），activeRunId 缺失
 *   （映射未学习）降级「结束中」提示态，不假设 runId 恒有值；
 * - 审批卡：归属本会话的 pending 审批横条置顶于时间线上方（审批是 run 的前置门，不进滚动区），
 *   批准/拒绝走幂等队列，决策通道不可用显式降级可重试；
 * - 空正文防线：发送条禁空白 + 渲染侧占位（投影层零过滤，空 content 行照常入库）。
 * wp-18：助手行走 [MarkdownText] 轻量 Markdown 渲染（标题/列表/代码块/粗斜体；流式行带
 * ▌ 光标渐进渲染），无 Markdown 语素或解析异常自动回退 wp-6 纯文本路径。
 * wp-5（桌面时间线补齐，iOS wp-7①/wp-12 同型）：
 * - 顶部「加载更早」翻页行三态（在途/可翻/翻到头收尾条）：canLoadEarlier 乐观判据
 *   （lastSeq > 已载行数）+ 首翻后服务端 hasMore 接管 + 游标倒退防护（快照重建弃旧回落），
 *   走 HubClient.loadEarlierMessages（wp-12 REST #8，门面已就绪本文件此前零消费）；
 * - 滚动锚定重做（替代旧「只挂 messages.size 无条件拽底」——旧实现流式原地增长不跟随 +
 *   用户上翻被拽回 + 与翻页恢复打架）：底部锚定态判定（几何相位区分，程序化跟随帧/内容
 *   增长帧不改锚定态）+ 贴底条件化（尾签变化 且 在底才跟随）+ 翻页落地锚行恢复（保阅读位置）；
 * - 时间线空态「暂无消息」占位（对齐会话列表空态纪律）。
 * wp-6（composer 补齐，iOS wp-14 parity + 桌面键盘发送语义）：
 * - 斜杠命令面板：draft 由 "/" 起手时悬浮于发送区上方，选中即动作（目录/前缀筛选/带参
 *   预填语义见 SlashCommandCatalog + SlashCommandRules——无参命令即选即发走发送同通道，
 *   带参命令预填命令文本续写参数；无命中/进入参数书写段自动收起，其余输入零干扰）；
 * - 动态目录（2026-11-07 轮）：目录 = 端点 #21 动态条目 ∪ 静态六（动态优先静态保底），
 *   "/" 草稿触发拉取、5 分钟 TTL 内存缓存、失败回落静态六（见 SlashCommandsCache）；
 * - Enter 键位矩阵（2026-11-21 回车语义轮，裁决纯函数 resolveEnterKey 全表，与 Catalyst
 *   ComposerKeyHandlers 一张表）：IME 组合中全键让行（组合期 Enter 属提交候选词）；
 *   Shift+Enter 任何情况强制换行；真空白草稿消费不发送；Cmd/Ctrl+Enter 跟 draftHasContent
 *   强制发送（多行含；附件独发含——同日债清轮对齐 Catalyst ⌘Return 快捷键通道，原「桌面
 *   键盘恒 no-op」知情差异消除）；面板可见 Enter=原样发送当前输入（不走补全）；单行裸
 *   Enter 发送、多行裸 Enter 与附件独发裸 Enter 换行（不再发送）。↑↓ 移面板高亮（环绕）、
 *   Tab 补全高亮（无键盘动作即首行）命令+空格（面板可见时消费，不可见透传）；
 * - 活跃 run 期间「运行中发送将排队」提示（iOS wp-14 同文案同语义：chat.send 不透传
 *   queueMode，发送照常、按 Gateway 默认队列语义排队；发送键位仍是 wp-8② Stop）。
 * 判定纯函数在 TimelineScroll.kt（:desktopApp:test 回归锚），composer 规则纯函数在
 * SlashCommandRules.kt（同回归锚纪律）。
 */
@Composable
internal fun ConversationScreen(
    client: DefaultHubClient,
    conversationId: String,
    label: String,
    conversations: List<UiConversation>,
    approvals: List<UiApproval>,
    decisions: ApprovalDecisionState,
    // 纯显示偏好（2026-11-19）：流式输出/思考中指示——权威在 DesktopDisplayPreferences，
    // HubShell 持镜像喂入；**数据面不动**，只裁渲染分支（streamingRowPresentation 决策）。
    streamingPlayback: Boolean,
    thinkingIndicator: Boolean,
    // 角色系统批次4：动效档总开关（avatarPrefs UI 镜像，SettingsScreen:465 同口径）——
    // composer 行首交互角色的 motion 门控。
    motionEnabled: Boolean,
) {
    val messages by client.observeMessages(conversationId).collectAsState(initial = emptyList())
    // wp-15：工作过程披露条数据面（hubToolEvents + hubRunStatus 两表合流快照）——此前零消费，
    // 会话打开即订阅；会话切换随 HubShell key(conversationId) 重挂换订阅。
    val workActivity by client.observeWorkActivity(conversationId)
        .collectAsState(initial = UiWorkActivity(emptyList(), emptyList()))
    // wp-6：TextFieldValue 携带 IME 组合区间（composition），Enter 发送裁决要区分
    // 「组合窗中回车 = 提交候选词」与「普通回车 = 发送」——纯 String 拿不到该信号。
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // v1.2.0 多模态（设计稿 §6 桌面行）最小附件入口草稿态：FileDialog 选图 → uploadMedia
    // 上传成功即挂 AttachmentRef 草稿 chip → 发送随 commands.sendMessage 携带。进程内轻量态，
    // 随会话切换（remember key）重置；上传失败显式横幅可重试，不静默。
    var draftAttachments by remember(conversationId) { mutableStateOf<List<AttachmentRef>>(emptyList()) }
    var uploadingAttachments by remember(conversationId) { mutableStateOf(false) }
    var attachmentError by remember(conversationId) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    // 2026-11-07 二轮：回到底部按钮可见性——最后一条 item 不在视口即显示（derivedStateOf
    // 只在跨阈值时重composition，逐帧布局信息不触发）
    val showJumpToBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible < info.totalItemsCount - 1
        }
    }
    val scope = rememberCoroutineScope()
    // 已读上报基线：只认真实服务端 seq（乐观行占位 seq=Long.MAX_VALUE、failed 行非服务端消息，均不计入）
    var reportedReadUpTo by remember { mutableStateOf(0L) }

    // wp-8②：实时行——conversationId 是打开时刻的句柄，hasActiveRun/activeRunId 随 run.* 事件
    // 后续翻转，Stop 按钮态一律从会话列表现值取，不得用打开时刻快照（会话列表 Flow 持续推送）。
    val liveConv = conversations.firstOrNull { it.conversationId == conversationId }
    // 多网关轮（v1.3.0，设计稿 §3.2）：会话页头部网关名 caption（会话行网关名同构延伸）——
    // hub.gateways 按 gatewayId 查名，空名/未知回退 gatewayId 尾段（gatewayNameFor 口径）；
    // 归属未学习（liveConv = null）时省略不占位，不编造。
    val gateways by client.observeGateways().collectAsState(initial = emptyList())
    val isRunActive = liveConv?.hasActiveRun == true
    // Stop 可执行 = 活跃 run 且 activeRunId 已习得（wp-5 v4 投影）。映射未学习场景（null——
    // DefaultEventProjector.setRun 在 resolveConversation 失败时直接 return，不落库）置灰为
    // 「结束中」提示态，不假设 runId 恒有值、不误触。
    val canCancelRun = !liveConv?.activeRunId.isNullOrEmpty()

    // ── 角色系统批次4：composer 行首 32dp 交互角色的眼神触发面（与 iOS HubChatView 同参同观感）──
    // 触发三路：输入聚焦下视（onFocusChanged）、composer 区指针注视（pointerInput Move，
    // 锚/半径/窗幅 = BotCharacter 双端同表常量）、新消息一次性下瞥 400ms（尾行 seq 推进触发，
    // 首载不瞥；乐观占位 seq 哨兵行与落地行各瞥一次，体感均为「有新行」）。
    // gaze 合成优先级：注视 > 聚焦下视 > 下瞥窗 > null。
    var composerFocused by remember { mutableStateOf(false) }
    var pointerGaze by remember { mutableStateOf<BotGaze?>(null) }
    var glanceActive by remember { mutableStateOf(false) }
    var glancePrimed by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    LaunchedEffect(messages.lastOrNull()?.seq) {
        if (!glancePrimed) {
            glancePrimed = true // 首载不瞥（对齐 iOS onChange 语义）
            return@LaunchedEffect
        }
        glanceActive = true
        delay(400)
        glanceActive = false
    }
    // DONE 瞬时窗：hasActiveRun 真→假 且终态非 failed（本页有失败信号，较列表行多一层门控）
    // 触发 900ms；跳变即重启协程取消在途 delay，天然防陈旧回落（批次3 列表卡同式）。
    var prevActive by remember { mutableStateOf(isRunActive) }
    var doneTransient by remember { mutableStateOf(false) }
    LaunchedEffect(isRunActive) {
        when {
            isRunActive -> doneTransient = false
            prevActive && !messages.lastOrNull().isFailedMessage() -> {
                doneTransient = true
                delay(900)
                doneTransient = false
            }
        }
        prevActive = isRunActive
    }
    // 角色六态同参消费（批次2 列表行恒 false 的两路，本页为真源）：thinkingNow=workActivity
    // 细分（wp-15，仅聊天页细分）；ERROR=末条消息 failed（全覆盖于聊天页，列表行无此信号）。
    val chatThinkingNow = workActivity.phases.lastOrNull()?.phase == "thinking"
    val chatAwaitingApproval = approvals.any { it.conversationId == conversationId }
    val chatLastMessageFailed = messages.lastOrNull().isFailedMessage()
    val chatGaze: BotGaze? = pointerGaze
        ?: if (composerFocused || glanceActive) BotGaze.DOWN else null

    // wp-5 滚动锚定 + 历史翻页状态机（会话切换随 HubShell key(conversationId) 重挂整组重置，
    // 与 iOS resetPagingState 随会话期同语义）。判定纯函数见 TimelineScroll.kt。
    var atBottom by remember { mutableStateOf(true) }
    var followAnimating by remember { mutableStateOf(false) }
    var earlierExhausted by remember { mutableStateOf(false) }
    var earlierCursor by remember { mutableStateOf<Long?>(null) }
    var loadingEarlier by remember { mutableStateOf(false) }
    var loadEarlierError by remember { mutableStateOf<String?>(null) }
    var restoreArmed by remember { mutableStateOf(false) }
    var restoreAnchorId by remember { mutableStateOf<String?>(null) }
    // 会话实时 lastSeq 快照化：conversations 是参数引用，效果闭包捕获会过期——翻页行可见性
    // 在效果闭包内也要取现值（iOS liveSelected?.lastSeq 同口径实时行）。
    var liveLastSeq by remember { mutableStateOf(0L) }
    val liveSeqNow = liveConv?.lastSeq ?: 0L
    LaunchedEffect(liveSeqNow) { liveLastSeq = liveSeqNow }

    /** 顶部翻页行是否在滚动区内（渲染态用组合期 canLoadEarlierNow；效果闭包内用本快照现值）。 */
    val loaderVisibleNow = {
        earlierRowState(
            loading = loadingEarlier,
            canLoad = canLoadEarlier(earlierExhausted, liveLastSeq, messages.size, messages.isEmpty()),
            exhausted = earlierExhausted,
        ) != EarlierRowState.Hidden
    }

    /** LazyColumn 项下标换算：layoutIndex = messagesIndex + 常驻前缀项数（翻页行 + 错误横幅）。 */
    val prefixCountNow = { timelinePrefixItemCount(loaderVisibleNow(), loadEarlierError != null) }

    // 已读上报（会话完整渲染后 readUpTo = 已渲染最大真实 seq；只推进不回退，避免噪声）
    val maxRenderedSeq = messages
        .filter { it.seq != OPTIMISTIC_SEQ_SENTINEL && it.state != "failed" }
        .maxOfOrNull { it.seq } ?: 0L
    LaunchedEffect(conversationId, maxRenderedSeq) {
        if (maxRenderedSeq > reportedReadUpTo) {
            reportedReadUpTo = maxRenderedSeq
            client.markRead(conversationId, maxRenderedSeq)
        }
    }

    // wp-5 滚动锚定①：底部锚定态判定（iOS wp-7① onScrollGeometryChange + onScrollPhaseChange
    // 同型）——锚定态只被用户驱动的滚动帧与滚动结束沿翻转；流式内容增长帧（非用户驱动且无
    // 滚动沿）不改锚定态，据增长解锚会让跟随永久停摆。几何零值（空列表）不判。
    LaunchedEffect(listState) {
        var prevScrolling = false
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val lastInfo = visible.lastOrNull()
            ScrollSample(
                scrolling = listState.isScrollInProgress,
                lastItemVisible = info.totalItemsCount > 0 && lastInfo != null &&
                    lastInfo.index == info.totalItemsCount - 1,
                lastItemEnd = lastInfo?.let { it.offset + it.size } ?: 0,
                viewportEnd = info.viewportEndOffset,
                contentFullyVisible = info.totalItemsCount > 0 && visible.isNotEmpty() &&
                    visible.first().index == 0 && visible.last().index == info.totalItemsCount - 1,
            )
        }.collect { s ->
            val justSettled = !s.scrolling && prevScrolling
            prevScrolling = s.scrolling
            if (s.lastItemVisible &&
                shouldRecomputeAnchor(isUserDrivenScroll(s.scrolling, followAnimating), justSettled)
            ) {
                atBottom = isAtBottom(s)
            }
        }
    }

    // wp-5 滚动锚定②：贴底条件化跟随（iOS wp-7① onChange tailSignature 同型）——尾签变化 且
    // 在底部锚定态才跟随；尾签覆盖行数与尾行身份/状态/内容量（比旧「只看条数」多覆盖流式
    // 原地增长与占位行收敛）。首签（打开会话/空会话收首批）直接定位到尾（iOS onAppear 同位）；
    // 翻页 prepend 也变签，但被 atBottom 守卫拦住（翻页时用户在上方读史）。
    LaunchedEffect(listState) {
        var primed = false
        var lastSig: String? = null
        snapshotFlow { tailSignature(messages) }.collect { sig ->
            val prevSig = lastSig
            val isFirst = !primed
            primed = true
            lastSig = sig
            if (messages.isEmpty()) return@collect
            if (isFirst || prevSig.isNullOrEmpty()) {
                listState.scrollToItem(messages.lastIndex + prefixCountNow())
                return@collect
            }
            if (!shouldFollowTail(tailChanged = sig != prevSig, atBottom = atBottom)) return@collect
            followAnimating = true
            try {
                listState.animateScrollToItem(messages.lastIndex + prefixCountNow())
            } finally {
                followAnimating = false
            }
        }
    }

    // wp-5 滚动锚定③：翻页落地滚动恢复（iOS onChange messages.count + takeRestoreScrollTarget
    // 同型）——新页合入列表后滚回触发前最老一行（锚行置顶），保住阅读位置。锚仅在成功回执
    // 后武装，在途新尾消息到达不触发；锚行随化身更替消失即弃锚不滚。
    LaunchedEffect(listState) {
        snapshotFlow { messages.map { it.messageId } }.collect { ids ->
            if (!restoreArmed) return@collect
            val target = restoreScrollTarget(
                armed = true,
                anchorMessageId = restoreAnchorId,
                messageIds = ids,
            )
            restoreArmed = false
            restoreAnchorId = null
            if (target != null) {
                listState.scrollToItem(target + prefixCountNow(), 0)
            }
        }
    }

    // 组合期渲染态（每次重组按现值重算）
    val canLoadEarlierNow = canLoadEarlier(earlierExhausted, liveSeqNow, messages.size, messages.isEmpty())
    val loaderState = earlierRowState(loadingEarlier, canLoadEarlierNow, earlierExhausted)

    // wp-12 加载更早（iOS HubStore.loadEarlier 同型）：按 oldestSeq keyset 翻页，REST 走
    // loadEarlierMessages（nil = 通道不可用/REST 未成功，显式降级可重试不静默）；成功回执后
    // 服务端 hasMore 接管按钮可见性，翻到头锁存。限流纪律：一次一请求（loadingEarlier 互斥）。
    fun loadEarlier() {
        if (loadingEarlier || earlierExhausted || !canLoadEarlierNow) return
        val oldestLoaded = messages.firstOrNull()
        val before = nextBeforeCursor(earlierCursor, oldestLoaded?.seq) ?: return
        if (before < 1) return // 契约 Before minimum: 1（08 §4.3）
        // 阅读位置恢复锚 = 触发前最老一行（新页将合入其上方，落地后滚回该行，视口不跳）
        restoreAnchorId = oldestLoaded?.messageId
        restoreArmed = false
        loadingEarlier = true
        loadEarlierError = null
        scope.launch {
            val page = client.loadEarlierMessages(conversationId, before)
            loadingEarlier = false
            if (page == null) {
                restoreAnchorId = null
                restoreArmed = false
                loadEarlierError = "加载更早失败（网络或服务不可达），可重试"
                return@launch
            }
            earlierCursor = page.oldestSeq
            if (page.count == 0 || !page.hasMore) {
                // 翻到头锁存：入口消失，顶部呈收尾条（验收项）
                earlierExhausted = true
                restoreAnchorId = null
                restoreArmed = false
            } else {
                restoreArmed = true
            }
            loadEarlierError = null
        }
    }

    // 2026-11-21 面板键位轮：斜杠面板键盘高亮行（nil=未动过键盘，高亮落第 0 行；派生生效值
    // 见下方 slashHighlightIndex）。显式重置口径：Tab 补全/发送/预填后清空——面板重开高亮
    // 回首行（iOS 只 clamp 不重置，「↑↓ 保持现状」轮未修——两端口径差异知情在案）。
    // 会话切换随 HubShell key(conversationId) 整体重挂自然重置，无需额外 key。
    var slashKeyboardIndex by remember { mutableStateOf<Int?>(null) }

    // wp-6：发送一条（发送键 / Enter / 无参斜杠命令选中三路同一通道）——commands 幂等队列，
    // sendMessage 每次生成新幂等键（ADR-07）；空白草稿禁发（与发送键 isNotBlank 防线同口径）。
    // v1.2.0 多模态：纯图片消息允许空文本（契约「message 与 attachments 至少一项非空」，服务端
    // 校验；空文本随 attachments 发出，乐观行落附件列即时渲染 chip）；两者皆空仍禁发。
    fun sendDraft() {
        val text = input.text.trim()
        if (text.isEmpty() && draftAttachments.isEmpty()) return
        val attachments = draftAttachments
        input = TextFieldValue("")
        draftAttachments = emptyList()
        slashKeyboardIndex = null // 发送后草稿清空、面板收起：高亮回 0（重开口径）
        scope.launch { client.commands?.sendMessage(conversationId, text, attachments) }
    }

    /**
     * v1.2.0 多模态（设计稿 §6 桌面行）：附件入口——JVM Swing FileDialog 选图（≤4 张，
     * [MAX_DRAFT_ATTACHMENTS]）→ 逐张经 shared [DefaultHubClient.uploadMedia]（端点 #23，
     * 服务端魔数嗅探 + 20MB 帽为准）→ 成功即挂草稿 chip。失败显式 [attachmentError] 可重试
     * 不静默；白名单外扩展名与超限选择本地先拦（减少无谓上传）。取数挂屏幕级 scope（同斜杠
     * 目录轮纪律：逐张上传不被 UI 重组重启，屏幕销毁即放弃）。
     */
    fun pickAndUploadAttachments() {
        if (uploadingAttachments) return
        val remaining = MAX_DRAFT_ATTACHMENTS - draftAttachments.size
        if (remaining <= 0) {
            attachmentError = "附件最多 $MAX_DRAFT_ATTACHMENTS 张"
            return
        }
        uploadingAttachments = true
        attachmentError = null
        scope.launch {
            try {
                val picked = pickImageFiles()
                if (picked.isEmpty()) return@launch // 用户取消：finally 收在途标记
                val images = picked.filter { imageMimeOf(it.name) != null }
                val accepted = images.take(remaining)
                val hints = buildList {
                    if (images.size > remaining) add("附件最多 $MAX_DRAFT_ATTACHMENTS 张，仅收前 $remaining 张")
                    val rejected = picked.size - images.size
                    if (rejected > 0) add("$rejected 个非图片文件已跳过（白名单 jpg/png/webp/gif）")
                }
                if (hints.isNotEmpty()) attachmentError = hints.joinToString("；")
                for (file in accepted) {
                    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                    val upload = client.uploadMedia(
                        conversationId,
                        bytes,
                        mime = imageMimeOf(file.name) ?: continue,
                        fileName = file.name,
                    )
                    draftAttachments = draftAttachments + AttachmentRef(
                        mediaId = upload.mediaId,
                        mimeType = upload.mimeType,
                        fileName = file.name,
                        sizeBytes = upload.sizeBytes,
                        width = upload.width,
                        height = upload.height,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubException) {
                // 服务端确定性拒绝（400 HUB-BIZ-004 类型/大小、401/403 鉴权、429 限流）与网络
                // 失败分开呈现——错误码原样带出，不吞不译
                attachmentError = "附件上传被拒（${e.payload.code}），可重试"
            } catch (_: Exception) {
                attachmentError = "附件上传失败（网络或服务不可达），可重试"
            } finally {
                uploadingAttachments = false
            }
        }
    }

    // wp-6 + 2026-11-08 修复轮（用户拍板）：斜杠命令**点行 = 直接发送**——无论是否带参，
    // 与发送键同一通道（commands.sendMessage 普通文本，Gateway 原生斜杠语义）；带参命令
    // 裸发由 Gateway 回用法提示，需要写参数时走行尾 ✎ 预填路径（prefillSlashCommand）。
    fun selectSlashCommand(cmd: SlashCommand) {
        input = TextFieldValue("")
        slashKeyboardIndex = null // 点行发送：面板收起，高亮回 0（重开口径）
        scope.launch { client.commands?.sendMessage(conversationId, cmd.name) }
    }

    // 2026-11-08 修复轮：行尾 ✎ = 预填命令文本（含尾随空格、光标落行尾）进输入框续写参数，
    // 等待用户发送（不直接发送）。2026-11-21 面板键位轮：Tab 补全同走此函数（补全后草稿带
    // 空格 → 面板收起 → 高亮态清空重开口径）。
    fun prefillSlashCommand(cmd: SlashCommand) {
        input = TextFieldValue(cmd.name + " ", TextRange(cmd.name.length + 1))
        slashKeyboardIndex = null
    }

    // 2026-11-07 动态目录轮：动态斜杠命令目录（REST 端点 #21）会话态 + 草稿触发拉取（下方
    // LaunchedEffect）；合并规则见 SlashCommandRules.mergedSlashCommands（动态优先、静态保底）。
    var dynamicCommands by remember(conversationId) {
        mutableStateOf(SlashCommandsCache.valid(conversationId))
    }
    var slashFetchInFlight by remember(conversationId) { mutableStateOf(false) }
    var slashFetchFailed by remember(conversationId) { mutableStateOf(false) }
    LaunchedEffect(input.text, conversationId) {
        if (!input.text.startsWith("/")) {
            // 退出斜杠态重置失败标记：下次再进 "/" 草稿即重试（桌面侧「重新聚焦」对位语义）
            slashFetchFailed = false
            return@LaunchedEffect
        }
        if (slashFetchInFlight || slashFetchFailed) return@LaunchedEffect
        val cached = SlashCommandsCache.valid(conversationId)
        if (cached != null) {
            dynamicCommands = cached
            return@LaunchedEffect
        }
        slashFetchInFlight = true
        // 取数挂屏幕级 scope（而非本 effect 协程）：逐字符键入会重启本 effect 并取消在途
        // REST，残留 in-flight 标记死锁；挂屏幕 scope 生命周期与「聚焦重试」语义一致。
        scope.launch {
            val fetched = fetchSlashCommands(client, conversationId)
            slashFetchInFlight = false
            if (fetched == null) {
                // 失败保留静态六，不缓存不静默重试（下次退出/再进斜杠态或换会话再试）
                slashFetchFailed = true
            } else {
                SlashCommandsCache.put(conversationId, fetched)
                dynamicCommands = fetched
            }
        }
    }

    // wp-6：斜杠命令建议——draft 起手 "/" 且未进入参数书写段时给出目录前缀命中；普通消息、
    // URL、多行草稿等其余输入零干扰不触发（筛选细则见 SlashCommandCatalog.suggestions）。
    // 动态目录轮：数据源换合并目录（动态 #21 ∪ 静态六），筛选与 Enter 裁决语义零改动。
    val slashSuggestions = SlashCommandCatalog.suggestions(
        forDraft = input.text,
        catalog = mergedSlashCommands(dynamicCommands?.commands ?: emptyList()),
    )
    // 2026-11-21 面板键位轮：生效高亮行（↑↓ 移动 / Tab 补全 / 面板行底渲染三方消费）——
    // clamp 口径镜像 iOS slashActiveIndex（纯函数见 SlashCommandRules.slashActiveIndex）。
    val slashHighlightIndex = slashActiveIndex(slashKeyboardIndex, slashSuggestions.size)

    // 交互卡数据面（2026-11-08 修复轮泛化 + 同日自检轮补齐）：`/model`、`/models` → 模型
    // 目录卡（REST #22；models 为列表型指令，结构化数据面同源——点按钮仍发 `/model <id>`
    // 完成切换）；`/think` → 思考等级卡（Gateway 动态闭集的值 = #22 当前模型 thinkingLevels，
    // 见 thinkCommand）；其余带静态选项闭集的命令（Gateway catalog args 有界视图，如
    // /usage /exec）→ 通用选项卡。挂载判定（无状态可重推导）：最新一次裸交互命令交换的
    // 回复行——找最后一条正文恰为 `/<交互命令名>` 的用户行，其后第一条 assistant final 行
    // 即回复（两者间不得夹任何其他用户行/assistant 行——回复流式未落定期间不出卡），该行
    // 就地渲染交互卡替代原回复文本；点选项后卡面保留，再次裸发同名命令时卡面迁移（同时
    // 只活一张卡）。
    val mergedCatalog = mergedSlashCommands(dynamicCommands?.commands ?: emptyList())
    fun interactiveCommandFor(bareName: String): SlashCommand? {
        if (bareName == "model" || bareName == "models") return SlashCommand(
            name = "/model",
            summary = "切换会话模型（REST #22 动态目录）",
            takesArgument = true,
        )
        if (bareName == "think") {
            return thinkCommand(ModelsCache.valid(conversationId))
        }
        return mergedCatalog.firstOrNull {
            it.name.removePrefix("/").lowercase() == bareName && !it.choices.isNullOrEmpty()
        }
    }
    val interactiveCard: Pair<String, SlashCommand>? = run {
        val lastBareIdx = messages.indexOfLast {
            it.role == "user" && run {
                val t = it.content.trim().lowercase()
                t.startsWith("/") && interactiveCommandFor(t.removePrefix("/")) != null
            }
        }
        if (lastBareIdx < 0) {
            null
        } else {
            val bare = messages[lastBareIdx].content.trim().lowercase().removePrefix("/")
            val cmd = interactiveCommandFor(bare) ?: return@run null
            var result: String? = null
            for (idx in (lastBareIdx + 1) until messages.size) {
                val m = messages[idx]
                if (m.role == "user") break // 命令与回复间被其他用户行打断 = 交换不完整
                if (m.role == "assistant") {
                    result = if (m.state == "final" && m.content.isNotBlank()) m.messageId else null
                    break // 回复流式未落定 → 不出卡
                }
            }
            result?.let { it to cmd }
        }
    }
    LaunchedEffect(interactiveCard?.first) {
        if (interactiveCard == null) return@LaunchedEffect
        // 模型目录在位（model 卡数据面；choices 卡目录由下方候选信号触发）
        if (ModelsCache.valid(conversationId) == null) {
            fetchModels(client, conversationId)?.let { ModelsCache.put(conversationId, it) }
                ?: ModelsCache.markFailed(conversationId)
        }
    }
    // choices/think 卡数据面鸡生蛋修复：用户可能不经 "/" 草稿直接发出交互命令——每条新
    // 消息落定幂等触发一次目录拉取（TTL 缓存命中即空转），目录到位后 interactiveCard 判定
    // 出卡。think 卡数据面 = #22 当前模型 thinkingLevels，模型目录同口径补拉（2026-11-08
    // 自检轮）。
    LaunchedEffect(messages.lastOrNull()?.content) {
        if (SlashCommandsCache.valid(conversationId) == null) {
            fetchSlashCommands(client, conversationId)?.let { SlashCommandsCache.put(conversationId, it) }
        }
        if (ModelsCache.valid(conversationId) == null) {
            fetchModels(client, conversationId)?.let { ModelsCache.put(conversationId, it) }
                ?: ModelsCache.markFailed(conversationId)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部：会话名（投影 label）+ 网关名 caption（多网关轮 §3.2，归属未学习省略）
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label.ifBlank { "（未命名会话）" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val gatewayId = liveConv?.gatewayId.orEmpty()
                    if (gatewayId.isNotEmpty()) {
                        Text(
                            text = gatewayNameFor(gateways, gatewayId),
                            fontSize = 10.sp,
                            color = Color(0xFF999999),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // wp-9：审批横条（置顶于时间线上方）——审批是 run 的前置门，出现即需注意，不进滚动区防淹没
        AnimatedVisibility(
            visible = approvals.isNotEmpty(),
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                approvals.forEach { a ->
                    ApprovalCard(a, conversations, decisions, client, scope)
                }
            }
        }

        // 2026-11-07 二轮（x总改版）：工作过程披露条整体撤显示（iOS workProcessSection 同批）——
        // 进行中信号收敛到回复占位行「正在思考…/xx 正在处理中…」；workActivity 数据面保留
        // （占位行 thinking 判定消费）。

        // 消息时间线（wp-5：空态对齐会话列表空态纪律——居中主行 + 灰色副行）
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无消息", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "发送第一条消息开始（历史随快照与事件流回填）",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            // 2026-11-07 二轮：回到底部悬浮按钮容器（最后一条 item 不在视口时浮现，见 overlay）
            Box(Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // wp-12 历史翻页顶部行（时间线首行，随滚动区移动）——三态由状态机驱动：
                // 在途呈进度态（防并发翻页，REST 限流 60 次/分/设备）；可翻呈按钮；翻到头锁存
                // 呈收尾条。短会话（≤50 行，快照即全量）整行不出现，界面零噪声。
                when (loaderState) {
                    EarlierRowState.InFlight -> item(key = "earlier-loader-inflight") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("加载更早…", fontSize = 11.sp, color = Color(0xFF999999))
                        }
                    }
                    EarlierRowState.Loadable -> item(key = "earlier-loader") {
                        TextButton(
                            onClick = ::loadEarlier,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("↑ 加载更早", fontSize = 12.sp) }
                    }
                    EarlierRowState.Exhausted -> item(key = "earlier-loader-end") {
                        Text(
                            "已到最早消息",
                            fontSize = 11.sp,
                            color = Color(0xFF999999),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                    EarlierRowState.Hidden -> Unit
                }
                // wp-12 翻页失败降级横幅（wp-9 同纪律：显式可重试，不静默）
                if (loadEarlierError != null) {
                    item(key = "earlier-loader-error") {
                        Text(
                            "⚠ $loadEarlierError",
                            fontSize = 11.sp,
                            color = Color(0xFFC53030),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                items(messages, key = { it.messageId }) { m ->
                    val anchorCmd = if (interactiveCard?.first == m.messageId) interactiveCard?.second else null
                    MessageRow(
                        m,
                        client,
                        conversationId,
                        scope,
                        thinkingNow = workActivity.phases.lastOrNull()?.phase == "thinking",
                        agentLabel = label.ifBlank { "Agent" },
                        streamingPlayback = streamingPlayback,
                        thinkingIndicator = thinkingIndicator,
                        runActive = isRunActive,
                        interactiveCommand = anchorCmd,
                        onModelSelect = { item ->
                            input = TextFieldValue("")
                            scope.launch { client.commands?.sendMessage(conversationId, "/model ${item.id}") }
                        },
                        onOptionSelect = { cmd, value ->
                            input = TextFieldValue("")
                            scope.launch { client.commands?.sendMessage(conversationId, "${cmd.name} $value") }
                        },
                    )
                }
            }
            // 2026-11-07 二轮：回到底部悬浮按钮（iOS HubChatView 同款语义；最后一条不可见即浮现）
            if (showJumpToBottom) {
                TextButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Text("↓ 回到底部", fontSize = 12.sp)
                }
            }
            }
        }

        // wp-6：斜杠命令建议面板——draft 由 "/" 起手时悬浮于发送区上方（iOS wp-14 同位），
        // 选中即动作；无命中/进入参数书写段自动收起，其余输入零干扰。
        // 2026-11-21 面板键位轮：highlightIndex = 键盘高亮行（↑↓/Tab 消费，行底渲染见面板）。
        AnimatedVisibility(
            visible = slashSuggestions.isNotEmpty(),
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
        ) {
            SlashCommandPanel(
                suggestions = slashSuggestions,
                highlightIndex = slashHighlightIndex,
                onSelect = { selectSlashCommand(it) },
                onPrefill = { prefillSlashCommand(it) },
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }

        // 发送区
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                // #3（2026-11-08）：composer「运行中发送将排队」提示撤显示（iOS 2026-11-07
                // 二轮同款）——排队语义上移为消息自身的「排队中」状态徽标（消息级落库状态，
                // 见 UserMessageRow queued 分支），composer 行不再占位。
                // v1.2.0 多模态：草稿附件 chip 行（可摘除）+ 上传失败/超限提示（显式可重试，
                // 不静默——与 loadEarlierError 横幅同纪律）。
                if (draftAttachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        draftAttachments.forEachIndexed { idx, ref ->
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(
                                        text = "🖼 ${ref.fileName ?: "…${ref.mediaId.takeLast(6)}"}",
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                    )
                                    TextButton(
                                        onClick = {
                                            draftAttachments =
                                                draftAttachments.filterIndexed { i, _ -> i != idx }
                                        },
                                        contentPadding = PaddingValues(horizontal = 4.dp),
                                    ) {
                                        Text("✕", fontSize = 10.sp, color = Color(0xFF999999))
                                    }
                                }
                            }
                        }
                    }
                }
                if (attachmentError != null) {
                    Text(
                        "⚠ $attachmentError",
                        fontSize = 11.sp,
                        color = Color(0xFFC53030),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 角色系统批次4：注视——指针在 composer 区移动时眼睛看向指针方向
                        //（锚=角色中心近似 (16,14)、视野半径 120、窗幅 4.5×3.5——2026-11-21 随眼窗扩容，双端同表常量）
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Move) {
                                        val pos = event.changes.firstOrNull()?.position ?: continue
                                        val dpX = with(density) { pos.x.toDp().value }
                                        val dpY = with(density) { pos.y.toDp().value }
                                        val nx = ((dpX - BOT_GAZE_ANCHOR_X)
                                            .coerceIn(-BOT_GAZE_RADIUS, BOT_GAZE_RADIUS) / BOT_GAZE_RADIUS)
                                        val ny = ((dpY - BOT_GAZE_ANCHOR_Y)
                                            .coerceIn(-BOT_GAZE_RADIUS, BOT_GAZE_RADIUS) / BOT_GAZE_RADIUS)
                                        pointerGaze = BotGaze(
                                            dx = BOT_GAZE_AMP_X * nx,
                                            dy = BOT_GAZE_AMP_Y * ny,
                                        )
                                    } else if (event.type == PointerEventType.Exit) {
                                        // 指针离场复位（复审修复）：否则 pointerGaze 恒非 null，
                                        // 聚焦下视/新消息下瞥两路被永久遮蔽（iOS .ended 置 nil 对齐）
                                        pointerGaze = null
                                    }
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 角色系统批次4（2026-11-20）：composer 行首 32dp 交互角色——thinkingNow
                    // 细分只在聊天页（workActivity wp-15）；ERROR=末条消息 failed（全覆盖于
                    // 聊天页）；DONE 瞬时窗见上方 LaunchedEffect；「正在思考…▌」占位行文字信号
                    // 保留为读屏锚（assistantPlaceholderText）。
                    BotCharacter(
                        seed = botSeedFor(conversationId),
                        state = botStateFor(
                            hasActiveRun = isRunActive,
                            thinkingNow = chatThinkingNow,
                            awaitingApproval = chatAwaitingApproval,
                            lastMessageFailed = chatLastMessageFailed,
                            doneTransient = doneTransient,
                        ),
                        size = 32.dp,
                        motionEnabled = motionEnabled,
                        interactive = true,
                        gaze = chatGaze,
                    )
                    Spacer(Modifier.width(10.dp))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("发送消息…") },
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { composerFocused = it.isFocused } // 批次4：聚焦下视
                            .onPreviewKeyEvent { event ->
                                // wp-6 + 2026-11-21 回车语义轮：键位拦截在 preview 相（先于
                                // TextField 默认行为）。分流序 = SlashCommandRules 头注释优先级表
                                //（与 Catalyst ComposerKeyHandlers 一张表）：
                                //   IME 组合中（composition != null）→ 全键让行默认（组合期
                                //   Enter 属提交候选词、↑↓ 属候选词导航、Tab 属候选选定）；
                                //   Enter → resolveEnterKey（Shift 换行 / 真空白消费 / Cmd 跟
                                //   draftHasContent 强发含附件 / 面板可见原样发送 / 单行发送、
                                //   多行与附件独发换行）；
                                //   ↑↓/Tab → 仅面板可见时消费（移高亮 / 补全高亮+空格），
                                //   面板不可见透传（Tab 照常走焦点遍历等系统默认）。
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                                val isPanelNav = slashSuggestions.isNotEmpty() &&
                                    (event.key == Key.DirectionUp ||
                                        event.key == Key.DirectionDown ||
                                        event.key == Key.Tab)
                                if (!isEnter && !isPanelNav) return@onPreviewKeyEvent false
                                if (input.composition != null) return@onPreviewKeyEvent false
                                if (isEnter) {
                                    when (
                                        resolveEnterKey(
                                            isShiftPressed = event.isShiftPressed,
                                            isMetaOrCtrlPressed =
                                                event.isCtrlPressed || event.isMetaPressed,
                                            draft = input.text,
                                            slashPanelVisible = slashSuggestions.isNotEmpty(),
                                            hasAttachments = draftAttachments.isNotEmpty(),
                                        )
                                    ) {
                                        EnterKeyBehavior.Newline -> false
                                        EnterKeyBehavior.Ignored -> true
                                        EnterKeyBehavior.Send -> {
                                            sendDraft()
                                            true
                                        }
                                    }
                                } else {
                                    when (event.key) {
                                        Key.DirectionUp -> {
                                            slashKeyboardIndex = slashMovedIndex(
                                                slashKeyboardIndex, -1, slashSuggestions.size,
                                            )
                                            true
                                        }
                                        Key.DirectionDown -> {
                                            slashKeyboardIndex = slashMovedIndex(
                                                slashKeyboardIndex, 1, slashSuggestions.size,
                                            )
                                            true
                                        }
                                        else -> { // Key.Tab（isPanelNav 门内必为面板可见）
                                            slashSuggestions.getOrNull(
                                                slashHighlightIndex ?: 0,
                                            )?.let { prefillSlashCommand(it) }
                                            true
                                        }
                                    }
                                }
                            },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    // v1.2.0 多模态最小附件入口（设计稿 §6 桌面行）：文件选择器选图（≤4 张）→
                    // 经 shared uploadMedia 上传 → 草稿挂 chip → 发送带 attachments。在途态防
                    // 并发开框，上限满即禁（入口侧先拦，契约 ≤4 服务端同校验）。
                    TextButton(
                        onClick = ::pickAndUploadAttachments,
                        enabled = !uploadingAttachments &&
                            draftAttachments.size < MAX_DRAFT_ATTACHMENTS,
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text(if (uploadingAttachments) "上传中…" else "📎", fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (isRunActive && canCancelRun && input.text.isBlank() && draftAttachments.isEmpty()) {
                        // wp-8② + #4（2026-11-08 UX 轮改版，iOS 同口径）：Stop 按钮仅在「活跃 run
                        // 且草稿为空」时占键位——草稿有内容时发送键优先（发送新消息不打断进行中
                        // run，Gateway 排队语义，消息按 run 态落 queued 徽标）；中止后部分输出
                        // 保留由 Gateway 权威收敛（run.aborted 回流投影库）。终止可经斜杠 /stop。
                        // v1.2.0 多模态：附件也算草稿内容——纯附件草稿（文本空）同样发送键优先。
                        Button(
                            onClick = {
                                val runId = liveConv?.activeRunId ?: return@Button
                                scope.launch { client.commands?.cancelRun(runId) }
                            },
                        ) { Text("■ 停止") }
                    } else {
                        // #3 修复轮（2026-11-08）：防死锁——run 活跃但 activeRunId 缺失时禁用态
                        // Stop 不再独占键位：发送键照常可用（排队语义），「结束中」降级为旁注。
                        // 此前禁用 Stop 独占 + 发送键被替换 = 服务端聚合卡 true 时 composer 整体锁死
                        //（真机实测 roster 瞬态信号把会话活跃 run 卡死）。
                        if (isRunActive) {
                            Text("结束中", fontSize = 10.sp, color = Color(0xFF999999))
                            Spacer(Modifier.width(6.dp))
                        }
                        // 空正文防线（发送侧）：空白草稿禁发（isNotBlank = 去空白后非空），点击再兜一层。
                        // v1.2.0 多模态：纯附件草稿（文本空、附件非空）放行——契约允许空文本消息，
                        // sendDraft 内再兜「两者皆空禁发」。（Enter 判据 2026-11-21 债清对齐：⌘/Ctrl
                        // 强发与发送键同判据 draftHasContent 含附件——附件独发 ⌘Enter 直接发送，
                        // 原「桌面键盘恒 no-op」知情差异消除；裸 Enter 附件独发按多行矩阵换行、
                        // 真空白兜底消费先于 ⌘ 判定均不变。）
                        Button(
                            onClick = { sendDraft() },
                            enabled = input.text.isNotBlank() || draftAttachments.isNotEmpty(),
                        ) {
                            Text("发送")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 消息行（wp-6 渲染基线 + wp-10 操作 + wp-18 助手行 Markdown；state ∈ final/streaming/failed、
 * uiState 仅用户行 sending/delivered/read/failed）：
 * - role=""：compaction/reset 合成行 → 分隔条（无操作菜单）
 * - role=user：右对齐气泡，uiState 标签（发送中/发送失败/已送达/已读）
 * - role=assistant：左对齐卡片，Markdown 渲染（wp-18），streaming 行带 ▌ 光标与「流式生成中」标签
 * 操作菜单（wp-10，桌面右键 = iOS 长按菜单同位）：复制全文 / 失败行重发。
 */
@Composable
private fun MessageRow(
    m: UiMessage,
    client: DefaultHubClient,
    conversationId: String,
    scope: kotlinx.coroutines.CoroutineScope,
    /** 2026-11-07 二轮：thinking 帧在途 + agent 显示名（占位行动画文案用，见流式空行分支）。 */
    thinkingNow: Boolean,
    agentLabel: String,
    /** 纯显示偏好（2026-11-19）：流式输出/思考中指示（streamingRowPresentation 决策消费）。 */
    streamingPlayback: Boolean,
    thinkingIndicator: Boolean,
    /** 会话活跃 run 现值（isRunActive 透传）：僵尸流式行门控（2026-11-19 清账①）。 */
    runActive: Boolean,
    /** 交互卡泛化（2026-11-08）：非 null = 本行是最新一次裸交互命令交换的回复行（model/带选项命令）。 */
    interactiveCommand: SlashCommand?,
    onModelSelect: (ModelOptionItem) -> Unit,
    onOptionSelect: (SlashCommand, String) -> Unit,
) {
    when {
        m.role.isEmpty() -> CompactionSeparator()
        else -> {
            val clipboard = LocalClipboardManager.current
            ContextMenuArea(
                items = {
                    buildList {
                        if (isCopyable(m)) {
                            add(
                                ContextMenuItem("复制全文") {
                                    clipboard.setText(AnnotatedString(m.content))
                                },
                            )
                        }
                        if (isResendable(m)) {
                            add(
                                ContextMenuItem("重发") {
                                    // 用户显式重发 = 新意图新键（sendMessage 每次生成新幂等键，ADR-07）
                                    scope.launch {
                                        client.commands?.sendMessage(conversationId, m.content)
                                    }
                                },
                            )
                        }
                    }
                },
            ) {
                when {
                    m.role == "user" -> UserMessageRow(m)
                    else -> AssistantMessageRow(m, conversationId, thinkingNow, agentLabel, streamingPlayback, thinkingIndicator, runActive, interactiveCommand, onModelSelect, onOptionSelect)
                }
            }
        }
    }
}

@Composable
private fun UserMessageRow(m: UiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        // v1.2.0 多模态（设计稿 §6 桌面行）：user 行附件 chip 渲染（mediaId 引用元数据面，
        // 不经 #24 拉字节——桌面最小口径）。本地乐观行（v9 附件随行落列）与服务端权威行同构。
        if (m.attachments.isNotEmpty()) {
            AttachmentChipsRow(m.attachments, modifier = Modifier.padding(bottom = 4.dp))
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                // wp-8③ 空正文防线：空 SENDING 用户行不渲染空气泡，占位「…」+ 状态徽标照常翻转
                text = if (isEmptyUserBody(m)) "…" else m.content,
                color = if (isEmptyUserBody(m)) Color(0xFF999999) else Color.Unspecified,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTs(m.ts), fontSize = 11.sp, color = Color(0xFF999999))
            when (m.uiState) {
                // #3（2026-11-08）：排队中 = 消息级落库状态（发送时 run 活跃），run 终态由
                // 投影层统一释放翻已送达——替代旧 composer 会话级队列提示。
                "queued" -> TagChip("排队中", Color(0xFFB26A00), Modifier.padding(start = 6.dp))
                "sending" -> TagChip("发送中…", Color(0xFF666666), Modifier.padding(start = 6.dp))
                "failed" -> TagChip("发送失败", MaterialTheme.colorScheme.error, Modifier.padding(start = 6.dp))
                "delivered" -> TagChip("已送达", Color(0xFF2E9E44), Modifier.padding(start = 6.dp))
                "read" -> TagChip("已读", Color(0xFF2E9E44), Modifier.padding(start = 6.dp))
                else -> Unit
            }
            if (m.state == "failed") {
                TagChip("消息失败", MaterialTheme.colorScheme.error, Modifier.padding(start = 6.dp))
            }
        }
        FailedReasonLine(m)
    }
}

/**
 * 2026-11-19 纯显示偏好（流式输出/思考中指示）：助手流式行呈现决策纯函数（:desktopApp:test
 * 回归锚锁行为不只锁存取——TimelineScroll.kt 纯函数同纪律；桌面↔iOS parity：iOS
 * HubMessageRow 早退分支 + HubStore.streamingPlayback/thinkingIndicator 同语义）：
 * - state ≠ "streaming" → null：final/failed 行不进占位决策、照常整段渲染（重进会话不丢
 *   数据的 UI 面）；
 * - 流式行 + 会话无活跃 run（runActive=false）→ 僵尸流式行（run 终态事件未达或终态后迟到
 *   delta 重建行，2026-11-19 清账①）：不挂任何进行中信号——空正文 = Silent 整行撤显示，
 *   非空正文 = null 按普通内容整段渲染（调用侧光标/chip 随 presentation 门控，无 ▌ 无
 *   「流式生成中」），等 final 权威行或用户新意图收敛；
 * - 流式行 + runActive：正文空 或 流式输出关 → thinkingIndicator 开 = Placeholder（占位
 *   文案，2026-11-08 UX 轮「进行中信号收敛到回复占位行」既有口径）、关 = Silent（整行含
 *   时间戳行撤显示，Stop 键/「结束中」旁注等功能面不动）；
 * - 其余（流式开且正文非空）→ Progressive（▌ 光标 + 「流式生成中」chip，现状）。
 */
internal enum class StreamingRowPresentation { Placeholder, Progressive, Silent }

internal fun streamingRowPresentation(
    state: String,
    contentBlank: Boolean,
    streamingPlayback: Boolean,
    thinkingIndicator: Boolean,
    runActive: Boolean,
): StreamingRowPresentation? {
    if (state != "streaming") return null
    if (!runActive) {
        return if (contentBlank) StreamingRowPresentation.Silent else null
    }
    if (contentBlank || !streamingPlayback) {
        return if (thinkingIndicator) StreamingRowPresentation.Placeholder else StreamingRowPresentation.Silent
    }
    return StreamingRowPresentation.Progressive
}

/** 占位文案（2026-11-07 二轮回复占位行内联文案逐字提取，:desktopApp:test 两态逐字锁）。 */
internal fun assistantPlaceholderText(thinkingNow: Boolean, agentLabel: String): String =
    (if (thinkingNow) "正在思考…" else "$agentLabel 正在处理中…") + "▌"

/** 洪泛档阈值（单位=字素 Cluster：积压 >8192 簇转洪泛档）：2s 合并窗典型几百字，
 *  8192 已是洪泛输出（桥合并缓冲硬帽 256KiB 量级）——此量级逐字档追平成本失衡，快速追平无
 *  体感损失。与 iOS StreamingTypewriter floodBacklogChars 同值同单位对锁（Swift String.count
 *  即簇计数，名称沿 iOS 惯称 chars 不改；2026-11-21 簇计量统一轮起两端「8192」同义，
 *  旧「桌面按 UTF-16 码元」知情差异废止，边界值表按簇见 StreamingTypewriterPacingTest）。 */
internal const val TYPEWRITER_FLOOD_BACKLOG_CHARS = 8192

/**
 * 打字机步进（2026-11-21 流式单行归一轮；同日走查②扩洪泛档；同日簇计量统一轮——积压单位
 * 改按字素 Cluster，与 iOS String.count 同义，常量 8192//12//3 两端从此同义）：
 * - 常规档 max(8, 积压/12)：慢流恒 8 簇/16ms（≈500 簇/秒打字感下限），大块积压 ~12 步（≈200ms）
 *   内追平、积压不跨下一块；
 * - 洪泛档 积压/3（积压>8192 簇）：特大块若走几何档需 ~90 tick、且揭示循环每 tick 对已揭示前缀
 *   整体重嗅探+重解析（MarkdownText remember(content)），累计解析 ~45×积压，主线程掉帧；
 *   洪泛档 256KiB 量级 ~9 tick 追平、累计 ~2×积压（阈值邻域 1 tick）。>8k 簇积压下逐字观感
 *   本已不可信（恒 8 档需 16s+），快速追平无体感损失。
 * 与 iOS HubViews.swift StreamingTypewriter（revealStep 双档 + 16ms tick）双端注释对锁——单端
 * 改值必须双端同步，桌面回归锚兼作两端行为契约不漂移（StreamingRowPresentationTest 同纪律）。
 * 本函数与单位无关（入参=积压簇数、返回值=步进簇数），簇口径由推进方
 * StreamingTypewriterText/typewriterClusterEnds 落地并锁回归（旧「桌面 UTF-16 码元/iOS 字素
 * Cluster」计量知情差异废止：非 BMP（emoji 等）两端揭示速度/洪泛触发点从此同语义）。
 */
internal fun typewriterRevealStep(backlog: Int): Int =
    if (backlog > TYPEWRITER_FLOOD_BACKLOG_CHARS) backlog / 3 else maxOf(8, backlog / 12)

/**
 * 簇边界表（2026-11-21 簇计量统一轮，退役 typewriterSnapToCodePointBoundary）：ends[k] = 前
 * k+1 个字素 Cluster 的 UTF-16 长度。java.text.BreakIterator.getCharacterInstance() = UAX #29
 * extended grapheme cluster，与 iOS Swift Character 同语义——"a😀b"=3 簇、"\r\n" 同簇、
 * ZWJ 序列/旗帜/组合符同簇；孤立代理自成 1 簇不并邻（残缺内容不崩、不误并）。仅在内容变化时
 * 调用一次（delta 块到达频率，与 MarkdownText remember(content) 重解析同触发同量级），tick
 * 推进只做簇数算术、零逐 tick 重扫；显示前缀 substring(0, ends[shown-1]) 恒落在簇边界——
 * 劈半索引（代理对中位、ZWJ 中位）从此不可构造，终审的 UTF-16 贴齐被簇口径天然覆盖而退役
 * （为何无行为回归见 StreamingTypewriterText 注；值表回归锚在 StreamingTypewriterPacingTest）。
 */
internal fun typewriterClusterEnds(content: String): IntArray {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(content) // setText 重置到文首（JDK 契约），空串 next() 即 DONE
    val ends = IntArray(content.length) // 簇数 ≤ 码元数，容量恒够
    var count = 0
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        ends[count++] = end
        end = iterator.next()
    }
    return ends.copyOf(count)
}

/** 打字机 tick 周期（毫秒，iOS StreamingTypewriter.tickNanos = 16_000_000 对应值）。 */
private const val TYPEWRITER_TICK_MS = 16L

@Composable
private fun AssistantMessageRow(
    m: UiMessage,
    conversationId: String,
    thinkingNow: Boolean,
    agentLabel: String,
    /** 纯显示偏好（2026-11-19）：流式输出/思考中指示（呈现决策见 streamingRowPresentation）。 */
    streamingPlayback: Boolean,
    thinkingIndicator: Boolean,
    /** 会话活跃 run 现值：僵尸流式行门控（2026-11-19 清账①）。 */
    runActive: Boolean,
    interactiveCommand: SlashCommand?,
    onModelSelect: (ModelOptionItem) -> Unit,
    onOptionSelect: (SlashCommand, String) -> Unit,
) {
    // Silent = 整行撤显示（含时间戳行），等 final 权威行落库时整段接管——数据面不动，delta
    // 照常入投影库、已读水位照常推进（iOS HubMessageRow 早退 Color.clear 同口径 parity）。
    // runActive 透传（liveConv.hasActiveRun 现值）：僵尸流式行不挂进行中信号（清账①）。
    val presentation = streamingRowPresentation(m.state, m.content.isBlank(), streamingPlayback, thinkingIndicator, runActive)
    if (presentation == StreamingRowPresentation.Silent) {
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        if (interactiveCommand != null && m.state == "final" && m.content.isNotBlank()) {
            // 交互卡替换回复文本（挂最新一次裸交互命令交换的回复行；点选项后卡面保留）。
            if (interactiveCommand.name == "/model") {
                ModelsInteractiveCard(conversationId = conversationId, onSelect = onModelSelect)
            } else {
                CommandOptionsCard(command = interactiveCommand, onSelectValue = { onOptionSelect(interactiveCommand, it) })
            }
        } else
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            when {
                // 2026-11-07 二轮（x总改版，iOS 同口径）：工作过程条撤显示——进行中信号收敛到
                // 回复占位行：thinking 帧在途呈「正在思考…」，否则「<agent> 正在处理中…」；
                // 正文返回即被 Markdown 路径替换（05 §3.11：流式行允许跳过 delta，终态由 created 收敛）
                // 2026-11-19 纯显示偏好：占位与否改由 streamingRowPresentation 决策——流式输出
                // 关的非空增量行同样回落占位（文案经 assistantPlaceholderText 提取，逐字锁）。
                presentation == StreamingRowPresentation.Placeholder -> Text(
                    assistantPlaceholderText(thinkingNow, agentLabel),
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // wp-8③ 空正文防线：final/failed 空助手行渲染占位（不再出现空气泡——
                // 按 MessageActions.isEmptyBodyPlaceholder 既定契约补上占位文案）。
                // v1.2.0 多模态：空正文+有附件行不再落「（无文本内容）」占位（iOS 同口径，
                // 设计稿 §6）——附件 chip 面替代空气泡。
                isEmptyBodyPlaceholder(m) -> if (m.attachments.isNotEmpty()) {
                    AttachmentChipsRow(
                        m.attachments,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    Text(
                        "（无文本内容）",
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFF999999),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                // wp-18：助手行走轻量 Markdown 渲染器（标题/列表/代码块/粗斜体，流式行尾随
                // ▌ 光标）；无 Markdown 语素或解析异常时内置回退 wp-6 纯文本路径
                // 光标随 presentation 门控（=Progressive）：僵尸流式行无 ▌（2026-11-19 清账①）。
                // 2026-11-21 流式单行归一轮：Progressive 改挂打字机包装（挂载贴齐/前缀增长逐字/
                // 跳变贴齐，与 iOS StreamingTypewriter 同参数）——投影层行键归一为单行原地追加后，
                // 桌面增量块不再整块瞬显；final/failed/僵尸行仍整段 Markdown 渲染。
                else -> if (presentation == StreamingRowPresentation.Progressive) {
                    StreamingTypewriterText(
                        content = m.content,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    MarkdownText(
                        content = m.content,
                        showCursor = false, // Progressive 已上走打字机分支；此处恒非进行中行
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTs(m.ts), fontSize = 11.sp, color = Color(0xFF999999))
            // 2026-11-19 纯显示偏好：chip 与 MarkdownText 光标同门（=Progressive）——流式输出关
            // 的非空行呈占位文案不挂 chip；僵尸流式行（runActive=false）同样不挂（清账①）。
            if (presentation == StreamingRowPresentation.Progressive) {
                TagChip("流式生成中", Color(0xFF2B6CB0), Modifier.padding(start = 6.dp))
            }
        }
        FailedReasonLine(m)
    }
}

/**
 * 2026-11-21 流式单行归一轮：桌面打字机包装（iOS HubViews.swift StreamingTypewriter 镜像）。
 * 投影层行键归一为 dstream:<runId> 单行原地追加后，delta 块（桥 2s 合并窗）经本组件从
 * 「已见前缀」向新内容逐字放出，消除整块瞬显。
 *
 * 语义边界（与 iOS 逐条对齐）：
 * - 挂载首帧贴齐现有内容（快照行/列表滚动往返物化不重打；列表 key=messageId 行身份稳定，
 *   本组合项 @State 跨重组存活，仅挂载/重挂载时贴齐），仅存活期间的 append 逐字；
 * - 改写跳变（replace=true 语义、内容收缩、非前缀续写）不逐字、直接贴齐；
 * - 行收敛 final 时外层分支切回整段 MarkdownText（本组合项离场），剩余积压一次性补齐；
 * - 计量与推进以字素 Cluster 为最小单位（2026-11-21 簇计量统一轮，与 iOS String.count/
 *   Character 同义）：内部状态=已揭示簇数，步进=typewriterRevealStep(积压簇数) 簇/tick，
 *   显示切点=typewriterClusterEnds 簇表查 UTF-16 偏移——切点恒簇边界，代理对/ZWJ/CRLF
 *   多码元簇恒整簇揭示。终审的 typewriterSnapToCodePointBoundary（UTF-16 贴齐）被簇口径
 *   天然覆盖而退役、无行为回归：贴齐函数只修复「码元算术产生劈半索引」这一种病，簇计数
 *   语义下该索引不可构造（簇数→切点恒查表命中边界）；旧贴齐覆盖的代理对劈半在新口径下
 *   切点逐字节同旧（如 "a😀b" 旧贴齐后切 3=新表 ends[1]，"中文" 同值），且簇口径额外
 *   免疫旧贴齐不防的 ZWJ/组合符中位劈切（iOS 字素索引行为就此全量对齐）。
 *
 * 步长 = typewriterRevealStep(积压簇数) @ TYPEWRITER_TICK_MS——与 iOS 同参数同单位对锁
 * （见纯函数注；旧「桌面 UTF-16 码元/iOS 字素」计量知情差异废止）。
 * 揭示前缀喂 MarkdownText（showCursor=true，▌ 光标随前缀移动）；MarkdownText 本体零改动。
 */
@Composable
private fun StreamingTypewriterText(content: String, modifier: Modifier = Modifier) {
    // 已揭示字素 Cluster 数（单位与 iOS String.count 同义）；null = 未初始化（挂载即贴齐现内容，不重打）。
    var revealedCount by remember { mutableStateOf<Int?>(null) }
    // 上一帧内容（区分 append 前缀增长与改写跳变）。
    var lastSeen by remember { mutableStateOf("") }
    // 簇边界表：内容每变一次重建一次（=delta 块到达频率，与 MarkdownText remember(content)
    // 重解析同触发同量级）；揭示循环只做簇数算术，显示前缀查表取 UTF-16 切点——零逐 tick 重扫。
    val clusterEnds = remember(content) { typewriterClusterEnds(content) }
    LaunchedEffect(content) {
        val target = clusterEnds.size
        val progress = revealedCount
        if (progress == null) {
            // 挂载首帧贴齐（iOS onAppear 同语义：首跑即初始化，竞态兜底并入）
            revealedCount = target
            lastSeen = content
            return@LaunchedEffect
        }
        if (!content.startsWith(lastSeen)) {
            // 非前缀跳变（改写/收缩）：直接贴齐
            revealedCount = target
            lastSeen = content
            return@LaunchedEffect
        }
        lastSeen = content
        var shown = progress.coerceAtMost(target)
        revealedCount = shown
        while (shown < target) {
            // 纯簇数推进（与 iOS shown + revealStep(...) 同式）：簇数为整，切点查表恒簇边界，
            // 代理对/ZWJ 劈半无从产生（typewriterSnapToCodePointBoundary 职责被天然覆盖，见头注）
            shown = (shown + typewriterRevealStep(target - shown)).coerceAtMost(target)
            revealedCount = shown
            delay(TYPEWRITER_TICK_MS) // 新内容到达经 LaunchedEffect(content) 重启，取消本循环（iOS task(id:) 同语义）
        }
    }
    // 显示前缀：簇数 → UTF-16 切点（防御性 coerce 同旧版；表与 content 同源同 key remember，恒一致）
    val revealed = (revealedCount ?: clusterEnds.size).coerceIn(0, clusterEnds.size)
    MarkdownText(
        content = content.substring(0, if (revealed == 0) 0 else clusterEnds[revealed - 1]),
        showCursor = true,
        modifier = modifier,
    )
}

/** wp-8③：失败原因行（六值脱敏闭集 → 人读文案；未分类失败 reason=NULL 不虚构文案）。 */
@Composable
private fun FailedReasonLine(m: UiMessage) {
    val reason = failedReasonLabel(m) ?: return
    Text(
        text = "⚠ $reason",
        fontSize = 11.sp,
        color = Color(0xFFC53030),
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * wp-6：斜杠命令建议面板——发送区上方轻量目录（输入 "/" 即出、选中即动作；iOS wp-14
 * SlashCommandPanel 同构）。纵向滚动承载溢出（目录超出可视行数时内滚，上限 ≈4 行对齐 iOS
 * panelMaxHeight=148）；纯本地视图零网络，不劫持键盘焦点。
 * 2026-11-21 面板键位轮：行底键盘高亮（highlightIndex，↑↓ 移动 / Tab 补全的视觉面，iOS
 * 同轮 accent 薄底对位）。走查修复：高亮行滚入视野——↑↓ 环绕后高亮可落视口外（静态六
 * 目录在 "/" 草稿下已溢出 ≈4 行可视），Tab 却仍补全看不见的行=视觉反馈断裂；不用
 * LazyColumn（视口贪婪占满 heightIn 上限，短目录会出大片空底），保留 Column 包裹内容
 * 观感，行界经根坐标采样、与容器视口差值驱动最小滚动（对滚动平移实现细节免疫）。
 */
private const val SLASH_PANEL_MAX_HEIGHT_DP = 148

@Composable
private fun SlashCommandPanel(
    suggestions: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    /** 2026-11-08 修复轮：行尾 ✎ = 预填命令文本进输入框（带尾随空格）续写参数。 */
    onPrefill: (SlashCommand) -> Unit,
    /** 2026-11-21 面板键位轮：键盘高亮行（↑↓/Tab 消费方；null=无高亮态，视觉同旧行）。 */
    highlightIndex: Int? = null,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    // 行界表（根坐标 top + 行高，px）：onGloballyPositioned 逐帧回填（滚动平移也会触发重报），
    // 与容器根坐标同帧可比；目录变化即重建防陈旧条目。
    val rowBounds = remember(suggestions) { mutableMapOf<Int, Pair<Float, Int>>() }
    var containerTopRootY by remember { mutableStateOf(0f) }
    var viewportHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(highlightIndex, suggestions, viewportHeightPx, containerTopRootY) {
        val target = highlightIndex ?: return@LaunchedEffect
        val (rowTopRootY, rowHeight) = rowBounds[target] ?: return@LaunchedEffect
        if (viewportHeightPx <= 0) return@LaunchedEffect
        // 行相对视口的现位（含滚动平移）：视口上沿=containerTopRootY
        val relTop = rowTopRootY - containerTopRootY
        val relBottom = relTop + rowHeight
        val delta = when {
            relTop < 0f -> relTop // 高亮在视口上方（环绕到末行等）→ 上滚贴顶
            relBottom > viewportHeightPx -> (relBottom - viewportHeightPx) // 在下方 → 下滚贴底
            else -> return@LaunchedEffect // 完全可见不滚
        }
        scrollState.animateScrollTo(
            (scrollState.value + delta.roundToInt()).coerceIn(0, scrollState.maxValue),
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = SLASH_PANEL_MAX_HEIGHT_DP.dp)
                .onGloballyPositioned { coords ->
                    containerTopRootY = coords.positionInRoot().y
                    viewportHeightPx = coords.size.height
                }
                .verticalScroll(scrollState),
        ) {
            suggestions.forEachIndexed { index, cmd ->
                // 键盘高亮行底（对位 iOS accent 0.16 薄底，桌面取 primary≈0.12 alpha 同「轻
                // 强调」观感；与行内 hover/点击共存不冲突）。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            rowBounds[index] =
                                coords.positionInRoot().y to coords.size.height
                        }
                        .background(
                            if (index == highlightIndex) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                            } else {
                                Color.Transparent
                            },
                            RoundedCornerShape(8.dp),
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(cmd) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = cmd.name,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = cmd.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // 2026-11-08 修复轮：行尾 ✎ = 预填续写参数（原「带参命令点行预填」行为
                    // 移到此处，点行语义让位给直接发送）
                    TextButton(onClick = { onPrefill(cmd) }) {
                        Text("✎", fontSize = 12.sp, color = Color(0xFF999999))
                    }
                }
                if (index < suggestions.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}

// ── #22（2026-11-08）：斜杠交互卡——模型目录卡（iOS ModelsInteractiveCard 桌面同构） ──

/**
 * 模型目录卡（端点 #22 数据面，v1.1 additive 2026-11-08）：/model 无参命令回复落定后
 * 出现于时间线尾部（ConversationScreen.modelsCardActive 判定），按供应商分节展示模型
 * 按钮，点按钮发送 `/model <id>`（Gateway 原生模型标识复用 #22 目录 id 原样回填，客户端
 * 不拼装不改写——ADR-14）。数据 = [ModelsCache]（TTL 缓存 + effect 触发拉取，与命令目录
 * 同纪律）；拉取中呈进度态，空目录/截断如实标注；不可用模型按钮置灰（available=false，
 * 不编造原因）。卡片纯本地视图零命令副作用，选择动作走 sendMessage 既有通道。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsInteractiveCard(
    conversationId: String,
    onSelect: (ModelOptionItem) -> Unit,
) {
    val cached = ModelsCache.valid(conversationId)
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("切换模型", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(
                    "点击发送 /model <模型>",
                    fontSize = 11.sp,
                    color = Color(0xFF999999),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (cached?.truncated == true) {
                    Text("已截断", fontSize = 11.sp, color = Color(0xFF999999))
                }
            }
            if (cached != null) {
                if (cached.models.isEmpty()) {
                    Text("暂无可用模型", fontSize = 12.sp, color = Color(0xFF999999))
                } else {
                    val grouped = cached.models.groupBy { it.provider }
                    grouped.forEach { (provider, models) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(provider, fontSize = 11.sp, color = Color(0xFF777777))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                models.forEach { item ->
                                    ModelOptionButton(item, onSelect)
                                }
                            }
                        }
                    }
                }
            } else if (ModelsCache.failed(conversationId)) {
                // 失败显式降级（2026-11-18 hermes 模型卡挂死修复轮）：#22 目录面对该网关
                // 缺席（桥 fail-closed），转圈无出口——指引用法直切（桥 /model <名> 已通）。
                Text(
                    "模型目录不可用（该网关未提供）；可直接发送 /model <模型名> 切换",
                    fontSize = 12.sp,
                    color = Color(0xFF999999),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("正在获取模型目录…", fontSize = 12.sp, color = Color(0xFF999999))
                }
            }
        }
    }
}

/**
 * 通用选项卡（2026-11-08 修复轮，斜杠交互卡泛化；iOS CommandOptionsCard 桌面同构）：挂
 * 最新一次裸命令交换的回复行，替代原回复文本——按钮流展示 Gateway catalog 静态选项闭集
 * （REST #21 args 有界视图），点按钮发送 `/命令 <值>`（值原样回填）。数据 = 命令目录缓存
 * （与斜杠面板同源），选择走 sendMessage 既有通道。
 */
@Composable
private fun CommandOptionsCard(
    command: SlashCommand,
    onSelectValue: (String) -> Unit,
) {
    val choices = command.choices ?: emptyList()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(command.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                command.argName?.let { arg ->
                    Spacer(Modifier.width(6.dp))
                    Text("/$arg", fontSize = 11.sp, color = Color(0xFF999999))
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "点击发送 /" + command.name.removePrefix("/") + " <选项>",
                    fontSize = 11.sp,
                    color = Color(0xFF999999),
                    maxLines = 1,
                )
            }
            if (choices.isEmpty()) {
                Text("暂无可用选项", fontSize = 12.sp, color = Color(0xFF999999))
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    choices.forEach { choice ->
                        TextButton(
                            onClick = { onSelectValue(choice.value) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.Start) {
                                Text(choice.label, fontSize = 12.sp, maxLines = 1)
                                Text(
                                    choice.value,
                                    fontSize = 10.sp,
                                    color = Color(0xFF999999),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单模型按钮：显示名 + id 小字；available=false 置灰禁用（不编造原因）。 */
@Composable
private fun ModelOptionButton(item: ModelOptionItem, onSelect: (ModelOptionItem) -> Unit) {
    val available = item.available ?: true
    TextButton(
        onClick = { onSelect(item) },
        enabled = available,
        modifier = Modifier.alpha(if (available) 1f else 0.45f),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(item.name, fontSize = 12.sp, maxLines = 1)
            Text(
                item.id,
                fontSize = 10.sp,
                color = Color(0xFF999999),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── v1.2.0 多模态最小附件入口（设计稿 §6 桌面行）：选图 → 上传 → 草稿 ──────────

/** 附件张数上限（契约 SendMessageRequest.attachments ≤4；服务端同校验，客户端入口先拦）。 */
private const val MAX_DRAFT_ATTACHMENTS = 4

/** 服务端媒体白名单（契约 #23：jpeg/png/webp/gif，魔数嗅探为准）→ 本地声明 mime。 */
private val IMAGE_MIME_BY_EXT = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
    "gif" to "image/gif",
)

/** 扩展名 → 声明 mime；白名单外 null（服务端以魔数判定为准，本地先拦减少无谓上传）。 */
private fun imageMimeOf(name: String): String? =
    IMAGE_MIME_BY_EXT[name.substringAfterLast('.', "").lowercase()]

/**
 * Swing FileDialog 选图（ask 指定的 JVM 文件选择最小面）：多选开启，返回所选文件
 * （空 = 用户取消）。null 父窗——compose 1.12 的 WindowInfo 不再暴露 AWT container
 * （jar 实查仅 isWindowFocused/keyboardModifiers/containerSize），非模态对话框语义可接受；
 * setFilenameFilter 在 macOS 不生效（AWT 平台限制），白名单过滤由调用方按扩展名做。
 * 挂 IO 调度器：FileDialog.isVisible 阻塞至选完/取消，绝不上主线程。
 */
private suspend fun pickImageFiles(): List<File> = withContext(Dispatchers.IO) {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "选择图片", java.awt.FileDialog.LOAD)
    dialog.isMultipleMode = true
    dialog.isVisible = true
    val files = dialog.files?.toList().orEmpty()
    dialog.dispose()
    files
}
