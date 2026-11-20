package dev.sharpxia.agentreins.desktopui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sharpxia.agentreins.hub.DefaultHubClient
import dev.sharpxia.agentreins.hub.HubAuthState
import dev.sharpxia.agentreins.hub.HubException
import dev.sharpxia.agentreins.hub.UiApproval
import dev.sharpxia.agentreins.hub.UiConversation
import dev.sharpxia.agentreins.hub.UiGateway
import dev.sharpxia.agentreins.hub.auth.DeviceInfo
import dev.sharpxia.agentreins.hub.projection.AgentStatusResponse
import dev.sharpxia.agentreins.hub.avatar.AgentTint
import dev.sharpxia.agentreins.hub.avatar.AVATAR_PRESETS
import dev.sharpxia.agentreins.hub.avatar.BOT_STATE_IDLE
import dev.sharpxia.agentreins.hub.avatar.AvatarMotion
import dev.sharpxia.agentreins.hub.avatar.AvatarPalette
import dev.sharpxia.agentreins.hub.avatar.AvatarProfile
import dev.sharpxia.agentreins.hub.avatar.DEFAULT_AVATAR_PROFILE
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 设置页（wp-6 验收⑤ + wp-10 审批清单落点 + wp-11 网关入口 + wp-9 通知偏好面 + wp-14 关闭行为
 * 面）：账号/设备概览
 * + 设备清单管理（撤销他机；清单失败错误态 + 撤销失败显式降级文案可重试，wp-11 设备管理错误态）
 * + 网关与 Agent 入口卡（wp-11：快照计数摘要，点击跳网关独立页——
 * iOS 设置页 Section("网关") NavigationLink parity）
 * + 网关与 Agent 只读卡（控台轮 2026-11-11：每网关一行名称/在线态/lastSeen + agent 清单
 * label/agentId，agent 行首在线小点走 client.agentStatus（端点 #18）30s TTL 缓存；零管理动作
 * ——网关供给/轮换/撤销已搬服务端控台，iOS 设置页同款卡片 parity）+ 待审批清单（wp-9 的 desktop 呈现：全部
 * pending 审批含归属未学习行——那里没有会话页可挂，唯一可见处在此；对齐 iOS 设置页「待审批」
 * 入口）+ 通知偏好节（wp-9：总开关 + 已静音会话清单管理，静音为本地偏好跨登出保留，iOS
 * HubStore.mutedConversations 同口径）+ 关闭行为卡（wp-14：关窗最小化到托盘 / 关窗退出，
 * 本地偏好跨登出保留，缺省关窗最小化；托盘「退出」菜单不受开关影响始终真退出）+
 * 登出清库闭环（stop → runtime.logout（撤销设备 + 清凭据）
 * → wipeLocalData 七表清空；重登后从空库 bootstrap，05 §3.5 / 方案 §6-P4；静音偏好不在清库面，
 * 跨登出保留）。
 */
