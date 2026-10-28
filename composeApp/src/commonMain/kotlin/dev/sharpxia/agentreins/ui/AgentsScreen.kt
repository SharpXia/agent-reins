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
import androidx.compose.foundation.shape.CircleShape
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
                AgentSummaryRow(s, onClick = { onOpenAgent(s.profile.agentId) })
            }
        }
    }
}

@Composable
private fun AgentSummaryRow(s: AgentSummary, onClick: () -> Unit) {
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
            AgentAvatar(tint, s.profile.name)
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

/** agent 头像：tint 圆底 + 首字符。 */
@Composable
fun AgentAvatar(tint: AgentTintColors, name: String, size: Int = 42) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.bubble)
            .border(1.dp, tint.strong.copy(alpha = 0.25f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = InkTheme.type.headingSm,
            color = tint.strong,
        )
    }
}

/** Agent 域页：身份头（改名/选色）+ 该 agent 的会话组。 */
@Composable
fun AgentDetailScreen(
    model: AppModel,
    agentId: String,
    onOpenSession: (String) -> Unit,
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
                    AgentAvatar(tint, profile.name, size = 52)
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
                    model.select(s)
                    onOpenSession(s.id)
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

/** 身份定制 Sheet：别名输入 + 10 色选择。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AgentEditSheet(model: AppModel, agentId: String, onDismiss: () -> Unit) {
    val profile = model.agentProfiles[agentId] ?: dev.sharpxia.agentreins.AgentProfile.default(agentId)
    var name by remember { mutableStateOf(profile.name) }
    SandSheet(onDismiss = onDismiss, title = "编辑 Agent") {
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("取消", color = InkTheme.colors.ink) }
            TextButton(onClick = {
                model.setAgentName(agentId, name)
                onDismiss()
            }) { Text("保存", color = InkTheme.colors.ink) }
        }
    }
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
