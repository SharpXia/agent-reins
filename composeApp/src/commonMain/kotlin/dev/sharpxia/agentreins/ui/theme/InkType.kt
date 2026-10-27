package dev.sharpxia.agentreins.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * M3 墨水系设计令牌 · 字阶与圆角（逆向报告 §4.2/§4.3 逐值移植）。
 *
 * 字阶 8 级（size/lineHeight）：xs 12/16 · sm 13/18 · md 14/20 · lg 15/22 ·
 * heading-xs 15/22 · heading-sm 17/24 · heading-md 22/28 · heading-lg 26/32。
 * 权重语义：normal 400 / medium 500 / semibold 600 / bold 700。
 * 字族走系统字体（报告的 XVF 为版权字体，不复制）。
 */
@Immutable
data class InkType(
    val textXs: TextStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    val textSm: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    val textMd: TextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    val textLg: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    val headingXs: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    val headingSm: TextStyle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    val headingMd: TextStyle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    val headingLg: TextStyle = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    val mono: TextStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontFamily = FontFamily.Monospace),
) {
    companion object {
        /** 聊天正文基准（Grok Bot 正文观感：text-lg 15/22）。 */
        val body: TextStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    }
}

/** 圆角 6 级（dp）：4 / 6 / 8 / 12 / 16 / full。 */
@Immutable
data class InkShapes(
    val xs: Int = 4,
    val sm: Int = 6,
    val md: Int = 8,
    val lg: Int = 12,
    val xl: Int = 16,
) {
    val full: Int = 9999
}
