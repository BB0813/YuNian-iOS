package com.lianyu.ai.uicommon.picker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.picker.model.MediaItem

/**
 * 图片网格主界面 — Paging 3 + Compose。
 *
 * 核心性能要点：
 * - `key = { it.id }` — 确保 Compose 精确识别每个 Item
 * - `derivedStateOf { selectionMap.containsKey(id) }` — 隔离重组范围，
 *   选中 1 张图只重组那 1 个 Item，其他 59 个不重组
 * - `collectAsLazyPagingItems()` — Paging 3 原生 Compose 集成
 */
@Composable
internal fun ImageGrid(
    viewModel: PickerViewModel,
    onShowAlbums: () -> Unit,
    onItemClick: (Long) -> Unit,
    onItemPreview: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    hasSelection: Boolean,
    maxSelection: Int
) {
    val pickerState by viewModel.state.collectAsState()
    val selectionMap by viewModel.selectionMap.collectAsState()

    val lazyPagingItems: LazyPagingItems<MediaItem> =
        viewModel.pagingFlow.collectAsState().value
            .collectAsLazyPagingItems()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---------- 顶部栏 ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .statusBarsPadding(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 取消
                TextButton(onClick = onDismiss) {
                    Text("取消", color = Color.White, fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.weight(1f))

                // 相册名 + 下拉箭头
                TextButton(onClick = onShowAlbums) {
                    Text(
                        pickerState.currentAlbumName,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = "切换相册",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // 完成按钮
                if (maxSelection > 1) {
                    TextButton(
                        onClick = onConfirm,
                        enabled = hasSelection
                    ) {
                        val text = if (hasSelection) "完成(${selectionMap.size})" else "完成"
                        Text(
                            text,
                            color = if (hasSelection) Color(0xFF4FC3F7) else Color.Gray,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            // ---------- 图片网格 ----------
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalItemSpacing = 2.dp
            ) {
                items(
                    count = lazyPagingItems.itemCount,
                    key = { idx -> lazyPagingItems[idx]?.id ?: idx }
                ) { index ->
                    val item = lazyPagingItems[index]
                    if (item != null) {
                        // 关键：derivedStateOf 隔离重组范围
                        val isSelected by remember(item.id) {
                            derivedStateOf { selectionMap.containsKey(item.id) }
                        }
                        val order by remember(item.id) {
                            derivedStateOf { selectionMap[item.id] }
                        }

                        // 点击：根据元素在适配器中的位置，传给 Item 自己在 Grid 里回调
                        GridPhotoItem(
                            item = item,
                            isSelected = isSelected,
                            selectedOrder = order,
                            maxSelection = maxSelection,
                            onClick = {
                                onItemClick(item.id)
                            },
                            onRequestPreview = {
                                onItemPreview(index)
                            }
                        )
                    } else {
                        // 占位
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(Color(0xFF333333))
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 网格中的单张图片 Item
// ============================================================================

@Composable
private fun GridPhotoItem(
    item: MediaItem,
    isSelected: Boolean,
    selectedOrder: Int?,
    maxSelection: Int,
    onClick: () -> Unit,
    onRequestPreview: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        // 图片
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxSize()
                .clickable { onClick() },
            contentScale = ContentScale.Crop
        )

        // 多选模式下长按进入预览
        if (maxSelection > 1) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onRequestPreview() }
            )
        }

        // 选中角标
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) Color(0xFF4FC3F7)
                    else Color(0x88000000)
                )
                .then(
                    if (!isSelected) Modifier.border(1.5.dp, Color.White, CircleShape)
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected && selectedOrder != null) {
                Text(
                    "$selectedOrder",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
