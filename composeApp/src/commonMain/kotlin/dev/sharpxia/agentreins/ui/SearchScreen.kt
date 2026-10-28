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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.openclaw.SearchHit
import dev.sharpxia.agentreins.ui.components.EmptyState
import dev.sharpxia.agentreins.ui.components.SectionHeader
import dev.sharpxia.agentreins.ui.components.SandCard
import dev.sharpxia.agentreins.ui.components.SandDivider
import dev.sharpxia.agentreins.ui.components.UnreadBadge
import dev.sharpxia.agentreins.ui.components.sandPressFill
import dev.sharpxia.agentreins.ui.theme.InkTheme
import kotlinx.coroutines.delay

/**
 * 搜索 tab（M3.6）：上下双源。
 * 上=客户端会话即时过滤（标题/预览/key/agent 四路，秒出，离线可用）；
 * 下=服务端消息全文（sessions.search，M3.6 探针证实可用），400ms 去抖、联网才出。
 */
@Composable
fun SearchScreen(
    model: AppModel,
    onOpenSession: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val results by remember {
        derivedStateOf {
            val q = query.trim()
            if (q.isEmpty()) emptyList()
            else model.sessions.filter { s ->
                val hay = listOfNotNull(s.title, s.preview, s.id, s.agentId).joinToString(" ")
                q.split(" ").all { term -> hay.contains(term, ignoreCase = true) }
            }
        }
    }
    // 服务端搜索去抖：停手 400ms 才发；清空/过短立即收
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            model.searchServer("")
        } else {
            delay(400)
            model.searchServer(q)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "搜索",
            style = InkTheme.type.headingMd,
            color = InkTheme.colors.ink,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        InkSearchField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        when {
            query.isBlank() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "搜索会话与消息",
                subtitle = "会话名即时过滤；联网后可全文搜历史消息",
            )
            results.isEmpty() && model.searchHits.isEmpty() && !model.searching -> EmptyState(
                icon = Icons.Filled.Search,
                title = "无匹配结果",
                subtitle = "换个关键词试试",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                if (results.isNotEmpty()) {
                    item { SectionHeader("会话 · ${results.size}") }
                    items(results, key = { "s:${it.id}" }) { s ->
                        SearchResultRow(s, onClick = {
                            model.select(s)
                            onOpenSession(s.id)
                        })
                    }
                }
                if (model.searching) {
                    item {
                        Text(
                            "正在搜索历史消息…",
                            style = InkTheme.type.textXs,
                            color = InkTheme.colors.textTertiary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                val hits = model.searchHits
                if (hits.isNotEmpty()) {
                    item { SectionHeader("历史消息 · ${hits.size}") }
                    itemsIndexed(hits, key = { idx, hit -> "h:$idx:${hit.sessionKey}:${hit.messageId}" }) { _, hit ->
                        SearchHitRow(
                            model = model,
                            hit = hit,
                            query = query,
                            onClick = {
                                model.openSessionByKey(hit.sessionKey)
                                onOpenSession(hit.sessionKey)
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** 墨水风搜索框：elevated 底、无边框、聚焦不发光（M3.6 按压/过场 pass 一并替换 Material 描边框）。 */
@Composable
private fun InkSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(RoundedCornerShape(InkTheme.shapes.full.dp))
            .background(InkTheme.colors.elevated)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = InkTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = InkTheme.type.textMd.copy(color = InkTheme.colors.ink),
            cursorBrush = SolidColor(InkTheme.colors.ink),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            "会话 / 历史消息关键词",
                            style = InkTheme.type.textMd,
                            color = InkTheme.colors.textTertiary,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SearchResultRow(session: Session, onClick: () -> Unit) {
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
            Text(
                session.title ?: session.id,
                style = InkTheme.type.textLg,
                color = InkTheme.colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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

/** 服务端命中行：会话名 + 角色胶囊 + 摘要（命中词高亮）+ 相对时间。点击直达该会话。 */
@Composable
private fun SearchHitRow(
    model: AppModel,
    hit: SearchHit,
    query: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val session = model.sessions.firstOrNull { it.id == hit.sessionKey }
    val snippet = remember(hit.snippet, query) { highlightTerms(hit.snippet.replace('\n', ' '), query) }
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
                Text(
                    session?.title ?: hit.sessionKey.substringAfterLast(':'),
                    style = InkTheme.type.textLg,
                    color = InkTheme.colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 角色胶囊（review R2-P1-4）：与裸时间文本拉开层级
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .clip(RoundedCornerShape(InkTheme.shapes.full.dp))
                        .border(1.dp, InkTheme.colors.borderSecondary, RoundedCornerShape(InkTheme.shapes.full.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        if (hit.role == "user") "你" else "Agent",
                        style = InkTheme.type.textXs,
                        color = InkTheme.colors.textTertiary,
                    )
                }
                // 缺时间戳（0）不显示，防「19xxx天前」（review R3-P2-7）
                hit.timestampMs.takeIf { it > 0L }
                    ?.let { formatRelativeTime(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let {
                        Text(
                            it,
                            style = InkTheme.type.textXs,
                            color = InkTheme.colors.textDisabled,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
            }
            Text(
                snippet,
                style = InkTheme.type.textSm,
                color = InkTheme.colors.textTertiary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 命中词高亮（review R2-P1-3）：query 词（≥2 字符）墨色加粗，其余保持副文本灰。 */
private fun highlightTerms(text: String, query: String): AnnotatedString {
    val terms = query.trim().split(Regex("\\s+")).filter { it.length >= 2 }
    if (terms.isEmpty()) return AnnotatedString(text)
    val regex = runCatching {
        Regex(terms.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    }.getOrNull() ?: return AnnotatedString(text)
    return buildAnnotatedString {
        var last = 0
        for (m in regex.findAll(text)) {
            append(text.substring(last, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.value) }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}