@Composable
internal fun SettingsScreen(
    client: DefaultHubClient,
    session: HubAuthState.LoggedIn,
    onLoggedOut: () -> Unit,
    onOpenGateways: () -> Unit,
    pendingApprovals: List<UiApproval>,
    conversations: List<UiConversation>,
    decisions: ApprovalDecisionState,
    notificationsEnabled: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    mutedIds: Set<String>,
    onUnmute: (conversationId: String) -> Unit,
    // wp-14：关闭行为开关（onMinimizeToTrayOnClose = null = 不挂卡，旧装配兼容）
    minimizeToTrayOnClose: Boolean = true,
    onMinimizeToTrayOnClose: ((Boolean) -> Unit)? = null,
    // 2026-11-19：显示偏好开关（流式输出/思考中指示；on* = null = 不挂卡，旧装配兼容——
    // 仿上两行 minimizeToTrayOnClose 先例；缺省 true = 保持既有行为）
    streamingPlayback: Boolean = true,
    onStreamingPlayback: ((Boolean) -> Unit)? = null,
    thinkingIndicator: Boolean = true,
    onThinkingIndicator: ((Boolean) -> Unit)? = null,
    // v1.5.0 个性化形象轮：形象档卡（avatarProfile = null = 不挂卡，旧装配兼容——同上先例）。
    // onSelect = 统一选择入口（预设/配色/动效档/恢复默认均组装成完整档提交，PUT 成功才落本地）。
    avatarProfile: AvatarProfile? = null,
    avatarSaving: Boolean = false,
    avatarSaveError: String? = null,
    remoteAvatarPending: AvatarProfile? = null,
    onAvatarSelect: ((AvatarProfile) -> Unit)? = null,
    onAvatarPullRemote: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busyDeviceId by remember { mutableStateOf<String?>(null) }
    // wp-11 设备管理错误态：撤销失败显式降级文案（此前 runCatching 吞错仅 reload 兜底，
    // 与同页审批卡「决策通道不可用」显式降级纪律不一致）。可重试 = 失败不清行、按钮恢复，再点「撤销」。
    var revokeError by remember { mutableStateOf<String?>(null) }
    var logoutArmed by remember { mutableStateOf(false) }
    // wp-11 网关入口计数摘要：observeGateways 单点订阅（快照 + gateway.online/offline 翻转）
    val gateways by client.observeGateways().collectAsState(initial = emptyList())

    fun describe(e: Throwable): String =
        (e as? HubException)?.let { "${it.payload.code} ${it.payload.message}" } ?: (e.message ?: e::class.simpleName ?: "未知错误")

    fun reloadDevices() {
        val stack = client.runtime.authStack ?: return
        scope.launch {
            runCatching { stack.auth.withBearer { stack.api.listDevices() } }
                .onSuccess { devices = it.devices; loadError = null }
                .onFailure { loadError = "设备清单加载失败：${describe(it)}" }
        }
    }

    LaunchedEffect(Unit) { reloadDevices() }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.headlineSmall)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("账号", style = MaterialTheme.typography.titleSmall)
                    Text("用户：${session.userDisplay ?: "（静默恢复会话）"}")
                    Text("本设备 ID：${session.deviceId ?: "未知"}")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("设备清单", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { reloadDevices() }) { Text("刷新") }
                    }
                    when {
                        loadError != null -> Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        devices == null -> Text("加载中…")
                        devices!!.isEmpty() -> Text("无设备")
                        else -> devices!!.forEach { d ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(d.displayName, style = MaterialTheme.typography.bodyMedium)
                                        if (d.current == true) {
                                            Spacer(Modifier.width(6.dp))
                                            TagChip("本机", MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    MonoText("${d.deviceId} · ${d.platform}")
                                }
                                if (d.current != true && busyDeviceId != d.deviceId) {
                                    TextButton(
                                        onClick = {
                                            val stack = client.runtime.authStack ?: return@TextButton
                                            busyDeviceId = d.deviceId
                                            scope.launch {
                                                runCatching {
                                                    stack.auth.withBearer { stack.api.deleteDevice(d.deviceId) }
                                                }.fold(
                                                    onSuccess = {
                                                        busyDeviceId = null
                                                        revokeError = null
                                                        reloadDevices()
                                                    },
                                                    onFailure = { e ->
                                                        busyDeviceId = null
                                                        revokeError = "设备撤销失败：${describe(e)}（可重试）"
                                                    },
                                                )
                                            }
                                        },
                                    ) { Text("撤销") }
                                }
                                if (busyDeviceId == d.deviceId) Text("处理中…")
                            }
                        }
                    }
                }
                // wp-11 设备管理错误态：撤销失败显式降级（重试 = 失败行仍在列，再点「撤销」）。
                if (revokeError != null) {
                    Text(
                        revokeError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            // wp-11：网关与 Agent 入口卡（iOS 设置页 Section("网关") NavigationLink parity）——
            // 计数摘要随 observeGateways 推送（在线 x / 共 y；未接入呈占位），点击跳独立页
            GatewaysEntryCard(
                gateways = gateways,
                onOpen = onOpenGateways,
            )

            // 控台轮只读卡片「网关与 Agent」（iOS 设置页「网关」Section 卡片 parity）：每网关
            // 一行（名称/在线态/lastSeen）+ agent 清单（agentId/label，行首在线小点）。卡片零
            // 管理动作——网关供给/轮换/撤销已搬服务端控台；在线点数据面 = client.agentStatus
            // （端点 #18 直调），30s TTL + 在途去重（iOS fetchCommandsIfNeeded 先例同式），
            // 失败不缓存 → 不显示点，不阻塞 UI。
            GatewayOverviewCard(gateways = gateways, client = client)

            // wp-9：通知偏好节（总开关 + 已静音会话清单管理；静音改右键菜单入口同源，此处为
            // 查看与解除的唯一管理面——iOS 设置页静音清单管理 parity）
            NotificationPreferencesCard(
                notificationsEnabled = notificationsEnabled,
                onNotificationsEnabled = onNotificationsEnabled,
                mutedIds = mutedIds,
                conversations = conversations,
                onUnmute = onUnmute,
            )

            // 2026-11-19：显示偏好卡（流式输出/思考中指示，纯显示面——数据面不动，iOS 设置页
            // 「显示」Section parity；handler 非 null 判定仿下条 closeBehaviorHandler 写法）
            val streamingPlaybackHandler = onStreamingPlayback
            val thinkingIndicatorHandler = onThinkingIndicator
            if (streamingPlaybackHandler != null && thinkingIndicatorHandler != null) {
                DisplayPreferencesCard(
                    streamingPlayback = streamingPlayback,
                    onStreamingPlayback = streamingPlaybackHandler,
                    thinkingIndicator = thinkingIndicator,
                    onThinkingIndicator = thinkingIndicatorHandler,
                )
            }

            // v1.5.0：机器人形象卡（96dp 实时预览 + 8 款预设网格 + 十色圆点 + 动效档 + 恢复默认；
            // profile = null 不挂卡——iOS HubSettingsView「机器人形象」Section parity）
            val avatarHandler = onAvatarSelect
            val avatarProfileCurrent = avatarProfile
            val avatarPullHandler = onAvatarPullRemote
            if (avatarProfileCurrent != null && avatarHandler != null && avatarPullHandler != null) {
                AvatarPreferencesCard(
                    profile = avatarProfileCurrent,
                    saving = avatarSaving,
                    saveError = avatarSaveError,
                    remotePending = remoteAvatarPending,
                    onSelect = avatarHandler,
                    onPullRemote = avatarPullHandler,
                )
            }

            // wp-14：关闭行为卡（开 = 关窗最小化到托盘，进程与同步/通知面保持运行，托盘
            // 「显示主窗」唤回；关 = 关窗直接退出进程。托盘「退出」菜单始终真退出）。
            val closeBehaviorHandler = onMinimizeToTrayOnClose
            if (closeBehaviorHandler != null) {
                CloseBehaviorCard(
                    minimizeToTrayOnClose = minimizeToTrayOnClose,
                    onMinimizeToTrayOnClose = closeBehaviorHandler,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("待审批", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        if (pendingApprovals.isNotEmpty()) {
                            TagChip("${pendingApprovals.size} 项待处理", Color(0xFFB7791F))
                        }
                    }
                    PendingApprovalsPanel(
                        approvals = pendingApprovals,
                        conversations = conversations,
                        decisions = decisions,
                        client = client,
                        scope = scope,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("退出登录", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "登出将撤销本设备（服务端 Token 即时失效）并清空全部本地数据（命令队列/游标/消息），" +
                            "重登后从空库 bootstrap。",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777777),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                if (!logoutArmed) {
                                    logoutArmed = true
                                    return@Button
                                }
                                logoutArmed = false
                                scope.launch {
                                    client.stop()
                                    client.runtime.logout() // 撤销设备 + 清凭据；state 翻转为 NeedsLogin
                                    client.wipeLocalData()
                                    onLoggedOut()
                                }
                            },
                        ) {
                            Text(if (logoutArmed) "确认登出并清库？" else "登出")
                        }
                        if (logoutArmed) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { logoutArmed = false }) { Text("取消") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * wp-14：关闭行为卡——窗口关闭按钮的行为开关（默认开 = 关窗最小化到系统托盘：进程与同步/
 * 通知面保持运行，托盘「显示主窗」可唤回；关 = 关窗直接退出进程）。行为是本地用户偏好
 * （DesktopWindowPreferences，跨登出保留，权威由 main.kt onCloseRequest 实时读取，开关
 * 即时生效）。托盘菜单的「退出」不受本开关影响，始终真正退出进程。
 */
@Composable
private fun CloseBehaviorCard(
    minimizeToTrayOnClose: Boolean,
    onMinimizeToTrayOnClose: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("关闭行为", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (minimizeToTrayOnClose) "最小化到托盘" else "直接退出",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (minimizeToTrayOnClose) Color(0xFF2E9E44) else Color(0xFF999999),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = minimizeToTrayOnClose, onCheckedChange = onMinimizeToTrayOnClose)
            }
            Text(
                "开启后点窗口关闭按钮会隐藏到系统托盘（后台同步与通知保持运行，托盘「显示主窗」" +
                    "可唤回，菜单「退出」真正退出）；关闭后关窗将直接退出应用。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF777777),
            )
        }
    }
}

