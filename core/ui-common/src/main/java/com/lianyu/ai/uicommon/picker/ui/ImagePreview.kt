package com.lianyu.ai.uicommon.picker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import androidx.compose.ui.platform.LocalContext

/**
 * 大图预览页 — HorizontalPager，复用 PickerViewModel 数据。
 *
 * 核心要点：
 * - 复用 ViewModel 已有的 PagingData，不重新查询
 * - 默认加载缩略图（Coil size 参数），双指放大时加载原图
 * - 选中/取消通过共享 ViewModel 联动网格页
 */
@Composable
internal fun ImagePreview(
    viewModel: PickerViewModel,
    initialIndex: Int,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    val pickerState by viewModel.state.collectAsState()
    val selectionMap by viewModel.selectionMap.collectAsState()

    val lazyPagingItems = viewModel.pagingFlow.collectAsState().value
        .collectAsLazyPagingItems()

    val itemCount = lazyPagingItems.itemCount
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { itemCount })

    // 当前预览的 Item
    val currentItem = lazyPagingItems[pagerState.currentPage]

    // 是否放大视图（加载原图）
    var isZoomed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---------- 图片 Pager ----------
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = lazyPagingItems[page]
            if (item != null) {
                // 默认缩略图，放大时原图
                val model = if (isZoomed) {
                    ImageRequest.Builder(LocalContext.current)
                        .data(item.uri)
                        .size(Size.ORIGINAL)
                        .build()
                } else {
                    item.uri
                }

                AsyncImage(
                    model = model,
                    contentDescription = item.displayName,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { isZoomed = !isZoomed },
                    contentScale = if (isZoomed) ContentScale.Fit else ContentScale.Fit
                )
            }
        }

        // ---------- 顶部栏 ----------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .background(Color(0x66000000)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 返回
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "返回",
                    tint = Color.White
                )
            }

            // 页码
            Text(
                "${pagerState.currentPage + 1} / $itemCount",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )

            // 完成
            if (pickerState.maxSelection > 1) {
                TextButton(
                    onClick = onConfirm,
                    enabled = selectionMap.isNotEmpty()
                ) {
                    val text = if (selectionMap.isNotEmpty()) "完成(${selectionMap.size})" else "完成"
                    Text(
                        text,
                        color = if (selectionMap.isNotEmpty()) Color(0xFF4FC3F7) else Color.Gray,
                        fontSize = 15.sp
                    )
                }
            }
        }

        // ---------- 底部选中按钮 ----------
        if (pickerState.maxSelection > 1 && currentItem != null) {
            val currentId = currentItem.id
            val isCurrentSelected by remember(currentId) {
                derivedStateOf { selectionMap.containsKey(currentId) }
            }
            val order by remember(currentId) {
                derivedStateOf { selectionMap[currentId] }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (isCurrentSelected) Color(0xFF4FC3F7)
                            else Color(0x88000000)
                        )
                        .clickable { viewModel.toggleSelection(currentId) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrentSelected && order != null) {
                        Text(
                            "$order",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
