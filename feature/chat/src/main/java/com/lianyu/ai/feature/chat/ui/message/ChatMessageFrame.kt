package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
    val bubbleColor = if (isMine) colors.primaryBubbleBackground else colors.secondaryBubbleBackground
    val bubbleContentPadding = Modifier.padding(
        horizontal = adaptiveSizing.chatBubblePaddingHorizontal,
        vertical = adaptiveSizing.chatBubblePaddingVertical
    )
    val bubbleModifier = if (drawBubble) {
        Modifier
            .appBubbleBackground(
                color = bubbleColor,
                borderColor = if (!isMine && !isDarkTheme) colors.secondaryBubbleBorder else null,
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
        startSlot = avatar,
        endSlot = {},
        modifier = modifier,
        slotGap = dimens.avatarGap
    ) {
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
            Box(modifier = gestureModifier.then(bubbleModifier)) { content() }
        }
    }
}

