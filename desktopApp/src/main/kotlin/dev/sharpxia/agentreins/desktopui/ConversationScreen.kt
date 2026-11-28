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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.runtime.rememberUpdatedState
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
import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
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
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OPTIMISTIC_SEQ_SENTINEL: Long = Long.MAX_VALUE

/**
 * 宽窗宽度帽（2026-11-22 大屏观感对锁轮，iOS Catalyst 同测度参数）：气泡 640dp 帽 + 时间线
 * 水平 padding 14×2 = 668dp 内容列帽（iOS HubViews.swift HubMessageRow `.frame(maxWidth: 640)`
 * 与 MacSplitRoot.swift MacContentColumnModifier 同值同义）——大窗/全屏下正文行宽不随窗口
 * 全幅拉伸（观感层不因协议缩水的复刻基线）。桌面时间线 padding 16×2，行测度 636dp 在 668
 * 列帽内 4dp：气泡帽保留 = 双保险（防列宽调整后失效，iOS 同口径）。只收宽度——纵向锚定链
 *（weight/滚动/回到底部 overlay）零触碰。
 */
private const val BUBBLE_MAX_WIDTH_DP = 640
private const val CONTENT_COLUMN_MAX_WIDTH_DP = 668

/** 末条消息失败判定（角色系统批次4 聊天页 ERROR 态信号；state/uiState 口径与消息行失败徽标同源）。 */
private fun UiMessage?.isFailedMessage(): Boolean {
    val m = this ?: return false
    return m.state == "failed" || m.uiState == "failed"
}

/**
 * 已读上报水位 = 已渲染行中最大的真实服务端 seq（2026-11-22 B3 根修，排除三类非真实行）：
 * - 乐观哨兵行（seq=Long.MAX_VALUE 占位，服务端尚无对应行）；
 * - failed 行（非服务端消息行，不上报）；
 * - 流式行（state="streaming"，含 dstream:<runId> 占位行）——其 seq=conv.lastSeq+1（投影层
 *   占位行不推进 lastSeq，DefaultEventProjector 同口径）恰与该 run assistant final 行落库的
 *   真实 seq 同值：计入即「借未来 seq」——流式期按它上报被服务端 readUpTo 存在性校验恒拒
 *   （400 HUB-BIZ-004「不指向当前化身既有 message seq」），而基线已先行推进、命令 FAILED
 *   不重试；final 收敛落库 seq 同值、水位无变化→重报不触发，已读游标永久落后一拍
 *   （countUnread=1、重进会话才自愈）。排除后流式期不再借未来 seq，final 收敛时水位自
 *   lastSeq 真实跳变至 S 触发重报，此刻行已存在→受理→read.updated 回声清零；中途离场不
 *   预支已读，final 落地时无打开视图→未读=1 属正确语义。
 * 与 iOS HubViews.swift HubChatView maxRenderedSeq 同口径双端对锁（单端改口径须双端同步）。
 */
