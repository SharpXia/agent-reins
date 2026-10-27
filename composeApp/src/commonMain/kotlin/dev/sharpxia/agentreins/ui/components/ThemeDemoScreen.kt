package dev.sharpxia.agentreins.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.ui.ArIcons
import dev.sharpxia.agentreins.ui.theme.AgentTintName
import dev.sharpxia.agentreins.ui.theme.FuncColors
import dev.sharpxia.agentreins.ui.theme.InkTheme
import dev.sharpxia.agentreins.ui.theme.agentTint

/**
 * M3.0 验收页：墨水令牌全样本 + 亮暗手动切换（真机双主题验收用）。
 * M3.1 导航重构后挂进设置域作调试入口，不进正式信息架构。
 */
@Composable
fun ThemeDemoScreen(modifier: Modifier = Modifier) {
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(systemDark) }
    InkTheme(darkTheme = dark) {
        val c = InkTheme.colors
        Column(
            modifier
                .fillMaxSize()
                .background(c.paper)
                .statusBarsPadding(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ink 令牌", style = InkTheme.type.headingSm, color = c.ink)
                Spacer(Modifier.weight(1f))
                Text(
                    if (dark) "暗 ● 切亮" else "亮 ○ 切暗",
                    style = InkTheme.type.textSm,
                    color = c.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(InkTheme.shapes.full.dp))
                        .background(c.surfaceTertiary)
                        .clickable { dark = !dark }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                NeutralSection()
                FuncSection()
                TintSection()
                TypeSection()
                ShapeSection()
                ComponentSection()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun NeutralSection() {
    val c = InkTheme.colors
    SectionHeader("中性骨架")
    SandCard(Modifier.padding(horizontal = 16.dp)) {
        SwatchRow("ink / text-primary", c.ink)
        SwatchRow("text-secondary 68/66%", c.textSecondary)
        SwatchRow("text-tertiary 56/54%", c.textTertiary)
        SwatchRow("text-disabled 40/38%", c.textDisabled)
        SwatchRow("paper（页面底）", c.paper)
        SwatchRow("elevated（卡片底）", c.elevated)
        SwatchRow("surfaceTertiary 6/4%", c.surfaceTertiary)
        SwatchRow("inverse（主按钮）", c.inverse)
        SwatchRow("borderPrimary 12/10%", c.borderPrimary)
    }
}

@Composable
private fun FuncSection() {
    val c = InkTheme.colors
    SectionHeader("功能色四件套")
    SandCard(Modifier.padding(horizontal = 16.dp)) {
        FuncRow("info 蓝", c.info)
        FuncRow("danger 红", c.danger)
        FuncRow("success 绿", c.success)
        FuncRow("warning 橙", c.warning)
    }
}

@Composable
private fun TintSection() {
    val c = InkTheme.colors
    SectionHeader("botTint 10 色（身份色）")
    SandCard(Modifier.padding(horizontal = 16.dp)) {
        AgentTintName.entries.forEach { name ->
            val t = agentTint(name, c.isDark)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(t.strong))
                Box(
                    Modifier.size(14.dp).clip(CircleShape).background(t.bubble)
                        .border(1.dp, c.borderSecondary, CircleShape),
                )
                Text(name.name.lowercase(), style = InkTheme.type.textMd, color = c.ink)
                Spacer(Modifier.weight(1f))
                AgentTitlePill("agent", t.strong, t.pill)
            }
        }
    }
}

@Composable
private fun TypeSection() {
    val t = InkTheme.type
    val c = InkTheme.colors
    SectionHeader("字阶 8 级")
    SandCard(Modifier.padding(horizontal = 16.dp)) {
        listOf(
            "text-xs 12/16" to t.textXs,
            "text-sm 13/18" to t.textSm,
            "text-md 14/20" to t.textMd,
            "text-lg 15/22" to t.textLg,
            "heading-xs 15/22" to t.headingXs,
            "heading-sm 17/24" to t.headingSm,
            "heading-md 22/28" to t.headingMd,
            "heading-lg 26/32" to t.headingLg,
        ).forEach { (label, style) ->
            Text(
                label,
                style = style,
                color = c.ink,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Text(
            "mono 13/18 — code 墨水等宽",
            style = t.mono.copy(fontFamily = FontFamily.Monospace),
            color = c.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ShapeSection() {
    val s = InkTheme.shapes
    val c = InkTheme.colors
    SectionHeader("圆角 6 级")
    Row(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf("xs" to s.xs, "sm" to s.sm, "md" to s.md, "lg" to s.lg, "xl" to s.xl).forEach { (label, r) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(r.dp)).background(c.surfaceTertiary)
                        .border(1.dp, c.borderPrimary, RoundedCornerShape(r.dp)),
                )
                Text(label, style = InkTheme.type.textXs, color = c.textTertiary)
            }
        }
    }
}

@Composable
private fun ComponentSection() {
    SectionHeader("Sand 范式组件")
    SandCard(Modifier.padding(horizontal = 16.dp)) {
        SandRow(
            title = "可点击 Row",
            subtitle = "副标题两行内省略——iOS Settings 式主从结构，按压整行填充",
            onClick = {},
        )
        SandDivider()
        SandRow(
            title = "带值 Row",
            trailing = { Text("值", style = InkTheme.type.textMd, color = InkTheme.colors.textTertiary) },
            onClick = {},
        )
        SandDivider()
        SandRow(title = "禁用 Row", enabled = false, onClick = {})
    }
    Spacer(Modifier.height(8.dp))
    SandCard(Modifier.padding(horizontal = 16.dp)) {
        EmptyState(icon = ArIcons.Pin, title = "空态标题", subtitle = "副文说明放这里")
    }
}

@Composable
private fun SwatchRow(label: String, color: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape).background(color)
                .border(1.dp, InkTheme.colors.borderPrimary, CircleShape),
        )
        Text(label, style = InkTheme.type.textSm, color = InkTheme.colors.textSecondary)
    }
}

@Composable
private fun FuncRow(label: String, f: FuncColors) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(f.text))
        Text(label, style = InkTheme.type.textMd, color = f.text)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(14.dp).clip(CircleShape).background(f.subtle).border(1.dp, f.border, CircleShape))
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).border(2.dp, f.ring, RoundedCornerShape(4.dp)))
    }
}
