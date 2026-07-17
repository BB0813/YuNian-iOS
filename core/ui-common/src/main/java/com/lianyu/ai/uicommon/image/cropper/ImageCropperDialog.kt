package com.lianyu.ai.uicommon.image.cropper

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.lianyu.ai.uicommon.image.engine.ImageTransform
import com.lianyu.ai.uicommon.image.engine.TransformState
import com.lianyu.ai.uicommon.image.viewer.AtomicImageViewer
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 图片裁剪器顶层入口 — 全屏 Dialog，组装 viewer + overlay + toolbar。
 *
 * Z-Order（由底到顶）：
 * - 第 1 层：AtomicImageViewer
 * - 第 2 层：CropOverlay（遮罩 + 裁切框）
 * - 第 3 层：顶部工具栏 + 底部工具栏
 *
 * @param bitmap    待裁剪的图片
 * @param cropRatio 裁剪框宽高比（默认 1:1 正方形）
 * @param onConfirm 确认回调，返回裁切后的 Bitmap
 * @param onDismiss 关闭回调
 */
@Composable
fun ImageCropperDialog(
    bitmap: ImageBitmap,
    cropRatio: Float = 1f,
    onConfirm: (Bitmap) -> Unit,
    onDismiss: () -> Unit,
) {
    // ═══ 容器尺寸（用于计算 cropRect） ═══
    var containerSize by remember { mutableStateOf(Size.Zero) }

    // ═══ 计算裁剪框位置 ═══
    val cropRect = remember(containerSize, cropRatio) {
        if (containerSize == Size.Zero) {
            Rect.Zero
        } else {
            val maxWidth = containerSize.width * 0.9f
            val maxHeight = containerSize.height * 0.72f
            val cropW: Float
            val cropH: Float
            if (maxWidth / maxHeight > cropRatio) {
                cropH = maxHeight
                cropW = cropH * cropRatio
            } else {
                cropW = maxWidth
                cropH = cropW / cropRatio
            }
            val left = (containerSize.width - cropW) / 2f
            val top = (containerSize.height - cropH) / 2f
            Rect(left, top, left + cropW, top + cropH)
        }
    }

    // ═══ 初始适配变换 ═══
    val initialTransform = remember(bitmap, containerSize, cropRect) {
        if (cropRect == Rect.Zero || containerSize == Size.Zero) {
            TransformState(
                imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                viewportSize = containerSize
            )
        } else {
            ImageTransform.fitToCropRect(
                imageSize = Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                viewportSize = containerSize,
                cropRect = cropRect
            )
        }
    }

    // ═══ 协调器 ═══
    val coordinator = remember(cropRect) { CropCoordinator(cropRect) }

    // ═══ 变换状态（外部驱动） ═══
    var currentTransform by remember(initialTransform) { mutableStateOf(initialTransform) }

    // 始终指向最新 transform 的闭包 — 传递给 viewer 手势层，避免 pointerInput 重启
    val latestTransform = { currentTransform }

    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        // ═══ 边缘到边缘 ═══
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                )
                w.setBackgroundDrawable(ColorDrawable(0xFF1A1216.toInt()))
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WeChatDarkBackground)
                .onSizeChanged { containerSize = it.toSize() }
        ) {
            // ── 第 1 层：原子查看器 ──
            if (cropRect != Rect.Zero) {
                AtomicImageViewer(
                    bitmap = bitmap,
                    transform = currentTransform,
                    latestTransform = latestTransform,
                    onTransformRequest = { requested ->
                        currentTransform = coordinator.processTransform(requested)
                    },
                    minScale = initialTransform.scale,
                    maxScale = initialTransform.scale * 5f,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── 第 2 层：遮罩 + 裁切框 ──
            if (cropRect != Rect.Zero) {
                CropOverlay(
                    cropRect = cropRect,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // ── 第 3 层：顶部工具栏 ──
            TopToolbar(
                onCancel = onDismiss,
                onConfirm = {
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            coordinator.crop(bitmap, currentTransform)
                        }
                        onConfirm(result)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .zIndex(1f)
            )

            // ── 第 3 层：底部工具栏 ──
            BottomToolbar(
                onReset = {
                    currentTransform = initialTransform
                },
                onRotate = {
                    // v1 暂不实现旋转
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .zIndex(1f)
            )
        }
    }
}

// ═══════════════════════════════════════════
// 工具栏子组件
// ═══════════════════════════════════════════

@Composable
private fun TopToolbar(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onCancel) {
            Text("取消", color = androidx.compose.ui.graphics.Color.White, fontSize = 16.sp)
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = onConfirm) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "确认",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun BottomToolbar(
    onReset: () -> Unit,
    onRotate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 旋转按钮（v1 占位）
        IconButton(onClick = onRotate) {
            Icon(
                Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = "旋转",
                tint = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // 重置按钮
        IconButton(onClick = onReset) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "重置",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
