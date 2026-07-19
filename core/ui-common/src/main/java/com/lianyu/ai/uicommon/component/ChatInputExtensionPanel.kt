package com.lianyu.ai.uicommon.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Mood
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.uicommon.model.ApiProviderInfo
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.rememberAdaptiveSizing

/**
 * 扩展面板网格契约（架构级，禁止改成流式/整体居中）：
 * - 固定 2 行 × 4 列 = 8 槽/页
 * - 槽位索引：左上=0，向右递增，满行后换到下一行最左
 * - 功能按 [buildExtensionItems] 声明顺序依次填入槽位 0..n-1
 * - 空槽只占位，不收缩、不 SpaceEvenly、不把剩余项整体居中
 */
private const val EXTENSION_COLUMNS = 4
private const val EXTENSION_ROWS = 2
private const val EXTENSION_PAGE_CAPACITY = EXTENSION_COLUMNS * EXTENSION_ROWS

@Composable
fun ChatInputExtensionPanel(
    isVisible: Boolean,
    availableApis: List<ApiProviderInfo> = emptyList(),
    currentApi: ApiProviderInfo? = null,
    onSwitchApi: ((ApiProviderInfo) -> Unit)? = null,
    onAlbumClick: () -> Unit = {},
    onCameraClick: () -> Unit = {},
    onVideoCallClick: () -> Unit = {},
    onVoiceCallClick: (() -> Unit)? = null,
    onTtsModeClick: (() -> Unit)? = null,
    onLocationClick: () -> Unit = {},
    onVoiceInputClick: () -> Unit = {},
    onStickerClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically(animationSpec = tween(250)) + fadeIn(tween(200)),
        exit = shrinkVertically(animationSpec = tween(200)) + fadeOut(tween(150)),
        modifier = modifier
    ) {
        val adaptiveSizing = rememberAdaptiveSizing()
        val bgColor = MaterialTheme.colorScheme.surfaceVariant
        val iconBgColor = MaterialTheme.colorScheme.surface
        val textColor = MaterialTheme.colorScheme.onSurfaceVariant
        val iconTintColor = MaterialTheme.colorScheme.onSurface
        val indicatorActive = MaterialTheme.colorScheme.primary
        val indicatorInactive = MaterialTheme.colorScheme.outlineVariant

        val items = remember(
            availableApis,
            currentApi,
            onSwitchApi,
            onAlbumClick,
            onCameraClick,
            onVideoCallClick,
            onVoiceCallClick,
            onTtsModeClick,
            onLocationClick,
            onStickerClick,
            onVoiceInputClick
        ) {
            buildExtensionItems(
                availableApis = availableApis,
                currentApi = currentApi,
                onSwitchApi = onSwitchApi,
                onAlbumClick = onAlbumClick,
                onCameraClick = onCameraClick,
                onVideoCallClick = onVideoCallClick,
                onVoiceCallClick = onVoiceCallClick,
                onTtsModeClick = onTtsModeClick,
                onLocationClick = onLocationClick,
                onStickerClick = onStickerClick,
                onVoiceInputClick = onVoiceInputClick
            )
        }
        val pages = remember(items) { items.chunked(EXTENSION_PAGE_CAPACITY).ifEmpty { listOf(emptyList()) } }
        val pagerState = rememberPagerState(pageCount = { pages.size })

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(top = 10.dp, bottom = 8.dp)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth(),
                // 页边距固定；禁止用 contentPadding 制造“内容整体居中”的视觉
                contentPadding = PaddingValues(horizontal = 8.dp),
                pageSpacing = 0.dp,
                // 每页独立槽位网格，不随内容量改变对齐方式
                verticalAlignment = Alignment.Top
            ) { page ->
                ExtensionSlotGrid(
                    pageItems = pages[page],
                    iconBgColor = iconBgColor,
                    textColor = textColor,
                    iconTintColor = iconTintColor,
                    adaptiveSizing = adaptiveSizing
                )
            }

            if (pages.size > 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pages.size) { index ->
                        val selected = pagerState.currentPage == index
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (selected) 7.dp else 5.dp)
                                .clip(CircleShape)
                                .background(if (selected) indicatorActive else indicatorInactive)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 功能声明顺序即槽位填充顺序（架构级）：
 * 0 相册 → 1 拍摄 → 2 视频通话 → 3 语音通话(可选) →
 * 4 朗读模式(可选) → 5 位置 → 6 表情包 → 7 语音输入 → 8+ 切换模型(可选，溢出到下一页)
 *
 * 可选能力缺失时：跳过该项，后续项前移填入连续槽位（仍从左上开始，不居中）。
 */
