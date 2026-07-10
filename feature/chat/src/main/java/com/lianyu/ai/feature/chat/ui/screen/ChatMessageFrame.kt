package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import com.lianyu.ai.feature.chat.ui.theme.ChatBubbleSide
import com.lianyu.ai.feature.chat.ui.theme.ChatBubbleSpec
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.feature.chat.ui.theme.chatBubbleBackground
import com.lianyu.ai.uicommon.component.AppListItemLayout
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

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
    val colors = ChatTheme.colors
    val metrics = ChatTheme.metrics
    val shapes = ChatTheme.shapes
    val haptic = LocalHapticFeedback.current
    val bubbleColor = if (isMine) colors.userBubble else colors.aiBubble
    val bubbleContentPadding = Modifier.padding(
        horizontal = adaptiveSizing.chatBubblePaddingHorizontal,
        vertical = adaptiveSizing.chatBubblePaddingVertical
    )
    val bubbleModifier = if (drawBubble) {
        Modifier
            .chatBubbleBackground(
                color = bubbleColor,
                borderColor = if (!isMine && !isDarkTheme) colors.aiBorder else null,
                borderWidth = metrics.bubbleBorderWidth,
                spec = ChatBubbleSpec(
                    cornerRadius = adaptiveSizing.cornerRadius,
                    side = if (isMine) ChatBubbleSide.End else ChatBubbleSide.Start,
                    arrowWidth = shapes.bubbleArrowWidth,
                    arrowHeight = shapes.bubbleArrowHeight,
                    arrowOffsetY = shapes.bubbleArrowOffsetY
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
        slotGap = metrics.avatarGap
    ) {
        Column(horizontalAlignment = if (isMine) Alignment.End else Alignment.Start) {
            Box(modifier = gestureModifier.then(bubbleModifier)) { content() }
        }
    }
}