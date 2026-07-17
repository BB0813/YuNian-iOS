package com.lianyu.ai.uicommon.image.cropper

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap

/**
 * 裁剪遮罩 + 边框 — 纯 UI 绘制组件，无业务状态。
 *
 * Z-Order：
 * - 第 2 层：半透明遮罩，挖出裁剪孔
 * - 第 3 层：裁剪框边框 + 四角标记
 *
 * @param cropRect          裁剪框在 viewport 中的位置
 * @param maskColor         遮罩颜色（默认半透明黑）
 * @param frameColor        边框颜色（默认白）
 * @param frameStrokeWidth  边框线宽
 * @param cornerLength      四角标记长度
 * @param cornerStrokeWidth 四角标记线宽
 */
@Composable
fun CropOverlay(
    cropRect: Rect,
    modifier: Modifier = Modifier,
    maskColor: Color = Color.Black.copy(alpha = 0.5f),
    frameColor: Color = Color.White,
    frameStrokeWidth: Float = 2f,
    cornerLength: Float = 28f,
    cornerStrokeWidth: Float = 3f,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        drawMaskWithHole(cropRect, maskColor)
        drawRect(
            color = frameColor,
            topLeft = cropRect.topLeft,
            size = cropRect.size,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = frameStrokeWidth)
        )
        drawCorners(cropRect, cornerLength, cornerStrokeWidth, frameColor)
    }
}

// ═══════════════════════════════════════════
// 内部绘制函数
// ═══════════════════════════════════════════

/** Even-Odd 填充：整个屏幕 + 裁剪孔 → 只有孔外被填充 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawMaskWithHole(
    hole: Rect,
    maskColor: Color
) {
    val path = Path().apply {
        addRect(Rect(0f, 0f, size.width, size.height))
        addRect(hole)
        fillType = PathFillType.EvenOdd
    }
    drawPath(path, maskColor)
}

/** 四角 L 形标记 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCorners(
    rect: Rect,
    length: Float,
    strokeWidth: Float,
    color: Color
) {
    val l = length
    // 左上
    drawLine(color, Offset(rect.left, rect.top + l), Offset(rect.left, rect.top), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.left, rect.top), Offset(rect.left + l, rect.top), strokeWidth, cap = StrokeCap.Round)
    // 右上
    drawLine(color, Offset(rect.right - l, rect.top), Offset(rect.right, rect.top), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.right, rect.top), Offset(rect.right, rect.top + l), strokeWidth, cap = StrokeCap.Round)
    // 左下
    drawLine(color, Offset(rect.left, rect.bottom - l), Offset(rect.left, rect.bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.left, rect.bottom), Offset(rect.left + l, rect.bottom), strokeWidth, cap = StrokeCap.Round)
    // 右下
    drawLine(color, Offset(rect.right - l, rect.bottom), Offset(rect.right, rect.bottom), strokeWidth, cap = StrokeCap.Round)
    drawLine(color, Offset(rect.right, rect.bottom), Offset(rect.right, rect.bottom - l), strokeWidth, cap = StrokeCap.Round)
}
