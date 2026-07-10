package com.lianyu.ai.feature.chat.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lianyu.ai.uicommon.theme.AppBubbleSide
import com.lianyu.ai.uicommon.theme.AppBubbleSpec
import com.lianyu.ai.uicommon.theme.appBubbleBackground

/**
 * Bridge to [AppBubbleSide] for backward compatibility.
 */
typealias ChatBubbleSide = AppBubbleSide

/**
 * Bridge to [AppBubbleSpec] for backward compatibility.
 */
typealias ChatBubbleSpec = AppBubbleSpec

/**
 * Bridge to [appBubbleBackground] for backward compatibility.
 */
fun Modifier.chatBubbleBackground(
    color: Color,
    borderColor: Color? = null,
    borderWidth: Dp = 0.dp,
    spec: ChatBubbleSpec
): Modifier = this.appBubbleBackground(color, borderColor, borderWidth, spec)