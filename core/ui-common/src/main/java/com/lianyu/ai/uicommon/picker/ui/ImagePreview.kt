package com.lianyu.ai.uicommon.picker.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.lianyu.ai.uicommon.theme.*

private val Accent = PinkPrimary

/**
 * 大图预览页 — HorizontalPager 复用 PagingData。
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

    val currentItem = lazyPagingItems[pagerState.currentPage]

    // 放大/缩略切换
    var isZoomed by remember { mutableStateOf(false) }

    // 控制栏显隐
    var controlsVisible by remember { mutableStateOf(true) }
    val controlsAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(250),
        label = "controlsAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ═══ 图片 Pager ═══
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { controlsVisible = !controlsVisible }
        ) { page ->
            val item = lazyPagingItems[page]
            if (item != null) {
                val model = if (isZoomed) {
                    ImageRequest.Builder(LocalContext.current)
                        .data(item.uri)
                        .size(Size.ORIGINAL)
                        .crossfade(true)
                        .build()
                } else {
                    item.uri
                }

                AsyncImage(
                    model = model,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (isZoomed) ContentScale.Fit else ContentScale.Fit
                )
            }
        }

        // ═══ 顶部渐变控制栏 ═══
        AnimatedVisibility(
            visible = controlsAlpha > 0.01f,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(controlsAlpha)
                    .statusBarsPadding()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC000000),
                                Color(0x88000000),
                                Color.Transparent
                            )
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Text(
                        "${pagerState.currentPage + 1} / $itemCount",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )

                    if (pickerState.maxSelection > 1) {
                        TextButton(
                            onClick = onConfirm,
                            enabled = selectionMap.isNotEmpty()
                        ) {
                            val text = if (selectionMap.isNotEmpty())
                                "完成(${selectionMap.size})" else "完成"
                            Text(
                                text,
                                color = if (selectionMap.isNotEmpty()) Accent
                                    else Color.White.copy(alpha = 0.4f),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // ═══ 底部选中/原图浮层 ═══
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 原图按钮
            if (currentItem != null) {
                TextButton(
                    onClick = { isZoomed = !isZoomed },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (isZoomed) Accent else Color.White.copy(alpha = 0.7f)
                    )
                ) {
                    Text(
                        if (isZoomed) "原图 ✓" else "原图",
                        fontSize = 14.sp,
                        fontWeight = if (isZoomed) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // 多选按钮
            if (pickerState.maxSelection > 1 && currentItem != null) {
                val currentId = currentItem.id
                val isCurrentSelected by remember(currentId) {
                    derivedStateOf { selectionMap.containsKey(currentId) }
                }
                val order by remember(currentId) {
                    derivedStateOf { selectionMap[currentId] }
                }

                val btnScale by animateFloatAsState(
                    targetValue = if (isCurrentSelected) 1.1f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh
                    ),
                    label = "selectBtnScale"
                )

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(btnScale)
                        .clip(CircleShape)
                        .background(
                            if (isCurrentSelected) Accent
                            else Color.White.copy(alpha = 0.25f)
                        )
                        .clickable { viewModel.toggleSelection(currentId) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isCurrentSelected && order != null) {
                        Text(
                            "$order",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
