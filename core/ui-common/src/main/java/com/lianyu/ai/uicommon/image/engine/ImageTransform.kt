package com.lianyu.ai.uicommon.image.engine

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 纯数学变换工具 — 零 Compose UI 依赖。
 *
 * 职责：
 * - 将图片坐标映射到 viewport 坐标
 * - 确保变换后的图片边界紧贴裁剪框（绝不露白）
 * - 计算初始填充缩放
 * - 从最终变换状态裁切出 Bitmap
 */
object ImageTransform {

    // ═══════════════════════════════════════════════════════════
    // 坐标映射
    // ═══════════════════════════════════════════════════════════

    /**
     * 将图片空间中的矩形映射到 viewport 空间。
     * 变换模型：viewportXY = scale × imageXY + (offsetX, offsetY)
     */
    fun mapRect(state: TransformState, imageRect: Rect): Rect {
        val left = state.offsetX + imageRect.left * state.scale
        val top = state.offsetY + imageRect.top * state.scale
        val right = state.offsetX + imageRect.right * state.scale
        val bottom = state.offsetY + imageRect.bottom * state.scale
        return Rect(left, top, right, bottom)
    }

    /**
     * 将 viewport 空间中的矩形反向映射到图片空间。
     */
    fun inverseMapRect(state: TransformState, viewportRect: Rect): Rect {
        val left = (viewportRect.left - state.offsetX) / state.scale
        val top = (viewportRect.top - state.offsetY) / state.scale
        val right = (viewportRect.right - state.offsetX) / state.scale
        val bottom = (viewportRect.bottom - state.offsetY) / state.scale
        return Rect(left, top, right, bottom)
    }

    // ═══════════════════════════════════════════════════════════
    // 边界约束（抗白边核心算法）
    // ═══════════════════════════════════════════════════════════

    /**
     * 确保变换后的图片边界紧贴裁剪框，绝不露白。
     *
     * 算法：
     * - 图片宽/高 ≥ 裁剪框宽/高时：修正偏移使图片始终覆盖裁剪框
     * - 图片宽/高 < 裁剪框宽/高时：居中（理论上 minScale 保证了不会发生）
     *
     * @param state    当前变换状态
     * @param cropRect 裁剪框在 viewport 中的位置
     * @return 修正后的变换状态（offset 被调整，scale 不变）
     */
    fun clampToBounds(state: TransformState, cropRect: Rect): TransformState {
        val imgRect = mapRect(state, Rect(0f, 0f, state.imageSize.width, state.imageSize.height))
        var dx = 0f
        var dy = 0f

        // 水平方向：确保 cropRect 始终在图片内部
        if (imgRect.width >= cropRect.width) {
            if (imgRect.left > cropRect.left) {
                dx = cropRect.left - imgRect.left
            } else if (imgRect.right < cropRect.right) {
                dx = cropRect.right - imgRect.right
            }
        } else {
            // 图片比裁剪框窄 → 居中
            dx = cropRect.center.x - imgRect.center.x
        }

        // 垂直方向
        if (imgRect.height >= cropRect.height) {
            if (imgRect.top > cropRect.top) {
                dy = cropRect.top - imgRect.top
            } else if (imgRect.bottom < cropRect.bottom) {
                dy = cropRect.bottom - imgRect.bottom
            }
        } else {
            dy = cropRect.center.y - imgRect.center.y
        }

        return if (dx == 0f && dy == 0f) state
        else state.copy(offsetX = state.offsetX + dx, offsetY = state.offsetY + dy)
    }

    // ═══════════════════════════════════════════════════════════
    // 初始适配
    // ═══════════════════════════════════════════════════════════

    /**
     * 计算初始变换：图片至少填满裁剪框（scale = max(cropW/imgW, cropH/imgH)），居中放置。
     */
    fun fitToCropRect(imageSize: Size, viewportSize: Size, cropRect: Rect): TransformState {
        val scale = max(
            cropRect.width / imageSize.width,
            cropRect.height / imageSize.height
        )
        val scaledW = imageSize.width * scale
        val scaledH = imageSize.height * scale
        val offsetX = cropRect.left + (cropRect.width - scaledW) / 2f
        val offsetY = cropRect.top + (cropRect.height - scaledH) / 2f
        return TransformState(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            imageSize = imageSize,
            viewportSize = viewportSize,
        )
    }

    /**
     * 预览适配：图片完整落入 viewport（contain），居中放置。
     * 与 [fitToCropRect] 的 cover 策略相对，用于全屏查看器。
     */
    fun fitInside(imageSize: Size, viewportSize: Size): TransformState {
        if (imageSize.width <= 0f || imageSize.height <= 0f ||
            viewportSize.width <= 0f || viewportSize.height <= 0f
        ) {
            return TransformState(imageSize = imageSize, viewportSize = viewportSize)
        }
        val scale = min(
            viewportSize.width / imageSize.width,
            viewportSize.height / imageSize.height
        )
        val scaledW = imageSize.width * scale
        val scaledH = imageSize.height * scale
        val offsetX = (viewportSize.width - scaledW) / 2f
        val offsetY = (viewportSize.height - scaledH) / 2f
        return TransformState(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            imageSize = imageSize,
            viewportSize = viewportSize,
        )
    }

    /**
     * 预览边界约束：以整个 viewport 为可视区。
     * - 放大后：保证 viewport 不越出图片（与裁剪器一致）
     * - 未放大：居中（允许 letterbox）
     */
    fun clampToViewport(state: TransformState): TransformState {
        val viewport = Rect(0f, 0f, state.viewportSize.width, state.viewportSize.height)
        if (viewport.width <= 0f || viewport.height <= 0f) return state
        return clampToBounds(state, viewport)
    }

    // ═══════════════════════════════════════════════════════════
    // 裁切输出
    // ═══════════════════════════════════════════════════════════

    /**
     * 根据最终变换状态和裁剪框，从原图中裁切出结果。
     *
     * @return 裁切后的 [Bitmap]，尺寸 = cropRect 大小（向上取整）
     */
    fun cropBitmap(bitmap: ImageBitmap, state: TransformState, cropRect: Rect): Bitmap {
        val androidBitmap = bitmap.asAndroidBitmap()
        val srcRect = inverseMapRect(state, cropRect)
        val srcX = srcRect.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val srcY = srcRect.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val srcW = srcRect.width.roundToInt()
            .coerceIn(1, bitmap.width - srcX)
        val srcH = srcRect.height.roundToInt()
            .coerceIn(1, bitmap.height - srcY)
        val dstW = cropRect.width.roundToInt().coerceAtLeast(1)
        val dstH = cropRect.height.roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(
            Bitmap.createBitmap(androidBitmap, srcX, srcY, srcW, srcH),
            dstW, dstH, true
        )
    }
}
