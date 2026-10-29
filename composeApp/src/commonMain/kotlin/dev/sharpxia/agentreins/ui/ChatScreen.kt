package dev.sharpxia.agentreins.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.Message
import dev.sharpxia.agentreins.model.ThinkingEntry
import dev.sharpxia.agentreins.model.ToolCallEvent
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import dev.sharpxia.agentreins.openclaw.Outbox
import dev.sharpxia.agentreins.ui.components.InkMarkdown
import dev.sharpxia.agentreins.ui.components.SandSheet
import dev.sharpxia.agentreins.ui.components.SandSheetAction
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.AgentTintScope
import dev.sharpxia.agentreins.ui.theme.InkTheme
import dev.sharpxia.agentreins.ui.theme.LocalAgentTint
import dev.sharpxia.agentreins.ui.theme.agentTintFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** LazyColumn 行 key（稳定键=分区加载 prepend 时的位置保持依据；锚恢复同源）。 */
private fun rowKeyOf(row: ChatRow): String = when (row) {
    is ChatRow.DayHeader -> "day-${row.atMs}"
    is ChatRow.Entry -> row.entry.id
    is ChatRow.ToolGroup -> row.key
    is ChatRow.Boundary -> "bnd-${row.sessionKey}"
    is ChatRow.CompactMark -> "cmp-${row.sessionKey}"
}

/**
 * 钉到列表绝对底部（M4 反馈②③④统一根因）：scrollToItem(last) 把该条消息的**顶边**
 * 对齐视口顶——长消息超一屏时「到底」实际停在消息中部上方（进会话不到底/回底键无效/
 * 打字机不跟随全是它）。大 scrollOffset 会被 LazyList 钳制到列表末尾 = 对齐最后一条底边。
 */
private suspend fun LazyListState.snapToAbsoluteBottom(totalRows: Int) {
    if (totalRows > 0) scrollToItem(totalRows - 1, 100_000)
}

/**
 * 对话流（M3.3 墨水风重绘）：日分隔、助手墨水平文（Markdown v2 表格/嵌套/高亮）、
 * 用户 tint 气泡、流式 shimmer、消息入场/高度过渡、长按菜单、outbox 待发气泡。
 * 滚动定位/分页/键盘逻辑沿用 M2.6（真机打磨过的行为不动）。
 * M4 P3：[timeline] 非 null = conversation 拼接模式（多主 session 分区时间线）。
 */