/**
 * 2026-11-19：显示偏好卡——「流式输出」（开 = 流式行逐字揭示 + ▌ 光标 + 「流式生成中」标签，
 * 现状；关 = 流式增量不渲染、AI 回复等 final 权威行落库时一次呈现，生成过程与数据不受影响、
 * 已读水位照常推进）与「思考中指示」（开 = 回复占位文案「正在思考…/<agent> 正在处理中…」，
 * 现状；关 = 占位信号撤显示，停止按钮等功能面不受影响）。行为是本地用户偏好
 * （DesktopDisplayPreferences，跨登出保留，权威由 HubShell 镜像读取，开关即时生效）；
 * iOS HubSettingsView「显示」Section 同口径 parity。
 */
@Composable
private fun DisplayPreferencesCard(
    streamingPlayback: Boolean,
    onStreamingPlayback: (Boolean) -> Unit,
    thinkingIndicator: Boolean,
    onThinkingIndicator: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("流式输出", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (streamingPlayback) "逐字显示" else "完成后一次显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (streamingPlayback) Color(0xFF2E9E44) else Color(0xFF999999),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = streamingPlayback, onCheckedChange = onStreamingPlayback)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("思考中指示", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (thinkingIndicator) "已开启" else "已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (thinkingIndicator) Color(0xFF2E9E44) else Color(0xFF999999),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = thinkingIndicator, onCheckedChange = onThinkingIndicator)
            }
            Text(
                "流式输出关闭后，AI 回复将在生成完成后一次呈现（生成过程与数据不丢失，已读状态" +
                    "照常推进）；思考中指示关闭后仅撤去占位提示，停止按钮等功能不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF777777),
            )
        }
    }
}

