package com.lianyu.ai.uicommon.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import com.lianyu.ai.uicommon.theme.AppDimens

/**
 * App-level flat layout primitive for list items with a leading decorative slot,
 * a trailing decorative slot, and a content body.
 *
 * This is a generalized version of the chat-specific `ChatMessageFrame`.
 * It performs custom measurement & placement to avoid nested `Row`/`Column`
 * overhead, and provides unified gesture handling.
 *
 * Every list-type page in the app (chat, settings, contacts, history) can
 * reuse this layout and only provide their own content composables.
 *
 * **Layout direction:**
 * - `isStartAligned = true` → [startSlot] [gap] [content...] [endSlot]
 * - `isStartAligned = false` → [endSlot] [gap] [content...] [startSlot]
 *
 * @param isStartAligned whether the content aligns to the start (left) side.
 *   In chat, this is `true` for AI messages and `false` for user messages.
 * @param startSlot the primary decorative slot — typically an avatar.
 * @param endSlot the secondary decorative slot — usually empty; can hold
 *   status indicators, read receipts, etc.
 * @param onClick called when the content area is tapped.
 * @param onLongClick called when the content area is long-pressed.
 *   Haptic feedback is automatically performed.
 * @param slotGap horizontal gap between the slots and the content body.
 * @param content the main content body — a text bubble, image, attachment, etc.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun AppListItemLayout(
    isStartAligned: Boolean,
    startSlot: @Composable () -> Unit,
    endSlot: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    slotGap: Dp = AppDimens.AvatarGap,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current

    val gestureModifier = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = {
                haptic.performHapticFeedback(
                    androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                )
                onLongClick?.invoke()
            },
            interactionSource = null,
            indication = null
        )
    } else {
        Modifier
    }

    Layout(
        modifier = modifier,
        content = {
            Box(modifier = Modifier.layoutId("startSlot")) { startSlot() }
            Box(modifier = Modifier.layoutId("endSlot")) { endSlot() }
            Box(modifier = Modifier.layoutId("content").then(gestureModifier)) { content() }
        }
    ) { measurables, constraints ->
        val startMeasurable = measurables.first { it.layoutId == "startSlot" }
        val endMeasurable = measurables.first { it.layoutId == "endSlot" }
        val contentMeasurable = measurables.first { it.layoutId == "content" }

        val startPlaceable = startMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val endPlaceable = endMeasurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gapPx = slotGap.roundToPx()

        // Content gets the remaining width after both slots and gaps
        val contentMaxWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - startPlaceable.width - endPlaceable.width - gapPx * 2)
                .coerceAtLeast(0)
        } else {
            Constraints.Infinity
        }

        val contentPlaceable = contentMeasurable.measure(
            Constraints(
                minWidth = 0,
                maxWidth = contentMaxWidth,
                minHeight = 0,
                maxHeight = constraints.maxHeight
            )
        )

        val height = maxOf(startPlaceable.height, contentPlaceable.height, endPlaceable.height)
        val layoutWidth = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            startPlaceable.width + gapPx + contentPlaceable.width + gapPx + endPlaceable.width
        }.coerceIn(constraints.minWidth, constraints.maxWidth)

        layout(layoutWidth, height) {
            if (isStartAligned) {
                // [startSlot] [gap] [content............] [endSlot]
                startPlaceable.placeRelative(0, 0)
                contentPlaceable.placeRelative(
                    startPlaceable.width + gapPx,
                    0
                )
                endPlaceable.placeRelative(
                    layoutWidth - endPlaceable.width,
                    0
                )
            } else {
                // [endSlot] [gap] [content............] [startSlot]
                val startX = layoutWidth - startPlaceable.width
                val contentX = startX - gapPx - contentPlaceable.width
                endPlaceable.placeRelative(0, 0)
                contentPlaceable.placeRelative(
                    contentX.coerceAtLeast(endPlaceable.width + gapPx),
                    0
                )
                startPlaceable.placeRelative(
                    startX,
                    0
                )
            }
        }
    }
}