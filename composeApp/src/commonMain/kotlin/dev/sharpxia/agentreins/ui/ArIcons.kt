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
}
