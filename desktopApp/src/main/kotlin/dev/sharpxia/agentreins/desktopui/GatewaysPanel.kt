package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.UiAgentRef
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiGateway
import dev.sharpxia.agentreins.hub.projection.BridgeTokenIssuedResponse
import dev.sharpxia.agentreins.hub.projection.GatewayCreatedResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * wp-11：网关与 Agent 清单（独立页，nav 第三入口 + 设置页「网关与 Agent」卡入口）。
 * 多网关轮（v1.3.0，设计稿 §4.3 桌面同构精简版）改**三段式分组**：「分组」（每组一节，节头
 * 可改名/解散）+「未分组」+「网关」（网关卡保留原有 online/lastSeenAt 呈现 + 复植轮 provider
 * 徽标——桥自报供给方标识透传，空串无徽标）。分组偏好落 [DesktopAgentGroupStore]
 * （java.util.prefs，§4.2 JSON，与 iOS UserDefaults 各自持久互不同步）；组员资格与目录取交集
 * 渲染（[agentGroupsView]，iOS HubStore.groupAgents/ungroupedAgents 同构——已下线/未出现的
 * agent 不渲染、不编造）。
 *
 * AgentRefRow（原 wp-11 只读行）加「新建会话」action（§4.3，与需求②桌面入口打通）：复用
 * ConversationListPanel 的三态判定 [newConversationRowState]（多主会话轮置灰门拆除——入口
 * 常开，无卡 = 默认主线 mainSession=true / 有卡 = 新开主线 newMainSession=true，分流在
 * [submitCreate] 经 [hasMainCard] 判定，iOS HubStore.createConversation 同签名）与
 * [conversationEntryKey]，提交直调 shared 门面 createConversation（服务端组装 sessionKey，
 * 客户端永不拼键，ADR-14）；跨网关混组的行呈网关短名 caption（[gatewayNameFor]，空名回退
 * gatewayId 尾段）。agent 行右键菜单（iOS contextMenu 同构）：加入分组…（组选择器 + 新建组
 * 入口）/ 移出分组 / 管理会话（v1.4.0 [AgentSessionsManagerDialog]）。
 *
 * 网关供给面管理（复植轮复位，iOS GatewaysView 桌面同构精简版）：面板头「＋ 接入网关」→
 * #25 添加网关对话框（名称 ≤64 预校验 → 一次性凭据展示页，bridgeToken 明文仅对话框内存、
 * 关闭即失）；网关卡 contextMenu「轮换 Token」（#26，新 Token 一次性展示 + 24h 宽限文案）与
 * 「撤销网关」（#27 确认层，「历史保留只读」终态文案）——直调 HubClient 三管理门面
 * （createGateway/rotateBridgeToken/deleteGateway，删除成功门面自带 refreshGateways 对账），
 * 失败显式降级可重试不静默。
 *
 * 数据面 = HubClient.observeGateways()：hubGateways 快照整体覆盖（wp-2 填充）+
 * gateway.online/offline 事件翻转维护的 online 时点 + gateway.agents_changed 防抖回拉
 * （v1.3.0 §3.2），行随库变更推送，无本地刷新动作。agentsJson 已在门面解析为结构化
 * [UiAgentRef]（解析失败单行降级空清单、网关行本体不丢）。
 */
