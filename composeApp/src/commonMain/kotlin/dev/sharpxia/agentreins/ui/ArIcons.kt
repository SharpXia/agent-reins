package dev.sharpxia.agentreins.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 自绘矢量图标（Material Symbols 24dp 路径，Apache-2.0）。material-icons-core 的
 * 图标集不含 pin/archive/copy/refresh，extended 包体积不可接受——M2.6 需要的补齐在此。
 * 统一 24×24 viewport，fill 当前色由 Icon(tint=…) 控制（path 用占位黑，tint 生效）。
 */
object ArIcons {

    private fun builder(name: String) = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )

    private fun ImageVector.Builder.fill(pathBuilder: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(
            fill = SolidColor(Color.Black),
            pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero,
            pathBuilder = pathBuilder,
        )

    /** 置顶（push_pin filled）。 */
    val Pin: ImageVector by lazy {
        builder("ArPin").fill {
            moveTo(16f, 9f)
            verticalLineTo(4f)
            horizontalLineToRelative(1f)
            curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
            reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
            horizontalLineTo(7f)
            curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            reflectiveCurveToRelative(0.45f, 1f, 1f, 1f)
            horizontalLineToRelative(1f)
            verticalLineToRelative(5f)
            curveToRelative(0f, 1.66f, -1.34f, 3f, -3f, 3f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(5.97f)
            verticalLineToRelative(7f)
            lineToRelative(1f, 1f)
            lineToRelative(1f, -1f)
            verticalLineToRelative(-7f)
            horizontalLineTo(19f)
            verticalLineToRelative(-2f)
            curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f)
            close()
        }.build()
    }

    /** 归档（archive）。 */
    val Archive: ImageVector by lazy {
        builder("ArArchive").fill {
            moveTo(20.54f, 5.23f)
            lineToRelative(-1.39f, -1.68f)
            curveTo(18.88f, 3.21f, 18.47f, 3f, 18f, 3f)
            horizontalLineTo(6f)
            curveToRelative(-0.47f, 0f, -0.88f, 0.21f, -1.16f, 0.55f)
            lineTo(3.46f, 5.23f)
            curveTo(3.17f, 5.57f, 3f, 6.02f, 3f, 6.5f)
            verticalLineTo(19f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(6.5f)
            curveToRelative(0f, -0.48f, -0.17f, -0.93f, -0.46f, -1.27f)
            close()
            moveTo(12f, 17.5f)
            lineTo(6.5f, 12f)
            horizontalLineTo(10f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(3.5f)
            lineTo(12f, 17.5f)
            close()
            moveTo(5.12f, 5f)
            lineToRelative(0.81f, -1f)
            horizontalLineToRelative(12f)
            lineToRelative(0.94f, 1f)
            horizontalLineTo(5.12f)
            close()
        }.build()
    }

    /** 取消归档（unarchive）。 */
    val Unarchive: ImageVector by lazy {
        builder("ArUnarchive").fill {
            moveTo(20.55f, 5.22f)
            lineToRelative(-1.39f, -1.68f)
            curveTo(18.88f, 3.21f, 18.47f, 3f, 18f, 3f)
            horizontalLineTo(6f)
            curveToRelative(-0.47f, 0f, -0.88f, 0.21f, -1.15f, 0.55f)
            lineTo(3.46f, 5.22f)
            curveTo(3.17f, 5.57f, 3f, 6.01f, 3f, 6.5f)
            verticalLineTo(19f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(6.5f)
            curveToRelative(0f, -0.49f, -0.17f, -0.93f, -0.45f, -1.28f)
            close()
            moveTo(12f, 9.5f)
            lineTo(16.5f, 14f)
            horizontalLineTo(14f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(-2f)
            horizontalLineTo(7.5f)
            lineTo(12f, 9.5f)
            close()
            moveTo(5.12f, 5f)
            lineToRelative(0.82f, -1f)
            horizontalLineToRelative(12f)
            lineToRelative(0.93f, 1f)
            horizontalLineTo(5.12f)
            close()
        }.build()
    }

    /** 复制（content_copy）。 */
    val Copy: ImageVector by lazy {
        builder("ArCopy").fill {
            moveTo(16f, 1f)
            horizontalLineTo(4f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            verticalLineTo(3f)
            horizontalLineToRelative(12f)
            verticalLineTo(1f)
            close()
            moveTo(19f, 5f)
            horizontalLineTo(8f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            horizontalLineTo(8f)
            verticalLineTo(7f)
            horizontalLineToRelative(11f)
            verticalLineToRelative(14f)
            close()
        }.build()
    }

    /** 重试（refresh）。 */
    val Retry: ImageVector by lazy {
        builder("ArRetry").fill {
            moveTo(17.65f, 6.35f)
            curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
            curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
            reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
            curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
            horizontalLineToRelative(-2.08f)
            curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
            curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
            reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
            curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
            lineTo(13f, 11f)
            horizontalLineToRelative(7f)
            verticalLineTo(4f)
            lineToRelative(-2.35f, 2.35f)
            close()
        }.build()
    }

    /** 右尖括号（chevron_right，列表行尾部导航指示）。 */
    val Chevron: ImageVector by lazy {
        builder("ArChevron").fill {
            moveTo(10f, 6f)
            lineTo(8.59f, 7.41f)
            lineTo(13.17f, 12f)
            lineTo(8.59f, 16.59f)
            lineTo(10f, 18f)
            lineTo(16f, 12f)
            close()
        }.build()
    }

    /** 隐藏（visibility_off，隐私分区）。 */
    val EyeOff: ImageVector by lazy {        builder("ArEyeOff").fill {
            moveTo(12f, 7f)
            curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
            curveToRelative(0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
            lineToRelative(2.92f, 2.92f)
            curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            curveToRelative(-1.4f, 0f, -2.74f, 0.25f, -3.98f, 0.7f)
            lineToRelative(2.16f, 2.16f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            lineTo(12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineToRelative(2.28f, 2.28f)
            curveToRelative(0.46f, 0.3f, 0.86f, 0.7f, 1.29f, 0.87f)
            curveTo(3.33f, 8.69f, 1.65f, 10.86f, 1f, 12.5f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            curveToRelative(1.55f, 0f, 3.03f, -0.3f, 4.38f, -0.84f)
            lineToRelative(0.42f, 0.42f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            lineTo(2f, 4.27f)
            lineTo(2f, 4.27f)
            close()
            moveTo(7.53f, 9.8f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
            curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
            curveToRelative(0.22f, 0f, 0.44f, -0.03f, 0.65f, -0.08f)
            lineToRelative(1.55f, 1.55f)
            curveToRelative(-0.67f, 0.33f, -1.41f, 0.53f, -2.2f, 0.53f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            curveToRelative(0f, -0.79f, 0.2f, -1.53f, 0.53f, -2.2f)
            lineTo(7.53f, 9.8f)
            lineTo(7.53f, 9.8f)
            close()
        }.build()
    }

    /** 引用（reply，QuoteBar/消息操作）。 */
    val Reply: ImageVector by lazy {
        builder("ArReply").fill {
            moveTo(10f, 9f)
            verticalLineTo(5f)
            lineToRelative(-7f, 7f)
            lineToRelative(7f, 7f)
            lineToRelative(0f, -4.1f)
            curveToRelative(5f, 0f, 8.5f, 1.6f, 11f, 5.1f)
            curveToRelative(-1f, -5f, -4f, -10f, -11f, -11f)
            lineTo(10f, 9f)
            lineTo(10f, 9f)
            close()
        }.build()
    }

    /** 分支（call_split / git-branch，消息分支/会话分叉）。 */
    val Branch: ImageVector by lazy {
        builder("ArBranch").fill {
            moveTo(17f, 2f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            horizontalLineToRelative(-4f)
            curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
            verticalLineToRelative(2f)
            curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
            horizontalLineToRelative(4f)
            curveToRelative(1.1f, 0f, 2f, 0.9f, 2f, 2f)
            lineToRelative(0f, 2f)
            curveToRelative(0f, 1.1f, -0.9f, 2f, -2f, 2f)
            horizontalLineToRelative(-4f)
            curveToRelative(-0.55f, 0f, -1f, -0.45f, -1f, -1f)
            verticalLineTo(9f)
            curveToRelative(0f, -0.55f, 0.45f, -1f, 1f, -1f)
            horizontalLineToRelative(4f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(2f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(7f, 2f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            lineTo(7f, 2f)
            lineTo(7f, 2f)
            close()
            moveTo(7f, 16f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            lineTo(7f, 16f)
            lineTo(7f, 16f)
            close()
        }.build()
    }
}
