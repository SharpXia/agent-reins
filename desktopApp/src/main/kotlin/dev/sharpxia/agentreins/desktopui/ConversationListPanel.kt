package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.UiAgentRef
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiGateway
import kotlinx.coroutines.launch

/**
 * 会话列表（wp-6 验收③）：卡片唯一来源 = Hub 投影（快照/列表校准 upsert，v1.1 卡片键 = conversationId）。
 * 未读徽标 = 投影 unreadCount（排除本用户行/合成行/未落定流式行，05 §3.7）；hasActiveRun/reconciling
 * 为投影计算值，UI 只呈现不发明。
 * wp-9：右键菜单按会话静音开关（iOS HubStore.setMuted 桌面同位——本地偏好持久化，跨登出
 * 保留；静音吞回复/run 终态通知、不吞审批，口径与协调器 [DesktopNotificationCoordinator]
 * 一致）+ 已静音卡片「已静音」标识（设置页清单管理入口同面可见）。
 * wp-7：右键菜单扩展为会话操作三件套（静音 + 重命名 + 压缩上下文）+ 新建会话入口：
 * - 重命名（#19 conversation.rename）：对话框预填当前 label，trim 非空防线（服务端空串
 *   400 HUB-BIZ-004 拒绝前置到输入层）；label 变化经 session.updated 事件回流列表，客户端
 *   零解析（回执只判 ok/not-ok）；失败显式降级态可重试（对话框不关，输入保留）。
 * - 压缩上下文（#18 conversation.compact）：在途禁用（「压缩中…」）+ 活跃 run 期间禁用
 *   （iOS HubViews 菜单 .disabled(compacting || conv.hasActiveRun) 同口径）；结果以
 *   compaction 合成行/校准回流，本端零解析；失败以面板横幅显式降级可重试。
 * - 新建会话入口（#16，网关/Agent 维度）：面板头「＋ 新建会话」→ 网关/Agent 弹窗，行态
 *   四态判定 [newConversationRowState]（hasMainCard 门语义复刻 iOS HubStore.swift:162-175：
 *   已有主线卡置灰、「已提交」等待态、提交中 spinner、可新建）；提交失败显式降级可重试；
 *   成卡以校准收敛为准零解析（回执不拼卡，DefaultHubClient 成功即触发一次校准，秒级入列；
 *   R4 周期兜底）。入口经 shared 门面自动携带 mainSession=true（wp-2 落地于
 *   DefaultCommandClient.createConversation，服务端组装 sessionKey=agent:<agentId>:main，
 *   客户端永不拼键，ADR-14）。
 */