@Composable
internal fun GatewaysPanel(
    client: DefaultHubClient,
    agentGroups: DesktopAgentGroupStore,
    modifier: Modifier = Modifier,
) {
    val gateways by client.observeGateways().collectAsState(initial = emptyList())
    val conversations by client.observeConversations().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // ── 新建会话瞬态（面板级持有；三态判定纯函数与 ConversationListPanel 共用） ──
    var creatingKey by remember { mutableStateOf<String?>(null) }
    var submittedKeys by remember { mutableStateOf(emptySet<String>()) }
    var createError by remember { mutableStateOf<String?>(null) }

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

    // 成卡收敛清理（ConversationListPanel 同语义，[pruneSubmittedKeys] 共用实现）：已兜底成卡
    // 的 (gatewayId, agentId) 移出「已提交」瞬态——行态三态判定不再读卡（入口常开），收敛即
    // 「成卡清瞬态」的唯一面（评审 H3）
    LaunchedEffect(conversations) {
        val pruned = pruneSubmittedKeys(submittedKeys, conversations)
        if (pruned != submittedKeys) submittedKeys = pruned
    }

    // ── 分组管理瞬态：组选择器目标 / 新建组（建后即入组）/ 改名目标 ──
    var pickerTarget by remember { mutableStateOf<GatewayAgentRef?>(null) }
    var pendingAssignAfterCreate by remember { mutableStateOf<GatewayAgentRef?>(null) }
    var groupNameDialog by remember { mutableStateOf<GroupDialogRequest?>(null) }

    // ── 网关供给面管理瞬态（v1.3.0 #25/#26/#27 复植轮 + v1.4.0 管理会话；桌面同构精简版） ──
    var showAddGateway by remember { mutableStateOf(false) }
    var rotateTarget by remember { mutableStateOf<UiGateway?>(null) }
    var revokeTarget by remember { mutableStateOf<UiGateway?>(null) }
    var sessionsManagerTarget by remember { mutableStateOf<AgentSessionsTarget?>(null) }

    if (gateways.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint(
                "暂无网关",
                "网关接入并完成首快照后自动列入此处",
            )
        }
    } else {
        val groupList = agentGroups.groups
        val memberList = agentGroups.members
        val view = remember(gateways, groupList, memberList) {
            agentGroupsView(gateways, AgentGroupsState(groupList, memberList))
        }
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("网关与 Agent", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                // 网关供给面入口（iOS GatewaysView toolbar「+」桌面同位）：#25 添加网关 sheet
                TextButton(onClick = { showAddGateway = true }) {
                    Text("＋ 接入网关", fontSize = 12.sp)
                }
                TextButton(onClick = { groupNameDialog = GroupDialogRequest(null) }) {
                    Text("＋ 新建分组", fontSize = 12.sp)
                }
            }
            // 提交失败显式降级横幅（ConversationListPanel 同纪律：点击即清可重试）
            createError?.let { err ->
                Surface(
                    color = Color(0xFFB45309).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { createError = null }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("⚠", color = Color(0xFFB45309), fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(err, color = Color(0xFFB45309), fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.size(8.dp))

            // 段一「分组」（§4.3：每组一节，节头可改名/解散；组内 agent = 组员资格 ∩ 目录）
            SectionHeader("分组")
            if (view.grouped.isEmpty()) {
                SectionHint("暂无分组——右键 Agent 行选「加入分组…」，或点「＋ 新建分组」")
            } else {
                view.grouped.forEach { (group, rows) ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📁", fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    group.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(
                                    onClick = { groupNameDialog = GroupDialogRequest(group.id, group.name) },
                                    contentPadding = DefaultTextButtonPadding,
                                ) { Text("改名", fontSize = 12.sp) }
                                TextButton(
                                    onClick = { agentGroups.dissolveGroup(group.id) },
                                    contentPadding = DefaultTextButtonPadding,
                                ) { Text("解散", fontSize = 12.sp, color = Color(0xFFB45309)) }
                            }
                            if (rows.isEmpty()) {
                                Text(
                                    "该组暂无目录内 Agent（已下线的成员不渲染，不编造）",
                                    fontSize = 12.sp,
                                    color = Color(0xFFAAAAAA),
                                )
                            } else {
                                rows.forEach { row ->
                                    AgentRefRow(
                                        agent = row.agent,
                                        gatewayName = gatewayNameFor(gateways, row.gateway.gatewayId),
                                        showGatewayName = true,
                                        rowState = newConversationRowState(
                                            row.gateway.gatewayId, row.agent.agentId, submittedKeys, creatingKey,
                                        ),
                                        onCreateConversation = { submitCreate(row.gateway.gatewayId, row.agent.agentId) },
                                        inGroup = true,
                                        onJoinGroup = { pickerTarget = row },
                                        onLeaveGroup = { agentGroups.removeMember(row.gateway.gatewayId, row.agent.agentId) },
                                        onManageSessions = {
                                            sessionsManagerTarget = AgentSessionsTarget(
                                                row.gateway.gatewayId, row.agent.agentId,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 段二「未分组」（组员资格为 nil 的目录 agent）
            SectionHeader("未分组")
            if (view.ungrouped.isEmpty()) {
                SectionHint("目录内 Agent 均已分组")
            } else {
                view.ungrouped.forEach { row ->
                    AgentRefRow(
                        agent = row.agent,
                        gatewayName = gatewayNameFor(gateways, row.gateway.gatewayId),
                        showGatewayName = true,
                        rowState = newConversationRowState(
                            row.gateway.gatewayId, row.agent.agentId, submittedKeys, creatingKey,
                        ),
                        onCreateConversation = { submitCreate(row.gateway.gatewayId, row.agent.agentId) },
                        inGroup = false,
                        onJoinGroup = { pickerTarget = row },
                        onLeaveGroup = {},
                        onManageSessions = {
                            sessionsManagerTarget = AgentSessionsTarget(
                                row.gateway.gatewayId, row.agent.agentId,
                            )
                        },
                    )
                }
            }

            // 段三「网关」（§4.3：网关卡保留原有 online/lastSeenAt 呈现 + 复植轮 provider 徽标；
            // agent 行三处共用——卡内行同挂分组菜单与「新建会话」，iOS agentRow 三处共用同构；
            // 卡 contextMenu 管理动作（轮换 Token/撤销网关）复植轮复位）
            SectionHeader("网关")
            gateways.forEach { gw ->
                GatewayCard(
                    gw = gw,
                    conversations = conversations,
                    submittedKeys = submittedKeys,
                    creatingKey = creatingKey,
                    onCreate = { gatewayId, agentId -> submitCreate(gatewayId, agentId) },
                    isGrouped = { agentId -> agentGroups.groupIdOf(gw.gatewayId, agentId) != null },
                    onJoinGroup = { agent -> pickerTarget = GatewayAgentRef(gw, agent) },
                    onLeaveGroup = { agentId -> agentGroups.removeMember(gw.gatewayId, agentId) },
                    onManageSessions = { agentId ->
                        sessionsManagerTarget = AgentSessionsTarget(gw.gatewayId, agentId)
                    },
                    onRotate = { rotateTarget = gw },
                    onRevoke = { revokeTarget = gw },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }

    // 组选择器（iOS GroupPickerSheet 同构精简版）：现有组 + 「＋ 新建组…」（建后即入组）
    pickerTarget?.let { target ->
        GroupPickerDialog(
            agentLabel = target.agent.label.ifBlank { target.agent.agentId },
            groups = agentGroups.groups,
            currentGroupId = agentGroups.groupIdOf(target.gateway.gatewayId, target.agent.agentId),
            onPick = { groupId ->
                agentGroups.assign(target.gateway.gatewayId, target.agent.agentId, groupId)
                pickerTarget = null
            },
            onCreateNew = {
                pendingAssignAfterCreate = target
                groupNameDialog = GroupDialogRequest(null)
            },
            onDismiss = {
                pickerTarget = null
                pendingAssignAfterCreate = null // 弃链清理：残留会把下一个新建组误挂上旧目标
            },
        )
    }

    // 组名对话框（新建分组 / 改名共用；trim 非空防线前置到输入层）
    groupNameDialog?.let { req ->
        GroupNameDialog(
            title = if (req.groupId == null) "新建分组" else "重命名分组",
            initial = req.initial.orEmpty(),
            confirmLabel = if (req.groupId == null) "创建" else "保存",
            onDismiss = {
                groupNameDialog = null
                pendingAssignAfterCreate = null // 取消即弃「新建组→入组」链
            },
            onSubmit = { name ->
                val trimmed = name.trim()
                val target = pendingAssignAfterCreate
                if (req.groupId == null) {
                    val id = agentGroups.createGroup(trimmed)
                    // 来自组选择器「＋ 新建组…」链路：建组后立即把目标 agent 入组（iOS 同语义）
                    if (target != null) {
                        agentGroups.assign(target.gateway.gatewayId, target.agent.agentId, id)
                        pendingAssignAfterCreate = null
                        pickerTarget = null
                    }
                } else {
                    agentGroups.renameGroup(req.groupId, trimmed)
                }
                groupNameDialog = null
            },
        )
    }

    // ── 网关供给面管理对话框（v1.3.0 #25/#26/#27 复植轮；桌面 Dialog 同构精简版） ──
    if (showAddGateway) {
        AddGatewayDialog(
            client = client,
            onDismiss = { showAddGateway = false },
        )
    }
    rotateTarget?.let { target ->
        RotateGatewayDialog(
            client = client,
            gateway = target,
            onDismiss = { rotateTarget = null },
        )
    }
    revokeTarget?.let { target ->
        RevokeGatewayDialog(
            client = client,
            gateway = target,
            onDismiss = { revokeTarget = null },
        )
    }

    // v1.4.0：会话管理对话框（agent 行右键菜单「管理会话」入口；与 ConversationListPanel
    // 会话卡共用同一 [AgentSessionsManagerDialog]）
    sessionsManagerTarget?.let { target ->
        AgentSessionsManagerDialog(
            client = client,
            target = target,
            onDismiss = { sessionsManagerTarget = null },
        )
    }
}

/**
 * #25 名称预校验（AddGatewayDialog 输入层，回归锚 SessionManagerRulesTest）：契约 name 必填
 * 非空、≤64 字符（服务端 400 HUB-BIZ-001 裁决；客户端预校验只为 UX，不替代服务端校验）。
 */
internal fun gatewayNameValid(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() && trimmed.length <= 64
}

/**
 * 一次性凭据展示行（#25/#26 共用，iOS OneTimeValueRow 桌面同构）：label + 等宽值 + 复制按钮
 * （2s 反馈）。凭据只在本组合内存，不落任何持久化（不持久化纪律，契约 #25 description）。
 */
@Composable
private fun OneTimeValueRow(label: String, value: String) {
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 11.sp, color = Color(0xFF888888), modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(value))
                    copied = true
                    scope.launch {
                        delay(2_000)
                        copied = false
                    }
                },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(if (copied) "已复制" else "复制", fontSize = 11.sp)
            }
        }
        MonoText(value, color = Color(0xFF444444))
    }
}

/** 管理动作失败降级行（添加/轮换/撤销三对话框共用：显式呈现可重试，不静默）。 */
@Composable
private fun MgmtErrorRow(message: String) {
    Text(message, color = Color(0xFFB45309), fontSize = 12.sp)
}

/**
 * 网关管理对话框 · 添加网关（iOS AddGatewaySheet 桌面同构）：#25 名称表单 → 一次性凭据展示页
 * （gatewayId + bridgeToken，OneTimeValueRow 复制反馈）。一次性凭据只在本对话框 remember 会话
 * 内存持有，**关闭组合即弃**（对话框条件卸载，状态无处存活——不持久化纪律）；展示页常驻
 * 「关闭后不可再看」警示。#25 名称不判重、无幂等键——提交中禁用按钮防重。
 */
@Composable
private fun AddGatewayDialog(
    client: DefaultHubClient,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 一次性凭据（nil = 表单态；仅会话内存，对话框关闭即释放）
    var created by remember { mutableStateOf<GatewayCreatedResponse?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.width(420.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val info = created
                if (info != null) {
                    // 一次性凭据展示页
                    Text("网关已创建：${info.name}", style = MaterialTheme.typography.titleMedium)
                    OneTimeValueRow("网关 ID（Bridge env：BRIDGE_GATEWAY_ID）", info.gatewayId)
                    OneTimeValueRow("Bridge Token（Bridge env：BRIDGE_TOKEN）", info.bridgeToken)
                    Text(
                        "把以上两项写入目标设备 Bridge env（连同 BRIDGE_HUB_URL）并启动进程；网关拨出后即出现在清单。",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                    )
                    Text(
                        "一次性明文：关闭后不可再看，服务端只存摘要。请先复制，再关闭本页。",
                        fontSize = 12.sp,
                        color = Color(0xFFB45309),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }
                } else {
                    // 名称表单态
                    Text("添加网关", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        enabled = !submitting,
                        label = { Text("网关名称（≤64 字符）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "名称不判重：重复提交会产生多个网关各持独立 Token。创建后把 Bridge Token 配到目标设备 Bridge env 并启动进程。",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                    )
                    error?.let { MgmtErrorRow(it) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                submitting = true
                                error = null
                                scope.launch {
                                    val result = runCatching { client.createGateway(name.trim()) }
                                    submitting = false
                                    result.fold(
                                        onSuccess = { created = it },
                                        onFailure = {
                                            error = it.message?.takeIf { m -> m.isNotBlank() }
                                                ?: "创建未成功，可重试"
                                        },
                                    )
                                }
                            },
                            enabled = gatewayNameValid(name) && !submitting,
                        ) {
                            Text(if (submitting) "创建中…" else "创建网关")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 网关管理对话框 · 轮换 Token（#26，iOS RotateBridgeTokenSheet 桌面同构）：确认页 → 重新签发
 * bridgeToken，新明文一次性展示（复制按钮 + 「关闭后不可再看」警示）；旧 Token 24h 宽限期内
 * 新旧双活（并发轮换 last-write-wins）。目标网关已撤销时 401 语义拒绝（HUB-AUTH-005 族）。
 */
@Composable
private fun RotateGatewayDialog(
    client: DefaultHubClient,
    gateway: UiGateway,
    onDismiss: () -> Unit,
) {
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 新签发明文（nil = 确认态；仅会话内存，对话框关闭即释放——不持久化纪律同 #25）
    var issuedToken by remember { mutableStateOf<BridgeTokenIssuedResponse?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.width(420.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val issued = issuedToken
                if (issued != null) {
                    // 新 Token 一次性展示页
                    Text("新 Token 已签发", style = MaterialTheme.typography.titleMedium)
                    OneTimeValueRow("新 Bridge Token（Bridge env：BRIDGE_TOKEN）", issued.bridgeToken)
                    Text(
                        "把新 Token 写入目标设备 Bridge env 并重启进程即完成换装；旧 Token 宽限 24 小时后失效。",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                    )
                    Text(
                        "一次性明文：关闭后不可再看，服务端只存摘要。请先复制，再关闭本页。",
                        fontSize = 12.sp,
                        color = Color(0xFFB45309),
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }
                } else {
                    // 确认页
                    Text("轮换 Bridge Token", style = MaterialTheme.typography.titleMedium)
                    Text(gateway.name.ifBlank { "网关" }, fontSize = 13.sp)
                    MonoText(gateway.gatewayId, color = Color(0xFF999999))
                    Text(
                        "签发新明文 Token（一次性展示）；旧 Token 24 小时宽限期内新旧双活，并发轮换以最后一次签发为准。",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                    )
                    error?.let { MgmtErrorRow(it) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                submitting = true
                                error = null
                                scope.launch {
                                    val result = runCatching { client.rotateBridgeToken(gateway.gatewayId) }
                                    submitting = false
                                    result.fold(
                                        onSuccess = { issuedToken = it },
                                        onFailure = {
                                            error = it.message?.takeIf { m -> m.isNotBlank() }
                                                ?: "轮换未成功，可重试"
                                        },
                                    )
                                }
                            },
                            enabled = !submitting,
                        ) {
                            Text(if (submitting) "轮换中…" else "轮换并展示新 Token")
                        }
                    }
                }
            }
        }
    }
}

/**
 * 网关管理对话框 · 撤销（#27 确认层，iOS RevokeGatewaySheet 桌面同构）：明示「历史保留（只读）、
 * 命令不再下发」；撤销为终态，重复撤销对属主幂等（204）。Hub 即时收口连接、目录消失——本地
 * 投影行收敛已下沉门面（deleteGateway 成功即触发 refreshGateways 回拉差集对账，撤销卡即时消失）。
 */
@Composable
private fun RevokeGatewayDialog(
    client: DefaultHubClient,
    gateway: UiGateway,
    onDismiss: () -> Unit,
) {
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = { if (!submitting) onDismiss() }) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.width(420.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("撤销网关", style = MaterialTheme.typography.titleMedium)
                Text(gateway.name.ifBlank { "网关" }, fontSize = 13.sp)
                MonoText(gateway.gatewayId, color = Color(0xFF999999))
                Text(
                    "撤销后该网关连接即时收口、命令不再下发；历史会话保留（只读）。此操作为终态，重复撤销无副作用。",
                    fontSize = 12.sp,
                    color = Color(0xFFB45309),
                )
                error?.let { MgmtErrorRow(it) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            submitting = true
                            error = null
                            scope.launch {
                                val result = runCatching { client.deleteGateway(gateway.gatewayId) }
                                submitting = false
                                result.fold(
                                    onSuccess = { onDismiss() }, // 204 终态：关闭；卡收敛由门面回拉对账
                                    onFailure = {
                                        error = it.message?.takeIf { m -> m.isNotBlank() }
                                            ?: "撤销未成功，可重试"
                                    },
                                )
                            }
                        },
                        enabled = !submitting,
                    ) {
                        Text(if (submitting) "撤销中…" else "撤销网关")
                    }
                }
            }
        }
    }
}

/** 组名对话框请求（groupId = null 新建；非 null 改名并预填现名）。 */
private data class GroupDialogRequest(val groupId: String?, val initial: String? = null)

/**
 * Agent 只读行 → 交互行（多网关轮 §4.3）：label（缺省兜底 agentId）+ 跨网关短名 caption +
 * 「新建会话」action（三态呈现）+ 右键菜单（iOS agent 行 contextMenu 同构：加入分组…/移出分组/
 * 管理会话）。[showGatewayName] = false 用于「网关」段卡内行（归属已由卡头表达，不重复）。
 */
@Composable
private fun AgentRefRow(
    agent: UiAgentRef,
    gatewayName: String,
    showGatewayName: Boolean,
    rowState: NewConversationRowState,
    onCreateConversation: () -> Unit,
    inGroup: Boolean,
    onJoinGroup: () -> Unit,
    onLeaveGroup: () -> Unit,
    onManageSessions: () -> Unit,
) {
    val menuItems = listOfNotNull(
        ContextMenuItem("加入分组…") { onJoinGroup() },
        if (inGroup) ContextMenuItem("移出分组") { onLeaveGroup() } else null,
        ContextMenuItem("管理会话") { onManageSessions() },
    )
    ContextMenuArea(items = { menuItems }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("👤", fontSize = 11.sp, color = Color(0xFF888888))
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    agent.label.ifBlank { agent.agentId },
                    fontSize = 13.sp,
                )
                if (showGatewayName && gatewayName.isNotBlank()) {
                    Text(gatewayName, fontSize = 10.sp, color = Color(0xFF999999), maxLines = 1)
                }
            }
            if (rowState == NewConversationRowState.CREATING) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            TextButton(onClick = onCreateConversation, enabled = rowState == NewConversationRowState.IDLE) {
                Text(
                    when (rowState) {
                        NewConversationRowState.SUBMITTED -> "已提交"
                        NewConversationRowState.CREATING -> "提交中…"
                        NewConversationRowState.IDLE -> "新建会话"
                    },
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 段头（三段式「分组/未分组/网关」共用）。 */
@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** 段内空态弱提示。 */
@Composable
private fun SectionHint(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        color = Color(0xFFAAAAAA),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * 网关卡：在线态圆点 + 名称（空名兜底「网关」）+ 在线/离线徽标 + provider 徽标（桥自报供给方
 * 标识透传；空串 = 无徽标，未知不做枚举假设）+ id + 最近活跃 + Agent 清单。卡 contextMenu
 * 管理动作（轮换 Token #26 / 撤销网关 #27，iOS 网关卡 contextMenu 桌面同位）。agent 行与
 * 「分组/未分组」两段共用 [AgentRefRow]（分组菜单 + 「新建会话」+「管理会话」三处同挂）。
 */
@Composable
private fun GatewayCard(
    gw: UiGateway,
    conversations: List<UiConversation>,
    submittedKeys: Set<String>,
    creatingKey: String?,
    onCreate: (gatewayId: String, agentId: String) -> Unit,
    isGrouped: (agentId: String) -> Boolean,
    onJoinGroup: (agent: UiAgentRef) -> Unit,
    onLeaveGroup: (agentId: String) -> Unit,
    onManageSessions: (agentId: String) -> Unit,
    onRotate: () -> Unit,
    onRevoke: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ContextMenuArea(
        items = {
            listOf(
                ContextMenuItem("轮换 Token") { onRotate() },
                ContextMenuItem("撤销网关") { onRevoke() },
            )
        },
    ) {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OnlineDot(gw.online)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        gw.name.ifBlank { "网关" },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    // 复植轮：provider 徽标（桥自报供给方标识透传；空串 = 无徽标）
                    if (gw.provider.isNotBlank()) {
                        TagChip(gw.provider, Color(0xFF777777))
                        Spacer(Modifier.width(6.dp))
                    }
                    TagChip(
                        if (gw.online) "在线" else "离线",
                        if (gw.online) Color(0xFF2E9E44) else Color(0xFF999999),
                    )
                }
                MonoText(gw.gatewayId, color = Color(0xFF999999))
                // 最近活跃：lastSeenAt = 快照字段或 gateway.online/offline 事件 ts（信封 ISO-8601），
                // 尽力格式化为本地时间、失败原样透出（formatTs 三形态兼容）
                gw.lastSeenAt?.takeIf { it.isNotBlank() }?.let { seen ->
                    Text(
                        "最近活跃 ${formatTs(seen)}",
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                    )
                }
                if (gw.agents.isEmpty()) {
                    Text(
                        "该网关暂无 active Agent",
                        fontSize = 12.sp,
                        color = Color(0xFFAAAAAA),
                    )
                } else {
                    gw.agents.forEach { agent ->
                        AgentRefRow(
                            agent = agent,
                            gatewayName = "", // showGatewayName = false：归属已由卡头表达，caption 不渲染
                            showGatewayName = false,
                            rowState = newConversationRowState(
                                gw.gatewayId, agent.agentId, submittedKeys, creatingKey,
                            ),
                            onCreateConversation = { onCreate(gw.gatewayId, agent.agentId) },
                            inGroup = isGrouped(agent.agentId),
                            onJoinGroup = { onJoinGroup(agent) },
                            onLeaveGroup = { onLeaveGroup(agent.agentId) },
                            onManageSessions = { onManageSessions(agent.agentId) },
                        )
                    }
                }
            }
        }
    }
}

/** 在线态圆点（iOS Circle().fill(gw.online ? green : secondary) 同语义）。 */
@Composable
private fun OnlineDot(online: Boolean) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                if (online) Color(0xFF2E9E44) else Color(0xFF999999),
                CircleShape,
            ),
    )
}

