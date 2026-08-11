package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.lianyu.ai.uicommon.component.AppListItemLayout
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.AppBubbleSide
import com.lianyu.ai.uicommon.theme.AppBubbleSpec
import com.lianyu.ai.uicommon.theme.AppTheme
import com.lianyu.ai.uicommon.theme.appBubbleBackground

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
    // 自己 lilac（primary）/ AI sky（secondary）分色区分归属，side 镜像为辅
    val bubbleColor = if (isMine) colors.primaryBubbleBackground else colors.secondaryBubbleBackground
    // 箭头占位：画在气泡盒内侧，不计入正文区
    val bubbleArrowWidth = 5.dp
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
                    arrowWidth = bubbleArrowWidth,
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

    // 整行占满列表宽度：AppListItemLayout 先扣本侧头像列，内容只在中间走廊测量。
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
        modifier = modifier.fillMaxWidth(),
        slotGap = dimens.avatarGap
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // 对齐规则（两侧对称）：
            // AI 正文起点 = 本侧头像 + 间距 + 箭头
            // 我方气泡最大左缘对齐该点 → 内容列内再预留「对侧头像 + 间距 + 箭头」
            // 气泡盒宽度含本侧箭头；正文区不含箭头尾巴。
            val oppositeReserve = adaptiveSizing.avatarSize + dimens.avatarGap + bubbleArrowWidth
            val bubbleMaxWidth = (maxWidth - oppositeReserve).coerceAtLeast(0.dp)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .then(gestureModifier)
                        .then(bubbleModifier)
                ) {
                    content()
                }
            }
        }
    }
}

