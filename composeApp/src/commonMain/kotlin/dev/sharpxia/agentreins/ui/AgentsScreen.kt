package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AgentRegistry
import dev.sharpxia.agentreins.AgentSummary
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.model.sessionKeyAgentId
import dev.sharpxia.agentreins.ui.avatar.AgentExpression
import dev.sharpxia.agentreins.ui.avatar.DefaultFaceFactors
import dev.sharpxia.agentreins.ui.avatar.FaceSculptDialog
import dev.sharpxia.agentreins.ui.avatar.RobotAvatar
import dev.sharpxia.agentreins.ui.avatar.agentFaceFactorsOf
import dev.sharpxia.agentreins.ui.avatar.avatarExpressionOf
import dev.sharpxia.agentreins.ui.components.AgentTitlePill
import dev.sharpxia.agentreins.ui.components.EmptyState
import dev.sharpxia.agentreins.ui.components.SandCard
import dev.sharpxia.agentreins.ui.components.SandSheet
import dev.sharpxia.agentreins.ui.components.UnreadBadge
import dev.sharpxia.agentreins.ui.components.sandPressFill
import dev.sharpxia.agentreins.ui.theme.AgentTintColors
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.AgentTintScope
import dev.sharpxia.agentreins.ui.theme.InkTheme
import dev.sharpxia.agentreins.ui.theme.LocalAgentTint
import dev.sharpxia.agentreins.ui.theme.agentTint

/**
 * M3.1 Agents tab：agent 分组入口（tint 头像 + 名称 + 会话数/未读/最近活跃），
 * 点入 Agent 域页（该 agent 的会话组 + 身份色/别名定制）。
 * 名称与 tint 是本地个性化（协议无 agent 写入面），kv 持久化。
 */
@Composable
fun AgentsScreen(
    model: AppModel,
    onOpenAgent: (String) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val summaries = remember(model.sessions, model.agentProfiles) {
        AgentRegistry.summarize(model.sessions, model.agentProfiles)
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Agents", style = InkTheme.type.headingMd, color = InkTheme.colors.ink)
                Text(
                    if (model.status is dev.sharpxia.agentreins.model.GatewayStatus.Connected) "${summaries.size} 个 agent" else "离线中 · 显示缓存",
                    style = InkTheme.type.textSm,
                    color = InkTheme.colors.textTertiary,
                )
            }
        }
        if (summaries.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.AccountCircle,
                title = "尚无 agent",
                subtitle = "连接网关后，有会话往来的 agent 会出现在这里",
            )
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(summaries, key = { it.profile.agentId }) { s ->
                AgentSummaryRow(
                    s,
                    expression = avatarExpressionOf(s.profile.agentId, model.agentProfiles),
                    faceFactors = agentFaceFactorsOf(s.profile.agentId, model.agentProfiles),
                    onClick = { onOpenAgent(s.profile.agentId) },
                )
            }
        }
    }
}

@Composable
private fun AgentSummaryRow(s: AgentSummary, expression: AgentExpression, faceFactors: List<Double>, onClick: () -> Unit) {
    AgentTintScope(s.profile.tint) {
        val tint = LocalAgentTint.current
        val interaction = remember { MutableInteractionSource() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .sandPressFill(interaction, 0)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            AgentAvatar(tint, s.profile.name, expression = expression, faceFactors = faceFactors)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.profile.name,
                        style = InkTheme.type.headingXs,
                        color = InkTheme.colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    formatRelativeTime(s.lastActiveAtMs).takeIf { it.isNotEmpty() }?.let {
                        Spacer(Modifier.size(6.dp))
                        Text(it, style = InkTheme.type.textXs, color = InkTheme.colors.textTertiary)
                    }
                }
                Text(
                    buildString {
                        append("${s.sessionCount} 个会话")
                        if (s.unreadTotal > 0) append(" · ${s.unreadTotal} 条未读")
                    },
                    style = InkTheme.type.textSm,
                    color = InkTheme.colors.textTertiary,
                )
            }
            if (s.unreadTotal > 0) UnreadBadge(s.unreadTotal)
        }
    }
}

/**
 * agent 头像：黏土 blob Canvas 薄壳（RobotAvatar 渲染，face-sculpt §1/§2）。
 * 首字符圆底旧实现整段删除、不留双轨；name 参数仅为保签名兼容。
 * faceFactors 必须由调用点解析后显式传入（渲染器零解析）。
 */
