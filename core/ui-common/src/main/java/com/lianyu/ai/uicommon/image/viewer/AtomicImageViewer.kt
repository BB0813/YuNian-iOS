package com.lianyu.ai.uicommon.image.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import com.lianyu.ai.uicommon.image.engine.TransformState

/**
 * 原子查看器 — 纯渲染 + 手势检测，零业务逻辑。
 *
 * 职责：
 * - 接收 [transform] 渲染图片
 * - 检测用户手势，通过 [onTransformRequest] 向外抛出变换请求
 * - **不持有内部状态**，所有变换由外部驱动
 *
 * 设计要点：
 * - 渲染直接使用外部传入的 [transform]
 * - 手势通过 [latestTransform] 闭包读取最新值，`pointerInput(Unit)` 避免重启
 * - 缩放以焦点为中心（centroid 保持不动）
 * - 不依赖 cropper 包中的任何类型
 *
 * @param bitmap            要显示的图片
 * @param transform         当前变换状态（由外部驱动，用于渲染）
 * @param latestTransform   始终指向最新 transform 的闭包（用于手势处理）
 * @param onTransformRequest 用户手势产生的变换请求（外部负责 clamp 后写回）
 * @param minScale          最小缩放倍数
 * @param maxScale          最大缩放倍数
 */
@Composable
fun AtomicImageViewer(
    bitmap: ImageBitmap,
    transform: TransformState,
    latestTransform: () -> TransformState,
    onTransformRequest: (TransformState) -> Unit,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    modifier: Modifier = Modifier,
) {
    // 渲染始终跟随外部 transform
    // 手势通过 latestTransform() 闭包读取最新值，pointerInput 以 Unit 为 key 保持稳定
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val current = latestTransform()
                    val oldScale = current.scale
                    val newScale = (oldScale * zoom).coerceIn(minScale, maxScale)

                    // 缩放时保持焦点不动：计算焦点对应的图片坐标，再做逆变换
                    val imageX = (centroid.x - current.offsetX) / oldScale
                    val imageY = (centroid.y - current.offsetY) / oldScale
                    val newOffsetX = centroid.x - newScale * imageX + pan.x
                    val newOffsetY = centroid.y - newScale * imageY + pan.y

                    onTransformRequest(
                        current.copy(
                            scale = newScale,
                            offsetX = newOffsetX,
                            offsetY = newOffsetY,
                        )
                    )
                }
            }
    ) {
        withTransform({
            translate(left = transform.offsetX, top = transform.offsetY)
            scale(transform.scale, transform.scale, Offset.Zero)
        }) {
            drawImage(bitmap, topLeft = Offset.Zero)
        }
    }
}
