package dev.sharpxia.agentreins.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.ui.ArIcons
import dev.sharpxia.agentreins.ui.theme.InkTheme

/**
 * M3 Sand 范式组件首批（逆向报告 §6：iOS Settings 式「分组列表 + Row」为主、
 * 「Card 承载富内容」）。全部从 InkTheme 取令牌，不散写硬编码。
 */

/** 按压填充（iOS pressFill：瞬时高亮，无 Material ripple 扩散）。 */
@Composable
fun Modifier.sandPressFill(interactionSource: MutableInteractionSource, cornerRadius: Int): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    return clip(RoundedCornerShape(cornerRadius.dp)).let { base ->
        if (pressed) base.background(InkTheme.colors.surfaceTertiary) else base
    }
}

/** 分组卡片：elevated 底 + lg 圆角 + 次级描边。承载富内容与 Row 分组。 */
@Composable
fun SandCard(
    modifier: Modifier = Modifier,
    corner: Int = InkTheme.shapes.lg,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(corner.dp))
            .background(InkTheme.colors.elevated)
            .border(1.dp, InkTheme.colors.borderSecondary, RoundedCornerShape(corner.dp)),
        content = content,
    )
}

/**
 * Settings 式行：前导槽 + 标题/副标题 + 尾部槽。iOS 44pt 最低行高；
 * onClick 可空（纯展示行）；按压填充整行。
 */
@Composable
fun SandRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    showChevron: Boolean = onClick != null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = InkTheme.colors
    val type = InkTheme.type
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .let { m ->
                if (onClick != null && enabled) {
                    m.sandPressFill(interaction, cornerRadius = 0)
                        .clickable(interactionSource = interaction, indication = null) { onClick() }
                } else m
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leading != null) leading(this)
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = type.textLg,
                color = if (enabled) colors.ink else colors.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = type.textSm,
                    color = colors.textTertiary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing(this)
        if (showChevron) Icon(
            ArIcons.Chevron,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** iOS 分组节标题（列表上方小字说明）。 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = InkTheme.type.textSm,
        color = InkTheme.colors.textTertiary,
    )
}

/** Agent 标题胶囊（AgentTitlePill）：tint pill 底 + strong 文本。 */
@Composable
fun AgentTitlePill(
    name: String,
    tint: Color,
    pillFill: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(InkTheme.shapes.full.dp))
            .background(pillFill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
            Text(name, style = InkTheme.type.textXs, color = tint, maxLines = 1)
        }
    }
}

/** 空态：图标 + 标题 + 副文 + 可选 CTA。 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    cta: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = InkTheme.colors.textTertiary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(4.dp))
        Text(title, style = InkTheme.type.headingSm, color = InkTheme.colors.ink)
        if (subtitle != null) {
            Text(
                subtitle,
                style = InkTheme.type.textSm,
                color = InkTheme.colors.textTertiary,
                modifier = Modifier.heightIn(min = 0.dp),
            )
        }
        if (cta != null) {
            Spacer(Modifier.width(8.dp))
            cta()
        }
    }
}

/** 组内分隔线（Row 之间，起点缩进对齐文本）。 */
@Composable
fun SandDivider(modifier: Modifier = Modifier, indent: Int = 16) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = indent.dp)
            .heightIn(1.dp)
            .background(InkTheme.colors.borderTertiary),
    )
}