@Composable
internal fun ConversationListPanel(
    conversations: List<UiConversation>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    client: DefaultHubClient,
    modifier: Modifier = Modifier,
    mutedIds: Set<String> = emptySet(),
    onToggleMute: (conversationId: String, muted: Boolean) -> Unit = { _, _ -> },
) {
    // wp-7 会话操作瞬态（面板级持有，跨卡片/弹窗存活；提交协程挂面板 scope——弹窗中途关闭
    // 不取消在途请求，结果仍经事件流回流投影，UI 态写已离场快照无副作用）
    val scope = rememberCoroutineScope()
    var renameTarget by remember { mutableStateOf<UiConversation?>(null) }
    var renameSubmitting by remember { mutableStateOf(false) }
    var renameError by remember { mutableStateOf<String?>(null) }
    var compactingIds by remember { mutableStateOf(emptySet<String>()) }
    var compactError by remember { mutableStateOf<String?>(null) }
    var showNewConversation by remember { mutableStateOf(false) }
    // 「已提交」瞬态集（iOS HubStore.createdConversationFor 同型，键 = [conversationEntryKey]）：
    // 成卡即收敛清理（见下方 LaunchedEffect），置灰由主线卡判据接管
    var submittedKeys by remember { mutableStateOf(emptySet<String>()) }
    var creatingKey by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }

    fun renameNow(conv: UiConversation, label: String) {
        renameError = null
        renameSubmitting = true
        scope.launch {
            // v1.1 命令直调不走幂等队列（CommandClient 契约「失败由调用方提示手动重试」）：
            // 网络不可达抛异常 / 回执缺 commandId 均按未送达降级；PERSISTED 回执后 label 变化
            // 以 session.updated 事件回流列表，本端零解析
            val ok = runCatching { client.renameConversation(conv.conversationId, label) }
                .map { it.commandId.isNotBlank() }
                .getOrDefault(false)
            renameSubmitting = false
            if (ok) renameTarget = null else renameError = "重命名未送达（可能为网络不可达），可重试"
        }
    }

    fun compactNow(conv: UiConversation) {
        if (conv.conversationId in compactingIds || conv.hasActiveRun) return
        compactError = null
        compactingIds = compactingIds + conv.conversationId
        scope.launch {
            val ok = runCatching { client.compactConversation(conv.conversationId) }
                .map { it.commandId.isNotBlank() }
                .getOrDefault(false)
            compactingIds = compactingIds - conv.conversationId
            if (!ok) compactError = "压缩指令未送达（可能为网络不可达），可重试"
        }
    }

    fun submitCreate(gatewayId: String, agentId: String) {
        // iOS HubStore.createConversation 同守卫：提交中单飞 + 已有主线卡不得重复提交
        // （hasMainCard 门在行态已置灰，此处为防御性复核）
        if (creatingKey != null) return
        if (newConversationRowState(conversations, gatewayId, agentId, submittedKeys, creatingKey) ==
            NewConversationRowState.GRAYED
        ) return
        val key = conversationEntryKey(gatewayId, agentId)
        createError = null
        creatingKey = key
        scope.launch {
            val ok = runCatching { client.createConversation(gatewayId, agentId, null) }
                .map { it.commandId.isNotBlank() }
                .getOrDefault(false)
            creatingKey = null
            if (ok) submittedKeys = submittedKeys + key else createError = "提交未送达（可能为网络不可达），可重试"
        }
    }

    // wp-7 成卡收敛清理（iOS watchGateways formIntersection 同语义）：已兜底成卡的
    // (gatewayId, agentId) 移出「已提交」瞬态，行翻置灰由主线卡判据接管
    LaunchedEffect(conversations) {
        val cardKeys = conversations.map { conversationEntryKey(it.gatewayId, it.agentId) }.toSet()
        if (submittedKeys.any(cardKeys::contains)) submittedKeys = submittedKeys - cardKeys
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // wp-7：面板头 + 新建会话入口（iOS 工具栏 plus.message 同面——入口按网关/Agent
            // 维度弹窗呈现，hasMainCard 门在弹窗行内判定，不在本入口拦截）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { showNewConversation = true }) {
                    Text("＋ 新建会话", fontSize = 12.sp)
                }
            }
            // wp-7：压缩失败显式降级横幅（点击即清，可再次右键重试）
            compactError?.let { err ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { compactError = null }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("⚠", color = Color(0xFFB45309), fontSize = 11.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(err, color = Color(0xFFB45309), fontSize = 11.sp, modifier = Modifier.weight(1f))
                }
            }
            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂无会话", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "登录并追平同步后自动出现（快照 + R4 校准），或经「＋ 新建会话」创建",
                            fontSize = 11.sp,
                            color = Color(0xFF888888),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(conversations, key = { it.conversationId }) { conv ->
                        ConversationCard(
                            conv = conv,
                            selected = conv.conversationId == selectedId,
                            muted = conv.conversationId in mutedIds,
                            compacting = conv.conversationId in compactingIds,
                            onToggleMute = onToggleMute,
                            onRename = { renameTarget = it },
                            onCompact = { compactNow(it) },
                            onClick = { onSelect(conv.conversationId) },
                        )
                    }
                }
            }
        }
    }

    // wp-7：重命名对话框（目标/提交中/错误态面板级持有，对话框本身无状态失忆问题；
    // 失败不关闭——输入保留原样可重试）
    renameTarget?.let { target ->
        RenameConversationDialog(
            conv = target,
            submitting = renameSubmitting,
            error = renameError,
            onDismiss = { if (!renameSubmitting) renameTarget = null },
            onSubmit = { label -> renameNow(target, label) },
        )
    }

    // wp-7：新建会话弹窗（网关/Agent 维度四态行；conversations 持续推送——弹窗开着时成卡
    // 即时翻置灰，「已提交」瞬态即时收敛）
    if (showNewConversation) {
        NewConversationDialog(
            client = client,
            conversations = conversations,
            creatingKey = creatingKey,
            submittedKeys = submittedKeys,
            createError = createError,
            onSubmit = { gatewayId, agentId -> submitCreate(gatewayId, agentId) },
            onAckError = { createError = null },
            onDismiss = { showNewConversation = false },
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConversationCard(
    conv: UiConversation,
    selected: Boolean,
    muted: Boolean,
    compacting: Boolean,
    onToggleMute: (conversationId: String, muted: Boolean) -> Unit,
    onRename: (UiConversation) -> Unit,
    onCompact: (UiConversation) -> Unit,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    // wp-9 右键菜单：menuOpen/menuOffset 为卡片私有组合态；右键 = Press + Secondary 按钮
    // （compose 1.12 无 PointerEventType.SecondaryClick，按 PointerEvent.button 判定），
    // 位置经 LocalDensity 转 DpOffset 定锚（桌面 Compose 语境菜单惯用式，左键 clickable 不受影响）。
    // wp-7：同一菜单扩为三件套（静音/重命名/压缩）——沿用本文件 wp-9 已落地的
    // pointerInput+DropdownMenu 右键承载（与 ConversationScreen 消息行 ContextMenuArea 同效，
    // 单卡单菜单容纳全部会话操作，不与既有静音项分裂两个入口）
    var menuOpen by remember { mutableStateOf(false) }
    var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .pointerInput(conv.conversationId) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                                val pos = event.changes.firstOrNull()?.position ?: continue
                                menuOffset = DpOffset(
                                    with(density) { pos.x.toDp() },
                                    with(density) { pos.y.toDp() },
                                )
                                menuOpen = true
                            }
                        }
                    }
                }
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.label.ifBlank { "（未命名会话）" },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (muted) {
                    TagChip("已静音", Color(0xFF777777))
                    Spacer(Modifier.width(6.dp))
                }
                if (conv.hasActiveRun) {
                    ActiveRunDot()
                    Spacer(Modifier.width(6.dp))
                }
                if (conv.reconciling) {
                    TagChip("对账中", Color(0xFFB26A00))
                    Spacer(Modifier.width(6.dp))
                }
                if (conv.unread > 0) {
                    UnreadBadge(conv.unread)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conv.preview.ifBlank { " " },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF777777),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = formatTs(conv.lastActiveAt),
                    fontSize = 11.sp,
                    color = Color(0xFF999999),
                )
            }
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            offset = menuOffset,
        ) {
            DropdownMenuItem(
                text = { Text(if (muted) "取消静音" else "静音") },
                onClick = {
                    menuOpen = false
                    onToggleMute(conv.conversationId, !muted)
                },
            )
            // wp-7：重命名（对话框提交；label 变化经 session.updated 事件回流列表，零解析）
            DropdownMenuItem(
                text = { Text("重命名") },
                onClick = {
                    menuOpen = false
                    onRename(conv)
                },
            )
            // wp-7：压缩上下文（#18）。在途/活跃 run 期间禁用（iOS HubViews 菜单
            // .disabled(compacting || conv.hasActiveRun) 同口径）；结果以 compaction 合成行/
            // 校准回流，本端零解析
            DropdownMenuItem(
                text = { Text(if (compacting) "压缩中…" else "压缩上下文") },
                enabled = !compacting && !conv.hasActiveRun,
                onClick = {
                    menuOpen = false
                    onCompact(conv)
                },
            )
        }
    }
}