@Composable
internal fun ChatScreen(
    model: AppModel,
    sessionKey: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    timeline: List<AppModel.ConvSection>? = null,
    convTitle: String? = null,
    convSubtitle: String? = null,
    readOnly: Boolean = false,
) {
    val state = model.conversation
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptics = rememberHapticPlayer()
    val clipboard = LocalClipboardManager.current
    var initialScrollDone by remember { mutableStateOf(false) }
    var showJumpToLatest by remember { mutableStateOf(false) }

    // rows 重建用内容签名判定（size/首尾 id/尾部长度/工具卡状态），而非 entries 引用：
    // 后台 seq 缺口回填与 run 终局刷新会整体替换 entries——内容无变化时避免全列表重组
    // （mikepenz Markdown 重组成本高，滑动中表现为重绘/割裂，真机反馈实锤）
    val currentEntries = state?.entries ?: emptyList()
    val rowsSignature = remember(currentEntries) {
        buildString {
            append(currentEntries.size)
            append(':'); append(currentEntries.firstOrNull()?.id)
            append(':'); append(currentEntries.lastOrNull()?.id)
            append(':'); append((currentEntries.lastOrNull() as? Message)?.content?.length ?: 0)
            currentEntries.filterIsInstance<ToolCallEvent>().forEach {
                append(':'); append(it.id); append('='); append(it.state.name)
            }
            // 思考条目流式增长也要触发 rows 重建（快照全量重放，size 不变仅 text 变）
            currentEntries.filterIsInstance<ThinkingEntry>().forEach {
                append(':'); append(it.id); append('#'); append(it.text.length)
            }
        }
    }
    // 拼接模式：全部区的内容签名（任一区变化才重建整表）；压缩标记也进签名
    val compactMarks = model.compactMarks
    val timelineSignature = remember(timeline, compactMarks) {
        timeline?.joinToString("|") { s ->
            val e = s.state?.entries ?: emptyList()
            buildString {
                append(s.key); append('#'); append(e.size)
                append(':'); append(e.firstOrNull()?.id)
                append(':'); append(e.lastOrNull()?.id)
                append(':'); append((e.lastOrNull() as? Message)?.content?.length ?: 0)
                append(';'); append(compactMarks[s.key] ?: 0L)
                e.filterIsInstance<ToolCallEvent>().forEach {
                    append(':'); append(it.id); append('='); append(it.state.name)
                }
                e.filterIsInstance<ThinkingEntry>().forEach {
                    append(':'); append(it.id); append('#'); append(it.text.length)
                }
            }
        }
    }
    val rows = if (timeline != null) {
        remember(timelineSignature) {
            buildConversationRows(
                timeline.map { TimelineSection(it.key, it.label, it.state?.entries ?: emptyList()) },
                compactMarks = compactMarks,
            )
        }
    } else {
        remember(rowsSignature) { buildChatRows(currentEntries) }
    }
    // 分区锚定（M4 P5）：agent 主页 sessions 行点击 → 时间线定位到该 session 分区头，
    // 并确保该区内容已加载（视口触发只管进入视口的分区头，锚定可能直接落在中段）
    val currentRows by rememberUpdatedState(rows)
    LaunchedEffect(model.pendingConvAnchor) {
        val anchor = model.pendingConvAnchor ?: return@LaunchedEffect
        if (timeline == null) {
            model.pendingConvAnchor = null
            return@LaunchedEffect
        }
        while (currentRows.isEmpty()) delay(100)
        val idx = currentRows.indexOfFirst { it is ChatRow.Boundary && it.sessionKey == anchor }
        if (idx >= 0) {
            listState.scrollToItem(idx)
            model.pendingConvAnchor = null
            model.loadSectionHistoryIfNeeded(anchor)
        }
    }
    val pending = model.pendingOutbox
    val contentRowCount = rows.size + pending.size
    val lastTextLen = (state?.entries?.lastOrNull() as? Message)?.content?.length ?: 0
    val runActive = state?.activeRunId != null
    // 首页历史是否已到达（真机反馈 bug②）：未到达且无缓存时显示加载态而非空态
    val historyLoaded = state?.historyLoaded == true
    // 气泡左侧小 loading（M4 反馈④）：最后一条用户消息仍是时间线尾部 且 (在途发送 || run 活跃)
    // ——即「我发的消息还没被响应」的完整窗口；agent 开始输出（thinking/工具/回复条目在尾）
    // 后自动熄灭，交给 ThinkingCard/TypingIndicator
    val lastUserEntryIndex = rows.indexOfLast {
        it is ChatRow.Entry && it.entry is Message && (it.entry as Message).role == Message.Role.USER
    }
    val showTailSpinner = (sessionKey in model.sendingSessionKeys || runActive) &&
        lastUserEntryIndex >= 0 && lastUserEntryIndex == rows.lastIndex

    // ---- 下拉加载（M4 反馈⑤，第二轮重做）：自研 nestedScroll——material3
    // PullToRefreshBox 真机松手即回弹不触发。确定性语义：列表在顶时下拉以 0.4 阻尼
    // 累积 overscroll → 松手过阈值且加载可派发 = 顶部居中 spinner 持续到加载完成；
    // 不过阈值或派发失败（已到最早）= 平滑回弹不加载。 ----
    val density = LocalDensity.current
    val pullThresholdPx = with(density) { 72.dp.toPx() }
    var pullOffset by remember { mutableFloatStateOf(0f) }
    var pullLoading by remember { mutableStateOf(false) }
    val modelPullBusy = if (timeline != null) model.convSectionLoading != null else model.loadingOlder
    val pullBusyNow by rememberUpdatedState(modelPullBusy)
    val listAtTop by remember { derivedStateOf { !listState.canScrollBackward } }
    val pullLoad by rememberUpdatedState {
        val tl = timeline
        if (tl != null) tl.lastOrNull()?.let { model.loadSectionHistoryIfNeeded(it.key) } ?: false
        else model.loadOlderMessages()
    }
    val pullConn = remember(pullThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (dy > 0f && listAtTop) {
                    // 顶部下拉：阻尼累积，全部消费（列表不再滚动）
                    pullOffset = (pullOffset + dy * 0.4f).coerceAtMost(pullThresholdPx * 1.8f)
                    return Offset(0f, dy)
                }
                if (pullOffset > 0f && dy < 0f) {
                    // overscroll 中上推：先回收 overscroll，剩余给列表
                    val back = dy.coerceAtLeast(-pullOffset)
                    pullOffset += back
                    return Offset(0f, back)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (pullOffset > 0f) {
                    if (pullOffset >= pullThresholdPx && !pullBusyNow) {
                        pullLoading = pullLoad()
                    }
                    val from = pullOffset
                    androidx.compose.animation.core.animate(
                        initialValue = from,
                        targetValue = 0f,
                        animationSpec = tween(220),
                    ) { v, _ -> pullOffset = v }
                }
                return Velocity.Zero
            }
        }
    }
    // loading 灯熄灭：模型侧加载结束（或派发被拒）后收 spinner
    LaunchedEffect(pullLoading, modelPullBusy) {
        if (pullLoading && !modelPullBusy) {
            delay(250) // 至少闪现一拍，避免闪烁
            pullLoading = false
        }
    }
    val totalRows = contentRowCount +
        (if (state?.hasMoreHistory == true && contentRowCount > 0) 1 else 0) +
        (if (contentRowCount == 0 && !runActive && historyLoaded) 1 else 0) +
        (if (runActive) 1 else 0)
    // 用户是否在历史到达前主动滚动过（锚底保护：尊重用户位置）。
    // 只认真实拖拽（DragInteraction）：程序性 scrollToItem（开局锚底/分区加载）也会置
    // isScrollInProgress，把它当用户手势会误跳过历史到达后的锚底=进入位置随机（真机反馈④根因）
    var userScrolledBeforeLoad by remember { mutableStateOf(false) }
    // 流式跟随开关（反馈③）：用户上拉离开底部即停钉底，回到底部自动恢复跟随
    var userPinnedUp by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                userScrolledBeforeLoad = true
                userPinnedUp = true
            }
        }
    }
    LaunchedEffect(historyLoaded) {
        // 历史到达一次性锚底：修复「加载出来突然显示且不一定滑到底部」——整表替换会
        // 使 viewport 索引漂移；用户已在滚动则不打扰
        if (historyLoaded && !userScrolledBeforeLoad && contentRowCount > 0 && !listState.isScrollInProgress) {
            listState.snapToAbsoluteBottom(totalRows)
            initialScrollDone = true
        }
    }
    // 流式跟随（反馈③）：打字机增长/换行、思考条目增删、工具卡状态变化——任何内容
    // 变化只要用户没上拉离开底部，就钉到绝对底边（带 offset 钳制，长消息也贴真底）。
    val streamingTail = runActive || (currentEntries.lastOrNull() as? Message)?.streaming == true
    val userPinnedUpNow by rememberUpdatedState(userPinnedUp)
    LaunchedEffect(rowsSignature, totalRows, streamingTail) {
        if (contentRowCount == 0) return@LaunchedEffect
        if (!initialScrollDone) {
            listState.snapToAbsoluteBottom(totalRows)
            initialScrollDone = true
            return@LaunchedEffect
        }
        if (streamingTail && !userPinnedUpNow) {
            listState.snapToAbsoluteBottom(totalRows)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: Int.MAX_VALUE
            lastVisible < info.totalItemsCount - 2
        }.collect { away ->
            showJumpToLatest = away
            if (!away) userPinnedUp = false // 回到底部自动恢复跟随
        }
    }
    // 发送后滚到底（M4 反馈④）：看到刚发的消息。sendSignal 由 AppModel.sendText 递增
    val totalRowsNow by rememberUpdatedState(totalRows)
    LaunchedEffect(model.sendSignal) {
        if (model.sendSignal == 0) return@LaunchedEffect
        delay(120) // 乐观回显/待发条目落表
        listState.snapToAbsoluteBottom(totalRowsNow)
    }
    // 软键盘唤起（M4 反馈④）：视口被压缩后重钉底部——用户没上拉才跟随
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0 && !userPinnedUpNow && contentRowCount > 0) {
            delay(60) // 等布局按新 inset 收敛一帧
            listState.snapToAbsoluteBottom(totalRowsNow)
        }
    }
    // 分区懒加载（M4 反馈②）：分区头进入视口即加载该区（未拉过→首页；有更多→翻更早一页）。
    // 最旧区交给下拉手势（反馈⑤：标准 IM 阻尼交互），其余区保持视口触发；
    // 位置保持交给 LazyColumn 稳定 key 锚定（prepend 不动首可见项）。
    LaunchedEffect(timeline != null) {
        if (timeline == null) return@LaunchedEffect
        snapshotFlow {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            (first?.key as? String) to (model.convSectionLoading == null)
        }.collect { (firstKey, idle) ->
            if (!idle || firstKey == null || !firstKey.startsWith("bnd-")) return@collect
            if (firstKey == "bnd-${timeline?.lastOrNull()?.key}") return@collect
            model.loadSectionHistoryIfNeeded(firstKey.removePrefix("bnd-"))
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if (scrolling) {
                keyboard?.hide()
                focusManager.clearFocus()
            }
        }
    }
    StatusBarTapEffect {
        if (contentRowCount > 0) scope.launch { listState.animateScrollToItem(0) }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(InkTheme.colors.paper)
            .dismissKeyboardOnTap(),
    ) {
        // M3.5 审批 Sheet：approvals 通道事件驱动（网关启用审批 + 面板开关开启才有事件）
        model.pendingApproval?.let { req ->
            ApprovalSheet(request = req, onDecide = { model.respondApproval(it) })
        }
        var showStartFresh by remember { mutableStateOf(false) }
        ChatTopBar(
            model,
            sessionKey,
            runActive,
            historyLoaded,
            onBack,
            onShowStartFresh = { showStartFresh = true },
            titleOverride = convTitle,
            subtitleOverride = convSubtitle,
        )
        // 错误可见面（M4 P4：压缩上下文等 RPC 失败在对话页也要可见，点击清除）
        model.lastError?.let { err ->
            Surface(
                color = InkTheme.colors.danger.subtle,
                shape = RoundedCornerShape(InkTheme.shapes.md.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clickable { model.lastError = null },
            ) {
                Text(
                    err,
                    style = InkTheme.type.textSm,
                    color = InkTheme.colors.danger.text,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (showStartFresh) {
            StartFreshDialog(
                onConfirm = {
                    showStartFresh = false
                    model.startFresh()
                },
                onDismiss = { showStartFresh = false },
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .alpha(if (contentRowCount > 0 && !initialScrollDone) 0f else 1f)
                .nestedScroll(pullConn),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (contentRowCount == 0 && !runActive && historyLoaded) {
                    item(key = "empty") {
                        Text(
                            "暂无消息，发一条开始对话",
                            style = InkTheme.type.textSm,
                            color = InkTheme.colors.textTertiary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        )
                    }
                }
                itemsIndexed(rows, key = { _, row -> rowKeyOf(row) }) { index, row ->
                    // 入场动画只给「最新一条 + 活跃 run/流式中」：分页前插与已加载内容零动画
                    // （placementSpec=null 禁位置过渡——历史回填时全列表漂移/滑不动的元凶）
                    val tail = row as? ChatRow.Entry
                    val isLiveTail = index == rows.lastIndex && tail?.entry is Message &&
                        ((tail.entry as Message).streaming || runActive)
                    val itemModifier = if (isLiveTail) {
                        @OptIn(ExperimentalFoundationApi::class)
                        Modifier.animateItem(
                            fadeInSpec = tween(220),
                            placementSpec = null,
                            fadeOutSpec = null,
                        )
                    } else {
                        Modifier
                    }
                    Box(itemModifier) {
                        when (row) {
                            is ChatRow.DayHeader -> DayHeaderRow(row.atMs)
                            is ChatRow.Boundary -> BoundaryRow(row.label)
                            is ChatRow.CompactMark -> CompactMarkRow(row.atMs)
                            is ChatRow.Entry -> when (val entry = row.entry) {
                                is ToolCallEvent -> ToolCallCard(entry)
                                is ThinkingEntry -> ThinkingCard(entry)
                                is Message -> ChatMessageItem(
                                    model = model,
                                    message = entry,
                                    onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                                    onResend = { text -> haptics.tap(); model.resendText(text) },
                                    showSendSpinner = index == lastUserEntryIndex && showTailSpinner,
                                )
                            }
                            is ChatRow.ToolGroup -> ToolGroupCard(row.items)
                        }
                    }
                }
                items(pending, key = { "outbox-${it.idempotencyKey}" }) { cmd ->
                    PendingOutboxItem(
                        cmd = cmd,
                        onRetry = { haptics.tap(); model.retryQueuedNow() },
                        onDelete = { haptics.tap(); model.deleteQueued(cmd) },
                    )
                }
                if (runActive) {
                    // 生成中指示：思考条目在尾时由 ThinkingCard 承担「进行中」表达，不重复沉底
                    val thinkingAtTail = currentEntries.lastOrNull() is ThinkingEntry
                    if (!thinkingAtTail) {
                        item(key = "typing") { TypingIndicator() }
                    }
                }
            }
            // 下拉指示（M4 反馈⑤）：顶部居中；拖拽中按比例显示进度环，加载中转常转 spinner
            if (pullOffset > 2f || pullLoading) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp),
                ) {
                    if (pullLoading || modelPullBusy) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = InkTheme.colors.ink,
                        )
                    } else {
                        val frac = (pullOffset / pullThresholdPx).coerceIn(0f, 1f)
                        androidx.compose.material3.CircularProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp,
                            color = InkTheme.colors.ink,
                        )
                    }
                }
            }
            if (showJumpToLatest) {
                Surface(
                    onClick = {
                        haptics.tap()
                        scope.launch { listState.snapToAbsoluteBottom(totalRows) }
                    },
                    shape = CircleShape,
                    color = InkTheme.colors.elevated,
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkTheme.colors.borderPrimary),
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "查看最新消息",
                        tint = InkTheme.colors.ink,
                        modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
            }
        }

        if (readOnly) {
            // 只读浏览（M4 P5 D5：派生任务详情）：无输入面，不产生发送
            Box(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "只读浏览 · 派生任务会话",
                    style = InkTheme.type.textXs,
                    color = InkTheme.colors.textTertiary,
                )
            }
        } else {
            ChatInputBar(
                model = model,
                haptics = haptics,
                modifier = Modifier.windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
            )
        }
    }
}

