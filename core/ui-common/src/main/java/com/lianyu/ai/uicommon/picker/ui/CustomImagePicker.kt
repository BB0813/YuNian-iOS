package com.lianyu.ai.uicommon.picker.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel

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
        // ═══ 页面转场动画 ═══
        AnimatedContent(
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
    }
}

private enum class PickerPage { GRID, ALBUMS, PREVIEW }

/** 纯拼接 Content URI，零 I/O */
private fun contentResolverToUri(mediaId: Long): Uri =
    Uri.parse("${android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$mediaId")
