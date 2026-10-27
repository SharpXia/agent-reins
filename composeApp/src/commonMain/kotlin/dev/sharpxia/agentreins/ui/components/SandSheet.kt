package dev.sharpxia.agentreins.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.sharpxia.agentreins.ui.theme.InkTheme

/**
 * Sheet 基座（逆向报告 §6：「Sheet 承载一切二级操作」，全项目 56 个 Sheet 的统一容器）。
 * ModalBottomSheet 封装：墨水纸底 + 顶抓手 + 可选标题；圆角 xl；玻璃拟态留 M3.6 打磨。
 * 内容一律走 [SandSheetAction] 拼装，保证所有弹层观感一致。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SandSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sheetState: SheetState = rememberModalBottomSheetState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier,
        containerColor = InkTheme.colors.elevated,
        contentColor = InkTheme.colors.ink,
        shape = RoundedCornerShape(topStart = InkTheme.shapes.xl.dp, topEnd = InkTheme.shapes.xl.dp),
        dragHandle = null,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .padding(top = 8.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .align(Alignment.CenterHorizontally)
                    .background(InkTheme.colors.borderPrimary, RoundedCornerShape(InkTheme.shapes.full.dp)),
            )
            if (title != null) {
                Text(
                    title,
                    style = InkTheme.type.headingSm,
                    color = InkTheme.colors.ink,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Sheet 内一行动作（图标 + 文案，危险动作传 danger 色；按压整行填充）。 */
@Composable
fun SandSheetAction(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    color: Color = InkTheme.colors.ink,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .sandPressFill(interaction, cornerRadius = 0)
            .clickable(interactionSource = interaction, indication = null) { onClick() }
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.width(20.dp))
        }
        Text(text, style = InkTheme.type.textLg, color = color)
    }
}
