package com.lianyu.ai.uicommon.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

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

    val fillPath = buildBubblePath(
        rectLeft = rectLeft,
        rectRight = rectRight,
        rectHeight = size.height,
        radius = radius,
        arrowWidthPx = arrowWidthPx,
        arrowHeightPx = arrowHeightPx,
        arrowOffsetYPx = spec.arrowOffsetY.toPx(),
        side = spec.side
    )
    drawPath(path = fillPath, color = color)

    if (borderColor != null && borderWidth > 0.dp) {
        drawPath(
            path = fillPath,
            color = borderColor,
            style = Stroke(width = borderWidth.toPx())
        )
    }
}

/**
 * 构建气泡轮廓（圆角矩形 + 可选箭头）的单一连续 Path。
 *
 * 关键点：
 * 1. 单 Path 填充，消除两次 draw 的抗锯齿接缝
 * 2. 箭头侧圆角会按气泡高度收缩，保证侧边至少有一段平直区给三角贴合
 * 3. Start / End 使用同一套“尖→上底→绕矩形顺时针→下底→close”流程，仅做左右镜像
 *
 * Android [Path.arcTo]：0°=右, 90°=下, 180°=左, 270°=上；正 sweep 为顺时针。
 */
private fun buildBubblePath(
    rectLeft: Float,
    rectRight: Float,
    rectHeight: Float,
    radius: Float,
    arrowWidthPx: Float,
    arrowHeightPx: Float,
    arrowOffsetYPx: Float,
    side: AppBubbleSide
): Path {
    val width = (rectRight - rectLeft).coerceAtLeast(0f)
    val height = rectHeight.coerceAtLeast(0f)
    val baseR = radius.coerceIn(0f, min(width, height) / 2f)

    if (side == AppBubbleSide.None || arrowWidthPx <= 0f || arrowHeightPx <= 0f || height <= 0f) {
        return Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(Offset(rectLeft, 0f), Size(width, height)),
                    cornerRadius = CornerRadius(baseR, baseR)
                )
            )
        }
    }

    val halfH = arrowHeightPx / 2f
    // 箭头侧必须留出至少 arrowHeight 的平直段，否则三角底边贴在圆弧上会脱开
    val maxArrowSideR = ((height - arrowHeightPx) / 2f).coerceAtLeast(0f)
    val arrowSideR = min(baseR, maxArrowSideR)
    val otherR = baseR

    val minMid = arrowSideR + halfH
    val maxMid = (height - arrowSideR - halfH).coerceAtLeast(minMid)
    val arrowMidY = arrowOffsetYPx.coerceIn(minMid, maxMid)
    val arrowTop = arrowMidY - halfH
    val arrowBottom = arrowMidY + halfH

    // 四角半径：箭头侧两角用 arrowSideR，对侧两角用 otherR
    val tl: Float
    val tr: Float
    val br: Float
    val bl: Float
    when (side) {
        AppBubbleSide.Start -> {
            tl = arrowSideR
            bl = arrowSideR
            tr = otherR
            br = otherR
        }
        AppBubbleSide.End -> {
            tr = arrowSideR
            br = arrowSideR
            tl = otherR
            bl = otherR
        }
        AppBubbleSide.None -> {
            tl = otherR
            tr = otherR
            br = otherR
            bl = otherR
        }
    }

    return Path().apply {
        when (side) {
            AppBubbleSide.Start -> {
                // AI 气泡（左箭头）：尖 → 上底 → 左上 → 顶 → 右上 → 右 → 右下 → 底 → 左下 → 下底 → close
                moveTo(0f, arrowMidY)
                lineTo(rectLeft, arrowTop)
                lineTo(rectLeft, tl)
                if (tl > 0f) {
                    arcTo(Rect(rectLeft, 0f, rectLeft + tl * 2f, tl * 2f), 180f, 90f, false)
                } else {
                    lineTo(rectLeft, 0f)
                }
                lineTo(rectRight - tr, 0f)
                if (tr > 0f) {
                    arcTo(Rect(rectRight - tr * 2f, 0f, rectRight, tr * 2f), 270f, 90f, false)
                } else {
                    lineTo(rectRight, 0f)
                }
                lineTo(rectRight, height - br)
                if (br > 0f) {
                    arcTo(Rect(rectRight - br * 2f, height - br * 2f, rectRight, height), 0f, 90f, false)
                } else {
                    lineTo(rectRight, height)
                }
                lineTo(rectLeft + bl, height)
                if (bl > 0f) {
                    arcTo(Rect(rectLeft, height - bl * 2f, rectLeft + bl * 2f, height), 90f, 90f, false)
                } else {
                    lineTo(rectLeft, height)
                }
                lineTo(rectLeft, arrowBottom)
                close()
            }
            AppBubbleSide.End -> {
                // 自己气泡（右箭头）：与 Start 完全镜像的同一流程
                // 尖 → 上底 → 右上 → 顶 → 左上 → 左 → 左下 → 底 → 右下 → 下底 → close
                moveTo(rectRight + arrowWidthPx, arrowMidY)
                lineTo(rectRight, arrowTop)
                lineTo(rectRight, tr)
                if (tr > 0f) {
                    arcTo(Rect(rectRight - tr * 2f, 0f, rectRight, tr * 2f), 0f, -90f, false)
                } else {
                    lineTo(rectRight, 0f)
                }
                lineTo(rectLeft + tl, 0f)
                if (tl > 0f) {
                    arcTo(Rect(rectLeft, 0f, rectLeft + tl * 2f, tl * 2f), 270f, -90f, false)
                } else {
                    lineTo(rectLeft, 0f)
                }
                lineTo(rectLeft, height - bl)
                if (bl > 0f) {
                    arcTo(Rect(rectLeft, height - bl * 2f, rectLeft + bl * 2f, height), 180f, -90f, false)
                } else {
                    lineTo(rectLeft, height)
                }
                lineTo(rectRight - br, height)
                if (br > 0f) {
                    arcTo(Rect(rectRight - br * 2f, height - br * 2f, rectRight, height), 90f, -90f, false)
                } else {
                    lineTo(rectRight, height)
                }
                lineTo(rectRight, arrowBottom)
                close()
            }
            AppBubbleSide.None -> Unit
        }
    }
}