@Composable
fun AgentAvatar(
    tint: AgentTintColors,
    name: String,
    size: Int = 42,
    expression: AgentExpression = AgentExpression.CALM,
    faceFactors: List<Double> = DefaultFaceFactors,
) {
    RobotAvatar(
        palette = tint.name,
        expression = expression,
        size = size,
        isDark = InkTheme.colors.isDark,
        faceFactors = faceFactors,
    )
}

/** Agent 域页：身份头（改名/选色）+ 该 agent 的会话组（M4：sessions 管理落点）。 */
@Composable
fun AgentDetailScreen(
    model: AppModel,
    agentId: String,
    onOpenSession: (String) -> Unit,
    onOpenConversation: (convId: String, anchorSessionKey: String?) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val profile = model.agentProfiles[agentId] ?: dev.sharpxia.agentreins.AgentProfile.default(agentId)
    val sessions = remember(model.sessions, agentId) { AgentRegistry.sessionsOf(agentId, model.sessions) }
    var editOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = InkTheme.colors.ink)
            }
            Text("Agent", style = InkTheme.type.headingSm, color = InkTheme.colors.ink)
        }
        AgentTintScope(profile.tint) {
            val tint = LocalAgentTint.current
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { editOpen = true }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AgentAvatar(
                        tint,
                        profile.name,
                        size = 52,
                        expression = avatarExpressionOf(agentId, model.agentProfiles),
                        faceFactors = agentFaceFactorsOf(agentId, model.agentProfiles),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(profile.name, style = InkTheme.type.headingMd, color = InkTheme.colors.ink)
                        Text(agentId, style = InkTheme.type.textSm, color = InkTheme.colors.textTertiary)
                    }
                    Icon(Icons.Filled.Edit, contentDescription = "编辑", tint = InkTheme.colors.textTertiary, modifier = Modifier.size(18.dp))
                }
                AgentTitlePill("agent", tint.strong, tint.pill)
            }
        }
        Text(
            "会话 · ${sessions.size}",
            style = InkTheme.type.textSm,
            color = InkTheme.colors.textTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(sessions, key = { it.id }) { s ->
                AgentSessionRow(s, onClick = {
                    // M4 反馈②③：主线/渠道会话行 → 对应 conversation（编辑页是渠道对话的
                    // 唯一入口）；派生/系统/未识别行保持 per-session chat（治理用途）
                    when (dev.sharpxia.agentreins.openclaw.SessionClassifier.classify(s.id)) {
                        dev.sharpxia.agentreins.openclaw.SessionCategory.MAINLINE ->
                            onOpenConversation(
                                dev.sharpxia.agentreins.openclaw.ConversationRepository.convId(agentId),
                                s.id,
                            )
                        dev.sharpxia.agentreins.openclaw.SessionCategory.CHANNEL -> {
                            val bucket = dev.sharpxia.agentreins.openclaw.SessionClassifier
                                .channelBucketOf(s.id) ?: "channel"
                            onOpenConversation(
                                dev.sharpxia.agentreins.openclaw.ConversationRepository.convId(agentId, bucket),
                                s.id,
                            )
                        }
                        else -> {
                            model.select(s)
                            onOpenSession(s.id)
                        }
                    }
                })
            }
        }
    }

    if (editOpen) {
        AgentEditSheet(
            model = model,
            agentId = agentId,
            onDismiss = { editOpen = false },
        )
    }
}

