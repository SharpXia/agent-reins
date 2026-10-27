package dev.sharpxia.agentreins.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 未读徽标（>99 显示 99+）。M3.2 重绘为 tint 化。 */
@Composable
fun UnreadBadge(unread: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(
            if (unread > 99) "99+" else unread.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
}
