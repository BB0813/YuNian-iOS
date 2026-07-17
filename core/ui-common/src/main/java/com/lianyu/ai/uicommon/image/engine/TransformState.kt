package com.lianyu.ai.uicommon.image.engine

import androidx.compose.ui.geometry.Size

/**
 * 不可变的图片变换状态 — viewer 与 cropper 之间唯一的通信载体。
 *
 * 变换模型：viewportXY = scale × imageXY + (offsetX, offsetY)
 *
 * @param scale      当前缩放倍数
 * @param offsetX    缩放后图片左上角在 viewport 中的 X 偏移
 * @param offsetY    缩放后图片左上角在 viewport 中的 Y 偏移
 * @param rotation   旋转角度（度，v1 保留字段，始终为 0）
 * @param imageSize  原始图片尺寸
 * @param viewportSize 容器尺寸
 */
data class TransformState(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
    val imageSize: Size = Size.Zero,
    val viewportSize: Size = Size.Zero,
)
