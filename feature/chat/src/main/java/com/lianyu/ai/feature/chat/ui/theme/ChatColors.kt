package com.lianyu.ai.feature.chat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

object ChatColors {
    fun userBubbleBackground(colorScheme: ColorScheme): Color = colorScheme.primary
    fun userBubbleContent(colorScheme: ColorScheme): Color = colorScheme.onPrimary
    fun aiBubbleBackground(colorScheme: ColorScheme): Color = colorScheme.surfaceVariant
    fun aiBubbleContent(colorScheme: ColorScheme): Color = colorScheme.onSurface
    fun aiBubbleBorder(colorScheme: ColorScheme): Color = colorScheme.outline
    fun metadataContent(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant
    fun menuBackground(colorScheme: ColorScheme): Color = colorScheme.surface
    fun menuContent(colorScheme: ColorScheme): Color = colorScheme.onSurface
    fun menuIcon(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant
    fun quoteAuthor(colorScheme: ColorScheme, isUser: Boolean): Color = if (isUser) colorScheme.onPrimary else colorScheme.primary
    fun quotePreview(colorScheme: ColorScheme, isUser: Boolean): Color =
        if (isUser) colorScheme.onPrimary.copy(alpha = 0.82f) else colorScheme.onSurfaceVariant
    fun quoteBackground(colorScheme: ColorScheme, isUser: Boolean): Color =
        if (isUser) colorScheme.onPrimary.copy(alpha = 0.16f) else colorScheme.surface.copy(alpha = 0.72f)
    fun timeDividerBackground(colorScheme: ColorScheme): Color = colorScheme.surfaceVariant.copy(alpha = 0.7f)
    fun systemTipContent(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
}