@Composable
private fun ChatTopBar(
    model: AppModel,
    sessionKey: String,
    runActive: Boolean,
    historyLoaded: Boolean,
    onBack: () -> Unit,
    onShowStartFresh: () -> Unit,
    titleOverride: String? = null,
    subtitleOverride: String? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(color = InkTheme.colors.paper) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    model.select(null)
                    onBack()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = InkTheme.colors.ink,
                    )
                }
                Column(Modifier.weight(1f)) {
                    val session = model.sessions.firstOrNull { it.id == sessionKey }
                    Text(
                        titleOverride ?: session?.title ?: sessionKey,
                        style = InkTheme.type.headingSm,
                        color = InkTheme.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!historyLoaded) {
                        // 加载态上移 toolbar（真机反馈⑤-3）：进页即知在加载，列表区不再闪空态文案
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TypingDots()
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "加载中",
                                style = InkTheme.type.textXs,
                                color = InkTheme.colors.textTertiary,
                            )
                        }
                    } else {
                        Text(
                            subtitleOverride
                                ?: session?.kind?.takeIf { it.isNotBlank() }
                                ?: sessionKey,
                            style = InkTheme.type.textXs,
                            color = InkTheme.colors.textTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "会话菜单",
                            tint = InkTheme.colors.textSecondary,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("从头开始", style = InkTheme.type.textMd) },
                            leadingIcon = {
                                Icon(ArIcons.Retry, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = { menuOpen = false; onShowStartFresh() },
                        )
                    }
                }
            }
            // M4 反馈④：生成中的 loading 已移到「发送气泡左侧」小 spinner，顶栏不再重复
        }
    }
}