/**
 * wp-7 重命名对话框（iOS 重命名 alert 同语义）：预填当前 label、trim 非空防线（空串由
 * 保存键禁用前置，服务端 400 HUB-BIZ-004 不再可能触达）、失败显式降级态可重试（对话框
 * 不关闭、输入保留）；成功即关闭，label 变化经 session.updated 事件回流列表。
 */
@Composable
private fun RenameConversationDialog(
    conv: UiConversation,
    submitting: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    // 预填当前 label（按 conversationId 重挂重置草稿——同一会话重开回到当前名）
    var draft by remember(conv.conversationId) { mutableStateOf(conv.label) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.width(320.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("重命名会话", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    enabled = !submitting,
                    label = { Text("会话名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onSubmit(draft.trim()) },
                        enabled = !submitting && draft.trim().isNotEmpty(),
                    ) {
                        Text(if (submitting) "提交中…" else "保存")
                    }
                }
            }
        }
    }
}

/**
 * wp-7 新建会话弹窗（iOS NewConversationSheet parity，#16 聚合语义 2026-11-04 拍板的 UI 面）：
 * 网关分节（在线圆点 + 名称 + 在线徽标）× Agent 四态行（[newConversationRowState]）。
 * 数据面 = observeGateways Flow（弹窗组合期订阅，关闭即退订）；conversations 由面板参数
 * 持续推送——弹窗开着时成卡即时翻置灰。空清单/空 Agent 降级提示（iOS ContentUnavailableView
 * 同文案族）；提交失败错误横幅常驻弹窗底部，点击即清可重试；「完成」关闭。
 */
