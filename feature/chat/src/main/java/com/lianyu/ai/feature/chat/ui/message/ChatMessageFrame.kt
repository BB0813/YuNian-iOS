package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import com.lianyu.ai.uicommon.component.AppListItemLayout
import com.lianyu.ai.uicommon.theme.AppBubbleSide
import com.lianyu.ai.uicommon.theme.AppBubbleSpec
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.AppTheme
import com.lianyu.ai.uicommon.theme.appBubbleBackground
import androidx.compose.ui.unit.dp

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ChatMessageFrame(
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing,
    avatar: @Composable () -> Unit,
    timestamp: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    drawBubble: Boolean = true,
    isDarkTheme: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val colors = AppTheme.colors
    val dimens = AppTheme.dimens
    val haptic = LocalHapticFeedback.current
    // 自己 / AI 共用同一套 appBubbleBackground 实现：
    // - 颜色 token 一致（亮粉色）
    // - 仅 side 镜像：自己 End（右箭头），AI Start（左箭头）
    // - 边框策略一致，避免两侧视觉差异
    val bubbleColor = if (isMine) colors.primaryBubbleBackground else colors.secondaryBubbleBackground
    val bubbleContentPadding = Modifier.padding(
        horizontal = adaptiveSizing.chatBubblePaddingHorizontal,
        vertical = adaptiveSizing.chatBubblePaddingVertical
    )
    val bubbleModifier = if (drawBubble) {
        Modifier
            .appBubbleBackground(
                color = bubbleColor,
                borderColor = colors.secondaryBubbleBorder,
                borderWidth = dimens.bubbleBorderWidth,
                spec = AppBubbleSpec(
                    cornerRadius = adaptiveSizing.cornerRadius,
                    side = if (isMine) AppBubbleSide.End else AppBubbleSide.Start,
                    arrowWidth = 5.dp,
                    arrowHeight = 8.dp,
                    arrowOffsetY = 14.dp
                )
            )
            .then(bubbleContentPadding)
    } else {
        Modifier
    }
    val gestureModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                onLongClick?.invoke()
            },
            interactionSource = null,
            indication = null
        )
    } else {
        Modifier
    }

    AppListItemLayout(
        isStartAligned = !isMine,
        startSlot = {
            Box(
                modifier = Modifier.size(adaptiveSizing.avatarSize),
                contentAlignment = Alignment.TopCenter
            ) {
                avatar()
            }
        },
        endSlot = {},
        modifier = modifier,
        slotGap = dimens.avatarGap
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = dimens.textBubbleMaxWidth)
                    .then(gestureModifier)
                    .then(bubbleModifier)
            ) {
                content()
            }
        }
    }
}