@Composable
private fun DayHeaderRow(atMs: Long) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            dayHeaderLabel(atMs),
            style = InkTheme.type.textXs,
            color = InkTheme.colors.textTertiary,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

/** conversation 分区头（M4 P3）：新开主 session 的延续标记，居中小胶囊。 */
@Composable
private fun BoundaryRow(label: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = InkTheme.type.textXs,
            color = InkTheme.colors.textTertiary,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(InkTheme.colors.surfaceTertiary)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

/** 压缩上下文标记（M4 P4）：sessions.compact 成功的本地可视痕迹。 */
@Composable
private fun CompactMarkRow(atMs: Long) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            "已压缩上下文 · " + formatRelativeTime(atMs),
            style = InkTheme.type.textXs,
            color = InkTheme.colors.textTertiary,
            fontStyle = FontStyle.Italic,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(InkTheme.colors.surfaceTertiary)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatMessageItem(
    model: AppModel,
    message: Message,
    onCopy: (String) -> Unit,
    onResend: (String) -> Unit,
    showSendSpinner: Boolean = false,
) {
    // agent 身份色作用域：会话的 agent tint 派生用户气泡与强调（BotTintScope 等价）
    val session = model.sessions.firstOrNull { it.id == message.sessionId }
    val agentId = session?.agentId ?: sessionKeyAgentId(message.sessionId)
    val tintName = model.agentProfiles[agentId]?.tint
        ?: agentId?.let { agentTintFor(it) }
        ?: AgentTintName.GRAY
    AgentTintScope(tintName) {
        // 消息发送时间（M4 真机反馈①）：用户/助手消息右下角；system 过程注不标
        Column(Modifier.fillMaxWidth()) {
            when (message.role) {
                Message.Role.USER -> Row(verticalAlignment = Alignment.Bottom) {
                    // 气泡左侧小 loading（M4 反馈④）：发送中/待响应期间贴在消息旁
                    if (showSendSpinner) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 6.dp, bottom = 8.dp)
                                .size(14.dp),
                            strokeWidth = 2.dp,
                            color = LocalAgentTint.current.strong,
                        )
                    }
                    UserBubble(model, message, onCopy, onResend)
                }
                // 助手消息：墨水平文无气泡；流式中 shimmer，定型后 Markdown v2。
                // 长按区贴内容（全宽 clickable 吞点击的坑，真机实锤）
                Message.Role.ASSISTANT -> {
                    var menuOpen by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                                .then(if (message.streaming) Modifier.animateContentSize() else Modifier),
                        ) {
                            if (message.streaming) {
                                ShimmerStreamingText(message.content)
                            } else if (message.content.isNotBlank()) {
                                InkMarkdown(message.content)
                            }
                            if (menuOpen) {
                                MessageActionsSheet(
                                    message = message,
                                    onDismiss = { menuOpen = false },
                                    onCopy = onCopy,
                                    onResend = onResend,
                                    onQuote = { model.quoteMessage(it) },
                                    onFork = { model.forkFrom(it) },
                                )
                            }
                        }
                    }
                }
                // agent 的 commentary（item 流 kind=preamble）：过程说明行内注
                Message.Role.SYSTEM -> Text(
                    message.content,
                    style = InkTheme.type.textSm,
                    fontStyle = FontStyle.Italic,
                    color = InkTheme.colors.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
            if (message.role != Message.Role.SYSTEM) {
                val time = formatMessageTime(message.timestampMs)
                if (time.isNotEmpty()) {
                    Text(
                        time,
                        style = InkTheme.type.textXs,
                        color = InkTheme.colors.textTertiary,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 1.dp),
                    )
                }
            }
        }
    }
}

