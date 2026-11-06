package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
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
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiMessage
import dev.sharpxia.agentreins.hub.UiWorkActivity
import kotlinx.coroutines.launch

private const val OPTIMISTIC_SEQ_SENTINEL: Long = Long.MAX_VALUE

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
 * - Enter（无 Shift）发送、Shift+Enter 换行、Cmd/Ctrl+Enter 兜底发送（裁决纯函数
 *   resolveEnterKey；IME 组合中的 Enter 属提交候选词，不触发）；
 * - 活跃 run 期间「运行中发送将排队」提示（iOS wp-14 同文案同语义：chat.send 不透传
 *   queueMode，发送照常、按 Gateway 默认队列语义排队；发送键位仍是 wp-8② Stop）。
 * 判定纯函数在 TimelineScroll.kt（desktopTest 回归锚），composer 规则纯函数在
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
) {
    val messages by client.observeMessages(conversationId).collectAsState(initial = emptyList())
    // wp-15：工作过程披露条数据面（hubToolEvents + hubRunStatus 两表合流快照）——此前零消费，
    // 会话打开即订阅；会话切换随 HubShell key(conversationId) 重挂换订阅。
    val workActivity by client.observeWorkActivity(conversationId)
        .collectAsState(initial = UiWorkActivity(emptyList(), emptyList()))
    // wp-6：TextFieldValue 携带 IME 组合区间（composition），Enter 发送裁决要区分
    // 「组合窗中回车 = 提交候选词」与「普通回车 = 发送」——纯 String 拿不到该信号。
    var input by remember { mutableStateOf(TextFieldValue("")) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // 已读上报基线：只认真实服务端 seq（乐观行占位 seq=Long.MAX_VALUE、failed 行非服务端消息，均不计入）
    var reportedReadUpTo by remember { mutableStateOf(0L) }

    // wp-8②：实时行——conversationId 是打开时刻的句柄，hasActiveRun/activeRunId 随 run.* 事件
    // 后续翻转，Stop 按钮态一律从会话列表现值取，不得用打开时刻快照（会话列表 Flow 持续推送）。
    val liveConv = conversations.firstOrNull { it.conversationId == conversationId }
    val isRunActive = liveConv?.hasActiveRun == true
    // Stop 可执行 = 活跃 run 且 activeRunId 已习得（wp-5 v4 投影）。映射未学习场景（null——
    // DefaultEventProjector.setRun 在 resolveConversation 失败时直接 return，不落库）置灰为
    // 「结束中」提示态，不假设 runId 恒有值、不误触。
    val canCancelRun = !liveConv?.activeRunId.isNullOrEmpty()

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

    // wp-6：发送一条（发送键 / Enter / 无参斜杠命令选中三路同一通道）——commands 幂等队列，
    // sendMessage 每次生成新幂等键（ADR-07）；空白草稿禁发（与发送键 isNotBlank 防线同口径）。
    fun sendDraft() {
        val text = input.text.trim()
        if (text.isEmpty()) return
        input = TextFieldValue("")
        scope.launch { client.commands?.sendMessage(conversationId, text) }
    }

    // wp-6：斜杠命令选中（iOS selectSlashCommand 同语义）。无参命令即选即发——与发送键同一
    // 通道（commands.sendMessage 普通文本，Gateway 原生斜杠语义，Hub/桥/服务端命令面零改动）；
    // 带参命令预填命令文本（含尾随空格、光标落行尾）续写参数，最终发送仍走同一普通文本通道。
    fun selectSlashCommand(cmd: SlashCommand) {
        if (cmd.takesArgument) {
            input = TextFieldValue(cmd.name + " ", TextRange(cmd.name.length + 1))
        } else {
            input = TextFieldValue("")
            scope.launch { client.commands?.sendMessage(conversationId, cmd.name) }
        }
    }

    // wp-6：斜杠命令建议——draft 起手 "/" 且未进入参数书写段时给出目录前缀命中；普通消息、
    // URL、多行草稿等其余输入零干扰不触发（筛选细则见 SlashCommandCatalog.suggestions）。
    val slashSuggestions = SlashCommandCatalog.suggestions(forDraft = input.text)

    Column(modifier = Modifier.fillMaxSize()) {
        // 头部：会话名（投影 label）
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.ifBlank { "（未命名会话）" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // wp-9：审批横条（置顶于时间线上方）——审批是 run 的前置门，出现即需注意，不进滚动区防淹没
        if (approvals.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                approvals.forEach { a ->
                    ApprovalCard(a, conversations, decisions, client, scope)
                }
            }
        }

        // wp-15：工作过程披露条（审批横条同位、时间线上方，iOS workProcessSection parity）——
        // 只在有已捕获内容（工具行或阶段帧）时出现，零噪声；无活动会话不渲染、历史会话不虚构
        // （断线窗口丢失面不回填，06 §10.2）；run 终态阶段行由投影清行，UI 只渲染快照。
        if (shouldRenderWorkBar(workActivity.tools, workActivity.phases)) {
            WorkProcessBar(
                activity = workActivity,
                runActive = isRunActive,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

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
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
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
                    MessageRow(m, client, conversationId, scope)
                }
            }
        }

        // wp-6：斜杠命令建议面板——draft 由 "/" 起手时悬浮于发送区上方（iOS wp-14 同位），
        // 选中即动作；无命中/进入参数书写段自动收起，其余输入零干扰。
        if (slashSuggestions.isNotEmpty()) {
            SlashCommandPanel(
                suggestions = slashSuggestions,
                onSelect = { selectSlashCommand(it) },
                modifier = Modifier.padding(horizontal = 14.dp),
            )
        }

        // 发送区
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                if (isRunActive) {
                    // wp-6（iOS wp-14 队列提示同文案同语义）：队列模式提示文案——chat.send 不透传
                    // queueMode 参数（schema ChatSendParams.queueMode steer/followup/collect/
                    // interrupt 本轮不接），运行中发送按 Gateway 默认队列语义排队，文案与该实际
                    // 行为对齐；语义透传留待后续。发送照常不阻塞：键盘 Enter 与无参斜杠命令选中
                    // 仍可发（发送键位仍由 wp-8② Stop 常驻替代）。
                    Text(
                        "⏳ 运行中发送将排队",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("发送消息…") },
                        modifier = Modifier
                            .weight(1f)
                            .onPreviewKeyEvent { event ->
                                // wp-6：Enter 键裁决在 preview 相拦截（先于 TextField 默认换行）；
                                // IME 组合中的 Enter 属提交候选词，让行默认行为（不消费不发送）。
                                val isEnterDown = event.type == KeyEventType.KeyDown &&
                                    (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                if (!isEnterDown || input.composition != null) {
                                    return@onPreviewKeyEvent false
                                }
                                when (
                                    resolveEnterKey(
                                        isShiftPressed = event.isShiftPressed,
                                        isMetaOrCtrlPressed =
                                            event.isCtrlPressed || event.isMetaPressed,
                                        draft = input.text,
                                    )
                                ) {
                                    EnterKeyBehavior.Newline -> false
                                    EnterKeyBehavior.Ignored -> true
                                    EnterKeyBehavior.Send -> {
                                        sendDraft()
                                        true
                                    }
                                }
                            },
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (isRunActive) {
                        // wp-8②：Stop 按钮（grokbot 式常驻）——活跃 run 期间替代发送键；中止后部分
                        // 输出保留由 Gateway 权威收敛（run.aborted 回流投影库，UI 只翻转回发送键）。
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Button(
                                onClick = {
                                    val runId = liveConv?.activeRunId ?: return@Button
                                    scope.launch { client.commands?.cancelRun(runId) }
                                },
                                enabled = canCancelRun,
                            ) { Text("■ 停止") }
                            if (!canCancelRun) {
                                Text("结束中", fontSize = 10.sp, color = Color(0xFF999999))
                            }
                        }
                    } else {
                        // 空正文防线（发送侧）：空白草稿禁发（isNotBlank = 去空白后非空），点击再兜一层
                        Button(
                            onClick = { sendDraft() },
                            enabled = input.text.isNotBlank(),
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
                    else -> AssistantMessageRow(m)
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

@Composable
private fun AssistantMessageRow(m: UiMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            when {
                // 流式空行：typing 占位（…▌，wp-7 同型；05 §3.11：流式行允许跳过 delta，终态由 created 收敛）
                m.state == "streaming" && m.content.isBlank() -> Text(
                    "…▌",
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // wp-8③ 空正文防线：final/failed 空助手行渲染占位（不再出现空气泡——
                // 按 MessageActions.isEmptyBodyPlaceholder 既定契约补上占位文案）
                isEmptyBodyPlaceholder(m) -> Text(
                    "（无文本内容）",
                    fontStyle = FontStyle.Italic,
                    color = Color(0xFF999999),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                // wp-18：助手行走轻量 Markdown 渲染器（标题/列表/代码块/粗斜体，流式行尾随
                // ▌ 光标）；无 Markdown 语素或解析异常时内置回退 wp-6 纯文本路径
                else -> MarkdownText(
                    content = m.content,
                    showCursor = m.state == "streaming",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatTs(m.ts), fontSize = 11.sp, color = Color(0xFF999999))
            if (m.state == "streaming") {
                TagChip("流式生成中", Color(0xFF2B6CB0), Modifier.padding(start = 6.dp))
            }
        }
        FailedReasonLine(m)
    }
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
 */
private const val SLASH_PANEL_MAX_HEIGHT_DP = 148

@Composable
private fun SlashCommandPanel(
    suggestions: List<SlashCommand>,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = SLASH_PANEL_MAX_HEIGHT_DP.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            suggestions.forEachIndexed { index, cmd ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    )
                }
                if (index < suggestions.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 12.dp))
                }
            }
        }
    }
}
