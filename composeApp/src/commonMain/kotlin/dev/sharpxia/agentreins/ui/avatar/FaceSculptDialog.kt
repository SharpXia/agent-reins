package dev.sharpxia.agentreins.ui.avatar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.sharpxia.agentreins.AppModel
import dev.sharpxia.agentreins.ui.theme.InkTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 捏脸窗口（avatar-face-sculpt-20261030 §5，Compose 侧 = 全屏 Dialog）：
 * 大号实时预览（当前 tint × 当前表情的 blob 脸）+ 轮廓上 12 个手柄小圆点（位置 = facePoint）。
 * 拖拽 = 拖点相对圆心 (50,50) 的方位角命中最近索引 i，f_i = clamp(拖距/44, 0.70, 1.10)；
 * 黏土手感 = 邻点 i±1 各向新值靠拢（f_{i±1} += (f_i_new − f_{i±1}) × 0.30）再各自 clamp；
 * 拖动中逐帧经 AppModel.setAgentFace(encodeFaceFactors(…)) 即点即存，渲染走 §2 样条。
 * 「重置」= 12 点回 1.0（face 归位 ""= 正圆）；「完成」= 关闭。
 */
@Composable
fun FaceSculptDialog(
    model: AppModel,
    agentId: String,
    onDismiss: () -> Unit,
) {
    val profile = model.agentProfiles[agentId] ?: dev.sharpxia.agentreins.AgentProfile.default(agentId)
    val expression = avatarExpressionOf(agentId, model.agentProfiles)
    // 拖动期间以本地状态逐帧驱动（避免等 model 回流），写入与 model 同步落库
    var factors by remember(agentId) { mutableStateOf(agentFaceFactorsOf(agentId, model.agentProfiles)) }
    // 预览区实测像素边长：指针坐标 → 100×100 逻辑坐标的换算基准
    var previewPx by remember { mutableStateOf(0f) }

    fun applyDrag(pos: Offset) {
        if (previewPx <= 0f) return
        val s = previewPx / 100f
        val dx = pos.x / s - 50.0
        val dy = pos.y / s - 50.0
        // 方位角：12 点方向为 0°、顺时针（θ_i = i×30°）→ 最近索引
        var theta = atan2(dx, -dy) * 180.0 / PI
        if (theta < 0.0) theta += 360.0
        val i = ((theta / 30.0).roundToInt() % FACE_POINT_COUNT + FACE_POINT_COUNT) % FACE_POINT_COUNT
        val fNew = (sqrt(dx * dx + dy * dy) / FACE_R).coerceIn(FACE_MIN, FACE_MAX)
        val next = factors.toMutableList()
        next[i] = fNew
        // 黏土手感：邻点 i±1 各向新值靠拢 ×0.30 再 clamp（§5）
        val left = (i + FACE_POINT_COUNT - 1) % FACE_POINT_COUNT
        val right = (i + 1) % FACE_POINT_COUNT
        next[left] = (next[left] + (fNew - next[left]) * 0.30).coerceIn(FACE_MIN, FACE_MAX)
        next[right] = (next[right] + (fNew - next[right]) * 0.30).coerceIn(FACE_MIN, FACE_MAX)
        factors = next
        model.setAgentFace(agentId, encodeFaceFactors(next))
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = InkTheme.colors.paper) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "捏脸",
                    style = InkTheme.type.headingMd,
                    color = InkTheme.colors.ink,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    // 取可用区最小边（留 24dp 边距放手柄），取整 dp 保证头像与其上叠加层同口径
                    val side = (minOf(maxWidth, maxHeight) - 24.dp).coerceAtLeast(220.dp).value.roundToInt().dp
                    Box(
                        Modifier
                            .size(side)
                            .onSizeChanged { previewPx = it.width.toFloat() }
                            .pointerInput(previewPx) {
                                detectDragGestures(
                                    onDragStart = { applyDrag(it) },
                                    onDrag = { change, _ ->
                                        applyDrag(change.position)
                                        change.consume()
                                    },
                                )
                            },
                    ) {
                        RobotAvatar(
                            palette = profile.tint,
                            expression = expression,
                            size = side.value.roundToInt(),
                            isDark = InkTheme.colors.isDark,
                            faceFactors = factors,
                        )
                        // 手柄叠加层：12 个小圆点，位置 = facePoint（拖拽手势挂在宿主 Box 上）；
                        // InkTheme.colors 是 @Composable 取值，只能在组合期读，DrawScope 内用预取值
                        val ringColor = InkTheme.colors.ink
                        Canvas(Modifier.fillMaxSize()) {
                            val s = this.size.minDimension / 100f
                            factors.forEachIndexed { i, f ->
                                val p = facePoint(i, f)
                                val c = Offset((p.x * s).toFloat(), (p.y * s).toFloat())
                                drawCircle(ringColor, radius = 4.dp.toPx(), center = c)
                                drawCircle(Color.White, radius = 1.8.dp.toPx(), center = c)
                            }
                        }
                    }
                }
                Text(
                    "拖动轮廓上的手柄调整脸型",
                    style = InkTheme.type.textXs,
                    color = InkTheme.colors.textTertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = {
                        factors = DefaultFaceFactors
                        model.setAgentFace(agentId, "")
                    }) { Text("重置", color = InkTheme.colors.ink) }
                    TextButton(onClick = onDismiss) { Text("完成", color = InkTheme.colors.ink) }
                }
            }
        }
    }
}