/**
 * 组选择器（iOS GroupPickerSheet 精简版）：现有组清单 + 当前组标记 + 「＋ 新建组…」入口。
 * 点击组即 assign（重复加入同一二元组 = 覆盖换组，DesktopAgentGroupStore.assign）。
 */
@Composable
private fun GroupPickerDialog(
    agentLabel: String,
    groups: List<AgentGroupEntry>,
    currentGroupId: String?,
    onPick: (String) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.width(300.dp).heightIn(max = 420.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("加入分组", style = MaterialTheme.typography.titleMedium)
                Text(
                    "将「$agentLabel」加入：",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                )
                if (groups.isEmpty()) {
                    Text("暂无分组", fontSize = 13.sp, color = Color(0xFFAAAAAA), modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        groups.forEach { g ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(g.id) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text("📁", fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Text(g.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (g.id == currentGroupId) {
                                    Text("当前", fontSize = 11.sp, color = Color(0xFF2E9E44))
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCreateNew) { Text("＋ 新建组…", fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

/**
 * 组名对话框（新建分组 / 重命名共用）：trim 非空防线前置到输入层（保存键禁用，空组名不产生）。
 */
@Composable
private fun GroupNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.width(320.dp).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("分组名称") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSubmit(draft) }, enabled = draft.trim().isNotEmpty()) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

/**
 * 设置页「网关与 Agent」入口卡（iOS HubSettingsView Section("网关") NavigationLink parity）：
 * 标题 + 在线计数摘要，点击跳转网关独立页（pane 由壳层切换）。
 */
@Composable
internal fun GatewaysEntryCard(
    gateways: List<UiGateway>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().clickable { onOpen() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("网关与 Agent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (gateways.isEmpty()) {
                Text("未接入", fontSize = 11.sp, color = Color(0xFF999999))
            } else {
                val onlineCount = gateways.count { it.online }
                Text(
                    "$onlineCount/${gateways.size} 在线",
                    fontSize = 11.sp,
                    color = if (onlineCount > 0) Color(0xFF2E9E44) else Color(0xFF999999),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("›", fontSize = 14.sp, color = Color(0xFF999999))
        }
    }
}

/** TextButton 内容紧内边距（节头「改名/解散」紧排用；命名常量避免每处重复构造 PaddingValues）。 */
private val DefaultTextButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