/**
 * v1.5.0 个性化形象轮：机器人形象卡（iOS HubSettingsView「机器人形象」子页 parity）——
 * 96dp 实时预览（动效档 standard 才呼吸；off 静态）+ 8 款预设网格（44dp 缩略**静态渲染**
 * motionEnabled=false——AvatarIdleMotion 常驻循环多实例性能教训两端同守）+ 十色横排圆点
 * （strong 色，明暗随系统）+ 动效档两档开关（关闭/标准）+ 恢复默认。
 *
 * 选择即提交（低频不敏感）：预设 = 造型 + 推荐配色一并选（推荐语义），配色/动效单改保留
 * 其余维度；onSelect 组装完整档交壳层 PUT——**先 PUT 成功再更新本地**（失败显式错误可重试，
 * 不排队不回滚服务端）。编辑冲突（编辑期间收到远端更新）：顶部提示「其他设备已更新形象」
 * +一键拉取，不出发表单（设计稿 §syncMechanism 读路径④）。
 */
@Composable
private fun AvatarPreferencesCard(
    profile: AvatarProfile,
    saving: Boolean,
    saveError: String?,
    remotePending: AvatarProfile?,
    onSelect: (AvatarProfile) -> Unit,
    onPullRemote: () -> Unit,
) {
    // 档色序号（AvatarPalette 声明序 = AgentTint 序契约；批次5 换装 BotCharacter 传参用）
    val paletteIndex = AvatarPalette.entries.indexOf(profile.paletteId)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("形象档（默认造型与动效）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (saving) Text("保存中…", style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999))
            }

            // 编辑冲突提示（远端更新在途、未覆盖表单）：一键拉取收敛
            if (remotePending != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "其他设备已更新形象",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB45309),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPullRemote) { Text("拉取") }
                }
            }

            // 96dp 实时预览（批次5 换装 BotCharacter：档 12 值+色经 override 注入，seed 只余
            // 眼形/姿态/眨音取中性 botSettingsPreviewSeed；动效档 standard 才动，off 静态——A6）
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotCharacter(
                    seed = botSettingsPreviewSeed,
                    state = BOT_STATE_IDLE,
                    size = 96.dp,
                    motionEnabled = profile.motionLevel == AvatarMotion.STANDARD,
                    interactive = false,
                    faceFactorsOverride = profile.faceFactors,
                    paletteIndexOverride = paletteIndex,
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(
                        "造型 · ${AVATAR_PRESETS.firstOrNull { it.faceKey == profile.faceKey }?.zhName ?: "自定义"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "配色 · ${profile.paletteId.wire}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777777),
                    )
                    Text(
                        "动效 · ${if (profile.motionLevel == AvatarMotion.STANDARD) "标准" else "关闭"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF777777),
                    )
                }
            }

            // 预设网格 8 款（两行四列；缩略 44dp 静态；选中 = faceKey 相等）
            Text("造型", style = MaterialTheme.typography.titleSmall)
            listOf(0, 4).forEach { start ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AVATAR_PRESETS.subList(start, start + 4).forEach { preset ->
                        val selected = preset.faceKey == profile.faceKey
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelect(AvatarProfile(preset.faceFactors, preset.recommendedPalette, profile.motionLevel, profile.updatedAt)) }
                                .padding(4.dp),
                        ) {
                            BotCharacter(
                                seed = botSettingsPreviewSeed,
                                state = BOT_STATE_IDLE,
                                size = 44.dp,
                                motionEnabled = false, // 缩略静态渲染（常驻循环多实例性能纪律）
                                interactive = false,
                                faceFactorsOverride = preset.faceFactors, // AVATAR_PRESETS 双用：预设目录 12 值即缩略造型
                                paletteIndexOverride = AvatarPalette.entries.indexOf(preset.recommendedPalette),
                            )
                            Text(
                                preset.zhName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) Color(0xFF2B6CB0) else Color(0xFF777777),
                            )
                        }
                    }
                }
            }

            // 十色横排圆点（strong 色；选中描边）
            Text("配色", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AvatarPalette.entries.forEach { p ->
                    val selected = p == profile.paletteId
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(AgentTint.of(p).strongArgb(false)))
                            .border(
                                width = if (selected) 2.5.dp else 1.dp,
                                color = if (selected) Color(0xFF2B6CB0) else Color(0xFFCCCCCC),
                                shape = CircleShape,
                            )
                            .clickable { onSelect(AvatarProfile(profile.faceFactors, p, profile.motionLevel, profile.updatedAt)) },
                    )
                }
            }

            // 动效档（关闭 = 静止仅入场淡入+表情即时切换；标准 = +待机呼吸+过渡动画）
            Text("动效", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(AvatarMotion.OFF to "关闭", AvatarMotion.STANDARD to "标准").forEach { (motion, label) ->
                    val selected = motion == profile.motionLevel
                    OutlinedButton(
                        onClick = { onSelect(AvatarProfile(profile.faceFactors, profile.paletteId, motion, profile.updatedAt)) },
                        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                            contentColor = if (selected) Color(0xFF2B6CB0) else Color(0xFF777777),
                        ),
                        border = androidx.compose.material3.ButtonDefaults.outlinedButtonBorder.takeIf { !selected },
                    ) {
                        Text(if (selected) "$label ●" else label)
                    }
                }
            }

            TextButton(onClick = {
                onSelect(AvatarProfile(DEFAULT_AVATAR_PROFILE.faceFactors, AvatarPalette.GRAY, AvatarMotion.STANDARD, profile.updatedAt))
            }) { Text("恢复默认") }

            if (saveError != null) {
                Text(
                    "保存失败：$saveError（可重试）",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * wp-9：通知偏好卡——总开关（关闭 = 本机不再弹任何通知，含审批提醒；开关即时生效，协调器
 * 经 DesktopMutePreferences 实时读取）+ 已静音会话清单（名称解析与协调器 displayLabel 同式：
 * label 空 → agentId → conversationId 兜底；解除静音即时生效）。静音为本地偏好，不在登出
 * 清库面（跨登出保留，iOS HubStore 同口径）。
 */
@Composable
private fun NotificationPreferencesCard(
    notificationsEnabled: Boolean,
    onNotificationsEnabled: (Boolean) -> Unit,
    mutedIds: Set<String>,
    conversations: List<UiConversation>,
    onUnmute: (conversationId: String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("通知", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    if (notificationsEnabled) "已开启" else "已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (notificationsEnabled) Color(0xFF2E9E44) else Color(0xFF999999),
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = notificationsEnabled, onCheckedChange = onNotificationsEnabled)
            }
            Text(
                "关闭后本机不再弹出任何本地通知（含审批提醒）。按会话静音在会话列表右键设置：" +
                    "静音的会话不再提醒新回复与运行终态，审批提醒不受静音影响。",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF777777),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("已静音会话", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(8.dp))
                if (mutedIds.isNotEmpty()) TagChip("${mutedIds.size} 个", Color(0xFF777777))
            }
            if (mutedIds.isEmpty()) {
                Text(
                    "无静音会话（右键会话卡片可静音）",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF999999),
                )
            } else {
                mutedIds.forEach { id ->
                    val conv = conversations.firstOrNull { it.conversationId == id }
                    val label = conv?.let { it.label.ifBlank { it.agentId.ifBlank { id } } } ?: id
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1)
                        TextButton(onClick = { onUnmute(id) }) { Text("解除静音") }
                    }
                }
            }
        }
    }
}

