package dev.sharpxia.agentreins.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.model.Session
import dev.sharpxia.agentreins.ui.components.EmptyState
import dev.sharpxia.agentreins.ui.components.UnreadBadge
import dev.sharpxia.agentreins.ui.theme.InkTheme

/**
 * 搜索 tab（M3.1 = 会话即时过滤：标题/预览/key/agentId 四路匹配，客户端缓存秒出）。
 * M3.2+ 增强：消息全文与工具结果（服务端 sessions.search 协作）。
 */
@Composable
fun SearchScreen(
    model: AppModel,
    onOpenSession: (String) -> Unit,
) {
    var query by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val results by remember {
        androidx.compose.runtime.derivedStateOf {
            val q = query.trim()
            if (q.isEmpty()) emptyList()
            else model.sessions.filter { s ->
                val hay = listOfNotNull(s.title, s.preview, s.id, s.agentId).joinToString(" ")
                q.split(" ").all { term -> hay.contains(term, ignoreCase = true) }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "搜索",
            style = InkTheme.type.headingMd,
            color = InkTheme.colors.ink,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("会话名 / 预览 / key / agent", style = InkTheme.type.textMd) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = InkTheme.colors.textTertiary) },
            singleLine = true,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(InkTheme.shapes.full.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        when {
            query.isBlank() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "搜索会话",
                subtitle = "输入关键词，实时过滤本机缓存的全部会话",
            )
            results.isEmpty() -> EmptyState(
                icon = Icons.Filled.Search,
                title = "无匹配会话",
                subtitle = "换个关键词试试",
            )
            else -> {
                Text(
                    "${results.size} 个结果",
                    style = InkTheme.type.textSm,
                    color = InkTheme.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
                LazyColumn(Modifier.fillMaxSize()) {
                    items(results, key = { it.id }) { s ->
                        SearchResultRow(s, onClick = {
                            model.select(s)
                            onOpenSession(s.id)
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(session: Session, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            UnreadBadge(session.unread)
        }
    }
}
