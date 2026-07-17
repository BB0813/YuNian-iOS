package com.lianyu.ai.uicommon.picker.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.picker.model.MediaItem
import com.lianyu.ai.uicommon.theme.PinkPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkSurface
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary

/** 选择器品牌色 */
private val Accent = PinkPrimary
private val Bg = WeChatDarkBackground
private val SurfaceColor = WeChatDarkSurface

/**
 * 图片网格主界面 — Paging 3 + Compose。
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
            .background(Bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ═══ 顶部栏 — 渐变背景 ═══
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                SurfaceColor,
                                SurfaceColor.copy(alpha = 0.95f),
                                SurfaceColor.copy(alpha = 0f)
                            )
                        )
                    )
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 取消
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = WeChatDarkTextPrimary, fontSize = 16.sp)
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 相册名 + 下拉箭头
                    TextButton(onClick = onShowAlbums) {
                        Text(
                            pickerState.currentAlbumName,
                            color = WeChatDarkTextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = "切换相册",
                            tint = WeChatDarkTextPrimary,
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
                                color = if (hasSelection) Accent else WeChatDarkTextSecondary,
                                fontSize = 16.sp,
                                fontWeight = if (hasSelection) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // ═══ 图片网格 ═══
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 1.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalItemSpacing = 1.dp
            ) {
                items(
                    count = lazyPagingItems.itemCount,
                    key = { idx -> lazyPagingItems[idx]?.id ?: idx }
                ) { index ->
                    val item = lazyPagingItems[index]
                    if (item != null) {
                        val isSelected by remember(item.id) {
                            derivedStateOf { selectionMap.containsKey(item.id) }
                        }
                        val order by remember(item.id) {
                            derivedStateOf { selectionMap[item.id] }
                        }

                        GridPhotoItem(
                            item = item,
                            isSelected = isSelected,
                            selectedOrder = order,
                            maxSelection = maxSelection,
                            onClick = { onItemClick(item.id) },
                            onRequestPreview = { onItemPreview(index) }
                        )
                    } else {
                        // 占位骨架
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .background(WeChatDarkSurface)
                        )
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 网格中的单张图片 Item
// ═════════════════════════════════════════════════════════════════════════════

@Composable
private fun GridPhotoItem(
    item: MediaItem,
    isSelected: Boolean,
    selectedOrder: Int?,
    maxSelection: Int,
    onClick: () -> Unit,
    onRequestPreview: () -> Unit
) {
    // 选中动画 — 弹簧缩放
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "selectionScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .scale(scale)
    ) {
        // 图片
        AsyncImage(
            model = item.uri,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(2.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() },
            contentScale = ContentScale.Crop
        )

        // 选中蒙层
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(2.dp))
                    .background(Accent.copy(alpha = 0.15f))
            )
        }

        // 选中角标 / 预览入口
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (isSelected) Accent
                    else Color(0x55000000)
                )
                .then(
                    if (!isSelected) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    else Modifier
                )
                .then(
                    if (maxSelection > 1) Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onRequestPreview() }
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected && selectedOrder != null) {
                Text(
                    "$selectedOrder",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
