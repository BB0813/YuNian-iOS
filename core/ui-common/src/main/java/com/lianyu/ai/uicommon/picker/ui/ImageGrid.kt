package com.lianyu.ai.uicommon.picker.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.lianyu.ai.uicommon.picker.model.MediaItem
import com.lianyu.ai.uicommon.theme.PinkPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkSurface
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 选择器品牌色 */
private val Accent = PinkPrimary
private val Bg = WeChatDarkBackground
private val SurfaceColor = WeChatDarkSurface

/** 日期格式化 — 滚动指示器用 */
private val dateFormat = SimpleDateFormat("yyyy年M月", Locale.getDefault())
private fun formatDate(ts: Long): String = dateFormat.format(Date(ts * 1000))

/**
 * 图片网格主界面。
 * 四列等宽网格 + 右侧滚动条 + 日期浮动标签。
 *
 * 注意：使用默认 fling，不额外阻尼；滚动条触摸区收窄，避免抢主列表滑动。
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

    val mediaList by viewModel.mediaList.collectAsState()

    val gridState = rememberLazyGridState()
    val totalCount = mediaList.size
    val dragScope = rememberCoroutineScope()

    // ═══ 滚动状态 ═══
    var isDraggingScrollbar by remember { mutableStateOf(false) }
    var showDateLabel by remember { mutableStateOf(false) }

    // 切换相册后回到顶部，避免旧滚动索引超出新列表导致空白/错位
    LaunchedEffect(pickerState.currentBucketId) {
        gridState.scrollToItem(0)
    }

    val firstVisibleDate by remember(totalCount) {
        derivedStateOf {
            val idx = gridState.firstVisibleItemIndex
            mediaList.getOrNull(idx)?.let { formatDate(it.dateAdded) }.orEmpty()
        }
    }

    // 滚动条进度 — 拖动时使用命令式 dragProgress，否则用 gridState 派生
    var dragProgress by remember { mutableFloatStateOf(0f) }
    val scrollProgress by remember {
        derivedStateOf {
            if (totalCount == 0) 0f
            else {
                val idx = gridState.firstVisibleItemIndex
                val off = gridState.firstVisibleItemScrollOffset
                val itemH = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 1
                val smoothIdx = idx.toFloat() - off.toFloat() / itemH.toFloat().coerceAtLeast(1f)
                (smoothIdx / totalCount).coerceIn(0f, 1f)
            }
        }
    }
    // 拖动时直接用手指位置算进度（实时跟随），松手后回退到 gridState 派生
    val displayProgress = if (isDraggingScrollbar) dragProgress else scrollProgress

    LaunchedEffect(gridState.isScrollInProgress, isDraggingScrollbar) {
        if (gridState.isScrollInProgress || isDraggingScrollbar) {
            showDateLabel = true
        } else {
            delay(500)
            showDateLabel = false
        }
    }

    val dateLabelAlpha by animateFloatAsState(
        targetValue = if (showDateLabel) 1f else 0f,
        animationSpec = tween(180),
        label = "dateAlpha"
    )

    Box(modifier = Modifier.fillMaxSize().background(Bg)) {
        Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            // ═══ 顶部栏 ═══
            PickerTopBar(
                modifier = Modifier.background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceColor,
                            SurfaceColor.copy(alpha = 0.95f),
                            SurfaceColor.copy(alpha = 0f)
                        )
                    )
                )
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = WeChatDarkTextPrimary, fontSize = 16.sp)
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onShowAlbums) {
                    Text(
                        pickerState.currentAlbumName,
                        color = WeChatDarkTextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        "切换相册",
                        tint = WeChatDarkTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (maxSelection > 1) {
                    TextButton(onClick = onConfirm, enabled = hasSelection) {
                        val t = if (hasSelection) "完成(${selectionMap.size})" else "完成"
                        Text(
                            t,
                            color = if (hasSelection) Accent else WeChatDarkTextSecondary,
                            fontSize = 16.sp,
                            fontWeight = if (hasSelection) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // ═══ 四列网格 + 滚动条 ═══
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().padding(end = 6.dp),
                    contentPadding = PaddingValues(horizontal = 1.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(1.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(
                        items = mediaList,
                        key = { it.id }
                    ) { item ->
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
                            onRequestPreview = { onItemPreview(mediaList.indexOf(item)) }
                        )
                    }
                }

                // ═══ 右侧滚动条（可拖拽 + 平滑滑块） ═══
                // 触摸区收窄到 12dp，避免覆盖网格右侧导致滑动手感发粘
                if (totalCount > 0) {
                    val thumbFraction = remember(totalCount) {
                        (40f / totalCount.coerceAtLeast(1)).coerceIn(0.03f, 0.12f)
                    }
                    var trackHeightPx by remember { mutableStateOf(0f) }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight(0.96f)
                            .width(12.dp)
                            .pointerInput(totalCount) {
                                detectVerticalDragGestures(
                                    onDragStart = {
                                        isDraggingScrollbar = true
                                        dragProgress = (it.y / size.height).coerceIn(0f, 1f)
                                        val targetIdx = (dragProgress * totalCount).toInt().coerceIn(0, totalCount - 1)
                                        dragScope.launch { gridState.scrollToItem(targetIdx) }
                                    },
                                    onVerticalDrag = { change, _ ->
                                        dragProgress = (change.position.y / size.height).coerceIn(0f, 1f)
                                        val targetIdx = (dragProgress * totalCount).toInt().coerceIn(0, totalCount - 1)
                                        dragScope.launch { gridState.scrollToItem(targetIdx) }
                                    },
                                    onDragEnd = { isDraggingScrollbar = false },
                                    onDragCancel = { isDraggingScrollbar = false }
                                )
                            },
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        // 内层：可视轨道（3dp 宽）
                        Box(
                            modifier = Modifier
                                .fillMaxHeight(1f)
                                .width(3.dp)
                                .padding(end = 1.dp)
                                .onSizeChanged { trackHeightPx = it.height.toFloat() }
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.10f))
                        ) {
                            // 白色滑块 — displayProgress 拖动时实时跟手
                            val maxY = trackHeightPx * (1f - thumbFraction)
                            val thumbY = displayProgress * maxY
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(thumbFraction)
                                    .offset { IntOffset(0, thumbY.toInt()) }
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Color.White)
                            )
                        }
                    }
                }

                // ═══ 日期浮动标签 ═══
                if (firstVisibleDate.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .offset(y = (-16).dp)
                            .alpha(dateLabelAlpha)
                            .background(Color(0xCC1A1A1A), RoundedCornerShape(10.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) {
                        Text(
                            firstVisibleDate,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// 单图 Item — 性能优化版
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
    // scale 放入 graphicsLayer — 动画走 GPU 层，绕过重组
    val targetScale by animateFloatAsState(
        targetValue = if (isSelected) 0.93f else 1f,
        animationSpec = tween(100),
        label = "selScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .background(SurfaceColor)          // 加载占位框架
            .graphicsLayer {
                scaleX = targetScale
                scaleY = targetScale
                clip = true
                shape = RoundedCornerShape(2.dp)
            }
    ) {
        // 缩略图 size=200：四列网格精确尺寸
        val ctx = LocalContext.current
        val request = remember(item.uri) {
            ImageRequest.Builder(ctx)
                .data(item.uri)
                .size(200)
                .build()
        }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onClick() },
            contentScale = ContentScale.Crop,
            loading = {
                Box(Modifier.fillMaxSize().background(SurfaceColor))
            },
            error = {
                Box(Modifier.fillMaxSize().background(SurfaceColor))
            }
        )

        // 选中蒙层
        if (isSelected) {
            Box(Modifier.fillMaxSize().background(Accent.copy(alpha = 0.15f)))
        }

        // 角标（多选模式才显示）
        if (maxSelection > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) Accent else Color(0x55000000))
                    .then(
                        if (!isSelected) Modifier.border(1.dp, Color.White.copy(alpha = 0.65f), CircleShape)
                        else Modifier
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onRequestPreview() },
                contentAlignment = Alignment.Center
            ) {
                if (isSelected && selectedOrder != null) {
                    Text("$selectedOrder", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