private fun buildExtensionItems(
    availableApis: List<ApiProviderInfo>,
    currentApi: ApiProviderInfo?,
    onSwitchApi: ((ApiProviderInfo) -> Unit)?,
    onAlbumClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onVoiceCallClick: (() -> Unit)?,
    onTtsModeClick: (() -> Unit)?,
    onLocationClick: () -> Unit,
    onStickerClick: () -> Unit,
    onVoiceInputClick: () -> Unit
): List<ExtensionItem> {
    val hasMultipleApis = availableApis.size > 1 && onSwitchApi != null
    return buildList {
        add(ExtensionItem("相册", Icons.Filled.Image, onAlbumClick))
        add(ExtensionItem("拍摄", Icons.Filled.CameraAlt, onCameraClick))
        add(ExtensionItem("视频通话", Icons.Filled.Videocam, onVideoCallClick))
        onVoiceCallClick?.let { add(ExtensionItem("语音通话", Icons.Filled.Call, it)) }
        onTtsModeClick?.let { add(ExtensionItem("朗读模式", Icons.AutoMirrored.Filled.VolumeUp, it)) }
        add(ExtensionItem("位置", Icons.Filled.LocationOn, onLocationClick))
        add(ExtensionItem("表情包", Icons.Filled.Mood, onStickerClick))
        add(ExtensionItem("语音输入", Icons.Filled.Mic, onVoiceInputClick))
        if (hasMultipleApis) {
            add(
                ExtensionItem("切换模型", Icons.Filled.SwapHoriz) {
                    val next = currentApi?.let { c ->
                        val idx = availableApis.indexOfFirst { it.name == c.name }
                        availableApis.getOrNull((idx + 1) % availableApis.size)
                    } ?: availableApis.first()
                    onSwitchApi!!(next)
                }
            )
        }
    }
}

/**
 * 固定 2×4 隐形槽位网格。
 * 每个槽 weight(1f) 等分列宽；空槽保留空间，禁止 SpaceEvenly / 剩余项整体居中。
 */
@Composable
private fun ExtensionSlotGrid(
    pageItems: List<ExtensionItem>,
    iconBgColor: Color,
    textColor: Color,
    iconTintColor: Color,
    adaptiveSizing: AdaptiveSizing
) {
    val slots: List<ExtensionItem?> = List(EXTENSION_PAGE_CAPACITY) { index ->
        pageItems.getOrNull(index)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(adaptiveSizing.extensionGridSpacing)
    ) {
        repeat(EXTENSION_ROWS) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                // 等分槽位，禁止 SpaceEvenly / Center / SpaceAround
                horizontalArrangement = Arrangement.Start
            ) {
                repeat(EXTENSION_COLUMNS) { col ->
                    val slotIndex = row * EXTENSION_COLUMNS + col
                    val item = slots[slotIndex]
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        // 槽内图标相对本槽居中；整页不因条目变少而整体居中
                        contentAlignment = Alignment.TopCenter
                    ) {
                        if (item != null) {
                            ExtensionIconButton(
                                label = item.label,
                                icon = item.icon,
                                iconBgColor = iconBgColor,
                                textColor = textColor,
                                iconTintColor = iconTintColor,
                                onClick = item.onClick,
                                adaptiveSizing = adaptiveSizing
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionIconButton(
    label: String,
    icon: ImageVector,
    iconBgColor: Color,
    textColor: Color,
    iconTintColor: Color,
    onClick: () -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp, horizontal = 2.dp)
    ) {
        // 涟漪仅作用在 icon 区域
        Box(
            modifier = Modifier
                .size(adaptiveSizing.extensionIconBoxSize)
                .clip(RoundedCornerShape(16.dp))
                .background(iconBgColor)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    role = Role.Button,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(adaptiveSizing.extensionIconSize),
                tint = iconTintColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = adaptiveSizing.fontSizeSmall.sp,
            color = textColor,
            fontWeight = FontWeight.Normal,
            maxLines = 1
        )
    }
}

private data class ExtensionItem(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)