/** #18 在线点缓存 TTL（毫秒；iOS HubStore.agentStatusCacheTTL 同口径，30 秒）。 */
private const val AgentStatusTtlMs = 30_000L

/** #18 在线点轮循周期（与 TTL 同值：每轮先补拉过期键再睡一周期，新鲜键空转）。 */
private const val AgentStatusRefreshMs = 30_000L

/**
 * 控台轮只读「网关与 Agent」卡（iOS HubSettingsView「网关」Section 卡片 parity）：每网关一行
 * （在线点 + 名称 + 在线/离线 + lastSeen）+ agent 清单（label/agentId + 行首在线小点）。
 * 在线点数据面 = [DefaultHubClient.agentStatus]（端点 #18 纯投影查询）：30s TTL + 在途去重
 * （iOS fetchCommandsIfNeeded 缓存先例同式），失败不缓存 → 该行不显示点，绝不阻塞渲染；
 * gatewayOnline 是 #18 唯一在线信号（agent 无独立在线态，随网关连接收口）。卡片零管理动作。
 */
@Composable
private fun GatewayOverviewCard(
    gateways: List<UiGateway>,
    client: DefaultHubClient,
) {
    // #18 缓存（键 = conversationEntryKey，即 gatewayId|agentId）：statuses 进组合（变更驱动
    // 行重渲）；fetchedAt/inFlight 只做 TTL 判定与去重、无渲染依赖，用普通 remember 容器
    // （仅主线程协程读写，Compose rememberCoroutineScope 默认 Main 派发）。
    var statuses by remember { mutableStateOf<Map<String, AgentStatusResponse>>(emptyMap()) }
    val fetchedAt = remember { mutableMapOf<String, Long>() }
    val inFlight = remember { mutableSetOf<String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(gateways) {
        while (true) {
            gateways.forEach { gw ->
                gw.agents.forEach { agent ->
                    val key = conversationEntryKey(gw.gatewayId, agent.agentId)
                    val now = System.currentTimeMillis()
                    val fetched = fetchedAt[key]
                    if (key in inFlight || (fetched != null && now - fetched < AgentStatusTtlMs)) {
                        return@forEach
                    }
                    inFlight += key
                    scope.launch {
                        val r = runCatching { client.agentStatus(gw.gatewayId, agent.agentId) }
                        inFlight -= key
                        r.getOrNull()?.let { status ->
                            statuses = statuses + (key to status)
                            fetchedAt[key] = System.currentTimeMillis()
                        }
                    }
                }
            }
            delay(AgentStatusRefreshMs)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("网关与 Agent", style = MaterialTheme.typography.titleSmall)
            if (gateways.isEmpty()) {
                Text("未接入", style = MaterialTheme.typography.bodySmall, color = Color(0xFF999999))
            }
            gateways.forEach { gw ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (gw.online) Color(0xFF2E9E44) else Color(0xFF999999),
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            gw.name.ifBlank { "网关" },
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (gw.online) "在线" else "离线",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (gw.online) Color(0xFF2E9E44) else Color(0xFF999999),
                        )
                    }
                    // 最近活跃：lastSeenAt = 快照字段或 gateway.online/offline 事件 ts（信封
                    // ISO-8601），formatTs 尽力本地化、失败原样透出（GatewayCard 同式）。
                    gw.lastSeenAt?.takeIf { it.isNotBlank() }?.let { seen ->
                        Text("最近活跃 ${formatTs(seen)}", fontSize = 11.sp, color = Color(0xFF888888))
                    }
                    if (gw.agents.isEmpty()) {
                        Text("该网关暂无 active Agent", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                    } else {
                        gw.agents.forEach { agent ->
                            // 无缓存（未拉到/拉取失败）不渲染点——「拉不到就不显示点」纪律。
                            val status = statuses[conversationEntryKey(gw.gatewayId, agent.agentId)]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (status != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(
                                                if (status.gatewayOnline) Color(0xFF2E9E44) else Color(0xFF999999),
                                                CircleShape,
                                            ),
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(agent.label.ifBlank { agent.agentId }, fontSize = 13.sp)
                                    Text(agent.agentId, fontSize = 10.sp, color = Color(0xFF999999), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
