package com.lianyu.ai.uicommon.image.cropper

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.lianyu.ai.uicommon.image.engine.ImageTransform
import com.lianyu.ai.uicommon.image.engine.TransformState

/**
 * 裁剪协调器 — viewer 与 cropper 之间的桥接层。
 *
 * 职责：
 * - 接收 viewer 的原始变换，通过 [ImageTransform.clampToBounds] 修正
 * - 将修正后的变换返回给 viewer
 * - 提供最终裁切输出
 *
 * 设计约束：
 * - 绝不修改 viewer 内部逻辑
 * - 只通过 [TransformState] 与 viewer 通信
 *
 * @param cropRect 裁剪框在 viewport 中的位置
 */
class CropCoordinator(
    private val cropRect: Rect
) {
    /**
     * 处理 viewer 抛出的变换请求。
     *
     * @return 修正后的变换（可能为原值，如果未越界）
     */
    fun processTransform(raw: TransformState): TransformState {
        return ImageTransform.clampToBounds(raw, cropRect)
    }

    /**
     * 从最终变换状态裁切出 [Bitmap]。
     */
    fun crop(bitmap: ImageBitmap, state: TransformState): Bitmap {
        return ImageTransform.cropBitmap(bitmap, state, cropRect)
    }

    /**
     * 从最终变换状态裁切出 [ImageBitmap]（Compose 友好）。
     */
    fun cropToImageBitmap(bitmap: ImageBitmap, state: TransformState): ImageBitmap {
        return crop(bitmap, state).asImageBitmap()
    }
}