@Composable
private fun NewConversationDialog(
    client: DefaultHubClient,
    conversations: List<UiConversation>,
    creatingKey: String?,
    submittedKeys: Set<String>,
    createError: String?,
    onSubmit: (gatewayId: String, agentId: String) -> Unit,
    onAckError: () -> Unit,
    onDismiss: () -> Unit,
) {
    val gateways by client.observeGateways().collectAsState(initial = emptyList())
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.width(420.dp).heightIn(max = 560.dp).padding(16.dp)) {
                Text("新建会话", style = MaterialTheme.typography.titleMedium)
                Text(
                    "入口只对无主线卡 Agent 开放；新会话锚定该 Agent 主线，校准后入列",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                )
                Spacer(Modifier.height(10.dp))
                if (gateways.isEmpty()) {
                    EmptyHint("暂无可用 Agent", "网关接入并列出 active Agent 后可在此新建会话")
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        gateways.forEach { gw ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            ) {
                                GatewayOnlineDot(gw.online)
                                Spacer(Modifier.width(6.dp))
                                Text(gw.name.ifBlank { "网关" }, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.width(8.dp))
                                TagChip(
                                    if (gw.online) "在线" else "离线",
                                    if (gw.online) Color(0xFF2E9E44) else Color(0xFF999999),
                                )
                            }
                            MonoText(gw.gatewayId, color = Color(0xFF999999))
                            if (gw.agents.isEmpty()) {
                                Text(
                                    "该网关暂无 active Agent",
                                    fontSize = 12.sp,
                                    color = Color(0xFFAAAAAA),
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            } else {
                                gw.agents.forEach { agent ->
                                    NewConversationAgentRow(
                                        gatewayId = gw.gatewayId,
                                        agent = agent,
                                        state = newConversationRowState(
                                            conversations, gw.gatewayId, agent.agentId, submittedKeys, creatingKey,
                                        ),
                                        onCreate = { onSubmit(gw.gatewayId, agent.agentId) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
                // 提交失败显式降级态（iOS createConversationError 红字同位）；点击即清可重试
                createError?.let { err ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAckError() }
                            .padding(top = 8.dp),
                    ) {
                        Text("⚠", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

/** 网关在线圆点（弹窗分节头用；GatewaysPanel.OnlineDot 为文件私有，此处独立同语义实现）。 */
@Composable
private fun GatewayOnlineDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(7.dp)
            .background(if (online) Color(0xFF2E9E44) else Color(0xFF999999), CircleShape),
    )
}

/** wp-7 新建会话单 Agent 行：四态呈现（iOS NewConversationAgentRow 同语义）。 */
@Composable
private fun NewConversationAgentRow(
    gatewayId: String,
    agent: UiAgentRef,
    state: NewConversationRowState,
    onCreate: () -> Unit,
) {
    if (state == NewConversationRowState.GRAYED) {
        // 置灰终态（iOS checkmark.circle + 「已有主线卡 · 新会话将作为后台任务会话静默」同文案：
        // 置灰是终态语义而非错误，行内常驻说明）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Text("✓", fontSize = 13.sp, color = Color(0xFF999999))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(agent.label.ifBlank { agent.agentId }, fontSize = 13.sp, color = Color(0xFF999999))
                Text(
                    "已有主线卡 · 新会话将作为后台任务会话静默",
                    fontSize = 11.sp,
                    color = Color(0xFFAAAAAA),
                    maxLines = 2,
                )
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(agent.label.ifBlank { agent.agentId }, fontSize = 13.sp)
                if (state == NewConversationRowState.SUBMITTED) {
                    Text(
                        "已提交 · 卡片校准后入列（或重进列表）",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                    )
                }
            }
            if (state == NewConversationRowState.CREATING) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            TextButton(onClick = onCreate, enabled = state == NewConversationRowState.IDLE) {
                Text(
                    when (state) {
                        NewConversationRowState.SUBMITTED -> "已提交"
                        NewConversationRowState.CREATING -> "提交中…"
                        else -> "新建"
                    },
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// ── wp-7 新建会话入口判定纯函数（desktopTest 回归锚 ConversationEntryRulesTest） ──────────

/** 「已提交」瞬态键：gatewayId|agentId（iOS HubStore.entryKey 同格式，同 agent 跨网关互不影响）。 */
internal fun conversationEntryKey(gatewayId: String, agentId: String): String = "$gatewayId|$agentId"

/** 新建会话入口单 Agent 行四态（iOS NewConversationAgentRow 同语义）。 */
internal enum class NewConversationRowState {
    /** 已有主线卡——置灰终态（新会话将作为后台任务会话静默）。 */
    GRAYED,

    /** 已提交等待成卡（校准收敛）。 */
    SUBMITTED,

    /** 提交在途（单飞）。 */
    CREATING,

    /** 可新建。 */
    IDLE,
}

/**
 * 行态判定（hasMainCard 门，语义复刻 iOS HubStore.swift:162-175）：置灰（已有主线卡 =
 * conversations 含 (gatewayId, agentId) 行）优先——iOS if hasMainCard 先判，已提交/提交中
 * 瞬态一律被主线卡判据接管（成卡即翻置灰）；键含 gatewayId，同 agent 跨网关互不影响。
 */
internal fun newConversationRowState(
    conversations: List<UiConversation>,
    gatewayId: String,
    agentId: String,
    submittedKeys: Set<String>,
    creatingKey: String?,
): NewConversationRowState {
    val key = conversationEntryKey(gatewayId, agentId)
    return when {
        conversations.any { it.gatewayId == gatewayId && it.agentId == agentId } -> NewConversationRowState.GRAYED
        creatingKey == key -> NewConversationRowState.CREATING
        key in submittedKeys -> NewConversationRowState.SUBMITTED
        else -> NewConversationRowState.IDLE
    }
}
