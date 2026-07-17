package com.lianyu.ai.uicommon.picker.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 自研图片选择器顶层入口 — 全屏 Dialog。
 *
 * 包含三个子页面：相册网格 | 文件夹列表 | 大图预览。
 * 内部使用共享 [PickerViewModel]，三个子页面共享同一个数据源和选中态。
 *
 * @param maxSelection 最大可选数量（默认 1，即选完立即回调）
 * @param onConfirmed  确认回调，返回已选中的 URI 列表
 * @param onDismiss    取消/关闭回调
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

    // 初始化 maxSelection
    LaunchedEffect(maxSelection) {
        viewModel.setMaxSelection(maxSelection)
    }

    // 页面路由状态
    var currentPage by remember { mutableStateOf(PickerPage.GRID) }
    var previewInitialIndex by remember { mutableStateOf(0) }

    // 完成按钮
    val hasSelection = selectionMap.isNotEmpty()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        when (currentPage) {
            PickerPage.GRID -> {
                ImageGrid(
                    viewModel = viewModel,
                    onShowAlbums = { currentPage = PickerPage.ALBUMS },
                    onItemClick = { index ->
                        if (maxSelection == 1) {
                            // 单选模式：点图直接确认
                            val uri = contentResolverToUri(context.contentResolver, index)
                            if (uri != null) onConfirmed(listOf(uri))
                        }
                    },
                    onItemPreview = { index ->
                        previewInitialIndex = index
                        currentPage = PickerPage.PREVIEW
                    },
                    onConfirm = {
                        val uris = viewModel.selectedIds().mapNotNull { id ->
                            contentResolverToUri(context.contentResolver, id)
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
                        val uris = viewModel.selectedIds().mapNotNull { id ->
                            contentResolverToUri(context.contentResolver, id)
                        }
                        onConfirmed(uris)
                    }
                )
            }
        }
    }
}

/** 子页面路由 */
private enum class PickerPage { GRID, ALBUMS, PREVIEW }

/** 根据 mediaId 构造 Content URI */
private fun contentResolverToUri(
    contentResolver: android.content.ContentResolver,
    mediaId: Long
): Uri? {
    val cursor = contentResolver.query(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(android.provider.MediaStore.Images.Media._ID),
        "${android.provider.MediaStore.Images.Media._ID} = ?",
        arrayOf(mediaId.toString()),
        null
    )
    return cursor?.use {
        if (it.moveToFirst()) {
            Uri.parse("${android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI}/$mediaId")
        } else null
    }
}