/** 用户 tint 气泡：bubble 底 + 墨水文本 + 右上小圆角（Grok bubbleUser 形态）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    model: AppModel,
    message: Message,
    onCopy: (String) -> Unit,
    onResend: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val tint = LocalAgentTint.current
    val c = InkTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        BoxWithConstraints {
            Surface(
                color = tint.bubble,
                shape = RoundedCornerShape(InkTheme.shapes.lg.dp, 5.dp, InkTheme.shapes.lg.dp, InkTheme.shapes.lg.dp),
            ) {
                Text(
                    message.content,
                    style = InkTheme.type.textLg,
                    color = c.ink,
                    modifier = Modifier
                        .widthIn(max = maxWidth * 0.82f)
                        .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        .padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
            if (menuOpen) {
                MessageActionsSheet(
                    message = message,
                    onDismiss = { menuOpen = false },
                    onCopy = onCopy,
                    onResend = onResend,
                    onQuote = { model.quoteMessage(it) },
                    onFork = { model.forkFrom(it) },
                )
            }
        }
    }
}

/** 长按 MessageActionsSheet（M3.4）：复制 / 引用 / 重发(用户) / 分支(助手)——Sheet 承载二级操作。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MessageActionsSheet(
    message: Message,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
    onResend: (String) -> Unit,
    onQuote: (String) -> Unit,
    onFork: (Message) -> Unit,
) {
    SandSheet(
        onDismiss = onDismiss,
        title = "消息操作",
    ) {
        SandSheetAction("复制", icon = ArIcons.Copy, onClick = { onCopy(message.content); onDismiss() })
        SandSheetAction("引用", icon = ArIcons.Reply, onClick = { onQuote(message.content); onDismiss() })
        if (message.role == Message.Role.USER) {
            SandSheetAction("重发", icon = ArIcons.Retry, onClick = { onResend(message.content); onDismiss() })
            // fork 锚点必须是 user entry（网关实测）；分支挂在提问消息上
            SandSheetAction(
                "分支（从这条重新开话）",
                icon = ArIcons.Branch,
                onClick = { onFork(message); onDismiss() },
            )
        }
    }
}

/** 审批卡 Sheet（M3.5）：摘要 + 详情 + 批准/拒绝（danger）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ApprovalSheet(
    request: dev.sharpxia.agentreins.model.ApprovalRequest,
    onDecide: (Boolean) -> Unit,
) {
    val c = InkTheme.colors
    SandSheet(onDismiss = { onDecide(false) }, title = "需要你的批准") {
        Text(
            request.summary,
            style = InkTheme.type.textLg,
            color = c.ink,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        request.detailJson?.let {
            Text(
                it.take(600),
                style = InkTheme.type.mono.copy(fontSize = InkTheme.type.textXs.fontSize),
                color = c.textTertiary,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .background(c.surfaceTertiary, RoundedCornerShape(InkTheme.shapes.sm.dp))
                    .padding(8.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { onDecide(false) }) { Text("拒绝", color = c.danger.text) }
            TextButton(onClick = { onDecide(true) }) { Text("批准", color = c.ink) }
        }
    }
}

/** 重置语义确认弹窗：对齐「丢什么/留什么」结构（自写文案，不抄原文）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun StartFreshDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val c = InkTheme.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从头开始？", style = InkTheme.type.headingSm, color = c.ink) },
        text = {
            Column {
                Text(
                    "这个会话的聊天记录将不再出现在新会话里。",
                    style = InkTheme.type.textMd,
                    color = c.textSecondary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "✓ 会保留：Agent 的指令、记忆与技能配置\n✗ 会留在旧会话：本次对话的全部消息",
                    style = InkTheme.type.textSm,
                    color = c.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("开新会话", color = c.ink)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = c.textTertiary)
            }
        },
        containerColor = c.elevated,
    )
}

/** outbox 待发气泡：灰阶弱化 + 「未发出」标记 + 重试/删除。 */
@Composable
private fun PendingOutboxItem(cmd: Outbox.Command, onRetry: () -> Unit, onDelete: () -> Unit) {
    val tint = LocalAgentTint.current
    val c = InkTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End) {
            Surface(
                color = tint.bubble.copy(alpha = tint.bubble.alpha * 0.55f),
                shape = RoundedCornerShape(InkTheme.shapes.lg.dp, 5.dp, InkTheme.shapes.lg.dp, InkTheme.shapes.lg.dp),
            ) {
                Text(
                    cmd.text,
                    style = InkTheme.type.textLg,
                    color = c.textTertiary,
                    modifier = Modifier.widthIn(max = 320.dp).padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                // 「未发出」= 确定未送达（离线入队/明确失败）；「核对中」= 回执超时结果
                // 未确认（幂等键保护自动核对，真机反馈 bug①）——两者不共用红色报警
                val unconfirmed = cmd.lastError?.contains("结果未确认") == true
                Text(
                    if (unconfirmed) "核对中" else "未发出",
                    style = InkTheme.type.textXs,
                    color = if (unconfirmed) c.textTertiary else c.danger.text,
                )
                IconButton(onClick = onRetry, modifier = Modifier.size(28.dp)) {
                    Icon(ArIcons.Retry, contentDescription = "重试发送", tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "放弃发送", tint = c.textTertiary, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/**
 * 流式中文本 shimmer（textShimmerBase → textTertiary 微光扫过）；
 * 定型后走 Markdown。横向渐变窗口按动画进度平移，无需实测文本宽度。
 */
@Composable
private fun ShimmerStreamingText(content: String) {
    val c = InkTheme.colors
    val shift by rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerShift",
    )
    val window = 420f
    val start = window * (shift * 2.4f - 1.2f)
    val brush = Brush.linearGradient(
        colors = listOf(c.shimmerBase, c.textTertiary, c.shimmerBase),
        start = Offset(start, 0f),
        end = Offset(start + window, 0f),
    )
    Text(
        content,
        style = TextStyle(
            fontSize = InkTheme.type.textLg.fontSize,
            lineHeight = InkTheme.type.textLg.lineHeight,
            brush = brush,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 生成中三点跳动。 */
@Composable
internal fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val color = InkTheme.colors.textTertiary
    Row {
        for (i in 0 until 3) {
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 450, delayMillis = i * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(5.dp)
                    .graphicsLayer { translationY = -3f * phase }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        TypingDots()
        Spacer(Modifier.size(8.dp))
        Text(
            "生成中…",
            style = InkTheme.type.textSm,
            fontStyle = FontStyle.Italic,
            color = InkTheme.colors.textTertiary,
        )
    }
}

/**
 * 思考卡（M3.6 反馈②）：瞬态条目，事件序落位=与后继工具/回复绑定。run 终局整卡消失。
 * 显示快照**尾部** 6 行（思考在末尾增长，旧 TypingIndicator 语义=看最新）；
 * 左缘细竖线做结构锚，与助手正文的斜体引用排版区分（review R2-P1-1）。
 */
@Composable
private fun ThinkingCard(entry: ThinkingEntry) {
    val c = InkTheme.colors
    val tail = remember(entry.text) {
        entry.text.lineSequence().filter { it.isNotBlank() }.toList().takeLast(6).joinToString("\n")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TypingDots()
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .width(2.dp)
                    .heightIn(min = 20.dp)
                    .background(c.borderTertiary),
            )
            Column {
                Text(
                    "思考中",
                    style = InkTheme.type.textXs,
                    color = c.textDisabled,
                )
                Text(
                    tail,
                    style = InkTheme.type.textSm,
                    fontStyle = FontStyle.Italic,
                    color = c.textTertiary,
                )
            }
        }
    }
}

/**
 * ComposerShell（M3.4）：引用条 QuoteBar（被引用消息随下一条发出）+ 圆角输入壳 +
 * 发送/停止态切换（run 进行中 = danger 停止键）。
 */
@Composable
private fun ChatInputBar(model: AppModel, haptics: HapticPlayer, modifier: Modifier = Modifier) {
    val c = InkTheme.colors
    val runActive = model.conversation?.activeRunId != null
    Column(modifier.fillMaxWidth().background(c.paper)) {
        // QuoteBar：引用条（M3.4）
        model.quotedText?.let { quoted ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(InkTheme.shapes.md.dp))
                    .background(c.surfaceTertiary)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 28.dp)
                        .background(c.inverse, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    quoted.lines().filter { it.isNotBlank() }.firstOrNull() ?: "",
                    style = InkTheme.type.textSm,
                    color = c.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "取消引用",
                    style = InkTheme.type.textXs,
                    color = c.info.text,
                    modifier = Modifier
                        .clickable { model.clearQuote() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            TextField(
                value = model.draft,
                onValueChange = { model.draft = it },
                placeholder = {
                    Text("发送消息…", color = c.textTertiary, style = InkTheme.type.textMd)
                },
                textStyle = InkTheme.type.textMd,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(22.dp),
                maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = c.surfaceTertiary,
                    unfocusedContainerColor = c.surfaceTertiary,
                    disabledContainerColor = c.surfaceTertiary,
                    focusedTextColor = c.ink,
                    unfocusedTextColor = c.ink,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { haptics.tap(); model.send() }),
            )
            Spacer(Modifier.size(6.dp))
            // 对话操作（M4 P4）：发送键左侧矢量 icon → 三动作（新开会话/压缩上下文/agent 状态）
            ComposerActions(model = model, haptics = haptics)
            Spacer(Modifier.size(2.dp))
            // 单按钮三态（M4 反馈①）：有内容=发送（运行中也发，网关 Queue: steer 排队）；
            // 空+运行中=终止（本地立即解锁+/stop 级联）；空+空闲=发送禁用
            if (runActive && model.draft.isBlank()) {
                StopButton(haptics = haptics, onStop = { model.stopRun() })
            } else {
                val enabled = model.draft.isNotBlank()
                Box(
                    Modifier
                        .padding(bottom = 6.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (enabled) c.inverse else c.surfaceTertiary)
                        .clickable(enabled = enabled) {
                            haptics.tap()
                            model.send()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (enabled) c.paper else c.textDisabled,
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

/** 停止键：danger 底方块（Grok ComposerPrimaryButton 停止态）。 */
@Composable
private fun StopButton(haptics: HapticPlayer, onStop: () -> Unit) {
    val c = InkTheme.colors
    Box(
        Modifier
            .padding(bottom = 6.dp)
            .size(34.dp)
            .clip(CircleShape)
            .background(c.danger.text)
            .clickable {
                haptics.tap()
                onStop()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(c.paper, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * Composer 动作入口（M4 P4 需求 D3）：发送按钮左侧自绘矢量 icon（ArIcons.Tune），
 * 点击弹 DropdownMenu（真机验证过的弹层；ModalBottomSheet 在抽屉层级内点击不可靠是
 * 0.2.15 实锤坑）。动作 v1：新开会话（对话内不跳路由）/ 压缩上下文 / 当前 agent 状态。
 */
@Composable
private fun ComposerActions(model: AppModel, haptics: HapticPlayer) {
    val c = InkTheme.colors
    Box {
        var open by remember { mutableStateOf(false) }
        Box(
            Modifier
                .padding(bottom = 6.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(c.surfaceTertiary)
                .clickable {
                    haptics.tap()
                    open = true
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                ArIcons.Tune,
                contentDescription = "对话操作",
                tint = c.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // 终止（M4 反馈：runtime 不可用时 abort 无目标——/stop 走网关命令通道，
            // 与 /status 同路；本地同步解锁不留卡死态）
            DropdownMenuItem(
                text = { Text("终止 (/stop)", style = InkTheme.type.textMd, color = c.ink) },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.textTertiary) },
                onClick = {
                    open = false
                    model.stopByCommand()
                },
            )
            DropdownMenuItem(
                text = { Text("新开会话", style = InkTheme.type.textMd, color = c.ink) },
                leadingIcon = { Icon(ArIcons.Retry, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.textTertiary) },
                onClick = {
                    open = false
                    if (model.convAgentId != null) model.createSessionInConversation() else model.startFresh()
                },
            )
            DropdownMenuItem(
                text = { Text("压缩上下文", style = InkTheme.type.textMd, color = c.ink) },
                leadingIcon = { Icon(ArIcons.Compress, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.textTertiary) },
                onClick = {
                    open = false
                    model.compactContext()
                },
            )
            DropdownMenuItem(
                text = { Text("当前 agent 状态", style = InkTheme.type.textMd, color = c.ink) },
                leadingIcon = { Icon(ArIcons.Tune, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.textTertiary) },
                onClick = {
                    open = false
                    model.sendAgentStatusPrompt()
                },
            )
        }
    }
}