/** 身份定制 Sheet：实时预览 + 别名输入 + 10 色选择 + 2×5 表情网格（规格 §10，即点即存）。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AgentEditSheet(model: AppModel, agentId: String, onDismiss: () -> Unit) {
    val profile = model.agentProfiles[agentId] ?: dev.sharpxia.agentreins.AgentProfile.default(agentId)
    var name by remember { mutableStateOf(profile.name) }
    // 预览/网格选中态一律走解析入口（§10.1/§10.3）：未定制时 profile.expression == ""，
    // 直读原始串会渲染不出派生表情、网格会判成全不选中；点击后 agentProfiles 变更即重组刷新
    val expression = avatarExpressionOf(agentId, model.agentProfiles)
    // face 同口径：解析后的系数表显式传渲染器（空串/非法 = 正圆），渲染器零解析
    val faceFactors = agentFaceFactorsOf(agentId, model.agentProfiles)
    var sculptOpen by remember { mutableStateOf(false) }
    SandSheet(onDismiss = onDismiss, title = "编辑 Agent") {
        // 复审修复：预览(64dp)+2×5 表情网格+按钮行总高约 500dp，矮视口（横屏/iPad 分屏/桌面
        // 窄窗）下超出 M3 ModalBottomSheet 可用高度会被静默裁切且滚不到——SandSheet 内容
        // Column（SandSheet.kt:54）无纵向滚动，这里只给本 Sheet 内容包一层纵向滚动，
        // SandSheet 本体不动（不波及既有其余 Sheet）；内部滚动与 sheet 的纵向展开手势嵌套不冲突
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            // 实时预览（§10.1）：标题下、显示名输入框上方，64dp，当前选中 tint × 表情
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                RobotAvatar(
                    palette = profile.tint,
                    expression = expression,
                    size = 64,
                    isDark = InkTheme.colors.isDark,
                    faceFactors = faceFactors,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("显示名") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "身份色",
                style = InkTheme.type.textSm,
                color = InkTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AgentTintName.entries.forEach { tintName ->
                    val t = agentTint(tintName, InkTheme.colors.isDark)
                    val selected = profile.tint == tintName
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(t.strong)
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) InkTheme.colors.ink else InkTheme.colors.borderPrimary,
                                CircleShape,
                            )
                            .clickable {
                                model.setAgentTint(agentId, tintName)
                                model.setAgentName(agentId, name)
                            },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            Text(
                "表情",
                style = InkTheme.type.textSm,
                color = InkTheme.colors.textTertiary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            // 表情网格（§10.3）：2 行 × 5，顺序 = AgentExpression 声明序；44dp 缩略 ≥ 40 天然完整版；
            // 选中描边与色点同款（未选 1pt borderPrimary / 选中 3pt ink）；点击即存并同句带动名防丢
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AgentExpression.entries.chunked(5).forEach { rowEntries ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        rowEntries.forEach { expr ->
                            val selected = expr == expression
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    model.setAgentExpression(agentId, expr)
                                    model.setAgentName(agentId, name)
                                },
                            ) {
                                Box(
                                    Modifier
                                        .size(50.dp)
                                        .border(
                                            if (selected) 3.dp else 1.dp,
                                            if (selected) InkTheme.colors.ink else InkTheme.colors.borderPrimary,
                                            RoundedCornerShape(12.dp),
                                        )
                                        .padding(2.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    RobotAvatar(
                                        palette = profile.tint,
                                        expression = expr,
                                        size = 44,
                                        isDark = InkTheme.colors.isDark,
                                        faceFactors = faceFactors,
                                    )
                                }
                                Text(
                                    expressionLabel(expr),
                                    style = InkTheme.type.textXs,
                                    color = InkTheme.colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
            // 捏脸（face-sculpt §5）：独立全屏窗口，即点即存
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                TextButton(onClick = { sculptOpen = true }) { Text("捏脸", color = InkTheme.colors.ink) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消", color = InkTheme.colors.ink) }
                TextButton(onClick = {
                    model.setAgentName(agentId, name)
                    onDismiss()
                }) { Text("保存", color = InkTheme.colors.ink) }
            }
        }
    }

    if (sculptOpen) {
        FaceSculptDialog(model = model, agentId = agentId, onDismiss = { sculptOpen = false })
    }
}

/** 表情中文名对照（规格 §4，编辑网格 label）。 */
private fun expressionLabel(e: AgentExpression): String = when (e) {
    AgentExpression.CALM -> "平静"
    AgentExpression.HAPPY -> "开心"
    AgentExpression.EXCITED -> "兴奋"
    AgentExpression.THINKING -> "思考"
    AgentExpression.CONFUSED -> "困惑"
    AgentExpression.SLEEPY -> "犯困"
    AgentExpression.SAD -> "难过"
    AgentExpression.SMUG -> "得意"
    AgentExpression.SURPRISED -> "惊讶"
    AgentExpression.LOVE -> "喜爱"
}

/** Agent 域内的会话行（M3.2 会与主列表行统一重绘）。 */
@Composable
private fun AgentSessionRow(session: Session, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .sandPressFill(interaction, 0)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.pinned) {
                    Icon(
                        ArIcons.Pin,
                        contentDescription = "已置顶",
                        tint = InkTheme.colors.textTertiary,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    session.title ?: session.id,
                    style = InkTheme.type.textLg,
                    fontWeight = if (session.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = InkTheme.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                formatRelativeTime(session.lastActiveAtMs).takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.size(6.dp))
                    Text(it, style = InkTheme.type.textXs, color = InkTheme.colors.textTertiary)
                }
            }
            Text(
                session.preview?.takeIf { it.isNotBlank() } ?: session.id,
                style = InkTheme.type.textSm,
                color = InkTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (session.unread > 0) {
            Spacer(Modifier.size(8.dp))
            UnreadBadge(session.unread)
        }
    }
}
