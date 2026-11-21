package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.UiAgentRef
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiGateway
import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.botSeedFor
import dev.sharpxia.agentreins.hub.avatar.botStateFor
import dev.sharpxia.agentreins.hub.projection.SessionSummary
import kotlinx.coroutines.delay
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
 *   三态判定 [newConversationRowState]（多主会话轮 v1.4.0：原 hasMainCard 置灰门拆除——
 *   入口常开，「已提交」等待态、提交中 spinner、可新建）；提交失败显式降级可重试（文案
 *   中性，评审 L9）；成卡以校准收敛为准零解析（回执不拼卡，DefaultHubClient 成功即触发
 *   一次校准，秒级入列；R4 周期兜底）。写路径按主线卡判据 [hasMainCard] 分流（iOS
 *   HubStore.createConversation 同签名）：无卡 = 默认主线（门面自动携带 mainSession=true，
 *   服务端组装 sessionKey=agent:<agentId>:main adopt 不 fork）；有卡 = 新开一条主线
 *   （newMainSession=true，服务端以请求幂等键确定性派生 main-<idempotencyKey>，重试同键
 *   同会话）——客户端永不拼键（wp-2 落地于 DefaultCommandClient.createConversation，ADR-14）。
 * v1.4.0 会话管理（#28 清单 / #19 rename / #29 delete）：agent 行（GatewaysPanel）与会话卡
 * 右键菜单「管理会话」→ [AgentSessionsManagerDialog]——全量会话行（管理面不收敛不筛族）；
 * rename 仅主线族行开放（评审 M7：#19 服务端只在卡行集内解析，task/channel 行必 404
 * HUB-BIZ-002——非主线行只给删除）；delete 走 #29 直调门面，乐观移除 + 事件级联
 * （session.removed）双路径幂等（验收 L6，iOS SessionManagerSheet 同语义）。
 * 多网关轮（v1.3.0，设计稿 §3.2）：会话行标题下加一行 caption 网关名（hub.gateways 按
 * gatewayId 查名，空名/未知回退 gatewayId 尾段——[gatewayNameFor]，iOS HubConversationRow
 * caption 同构），多网关混排列表的归属歧义就此消除。
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
    // 角色系统批次3：WAITING 谓词（wp-9 零新状态）——壳层把 pendingApprovals 按 conversationId
    // 折叠成 id 集（UiApproval.conversationId 可空 String?，null 归属不匹配任何行），
    // per-conversation Bool 透传（评审④）
    awaitingApprovalIds: Set<String> = emptySet(),
    // 角色系统批次3：动效档总开关（avatarPrefs UI 镜像 motionLevel==STANDARD，SettingsScreen:465 同口径；
    // off 档组件内不建帧循环，批次1 A6）
    motionEnabled: Boolean = true,
    // 会话级换形象轮（v1.5.2，2026-11-21）：全局形象档动效现值（avatarUi.motionLevel 同源）——
    // 会话换装 dialog 预览动效门控与 PUT 载荷 motionLevel（动效不进会话 sheet，仍全局档）
    globalMotion: AvatarMotion = AvatarMotion.STANDARD,
) {
    // 多网关轮（§3.2）：会话行网关名 caption 数据面（hub.gateways 快照/事件/回拉三路同源）
    val gateways by client.observeGateways().collectAsState(initial = emptyList())
    // 会话级换形象轮：会话覆盖档映射（契约 v1.5.2 #34 水合面；null=未水合、emptyMap=已水合
    // 无覆盖、键缺席=无覆盖走 seed 派生——store 不造默认值）。StateFlow 帧驱动自动刷新，
    // 卡片按 conversationId 直查不缓存副本
    val conversationAvatars by client.conversationAvatarProfiles.collectAsState()
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
    // 成卡即收敛清理（见下方 LaunchedEffect，评审 H3：已有卡 agent 不再显示「已提交」提示）
    var submittedKeys by remember { mutableStateOf(emptySet<String>()) }
    var creatingKey by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    // v1.4.0「管理会话」目标（会话卡右键菜单；nil = 关）
    var sessionsManagerTarget by remember { mutableStateOf<AgentSessionsTarget?>(null) }
    // 会话级换形象轮：「换形象」目标（会话卡右键菜单；nil = 关）
    var avatarTarget by remember { mutableStateOf<UiConversation?>(null) }

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
        // iOS HubStore.createConversation 同守卫：提交中单飞。多主会话轮置灰门拆除——写路径按
        // 主线卡判据分流（无卡 = 默认主线 mainSession=true adopt 不 fork；有卡 = 新开主线
        // newMainSession=true，服务端以请求幂等键确定性派生 main-<idempotencyKey>，重试同键同会话）
        if (creatingKey != null) return
        val key = conversationEntryKey(gatewayId, agentId)
        val newMainSession = hasMainCard(conversations, gatewayId, agentId)
        createError = null
        creatingKey = key
        scope.launch {
            val ok = runCatching {
                client.createConversation(gatewayId, agentId, null, newMainSession = newMainSession)
            }.map { it.commandId.isNotBlank() }.getOrDefault(false)
            creatingKey = null
            // 评审 L9：文案中性——网络不可达已入本地队列（PENDING_LOCAL）待自动重放、确定性
            // 拒绝走 FAILED，两条路径不预判（iOS HubStore.createConversation 同文案）
            if (ok) submittedKeys = submittedKeys + key else createError = "提交未送达或被服务端拒绝（可重试）"
        }
    }

    // wp-7 成卡收敛清理（iOS watchGateways formIntersection 同语义）：已兜底成卡的
    // (gatewayId, agentId) 移出「已提交」瞬态——行态三态判定不再读卡（入口常开），收敛即
    // 「成卡清瞬态」的唯一面（评审 H3）
    LaunchedEffect(conversations) {
        val pruned = pruneSubmittedKeys(submittedKeys, conversations)
        if (pruned != submittedKeys) submittedKeys = pruned
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // wp-7：面板头 + 新建会话入口（iOS 工具栏 plus.message 同面——入口按网关/Agent
            // 维度弹窗呈现，多主会话轮入口常开不在本入口拦截，分流在 submitCreate）
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
                            awaitingApproval = conv.conversationId in awaitingApprovalIds,
                            motionEnabled = motionEnabled,
                            gatewayName = gatewayNameFor(gateways, conv.gatewayId),
                            // 会话级换形象轮：覆盖档直查（map[conversationId]，StateFlow 帧驱动）
                            avatarOverride = conversationAvatars?.get(conv.conversationId),
                            onToggleMute = onToggleMute,
                            onRename = { renameTarget = it },
                            onCompact = { compactNow(it) },
                            onManageSessions = {
                                sessionsManagerTarget = AgentSessionsTarget(conv.gatewayId, conv.agentId)
                            },
                            onChangeAvatar = { avatarTarget = conv },
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

    // wp-7：新建会话弹窗（网关/Agent 维度三态行；conversations 持续推送——弹窗开着时成卡
    // 即时收敛「已提交」瞬态；入口常开，无卡/有卡分流在 submitCreate）
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

    // v1.4.0：会话管理对话框（#28 清单 / #19 rename 仅主线族 / #29 delete 乐观移除 + 事件级联）
    sessionsManagerTarget?.let { target ->
        AgentSessionsManagerDialog(
            client = client,
            target = target,
            onDismiss = { sessionsManagerTarget = null },
        )
    }

    // 会话级换形象对话框（#32 PUT / #33 DELETE；PUT/DELETE 成功经 shared 门面收敛进
    // conversationAvatarProfiles，本卡片列表帧驱动自动刷新）
    avatarTarget?.let { conv ->
        ConversationAvatarDialog(
            client = client,
            conversationId = conv.conversationId,
            conversationLabel = conv.label,
            globalMotion = globalMotion,
            onDismiss = { avatarTarget = null },
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
    awaitingApproval: Boolean,
    motionEnabled: Boolean,
    gatewayName: String,
    // 会话级换形象轮：本会话覆盖档（null = 无覆盖/未水合——渲染走 seed 派生零变化）
    avatarOverride: AvatarProfile?,
    onToggleMute: (conversationId: String, muted: Boolean) -> Unit,
    onRename: (UiConversation) -> Unit,
    onCompact: (UiConversation) -> Unit,
    onManageSessions: () -> Unit,
    onChangeAvatar: () -> Unit,
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
    // 角色系统批次3：DONE 瞬时窗——hasActiveRun 真→假 播 900ms 完成弹跳后回落 IDLE（终态按
    // 非 failed=已知边界⑧，UiConversation 无失败信号 HubClient.kt:50-66，ERROR 全覆盖在聊天页）。
    // LaunchedEffect 键=hasActiveRun：跳变即重启协程，在途 delay(900) 被取消，天然防陈旧回落
    // （新 run 启动即打断）；prevActive remember 初值=挂载时值，首组即非活跃不播 DONE。
    var prevActive by remember { mutableStateOf(conv.hasActiveRun) }
    var doneTransient by remember { mutableStateOf(false) }
    LaunchedEffect(conv.hasActiveRun) {
        when {
            conv.hasActiveRun -> doneTransient = false
            prevActive -> {
                doneTransient = true
                delay(900)
                doneTransient = false
            }
        }
        prevActive = conv.hasActiveRun
    }
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
                // 角色系统批次3（2026-11-20）：行首 36dp 会话角色替代行尾 ActiveRunDot——
                // seed=botSeedFor（同 cid 双端恒定，floorMod 分桶含负哈希金标锁），state 六态
                // 纯函数同参消费（botStateFor）；列表行不细分 THINKING（workActivity 逐行订阅
                // 违纪律，设计边界①两档合一呈 RUNNING）。帧门控在组件内（withFrameNanos 循环
                // 仅 motionEnabled=true 分支创建，LazyColumn 出屏 dispose 即停帧）。
                // 会话级换形象轮（v1.5.2）：有覆盖 = 造型/配色从覆盖档注入（seed 照传——眼形/
                // 姿态/眨音仍由会话身份派生）；无覆盖/null = 现状派生零变化（override 传 null
                // 即 seed 域）。
                BotCharacter(
                    seed = botSeedFor(conv.conversationId),
                    state = botStateFor(
                        hasActiveRun = conv.hasActiveRun,
                        thinkingNow = false,
                        awaitingApproval = awaitingApproval,
                        lastMessageFailed = false,
                        doneTransient = doneTransient,
                    ),
                    size = 36.dp,
                    motionEnabled = motionEnabled,
                    interactive = false,
                    faceFactorsOverride = avatarOverride?.faceFactors,
                    paletteIndexOverride = avatarOverride?.let { AvatarPalette.entries.indexOf(it.paletteId) },
                )
                Spacer(Modifier.width(10.dp))
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
                if (conv.reconciling) {
                    TagChip("对账中", Color(0xFFB26A00))
                    Spacer(Modifier.width(6.dp))
                }
                if (conv.unread > 0) {
                    UnreadBadge(conv.unread)
                }
            }
            // 多网关轮（§3.2）：标题下网关名 caption——多网关混排列表的归属歧义就此消除；
            // hub.gateways 按 gatewayId 查名，空名/未知回退 gatewayId 尾段（gatewayNameFor 口径）
            Text(
                text = gatewayName,
                fontSize = 10.sp,
                color = Color(0xFF999999),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
            // 会话级换形象轮（v1.5.2）：入口①——右键「换形象」打开会话换装对话框（#32/#33）
            DropdownMenuItem(
                text = { Text("换形象") },
                onClick = {
                    menuOpen = false
                    onChangeAvatar()
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
            // v1.4.0：管理会话（#28 清单 / #19 rename 仅主线族 / #29 delete；iOS 会话卡
            // contextMenu「管理会话」桌面同位）
            DropdownMenuItem(
                text = { Text("管理会话") },
                onClick = {
                    menuOpen = false
                    onManageSessions()
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
 * 网关分节（在线圆点 + 名称 + 在线徽标）× Agent 三态行（[newConversationRowState]）。
 * 数据面 = observeGateways Flow（弹窗组合期订阅，关闭即退订）；conversations 由面板参数
 * 持续推送——弹窗开着时成卡即时收敛「已提交」瞬态。空清单/空 Agent 降级提示
 * （iOS ContentUnavailableView 同文案族）；提交失败错误横幅常驻弹窗底部，点击即清可重试；
 * 「完成」关闭。多主会话轮：入口常开，无卡 = 默认主线 / 有卡 = 新开主线（分流在 submitCreate）。
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
    // v1.4.0 追补（2026-11-16 用户反馈「新建 agent 会话太容易误触」）：点行先确认再建——
    // 每次新建都是真建一张独立卡（newMainSession 幂等键派生语义），确认层防手滑连建。
    var confirmTarget by remember { mutableStateOf<Pair<String, UiAgentRef>?>(null) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.width(420.dp).heightIn(max = 560.dp).padding(16.dp)) {
                Text("新建会话", style = MaterialTheme.typography.titleMedium)
                Text(
                    "无主线卡 Agent 新建默认主线；已有主线卡的 Agent 新开独立主线会话——均校准后入列",
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
                                            gw.gatewayId, agent.agentId, submittedKeys, creatingKey,
                                        ),
                                        onCreate = { confirmTarget = gw.gatewayId to agent },
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

    // 新建确认层（v1.4.0 追补 2026-11-16；iOS confirmationDialog 同文案族）
    confirmTarget?.let { (gatewayId, agent) ->
        val newMain = hasMainCard(conversations, gatewayId, agent.agentId)
        val title = agent.label.ifBlank { agent.agentId }
        Dialog(onDismissRequest = { confirmTarget = null }) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.width(340.dp).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (newMain) "为「$title」新建主会话？" else "为「$title」新建会话？",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "每次新建都会创建一张独立的会话卡（多个主会话互不干扰）。",
                        fontSize = 12.sp,
                        color = Color(0xFF888888),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { confirmTarget = null }) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            confirmTarget = null
                            onSubmit(gatewayId, agent.agentId)
                        }) {
                            Text(if (newMain) "新建主会话" else "新建会话")
                        }
                    }
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

/** wp-7 新建会话单 Agent 行：三态呈现（多主会话轮置灰终态拆除；iOS NewConversationAgentRow 同语义）。 */
@Composable
private fun NewConversationAgentRow(
    gatewayId: String,
    agent: UiAgentRef,
    state: NewConversationRowState,
    onCreate: () -> Unit,
) {
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

// ── wp-7 新建会话入口判定纯函数（desktopApp jvmTest 回归锚 ConversationEntryRulesTest） ──────────

/** 「已提交」瞬态键：gatewayId|agentId（iOS HubStore.entryKey 同格式，同 agent 跨网关互不影响）。 */
internal fun conversationEntryKey(gatewayId: String, agentId: String): String = "$gatewayId|$agentId"

/** 主线卡判据（多主会话轮 2026-11-16：原入口禁用门降级为**分流判据**，iOS HubStore.hasMainCard
 *  同型）——该 (gatewayId, agentId) 已有卡片 = 已有主线，submitCreate 据此分流 newMainSession
 *  写路径；false = 默认主线（mainSession=true，服务端 DISTINCT ON 聚合保证一 agent 至多一张
 *  默认主线卡）。 */
internal fun hasMainCard(conversations: List<UiConversation>, gatewayId: String, agentId: String): Boolean =
    conversations.any { it.gatewayId == gatewayId && it.agentId == agentId }

/** 新建会话入口单 Agent 行三态（多主会话轮 GRAYED 置灰终态拆除后收敛；iOS 同语义）。 */
internal enum class NewConversationRowState {
    /** 已提交等待成卡（校准收敛；成卡即清瞬态，评审 H3）。 */
    SUBMITTED,

    /** 提交在途（单飞）。 */
    CREATING,

    /** 可新建（无卡 = 默认主线 / 有卡 = 新开主线，分流在 submitCreate 经 [hasMainCard]）。 */
    IDLE,
}

/**
 * 行态判定（多主会话轮 2026-11-16 三态收敛：原 hasMainCard 置灰门拆除——入口常开，行态只管
 * 瞬态呈现）：提交中（单飞）优先、已提交等待成卡次之、其余可新建；键含 gatewayId，同 agent
 * 跨网关互不影响。「已提交」瞬态由成卡收敛清理（[pruneSubmittedKeys]）——已有卡 agent 不再
 * 显示提示（评审 H3）；瞬态清空后防重仅剩提交中单飞 creatingKey。
 */
internal fun newConversationRowState(
    gatewayId: String,
    agentId: String,
    submittedKeys: Set<String>,
    creatingKey: String?,
): NewConversationRowState {
    val key = conversationEntryKey(gatewayId, agentId)
    return when {
        creatingKey == key -> NewConversationRowState.CREATING
        key in submittedKeys -> NewConversationRowState.SUBMITTED
        else -> NewConversationRowState.IDLE
    }
}

/**
 * 成卡收敛（桌面减法，ConversationListPanel/GatewaysPanel 两面板共用；iOS watchGateways
 * formIntersection 同语义）：已兜底成卡的 (gatewayId, agentId) 键移出「已提交」瞬态——
 * 行态判定不再读卡（入口常开），本函数即「成卡清瞬态」的唯一实现面。
 */
internal fun pruneSubmittedKeys(submittedKeys: Set<String>, conversations: List<UiConversation>): Set<String> {
    if (submittedKeys.isEmpty()) return submittedKeys
    val cardKeys = conversations.map { conversationEntryKey(it.gatewayId, it.agentId) }.toSet()
    return submittedKeys - cardKeys
}

// ── v1.4.0 会话管理面板（#28 清单 / #19 rename / #29 delete）纯函数（回归锚 SessionManagerRulesTest） ──

/** 「管理会话」目标载体（gatewayId|agentId 二元组；会话卡 / GatewaysPanel agent 行两入口共用）。 */
internal data class AgentSessionsTarget(val gatewayId: String, val agentId: String)

/**
 * rename 门（评审 M7，同 K2 iOS SessionManagerSheet）：#19 conversation.rename 服务端经
 * lookupActiveConversation 只在卡行集内解析，task/channel 行必 404 HUB-BIZ-002——**仅主线族
 * 行（isMainSession=true）开放重命名**，非主线行只给删除；dashboard 卡行重命名走既有卡 UI 入口。
 */
internal fun sessionRowRenameAllowed(isMainSession: Boolean): Boolean = isMainSession

/** 乐观移除（#29 delete 成功路径：清单行即撤，幂等——无匹配行原样返回；验收 L6 双路径之一）。 */
internal fun removeSessionRow(sessions: List<SessionSummary>, conversationId: String): List<SessionSummary> =
    sessions.filterNot { it.conversationId == conversationId }

/** 乐观改名（#19 PERSISTED 回执后行 label 即时翻新，幂等——无匹配行原样返回；权威面由
 *  session.updated 事件 / 下次 #28 重拉收敛）。 */
internal fun renameSessionRow(
    sessions: List<SessionSummary>,
    conversationId: String,
    label: String,
): List<SessionSummary> = sessions.map {
    if (it.conversationId == conversationId) it.copy(label = label) else it
}

/**
 * v1.4.0 会话管理对话框（#28 清单 / #19 rename / #29 delete；入口 = 会话卡与 GatewaysPanel
 * agent 行右键菜单「管理会话」，iOS SessionManagerSheet 桌面同构）：全量会话行（管理面视图
 * 不收敛不筛族——dashboard/主线族含 main-<idempotencyKey> 多主线/task/channel/subagent/cron
 * 全键族可见）。rename 仅主线族行开放（[sessionRowRenameAllowed]，评审 M7）；delete 走
 * #29 直调门面（204 后投影行删 = 卡列表乐观收敛，session.removed 事件级联幂等，验收 L6）+
 * 本清单行乐观移除。失败显式降级态可重试，不静默；对话框关闭即弃全部瞬态（下次进入重拉）。
 */
@Composable
internal fun AgentSessionsManagerDialog(
    client: DefaultHubClient,
    target: AgentSessionsTarget,
    onDismiss: () -> Unit,
) {
    // 状态机：sessions = null 加载中 / 非空已载（管理面无收敛语义，清单即服务端原样）；
    // 行级动作在途集防重复提交；错误行显式呈现可重试（iOS agentSessionsError 同式）
    var sessions by remember(target) { mutableStateOf<List<SessionSummary>?>(null) }
    var loading by remember(target) { mutableStateOf(false) }
    var loadError by remember(target) { mutableStateOf<String?>(null) }
    var deletingIds by remember(target) { mutableStateOf(emptySet<String>()) }
    var renamingIds by remember(target) { mutableStateOf(emptySet<String>()) }
    var deleteError by remember(target) { mutableStateOf<String?>(null) }
    var renameError by remember(target) { mutableStateOf<String?>(null) }
    // 重命名子对话框载体（nil = 关；预填当前 label）
    var renameTargetId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    // 删除确认载体（终态警示后提交）
    var deleteCandidate by remember { mutableStateOf<SessionSummary?>(null) }
    val scope = rememberCoroutineScope()

    fun load() {
        if (loading) return
        loading = true
        loadError = null
        scope.launch {
            val page = runCatching { client.listAgentSessions(target.gatewayId, target.agentId) }.getOrNull()
            loading = false
            if (page != null) sessions = page.sessions else loadError = "会话清单加载失败，可重试"
        }
    }

    fun renameSession(conversationId: String, label: String) {
        if (conversationId in renamingIds) return
        renamingIds = renamingIds + conversationId
        renameError = null
        scope.launch {
            val ok = runCatching { client.renameConversation(conversationId, label) }
                .map { it.commandId.isNotBlank() }
                .getOrDefault(false)
            renamingIds = renamingIds - conversationId
            if (ok) sessions = sessions?.let { renameSessionRow(it, conversationId, label) }
            else renameError = "重命名未送达或被服务端拒绝（可重试）"
        }
    }

    fun deleteSession(summary: SessionSummary) {
        val cid = summary.conversationId
        if (cid in deletingIds) return
        deletingIds = deletingIds + cid
        deleteError = null
        // 乐观移除（清单行）：与门面 204 后投影行删、session.removed 事件级联三面各自幂等
        // （验收 L6：乐观删后事件再删不告警不重复）
        sessions = sessions?.let { removeSessionRow(it, cid) }
        scope.launch {
            val ok = runCatching { client.deleteConversation(cid) }.isSuccess
            deletingIds = deletingIds - cid
            if (!ok) deleteError = "删除未送达或被服务端拒绝（可重试）"
        }
    }

    LaunchedEffect(target) { load() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.width(480.dp).heightIn(max = 560.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("管理会话", style = MaterialTheme.typography.titleMedium)
                Text(
                    "全量会话（管理面不筛族）；仅主线行可重命名，删除为终态不可恢复",
                    fontSize = 11.sp,
                    color = Color(0xFF888888),
                )
                Spacer(Modifier.height(6.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    when {
                        loading && sessions == null -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 12.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("加载中…", fontSize = 12.sp, color = Color(0xFF888888))
                        }
                        loadError != null -> Column(Modifier.padding(vertical = 8.dp)) {
                            Text(loadError!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            TextButton(onClick = { load() }) { Text("重试", fontSize = 12.sp) }
                        }
                        sessions != null && sessions!!.isEmpty() -> Text(
                            "该 Agent 暂无会话",
                            fontSize = 12.sp,
                            color = Color(0xFF888888),
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                        sessions != null -> sessions!!.forEach { s ->
                            SessionManageRow(
                                summary = s,
                                busy = s.conversationId in deletingIds || s.conversationId in renamingIds,
                                canRename = sessionRowRenameAllowed(s.isMainSession),
                                onRename = {
                                    renameTargetId = s.conversationId
                                    renameDraft = s.label
                                },
                                onDelete = { deleteCandidate = s },
                            )
                        }
                    }
                }
                // 行级动作失败显式降级（与清单加载错误并存，不静默）
                deleteError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                renameError?.let { err ->
                    Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }

    // 重命名子对话框（预填当前 label，trim 非空防线；仅主线族行可达——M7 门在行层已收口；
    // 保存即提交并关子框，在途态由行内 renamingIds spinner 呈现，失败经 renameError 行降级）
    renameTargetId?.let { id ->
        Dialog(onDismissRequest = { renameTargetId = null }) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.width(320.dp).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("重命名会话", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = renameDraft,
                        onValueChange = { renameDraft = it },
                        singleLine = true,
                        label = { Text("会话名称") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { renameTargetId = null }) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                renameSession(id, renameDraft.trim())
                                renameTargetId = null
                            },
                            enabled = renameDraft.trim().isNotEmpty(),
                        ) {
                            Text("保存")
                        }
                    }
                }
            }
        }
    }

    // 删除确认（终态警示；iOS confirmationDialog 同文案族）
    deleteCandidate?.let { candidate ->
        Dialog(onDismissRequest = { deleteCandidate = null }) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.width(340.dp).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("删除该会话？", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "删除为终态：会话消息与后台任务一并删除，不可恢复。",
                        fontSize = 12.sp,
                        color = Color(0xFF888888),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { deleteCandidate = null }) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            deleteCandidate = null
                            deleteSession(candidate)
                        }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

/** 管理清单单行：label（空回退 conversationId）+ 主线徽标 + 句柄/最近活跃 caption + 行内动作
 *  （重命名仅主线族行；删除全行开放，确认后乐观移除）。 */
@Composable
private fun SessionManageRow(
    summary: SessionSummary,
    busy: Boolean,
    canRename: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                summary.label.ifBlank { summary.conversationId },
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (summary.isMainSession) {
                TagChip("主线", Color(0xFF4A6DA7))
            }
            if (busy) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
            }
        }
        MonoText(summary.conversationId, color = Color(0xFF999999))
        Text(
            "最近活跃 ${formatTs(summary.lastActivityAt)}",
            fontSize = 11.sp,
            color = Color(0xFF888888),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canRename) {
                TextButton(onClick = onRename, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("重命名", fontSize = 12.sp)
                }
            }
            TextButton(onClick = onDelete, enabled = !busy, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                Text("删除", fontSize = 12.sp, color = Color(0xFFB45309))
            }
        }
    }
}