internal fun maxRenderedReadSeq(messages: List<UiMessage>): Long =
    messages
        .filter { it.seq != OPTIMISTIC_SEQ_SENTINEL && it.state != "failed" && it.state != "streaming" }
        .maxOfOrNull { it.seq } ?: 0L

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
    // 会话级换形象轮（v1.5.2，2026-11-21）：全局形象档动效现值（avatarUi.motionLevel 同源）——
    // 会话换装 dialog 预览动效门控与 PUT 载荷 motionLevel（动效不进会话 sheet，仍全局档）
    globalMotion: AvatarMotion = AvatarMotion.STANDARD,
) {
    // 实时新到登记（2026-11-28 流式逐字轮，iOS HubStore.noteTypewriterArrivals 镜像）：消息流
    // 每帧先交打字机账本观测——已打开会话里首次出现的助手 final 行登记为实时新到，行视图
    // 据此从 0 逐字打出（OpenClaw 等网关回复整条落库无 delta，流式行从未浮现）。onEach 在
    // 收集方上下文（主线程）执行，账本仅主线程访问。
    val messages by client.observeMessages(conversationId)
        .onEach { list ->
            TypewriterHandoff.ledger.observeMessages(
                conversationId,
                list.map { TypewriterHandoffLedger.ArrivalCandidate(it.messageId, it.role, it.state, it.ts, it.content.length) },
            )
        }
        .collectAsState(initial = emptyList())
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
    // DSK-1 散写收敛：墨水令牌单点读取
    val ink = LocalDesktopInk.current
    // 已读上报基线：只认真实服务端 seq（乐观哨兵/failed/流式行三类均不计入——口径与根因链
    // 详见 maxRenderedReadSeq 注；只推进不回退，避免噪声）
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

    // 会话级换形象轮（v1.5.2）：会话覆盖档（契约 #34 水合面；null map=未水合、键缺席=无覆盖
    // 走 seed 派生——store 不造默认值）。StateFlow 帧驱动自动刷新，conversationId 直查不缓存
    // 副本；dialog 入口② = 页头角色点按。
    val conversationAvatars by client.conversationAvatarProfiles.collectAsState()
    val avatarOverride = conversationAvatars?.get(conversationId)
    var avatarDialogOpen by remember { mutableStateOf(false) }

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
    // 程序化滚动计数（2026-11-23 批次二轮；自检轮 R1-12/R1-18 布尔→计数并发安全化）：包住
    // 全部非贴底跟随的程序化滚动出口（回底按钮/键盘命令/翻页恢复锚/首签定位）——期间
    // isUserDrivenScroll 恒 false，防跳转前几帧几何过窗误触发 loadEarlier（白付 REST）与恢复
    // 锚把视口从跳转半途硬拽回。计数而非布尔：并发跳转（键重复/连按）时 animateScrollToItem
    // 内部 MutatorMutex 取消前一跳，被取消协程的 finally 若清布尔会把仍在途的后一跳门放开
    // （近顶过窗误触发续拉 + 恢复锚拽回，正是旗标要防的两个后果）；++/-- 成对入 finally，
    // 门只在全部跳转收尾后放开。纪律注释：**新增程序化跳转必须入计数**（贴底跟随动画保持
    // followAnimating 不动——语义不同：跟随不触发续拉）。
    var programmaticJumpInFlight by remember { mutableStateOf(0) }
    var earlierExhausted by remember { mutableStateOf(false) }
    var earlierCursor by remember { mutableStateOf<Long?>(null) }
    var loadingEarlier by remember { mutableStateOf(false) }
    var loadEarlierError by remember { mutableStateOf<String?>(null) }
    // 近顶自动续拉失败锁存（自检轮 2 R2-9，iOS HubStore.autoEarlierBlocked 同型同义）：
    // 自动链 REST 失败即停手——否则用户停在近顶窗内的每个滚动帧都会重发失败请求并反复
    // 清/置错误横幅；手动按钮重试成功回执解锁。随会话切换 key(conversationId) 重挂复位
    // （同页翻页状态机同纪律）。
    var autoEarlierBlocked by remember { mutableStateOf(false) }
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

    // 已读上报（会话完整渲染后 readUpTo = 已渲染最大真实 seq；只推进不回退，避免噪声）。
    // 2026-11-22 B3 根修：口径换 maxRenderedReadSeq——排除流式行（不借 lastSeq+1 的未来 seq），
    // 流式期不上报，final 收敛时水位 lastSeq→S 真实跳变触发本效果重报，此刻行已在服务端→
    // 受理→read.updated 回声清零（旧内联口径计入流式行的根因链见该函数注）。
    val maxRenderedSeq = maxRenderedReadSeq(messages)
    LaunchedEffect(conversationId, maxRenderedSeq) {
        if (maxRenderedSeq > reportedReadUpTo) {
            reportedReadUpTo = maxRenderedSeq
            client.markRead(conversationId, maxRenderedSeq)
        }
    }

    // wp-5 滚动锚定②：贴底条件化跟随（iOS wp-7① onChange tailSignature 同型）——尾签变化 且
    // 在底部锚定态才跟随；尾签覆盖行数与尾行身份/状态/内容量（比旧「只看条数」多覆盖流式
    // 原地增长与占位行收敛）。首签（打开会话/空会话收首批）直接定位到尾（iOS onAppear 同位，
    // 程序化跳转入 programmaticJumpInFlight 计数——本轮）；翻页 prepend 也变签，但被 atBottom
    // 守卫拦住（翻页时用户在上方读史）。贴底跟随动画保持 followAnimating 旗标（不并入
    // programmaticJumpInFlight——跟随语义不同，见纯函数注）。
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
                programmaticJumpInFlight += 1
                try {
                    listState.scrollToItem(messages.lastIndex + prefixCountNow())
                } finally {
                    programmaticJumpInFlight -= 1
                }
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
                // 恢复后视口仍近顶，不包旗标会在恢复帧再次误触发自动续拉（本轮入计数）
                programmaticJumpInFlight += 1
                try {
                    listState.scrollToItem(target + prefixCountNow(), 0)
                } finally {
                    programmaticJumpInFlight -= 1
                }
            }
        }
    }

    // 组合期渲染态（每次重组按现值重算）
    val canLoadEarlierNow = canLoadEarlier(earlierExhausted, liveSeqNow, messages.size, messages.isEmpty())
    val loaderState = earlierRowState(loadingEarlier, canLoadEarlierNow, earlierExhausted)

    // wp-12 加载更早（iOS HubStore.loadEarlier 同型）：按 oldestSeq keyset 翻页，REST 走
    // loadEarlierMessages（nil = 通道不可用/REST 未成功，显式降级可重试不静默）；成功回执后
    // 服务端 hasMore 接管按钮可见性，翻到头锁存。限流纪律：一次一请求（loadingEarlier 互斥）。
    // [auto] 区分入口（自检轮 R1-8 + 自检轮 2 R2-9/R2-13/R2-14/R2-18，两语义在现实现恒同向
    // 收敛为单参，[armRestore] 派生值保留 R1-8 命名）：
    // - 手动点击（默认 auto=false）武装阅读位置恢复锚（armRestore=true）：锚位=触发前最老
    //   一行，新页合入其上方后滚回该行，视口不跳；
    // - 近顶自动续拉传 auto=true：不武装恢复锚；REST 失败另置 autoEarlierBlocked 停自动链，
    //   手动按钮重试成功回执解锁（iOS HubStore autoEarlierBlocked 同型同参，双端对锁）——
    //   否则用户停在近顶窗内的每个滚动帧都会重发失败请求、横幅反复闪灭。
    // 落点机制实况（自检轮 2 R2-14 纠注，原「锚定 firstVisibleIndex」描述失实）：时间线
    // items 按 messageId keyed（key=messageId，名称引用防行号再漂），prepend 后 LazyColumn
    // 按首可见行 key 保持视口——
    // 翻页行首可见时新页自其下展开（视口落新页，正是拖顶用户要的落点），消息行首可见时
    // 视口停在原行、新页在上方不可见。
    // 与 iOS 的 parity 差异（自检轮 2 R2-13 知情落档，非对齐遗漏）：iOS 对自动/手动两路都
    // 武装并消费恢复锚（HubStore.swift:1806/:1833——SwiftUI LazyVStack 无 keyed 锚定需显式
    // 滚回）；桌面 LazyColumn keyed 锚定天然保位，恢复锚只服务手动路径——口径差异同步写进
    // TimelineScroll.kt 头注与 CODE-INDEX §2.5，两端各自有意。
    // 失败分支清锚双端差异（自检轮 4 R4-10 知情落档，与上段锚武装差异并列）：REST 失败时
    // 桌面仅在 armRestore（手动路径）清旗标锚位——auto 失败不动已武装的手动恢复锚（R2-18
    // 语义）；iOS 无条件清 restoreAnchorMessageId/restoreArmed（Store/HubStore.swift:1815-1818，
    // auto 失败连带作废在途手动锚）——iOS 本轮零改动属既有行为，差异面由桌面 R2-18 修复
    // 直接引入，后续轮若对齐 iOS 再给 iOS 补同型 armRestore 门。
    // 自检轮 2 R2-18：自动路径对已武装的手动锚零触碰——触发段/失败段仅在 armRestore 时
    // 清旗标锚位，成功分支 restoreArmed = armRestore || restoreArmed 保已武装位；手动回执
    // 已到、恢复锚待消费的一两帧窗口内被自动续拉插队，不再静默作废手动阅读位置恢复。
    fun loadEarlier(auto: Boolean = false) {
        val armRestore = !auto
        if (auto && autoEarlierBlocked) return
        if (loadingEarlier || earlierExhausted || !canLoadEarlierNow) return
        val oldestLoaded = messages.firstOrNull()
        val before = nextBeforeCursor(earlierCursor, oldestLoaded?.seq) ?: return
        if (before < 1) return // 契约 Before minimum: 1（08 §4.3）
        if (armRestore) {
            // 阅读位置恢复锚 = 触发前最老一行（新页将合入其上方，落地后滚回该行，视口不跳）
            restoreAnchorId = oldestLoaded?.messageId
            restoreArmed = false
        }
        loadingEarlier = true
        loadEarlierError = null
        scope.launch {
            val page = client.loadEarlierMessages(conversationId, before)
            loadingEarlier = false
            if (page == null) {
                // 失败清锚双端差异（R4-10，见上函数注）：armRestore 门保手动锚——auto 失败
                // 不作废在途手动恢复锚；iOS 同分支无条件清（HubStore.swift:1815-1818）
                if (armRestore) {
                    restoreAnchorId = null
                    restoreArmed = false
                }
                loadEarlierError = "加载更早失败（网络或服务不可达），可重试"
                if (auto) autoEarlierBlocked = true
                return@launch
            }
            earlierCursor = page.oldestSeq
            if (page.count == 0 || !page.hasMore) {
                // 翻到头锁存：入口消失，顶部呈收尾条（验收项）
                earlierExhausted = true
                restoreAnchorId = null
                restoreArmed = false
            } else {
                restoreArmed = armRestore || restoreArmed
            }
            loadEarlierError = null
            autoEarlierBlocked = false
        }
    }

    // wp-5 滚动锚定①：底部锚定态判定（iOS wp-7① onScrollGeometryChange + onScrollPhaseChange
    // 同型）——锚定态只被用户驱动的滚动帧与滚动结束沿翻转；流式内容增长帧（非用户驱动且无
    // 滚动沿）不改锚定态，据增长解锚会让跟随永久停摆。几何零值（空列表）不判。
    // 2026-11-23 批次二轮整块搬到 loadEarlier 定义之后：近顶自动续拉需在 collect 内引用
    // loadEarlier，而 Kotlin 局部声明禁止前向引用（编译错误）；rememberUpdatedState 每次重组
    // 更新到最新闭包（:loadEarlier 捕获的 canLoadEarlierNow 等随实例新鲜）。三个
    // LaunchedEffect(listState) 各自独立 collect snapshotFlow、key 相同，顺序搬迁无语义影响。
    // 显式 lambda 携带入口参（自检轮 R1-8 armRestore 区分 + 自检轮 2 R2-9 auto 失败锁存区分，
    // 两语义恒同向收敛为单参：自动续拉 true / 手动点击默认 false）；rememberUpdatedState 每次
    // 重组更新到最新闭包（loadEarlier 捕获的 canLoadEarlierNow 等随实例新鲜）。
    val loadEarlierNow by rememberUpdatedState { auto: Boolean -> loadEarlier(auto = auto) }
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
                firstVisibleItemIndex = visible.firstOrNull()?.index ?: 0,
            )
        }.collect { s ->
            val justSettled = !s.scrolling && prevScrolling
            prevScrolling = s.scrolling
            // 程序化滚动计数并入相位门（本轮）：跳转/恢复锚在途（计数>0）的几何过窗不算用户驱动
            val userDriven = isUserDrivenScroll(s.scrolling, followAnimating || programmaticJumpInFlight > 0)
            if (s.lastItemVisible && shouldRecomputeAnchor(userDriven, justSettled)) {
                atBottom = isAtBottom(s)
            }
            // 近顶自动续拉（本轮，对齐 iOS HubViews userDriven 门）：用户滚动停在顶部近窗且
            // 入口 Loadable 才触发；loadEarlier 内 loadingEarlier/earlierExhausted/
            // canLoadEarlierNow 守卫照旧幂等双保险。iOS earlierAutoProximity 为像素、桌面为
            // 行数近似——parity 口径差异知情（见 TimelineScroll.kt 常量注）。
            // armRestore=false（自检轮 R1-8）：不武装恢复锚——prepend 后视口自然落在新页；
            // auto=true（自检轮 2 R2-9）：REST 失败锁存停自动链，手动按钮重试成功解锁。
            if (shouldAutoLoadEarlier(
                    firstVisibleItemIndex = s.firstVisibleItemIndex,
                    loaderState = earlierRowState(
                        loading = loadingEarlier,
                        canLoad = canLoadEarlier(earlierExhausted, liveLastSeq, messages.size, messages.isEmpty()),
                        exhausted = earlierExhausted,
                    ),
                    isUserDrivenScroll = userDriven,
                )
            ) {
                loadEarlierNow(true)
            }
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            // 时间线键盘导航（2026-11-23 批次二轮，对齐 iOS ⌘↓ 菜单回底——HubViews.swift
            // onChange(of: hub.jumpToBottomToken)，确认评审收口改符号引用防漂移）：
            // preview 相自根下行——composer 聚焦时 Home/End/⌘↓↑ 全放行给文本编辑（聚焦门，
            // preview 相根级 return true 会抢走 composer 行首/行尾光标键）；时间线导航仅在无
            // 输入聚焦态生效（iOS ⌘↓ 菜单回底在 composer 聚焦态同样不生效，两平台输入惯例
            // 差异知情）。命中命令即消费（return true），未命中/KeyUp 放行；resolveEnterKey
            // 分流在 composer 自身 onPreviewKeyEvent 不受影响。
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (composerFocused) return@onPreviewKeyEvent false
                val command = resolveTimelineScrollKey(
                    // Key.keyCode→Int 映射在接线处（桌面 keyCode 为 Long，.toInt() 即 AWT VK code，
                    // 见 TimelineScroll.kt 常量注）
                    keyCode = event.key.keyCode.toInt(),
                    isCtrlOrMeta = event.isCtrlPressed || event.isMetaPressed,
                ) ?: return@onPreviewKeyEvent false
                if (messages.isEmpty()) return@onPreviewKeyEvent false // 空会话无可滚（animateScrollToItem 越界）
                // 自检轮 2 R2-8：键盘跳顶显式解锚——用户意图离开底部（与 iOS 人意愿优先纪律
                // 同向）。跳顶后各帧非用户驱动且尾行不可见（本文件滚动锚定块 :600 守卫），
                // 锚定态永不重算：残真值会让下一次尾签变化（流式推进/新消息）把用户从历史区
                // 硬拽回底部。回底分支无需置值——落地后经 justSettled+lastItemVisible 重算回 true。
                if (command == TimelineScrollCommand.ScrollToTop) atBottom = false
                scope.launch {
                    programmaticJumpInFlight += 1
                    try {
                        when (command) {
                            TimelineScrollCommand.ScrollToBottom ->
                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                            TimelineScrollCommand.ScrollToTop -> listState.animateScrollToItem(0)
                        }
                    } finally {
                        programmaticJumpInFlight -= 1
                    }
                }
                true
            },
    ) {
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
                            color = ink.textTertiary, // 原 0xFF999999（DSK-1 散写收敛）
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
                modifier = Modifier
                    .fillMaxWidth()
                    // 内容列居中帽：宽窗审批卡不随窗口全幅拉伸，与时间线同测度
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = CONTENT_COLUMN_MAX_WIDTH_DP.dp)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
                        color = ink.textTertiary, // 原 0xFF888888
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            // 2026-11-07 二轮：回到底部悬浮按钮容器（最后一条 item 不在视口时浮现，见 overlay）。
            // 宽窗内容列居中帽（2026-11-22，iOS Catalyst 同测度）：fillMaxWidth 撑满后
            // wrapContentWidth 把 668 收口列居中——iOS MacContentColumnModifier 双 frame
            // 同式（先帽后撑满居中）；只收宽度，weight/滚动锚定零触碰。
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = CONTENT_COLUMN_MAX_WIDTH_DP.dp)
            ) {
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
                            Text("加载更早…", fontSize = 11.sp, color = ink.textTertiary) // 原 0xFF999999
                        }
                    }
                    EarlierRowState.Loadable -> item(key = "earlier-loader") {
                        // 手动点击路径（armRestore 默认 true）：武装恢复锚保阅读位置（自动续拉
                        // 才传 false，见 loadEarlier 注——自检轮 R1-8）
                        TextButton(
                            onClick = { loadEarlier() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("↑ 加载更早", fontSize = 12.sp) }
                    }
                    EarlierRowState.Exhausted -> item(key = "earlier-loader-end") {
                        Text(
                            "已到最早消息",
                            fontSize = 11.sp,
                            color = ink.textTertiary, // 原 0xFF999999
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
                            color = ink.dangerText, // 原 0xFFC53030 错误红
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
            // 滚动条（2026-11-23 批次二轮补齐三件之一）：默认 ScrollbarStyle 不引新令牌；
            // 铺在回底按钮之下（按钮后绘制在上）
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            // 2026-11-07 二轮：回到底部悬浮按钮（iOS HubChatView 同款语义；最后一条不可见即浮现）
            // 程序化跳转入计数（本轮）：动画中途 firstVisible 过窗不再误触发自动续拉（白付 REST）
            if (showJumpToBottom) {
                TextButton(
                    onClick = {
                        scope.launch {
                            programmaticJumpInFlight += 1
                            try {
                                listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
                            } finally {
                                programmaticJumpInFlight -= 1
                            }
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
                modifier = Modifier
                    .fillMaxWidth()
                    // 内容列居中帽：宽窗面板不随窗口全幅拉伸，与时间线/composer 同测度
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = CONTENT_COLUMN_MAX_WIDTH_DP.dp)
                    .padding(horizontal = 14.dp),
            )
        }

        // 发送区（底色带保持全幅 = composer 独立分区语义；输入测度收内容列帽，与气泡列
        // 对齐同一测度——iOS macContentColumn 把 composer 收进列帽的桌面对应面）
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.CenterHorizontally)
                    .widthIn(max = CONTENT_COLUMN_MAX_WIDTH_DP.dp)
                    .padding(12.dp),
            ) {
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
                                        Text("✕", fontSize = 10.sp, color = ink.textTertiary) // 原 0xFF999999
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
                        color = ink.dangerText, // 原 0xFFC53030 错误红
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
                    // 会话级换形象轮（v1.5.2）：有覆盖 = 造型/配色从覆盖档注入（seed 照传）；
                    // 无覆盖/null = 现状派生零变化。点按 = 换装 dialog 入口②（聊天页角色）。
                    Box(Modifier.clickable { avatarDialogOpen = true }) {
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
                            faceFactorsOverride = avatarOverride?.faceFactors,
                            paletteIndexOverride = avatarOverride?.let { AvatarPalette.entries.indexOf(it.paletteId) },
                        )
                    }
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
                            Text("结束中", fontSize = 10.sp, color = ink.textTertiary) // 原 0xFF999999
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

    // 会话级换装对话框（#32 PUT / #33 DELETE；成功经 shared 门面收敛进
    // conversationAvatarProfiles，本页角色渲染帧驱动自动刷新）
    if (avatarDialogOpen) {
        ConversationAvatarDialog(
            client = client,
            conversationId = conversationId,
            conversationLabel = label,
            globalMotion = globalMotion,
            onDismiss = { avatarDialogOpen = false },
        )
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
            // 行级右键菜单（wp-10 复制全文/重发）与正文 SelectionContainer（本轮）并存的桌面
            // 惯例：正文右键出选中拷贝菜单（SelectionContainer 自带）、行空白右键出全文/重发
            // ——菜单合并行为属实机走查项，预案留码：若互相吞菜单，ContextMenuItem 保持行级
            // 不动、SelectionContainer 只包非交互区（不预先实现）。
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
    // DSK-1 散写收敛：墨水令牌单点读取（状态徽标/时间戳弱化灰）
    val ink = LocalDesktopInk.current
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
            // 气泡宽度帽（iOS Catalyst HubMessageRow 640pt 同测度）：列 horizontalAlignment=End
            // 保住右贴落位（iOS .trailing 同口径），长正文帽内换行
            modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH_DP.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
        ) {
            // 正文选中（2026-11-23 批次二轮）：滑动游标选中局部复制（与行级 ContextMenuArea
            // 右键全文复制并存，见 MessageRow 注）
            SelectionContainer {
                Text(
                    // wp-8③ 空正文防线：空 SENDING 用户行不渲染空气泡，占位「…」+ 状态徽标照常翻转
                    text = if (isEmptyUserBody(m)) "…" else m.content,
                    color = if (isEmptyUserBody(m)) ink.textTertiary else Color.Unspecified, // 原 0xFF999999
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTs(m.ts), fontSize = 11.sp, color = ink.textTertiary) // 原 0xFF999999
            when (m.uiState) {
                // #3（2026-11-08）：排队中 = 消息级落库状态（发送时 run 活跃），run 终态由
                // 投影层统一释放翻已送达——替代旧 composer 会话级队列提示。
                // DSK-1 散写收敛：警示橙（原 0xFFB26A00）→ warningText、弱化灰（原 0xFF666666）
                // → textTertiary、状态绿（原 0xFF2E9E44）→ successText。
                "queued" -> TagChip("排队中", ink.warningText, Modifier.padding(start = 6.dp))
                "sending" -> TagChip("发送中…", ink.textTertiary, Modifier.padding(start = 6.dp))
                "failed" -> TagChip("发送失败", MaterialTheme.colorScheme.error, Modifier.padding(start = 6.dp))
                "delivered" -> TagChip("已送达", ink.successText, Modifier.padding(start = 6.dp))
                "read" -> TagChip("已读", ink.successText, Modifier.padding(start = 6.dp))
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

// ── 打字机节拍（2026-11-28 流式逐字轮；与 iOS Chat/StreamingTypewriter.swift TypewriterPacing
//    同值同义对锁——回归值表锁在 StreamingTypewriterPacingTest，单端改值必须双端同步） ──
//
// 旧档「max(1, 积压/90) 簇 / 16ms + 洪泛档 积压/3」废止：积压大时一 tick 跳多簇（2s 窗 300
// 簇积压 = 每 16ms 跳 3~4 簇）、抽干后空等到下一窗，且无等待光标——「流式输出粗糙」的来源。
// 新口径 = **恒逐字**：每 tick 至多放 1 个字素 Cluster，靠缩短 tick 提速而非加步长。

/** 慢速档 tick（毫秒）：小积压 ≈31 簇/秒，可辨识的逐字节拍（iOS baseTickNs=32ms）。 */
internal const val TYPEWRITER_BASE_TICK_MS = 32L

/** 帧率档 tick（毫秒）：≈60 簇/秒，逐字揭示的速度上限（iOS minTickNs=16ms）。 */
internal const val TYPEWRITER_MIN_TICK_MS = 16L

/** 追平视界（毫秒）：贴桥 2s 合并窗（delta-coalesce DEFAULT_COALESCE_WINDOW_MS）。 */
internal const val TYPEWRITER_HORIZON_MS = 2_000L

/**
 * 流式期滞后上限（簇）：积压 ≤750 恒逐字（一次性到达的整条常见回复完整逐字放完）；超限
 * 步进升档 ceil(积压/750)——持续快流（>60 簇/秒）下显示滞后有界（此时每帧多簇肉眼已
 * 不可分辨）。iOS TypewriterPacing.lagLimitChars 同值对锁。
 */
internal const val TYPEWRITER_LAG_LIMIT_CLUSTERS = 750

/**
 * final 续打期严格逐字的积压上限（簇）：行收敛 final 时剩余积压 ≤750 **仍严格逐字**——一次性
 * 回复的续打即正文本体，不得快进（iOS carrySingleLimit）。
 */
internal const val TYPEWRITER_CARRY_SINGLE_LIMIT = 750

/**
 * final 续打期超出 [TYPEWRITER_CARRY_SINGLE_LIMIT] 部分的追平除数（tick 量级，iOS
 * carryDrainTicks）：按 ceil(超出/60) 步进，积压指数消退、回到上限内即逐字收口——长回复
 * 尾包 ~60×(1+ln) tick 有界收敛，不一次跳出。
 */
internal const val TYPEWRITER_CARRY_DRAIN_TICKS = 60

/** 等待期光标闪烁半周期（毫秒）。 */
internal const val TYPEWRITER_BLINK_MS = 530L

/**
 * 首个流式行从 0 逐字的积压上限：首窗 ≤1200 簇逐字（探针实证：快 agent 一条 800 字回复在
 * 首个 2s 合并窗内整段到达并紧接 final——首窗不逐字则全程零逐字）；超此量=迟到物化的长
 * 内容，贴齐不重打。iOS TypewriterPacing.firstChunkTypingLimit。
 */
internal const val TYPEWRITER_FIRST_CHUNK_LIMIT = 1_200

/**
 * 本 tick 时长（毫秒）= 追平视界 / 积压，钳 [帧率档, 慢速档]：积压 ≤62 簇走慢速档，≥125 簇
 * 压到帧率档，之间线性过渡——持续快流下显示速率贴帧率档上限不整块倾泻，积压消退时 tick
 * 渐次放慢到慢速档（尾段减速收尾）。整数除法（毫秒粒度）与 iOS 同式，两端 tick 逐值相等；
 * 非正积压按 1。
 */
internal fun typewriterTickMs(backlog: Int): Long =
    (TYPEWRITER_HORIZON_MS / backlog.coerceAtLeast(1)).coerceIn(TYPEWRITER_MIN_TICK_MS, TYPEWRITER_BASE_TICK_MS)

/**
 * 流式期本 tick 步进（簇）：恒 1，仅积压超 [TYPEWRITER_LAG_LIMIT_CLUSTERS] 时 ceil(积压/上限)
 * 升档；入参非正按 0 处理（恒 1）。iOS TypewriterPacing.step 同式。
 */
internal fun typewriterStep(backlog: Int): Int =
    maxOf(1, (backlog.coerceAtLeast(0) + TYPEWRITER_LAG_LIMIT_CLUSTERS - 1) / TYPEWRITER_LAG_LIMIT_CLUSTERS)

/**
 * final 续打期本 tick 步进（簇）：积压 ≤ [TYPEWRITER_CARRY_SINGLE_LIMIT] 恒 1；超出部分按
 * ceil(超出/[TYPEWRITER_CARRY_DRAIN_TICKS]) 追平（积压指数消退，回到上限内即逐字收口）。
 * iOS TypewriterPacing.carryStep 同式。
 */
internal fun typewriterCarryStep(backlog: Int): Int {
    val excess = backlog - TYPEWRITER_CARRY_SINGLE_LIMIT
    if (excess <= 0) return 1
    return maxOf(1, (excess + TYPEWRITER_CARRY_DRAIN_TICKS - 1) / TYPEWRITER_CARRY_DRAIN_TICKS)
}

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

// ── 打字机交接账本（iOS Chat/StreamingTypewriter.swift TypewriterHandoff 镜像） ──

/**
 * 打字机交接账本（2026-11-28 流式逐字轮；纯逻辑类，时钟注入供单测）。三条职责：
 * 1. **final 续打**：流式行被 final 权威行替换时行身份（dstream:… → messageId）更换，
 *    LazyColumn 视为全新行，旧打字机连同剩余积压一并离场——旧口径尾包一次性跳出。流式行
 *    每 tick 记进度（按会话一键），final 行首次组合时查得「同会话 + 未过期 + 前缀吻合 +
 *    确有剩余」即从记录进度续打（TTL 5s：行滚出视口后再物化、快照/重进等迟到物化一律贴齐）；
 * 2. **首窗逐字 / 重物化不重打**（流式行）：流式行由首个 delta 创建，旧口径「挂载即贴齐」
 *    使每条回复的首个 2s 窗恒整块瞬显。按 messageId 辨别——本会话账本未见该行 = 首次出现，
 *    从 0 逐字；已见（滚动往返重新物化、runActive 瞬态翻转重建分支）= 贴齐不重打；
 * 3. **实时新到的 final 行逐字**（探针实证的主路径）：服务端事件表实查——OpenClaw 网关近
 *    3 天仅 2 条 message.delta 对 278 条 message.created，main 会话回复是 run.started →
 *    **整条 message.created** → run.completed，没有流式增量；快 agent 的一次性回复也是合并窗
 *    flush 与 final 同批落库。两条路径下流式行从未浮现，只给流式行做打字机 = 这类回复全程
 *    零逐字（「流式输出粗糙」的真根因）。现按「未见此 final 行 messageId + 消息时间戳在近
 *    [freshWindowMs] 内」判定为实时新到，从 0 逐字打出；历史行与已见行不重打。
 * 线程纪律：仅 UI 线程访问（组合与 LaunchedEffect 同线程），无同步。
 */
internal class TypewriterHandoffLedger(
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = 5_000,
    /** 「实时新到」时间窗（毫秒，iOS freshFinalWindow=120s 同值）：容纳设备与服务端时钟偏差。 */
    private val freshWindowMs: Long = 120_000,
) {
    data class Entry(val messageId: String, val shown: Int, val content: String, val at: Long)

    private val entries = HashMap<String, Entry>()

    /** 该会话账本是否未见此行（= 首次出现的流式行）；空键恒 false（不记账的调用点不触发首窗逐字）。 */
    fun isNewStream(conversationId: String, messageId: String): Boolean =
        conversationId.isNotEmpty() && messageId.isNotEmpty() && entries[conversationId]?.messageId != messageId

    fun record(conversationId: String, messageId: String, shown: Int, content: String) {
        if (conversationId.isEmpty()) return
        entries[conversationId] = Entry(messageId, shown, content, now())
    }

    /** final 行续打起点：null = 不续打（无记录/过期/前缀不吻合/无剩余积压）。过期即清（长内容串不滞留）。 */
    fun carryStart(conversationId: String, content: String): Int? {
        if (conversationId.isEmpty()) return null
        val e = entries[conversationId] ?: return null
        if (now() - e.at >= ttlMs) {
            entries.remove(conversationId)
            return null
        }
        val shown = e.shown.coerceIn(0, e.content.length)
        if (shown <= 0 || shown >= content.length || !content.startsWith(e.content)) return null
        return shown
    }

    /** 实时新到登记（messageId → 登记时刻），打字机起跑即消耗。 */
    private val pendingFresh = HashMap<String, Long>()

    /** 消息流观测状态（当前会话的已知消息 id）：首帧 = 历史加载，其后新出现的助手 final 行 = 实时新到。切会话即复位。 */
    private var observedConversationId: String? = null
    private val knownMessageIds = HashSet<String>()
    private var observedInitialFrame = false

    /** 消息流观测输入（消息流每帧逐行映射；只带判定所需字段）。 */
    data class ArrivalCandidate(
        val messageId: String,
        val role: String,
        val state: String,
        val ts: String?,
        val contentLength: Int,
    )

    /**
     * 消息流观测（消息流每帧到达时调用）：已打开会话里**首次出现**的助手 final 行登记为实时
     * 新到。首帧（打开会话的历史加载）只记已知不算新到；消息时间戳须在近 [freshWindowMs] 内
     * （翻页回灌的旧行不算）。判定落在数据入口而非行组合：组合可重入，入口首见唯一。
     */
    fun observeMessages(conversationId: String, list: List<ArrivalCandidate>) {
        if (conversationId.isEmpty()) return
        if (observedConversationId != conversationId) {
            observedConversationId = conversationId
            knownMessageIds.clear()
            observedInitialFrame = false
        }
        val initial = observedInitialFrame
        for (c in list) {
            if (!knownMessageIds.add(c.messageId)) continue
            if (initial && c.role == "assistant" && c.state == "final") noteFreshArrival(c)
        }
        observedInitialFrame = true
    }

    private fun noteFreshArrival(c: ArrivalCandidate) {
        if (c.messageId.isEmpty() || c.contentLength <= 0 || c.contentLength > FRESH_FINAL_TYPING_LIMIT) return
        val at = c.ts?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
        if (abs(now() - at) > freshWindowMs) return
        pendingFresh[c.messageId] = now()
    }

    /**
     * 实时新到的 final 行打字起点：0 = 从 0 逐字；null = 贴齐整段。非消耗——行组合可重入，
     * 登记项由打字机起跑时消耗（[consumeFreshFinal]）；登记后 [FRESH_PENDING_TTL_MS] 未起跑
     * 即过期贴齐（迟到物化不重打旧消息）。
     */
    fun freshFinalStart(messageId: String): Int? {
        val at = pendingFresh[messageId] ?: return null
        if (now() - at >= FRESH_PENDING_TTL_MS) {
            pendingFresh.remove(messageId)
            return null
        }
        return 0
    }

    /** 打字机起跑即消耗登记：此后的重物化由进度账本接续（TTL 内）或贴齐，不从头重打。 */
    fun consumeFreshFinal(messageId: String) {
        pendingFresh.remove(messageId)
    }

    /** 登出清账（换账号不残留）。 */
    fun reset() {
        entries.clear()
        pendingFresh.clear()
        observedConversationId = null
        knownMessageIds.clear()
        observedInitialFrame = false
    }

    private companion object {
        /** 实时新到 final 行逐字的内容上限（UTF-16 长度）：超长整条（历史回灌/特大粘贴）贴齐不重打。 */
        const val FRESH_FINAL_TYPING_LIMIT = 20_000
        /** 「实时新到」登记有效期（毫秒，iOS freshFinalPendingTTL=30s 同值）。 */
        const val FRESH_PENDING_TTL_MS = 30_000L
    }
}

/** 应用级单例（登出链调 [resetForLogout]）。 */
internal object TypewriterHandoff {
    val ledger = TypewriterHandoffLedger()
    fun resetForLogout() = ledger.reset()
}

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
    // DSK-1 散写收敛：墨水令牌单点读取（占位灰/时间戳/流式 chip）
    val ink = LocalDesktopInk.current
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
            // 气泡宽度帽（iOS Catalyst HubMessageRow 640pt 同测度）：列 horizontalAlignment=Start
            // 保住左贴落位（iOS .leading 同口径），长正文帽内换行
            modifier = Modifier.widthIn(max = BUBBLE_MAX_WIDTH_DP.dp),
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
                    color = ink.textTertiary, // 原 0xFF999999 占位灰（DSK-1 散写收敛）
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
                        color = ink.textTertiary, // 原 0xFF999999
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                // wp-18：助手行走轻量 Markdown 渲染器（标题/列表/代码块/粗斜体，流式行尾随
                // ▌ 光标）；无 Markdown 语素或解析异常时内置回退 wp-6 纯文本路径
                // 光标随 presentation 门控（=Progressive）：僵尸流式行无 ▌（2026-11-19 清账①）。
                // 2026-11-21 流式单行归一轮：Progressive 改挂打字机包装（挂载贴齐/前缀增长逐字/
                // 跳变贴齐，与 iOS StreamingTypewriter 同参数）——投影层行键归一为单行原地追加后，
                // 桌面增量块不再整块瞬显；final/failed/僵尸行仍整段 Markdown 渲染。
                // 2026-11-23 批次二轮：正文包 SelectionContainer（滑动游标选中局部复制；流式行
                // 选择随打字机增长属可接受，回退预案=仅 final 行包）。占位/无文本/交互卡分支
                // 不包（上方两分支与 interactiveCommand 分支天然在包层外）；MarkdownText 内嵌
                // JsonCardView 随包层覆盖（拖选无害、卡内展开点击不受影响——知情注释）。
                else -> SelectionContainer {
                    val bodyModifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    if (presentation == StreamingRowPresentation.Progressive) {
                        StreamingTypewriterText(
                            content = m.content,
                            modifier = bodyModifier,
                            messageId = m.messageId,
                            conversationId = conversationId,
                        )
                    } else {
                        // final 行打字（2026-11-28 流式逐字轮，iOS HubMessageRow carryStart 镜像）：
                        // 先查流式续打账本（流式行被 final 行替换时接续剩余积压）；否则「实时
                        // 新到」——未见此行且消息时间戳在近 2 分钟内——从 0 逐字打出（OpenClaw
                        // 等网关回复整条经 message.created 落库无 delta，这是逐字效果的主路径）。
                        // 起点首次组合定版（防先闪一帧全文）；历史行/已见行贴齐整段渲染。
                        var carryStart by remember(m.messageId) {
                            mutableStateOf(
                                if (m.state == "final" && m.content.isNotBlank()) {
                                    TypewriterHandoff.ledger.carryStart(conversationId, m.content)
                                        ?: TypewriterHandoff.ledger.freshFinalStart(m.messageId)
                                } else {
                                    null
                                },
                            )
                        }
                        val start = carryStart
                        if (start != null) {
                            StreamingTypewriterText(
                                content = m.content,
                                modifier = bodyModifier,
                                messageId = m.messageId,
                                conversationId = conversationId,
                                carryOverFrom = start,
                                onCaughtUp = { carryStart = null },
                            )
                        } else {
                            MarkdownText(
                                content = m.content,
                                showCursor = false, // Progressive 已上走打字机分支；此处恒非进行中行
                                modifier = bodyModifier,
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTs(m.ts), fontSize = 11.sp, color = ink.textTertiary) // 原 0xFF999999
            // 2026-11-19 纯显示偏好：chip 与 MarkdownText 光标同门（=Progressive）——流式输出关
            // 的非空行呈占位文案不挂 chip；僵尸流式行（runActive=false）同样不挂（清账①）。
            if (presentation == StreamingRowPresentation.Progressive) {
                TagChip("流式生成中", ink.accent, Modifier.padding(start = 6.dp)) // 原 0xFF2B6CB0 交互强调（DSK-1）
            }
        }
        FailedReasonLine(m)
    }
}

/**
 * 桌面打字机包装（iOS Chat/StreamingTypewriter.swift 镜像；2026-11-21 流式单行归一轮引入，
 * 2026-11-28 流式逐字轮重写为恒逐字 + 等待期光标闪烁 + final 续打）。投影层行键归一为
 * dstream:<runId> 单行原地追加后，delta 块（桥 2s 合并窗）经本组件从「已揭示前缀」向新内容
 * **逐字**放出。节拍与步进见 [typewriterTickMs] / [typewriterStep]。
 *
 * 语义边界（与 iOS 逐条对齐）：
 * - 首次出现的流式行（账本未见该 messageId、首窗 ≤ [TYPEWRITER_FIRST_CHUNK_LIMIT] 簇）从 0
 *   逐字——首窗不再整块瞬显；重物化 / 非前缀改写 / 内容收缩直接贴齐（列表 key=messageId 行
 *   身份稳定，本组合项 remember 跨重组存活，仅挂载/重挂载时定版起点）；
 * - 光标恒挂已揭示前缀末尾：逐字期间常亮，积压放空等待期每 [TYPEWRITER_BLINK_MS] 翻转
 *   （透明占位不增减字符，闪烁不动布局）；
 * - [carryOverFrom] 非 null = final 行续打流式行剩余积压（[TypewriterHandoffLedger] 注），
 *   打完经 [onCaughtUp] 交回常规 final 渲染；账本每 tick 记账，续打中行滚出再回来仍接续；
 * - 计量以字素 Cluster 为最小单位（与 iOS String.count / Character 同义）：内部状态=已揭示
 *   簇数，显示切点=[typewriterClusterEnds] 簇表查 UTF-16 偏移——切点恒簇边界，代理对/ZWJ/
 *   CRLF 多码元簇恒整簇揭示。
 */
@Composable
private fun StreamingTypewriterText(
    content: String,
    modifier: Modifier = Modifier,
    messageId: String = "",
    conversationId: String = "",
    carryOverFrom: Int? = null,
    onCaughtUp: (() -> Unit)? = null,
) {
    // 簇边界表：内容每变一次重建一次（=delta 块到达频率，与 MarkdownText remember(content)
    // 重解析同触发同量级）；揭示循环只做簇数算术，显示前缀查表取 UTF-16 切点——零逐 tick 重扫。
    val clusterEnds = remember(content) { typewriterClusterEnds(content) }
    // 起点须在首次组合定版（remember 无键 = 行身份存活期内只算一次；等 LaunchedEffect 再定
    // 会让 final 行先闪一帧全文再缩回前缀重打）：续打 → 记录进度；首次出现的流式行且首窗
    // 不大 → 0；其余 → null 贴齐。
    val freshStream = remember {
        TypewriterHandoff.ledger.isNewStream(conversationId, messageId) &&
            clusterEnds.size <= TYPEWRITER_FIRST_CHUNK_LIMIT
    }
    val initialStart = carryOverFrom ?: if (freshStream) 0 else null
    // 已揭示字素 Cluster 数；null = 未初始化（挂载即贴齐现内容，不重打）。
    var revealedCount by remember { mutableStateOf<Int?>(initialStart) }
    // 上一帧内容（区分 append 前缀增长与改写跳变）。
    var lastSeen by remember { mutableStateOf(if (initialStart == null) "" else content) }
    // 光标可见性（等待期翻转；逐字期间恒 true）。
    var cursorOn by remember { mutableStateOf(true) }

    LaunchedEffect(content) {
        val target = clusterEnds.size
        val start = revealedCount
        if (start == null || !content.startsWith(lastSeen)) {
            // 挂载首帧贴齐 / 非前缀跳变（改写/收缩）：直接贴齐
            revealedCount = target
            lastSeen = content
            TypewriterHandoff.ledger.record(conversationId, messageId, target, content)
            if (carryOverFrom != null) onCaughtUp?.invoke()
            return@LaunchedEffect
        }
        lastSeen = content
        var shown = start.coerceIn(0, target)
        revealedCount = shown
        cursorOn = true
        if (carryOverFrom != null) {
            // final 行打字起跑：消耗「实时新到」登记——此后重物化由进度账本接续，不从头重打。
            TypewriterHandoff.ledger.consumeFreshFinal(messageId)
        }
        TypewriterHandoff.ledger.record(conversationId, messageId, shown, content)
        if (shown >= target) {
            if (carryOverFrom != null) onCaughtUp?.invoke()
            return@LaunchedEffect
        }
        while (true) {
            val backlog = target - shown
            if (backlog <= 0) {
                // 等待新字符：光标闪烁（delay 即取消点——新内容到达经 LaunchedEffect(content)
                // 重启本协程，取消于 delay，进度跨重启保留）。
                delay(TYPEWRITER_BLINK_MS)
                cursorOn = !cursorOn
                continue
            }
            val tick = typewriterTickMs(backlog)
            delay(tick)
            // 每 tick 恒放步长个字素（不做 dt 补齐，iOS 同口径）：系统合并短定时器时按流逝时间
            // 补齐会一拍放出 2 簇——违背「一次一个字」；宁慢不跳。纯簇数推进：簇数为整，切点
            // 查表恒簇边界，代理对/ZWJ 劈半无从产生；final 行打字期 ≤750 簇恒逐字、超出部分
            // 有界追平（typewriterCarryStep）。
            val step = if (carryOverFrom != null) typewriterCarryStep(backlog) else typewriterStep(backlog)
            shown += minOf(step, backlog)
            revealedCount = shown
            cursorOn = true
            TypewriterHandoff.ledger.record(conversationId, messageId, shown, content)
            if (shown >= target) {
                if (carryOverFrom != null) {
                    // 收尾：末字落定后光标再闪一拍才交回常规渲染——一次性到达的回复没有
                    // 「等待新字符」的间隙，这一拍是它唯一可见的闪烁（delay 即取消点，无悬挂回调）。
                    cursorOn = false
                    delay(TYPEWRITER_BLINK_MS / 2)
                    cursorOn = true
                    delay(TYPEWRITER_BLINK_MS / 2)
                    onCaughtUp?.invoke()
                    return@LaunchedEffect
                }
            }
        }
    }
    // 显示前缀：簇数 → UTF-16 切点（防御性 coerce 同旧版；表与 content 同源同 key remember，恒一致）
    val revealed = (revealedCount ?: clusterEnds.size).coerceIn(0, clusterEnds.size)
    MarkdownText(
        content = content.substring(0, if (revealed == 0) 0 else clusterEnds[revealed - 1]),
        showCursor = true,
        cursorOn = cursorOn,
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
        color = LocalDesktopInk.current.dangerText, // 原 0xFFC53030 错误红（DSK-1 散写收敛）
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
                        Text("✎", fontSize = 12.sp, color = LocalDesktopInk.current.textTertiary) // 原 0xFF999999（DSK-1 散写收敛）
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
    // DSK-1 散写收敛：墨水令牌单点读取
    val ink = LocalDesktopInk.current
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
                    color = ink.textTertiary, // 原 0xFF999999
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (cached?.truncated == true) {
                    Text("已截断", fontSize = 11.sp, color = ink.textTertiary) // 原 0xFF999999
                }
            }
            if (cached != null) {
                if (cached.models.isEmpty()) {
                    Text("暂无可用模型", fontSize = 12.sp, color = ink.textTertiary) // 原 0xFF999999
                } else {
                    val grouped = cached.models.groupBy { it.provider }
                    grouped.forEach { (provider, models) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(provider, fontSize = 11.sp, color = ink.textTertiary) // 原 0xFF777777
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
                    color = ink.textTertiary, // 原 0xFF999999
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("正在获取模型目录…", fontSize = 12.sp, color = ink.textTertiary) // 原 0xFF999999
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
    // DSK-1 散写收敛：墨水令牌单点读取
    val ink = LocalDesktopInk.current
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
                    Text("/$arg", fontSize = 11.sp, color = ink.textTertiary) // 原 0xFF999999
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "点击发送 /" + command.name.removePrefix("/") + " <选项>",
                    fontSize = 11.sp,
                    color = ink.textTertiary, // 原 0xFF999999
                    maxLines = 1,
                )
            }
            if (choices.isEmpty()) {
                Text("暂无可用选项", fontSize = 12.sp, color = ink.textTertiary) // 原 0xFF999999
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
                                    color = ink.textTertiary, // 原 0xFF999999
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
                color = LocalDesktopInk.current.textTertiary, // 原 0xFF999999（DSK-1 散写收敛）
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
