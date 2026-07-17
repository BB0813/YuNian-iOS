package com.lianyu.ai.uicommon.picker.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.LocalImageLoader
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lianyu.ai.uicommon.picker.PickerImageLoader
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground

/**
 * 自研图片选择器顶层入口 — 全屏 Dialog，含转场动画。
 *
 * @param maxSelection 最大可选数量（默认 1）
 * @param onConfirmed  确认回调
 * @param onDismiss    关闭回调
 */
@Composable
fun CustomImagePicker(
    maxSelection: Int = 1,
    onConfirmed: (List<Uri>) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val viewModel: PickerViewModel = viewModel(
        factory = PickerViewModelFactory(context.contentResolver)
    )
    val pickerState by viewModel.state.collectAsState()
    val selectionMap by viewModel.selectionMap.collectAsState()

    LaunchedEffect(maxSelection) {
        viewModel.setMaxSelection(maxSelection)
    }

    var currentPage by remember { mutableStateOf(PickerPage.GRID) }
    var previewInitialIndex by remember { mutableStateOf(0) }
    val hasSelection = selectionMap.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false
        )
    ) {
        // ═══ 边缘到边缘：等效 enableEdgeToEdge() 对 Dialog 窗口 ═══
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                // FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_INSET_DECOR：
                // 让 Dialog 窗口布局延伸至状态栏 / 导航栏后方（等效 enableEdgeToEdge）
                @Suppress("DEPRECATION")
                w.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                )
                w.setBackgroundDrawable(ColorDrawable(0xFF1A1216.toInt()))
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.statusBarColor = Color.TRANSPARENT
                w.navigationBarColor = Color.TRANSPARENT
                // 深色背景 → 白色系统图标
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
                // API 29- fallback：旧式 decorView flag 确保状态栏区域可绘制
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT <= 29) {
                    w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                }
            }
        }

        val pickerImageLoader = remember { PickerImageLoader.get(context) }
        CompositionLocalProvider(LocalImageLoader provides pickerImageLoader) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(WeChatDarkBackground)
        ) {
        // ═══ 页面转场动画 ═══
        AnimatedContent(
            modifier = Modifier.fillMaxSize(),
            targetState = currentPage,
            transitionSpec = {
                when {
                    // GRID → ALBUMS: 从左滑入
                    targetState == PickerPage.ALBUMS -> {
                        (slideInHorizontally(tween(280)) { -it / 3 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally(tween(280)) { it / 3 } + fadeOut(tween(150)))
                    }
                    // ALBUMS → GRID: 从右滑出
                    initialState == PickerPage.ALBUMS -> {
                        (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(200)))
                            .togetherWith(slideOutHorizontally(tween(280)) { -it / 3 } + fadeOut(tween(150)))
                    }
                    // GRID → PREVIEW / PREVIEW → GRID: 缩放 + 淡入淡出
                    else -> {
                        (scaleIn(tween(240), initialScale = 0.92f) + fadeIn(tween(200)))
                            .togetherWith(fadeOut(tween(180)))
                    }
                }
            },
            label = "pageTransition"
        ) { page ->
            when (page) {
                PickerPage.GRID -> {
                    ImageGrid(
                        viewModel = viewModel,
                        onShowAlbums = { currentPage = PickerPage.ALBUMS },
                        onItemClick = { index ->
                            if (maxSelection == 1) {
                                onConfirmed(listOf(contentResolverToUri(index)))
                            }
                        },
                        onItemPreview = { index ->
                            previewInitialIndex = index
                            currentPage = PickerPage.PREVIEW
                        },
                        onConfirm = {
                            val uris = viewModel.selectedIds().map { id ->
                                contentResolverToUri(id)
                            }
                            onConfirmed(uris)
                        },
                        onDismiss = onDismiss,
                        hasSelection = hasSelection,
                        maxSelection = maxSelection
                    )
                }
                PickerPage.ALBUMS -> {
                    AlbumListSheet(
                        viewModel = viewModel,
                        onAlbumSelected = { bucketId, name ->
                            viewModel.switchAlbum(bucketId, name)
                            currentPage = PickerPage.GRID
                        },
                        onDismiss = { currentPage = PickerPage.GRID }
                    )
                }
                PickerPage.PREVIEW -> {
                    ImagePreview(
                        viewModel = viewModel,
                        initialIndex = previewInitialIndex,
                        onBack = { currentPage = PickerPage.GRID },
                        onConfirm = {
                            val uris = viewModel.selectedIds().map { id ->
                                contentResolverToUri(id)
                            }
                            onConfirmed(uris)
                        }
                    )
                }
            }
        }
        } // end Box fillMaxSize
        } // end CompositionLocalProvider
    }
}

private enum class PickerPage { GRID, ALBUMS, PREVIEW }

/** 纯拼接 Content URI，零 I/O */
private fun contentResolverToUri(mediaId: Long): Uri =
    Uri.parse("${android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$mediaId")
