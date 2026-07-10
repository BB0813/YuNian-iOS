package com.lianyu.ai.uicommon.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Which side of the bubble the decorative arrow points toward.
 */
enum class AppBubbleSide {
    Start,
    End,
    None
}

/**
 * Visual configuration for a single bubble shape.
 *
 * @param cornerRadius radius for all four corners of the rounded rect
 * @param side which side the arrow points toward (or [AppBubbleSide.None])
 * @param arrowWidth width of the triangular arrow at its base
 * @param arrowHeight height of the arrow from base to tip
 * @param arrowOffsetY vertical offset of the arrow from the top of the bubble
 */
data class AppBubbleSpec(
    val cornerRadius: Dp,
    val side: AppBubbleSide,
    val arrowWidth: Dp = 6.dp,
    val arrowHeight: Dp = 10.dp,
    val arrowOffsetY: Dp = 16.dp
)

/**
 * Draws a rounded-rectangle bubble background with an optional directional arrow
 * and optional border stroke.
 *
 * This is the app-level equivalent of the chat-specific `chatBubbleBackground`.
 */
fun Modifier.appBubbleBackground(
    color: Color,
    borderColor: Color? = null,
    borderWidth: Dp = 0.dp,
    spec: AppBubbleSpec
): Modifier = drawBehind {
    val radius = spec.cornerRadius.toPx()
    val arrowWidthPx = if (spec.side == AppBubbleSide.None) 0f else spec.arrowWidth.toPx()
    val arrowHeightPx = spec.arrowHeight.toPx()
    val rectLeft = if (spec.side == AppBubbleSide.Start) arrowWidthPx else 0f
    val rectRight = size.width - if (spec.side == AppBubbleSide.End) arrowWidthPx else 0f
    val rectSize = Size((rectRight - rectLeft).coerceAtLeast(0f), size.height)

    drawRoundRect(
        color = color,
        topLeft = Offset(rectLeft, 0f),
        size = rectSize,
        cornerRadius = CornerRadius(radius, radius)
    )

    if (spec.side != AppBubbleSide.None) {
        val arrowTop = spec.arrowOffsetY.toPx()
            .coerceIn(0f, (size.height - arrowHeightPx).coerceAtLeast(0f))
        val arrow = Path().apply {
            if (spec.side == AppBubbleSide.Start) {
                moveTo(rectLeft, arrowTop)
                lineTo(0f, arrowTop + arrowHeightPx / 2f)
                lineTo(rectLeft, arrowTop + arrowHeightPx)
            } else {
                moveTo(rectRight, arrowTop)
                lineTo(size.width, arrowTop + arrowHeightPx / 2f)
                lineTo(rectRight, arrowTop + arrowHeightPx)
            }
            close()
        }
        drawPath(path = arrow, color = color)
    }

    if (borderColor != null && borderWidth > 0.dp) {
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(rectLeft, 0f),
            size = rectSize,
            cornerRadius = CornerRadius(radius, radius),
            style = Stroke(width = borderWidth.toPx())
        )
    }
}