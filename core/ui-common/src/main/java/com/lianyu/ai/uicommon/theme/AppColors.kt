package com.lianyu.ai.uicommon.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * App-level semantic color accessors.
 *
 * These functions map Material3 [ColorScheme] tokens to semantic roles
 * used across all list-item / bubble-style UIs in the app — not just chat.
 *
 * Usage:
 * ```
 * val colors = MaterialTheme.colorScheme
 * Box(Modifier.background(AppColors.primaryBubbleBackground(colors)))
 * Text(color = AppColors.primaryBubbleContent(colors))
 * ```
 */
object AppColors {
    // ── Bubble backgrounds ──────────────────────────────────────────
    // 统一 AI 标准色 sky #CADEFC；自己/AI 同底，靠布局左右与箭头区分
    fun primaryBubbleBackground(colorScheme: ColorScheme): Color =
        secondaryBubbleBackground(colorScheme)
    fun primaryBubbleContent(colorScheme: ColorScheme): Color = BubbleOnPink
    fun secondaryBubbleBackground(colorScheme: ColorScheme): Color =
        if (isDarkSurface(colorScheme)) AiBubbleDark else AiBubbleLight
    fun secondaryBubbleContent(colorScheme: ColorScheme): Color = BubbleOnPink
    fun secondaryBubbleBorder(colorScheme: ColorScheme): Color =
        if (isDarkSurface(colorScheme)) AiBubbleBorderDark else AiBubbleBorderLight

    private fun isDarkSurface(colorScheme: ColorScheme): Boolean =
        colorScheme.surface.luminance() < 0.5f

    // ── Metadata (timestamps, captions) ─────────────────────────────
    fun metadataContent(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant

    // ── Context menus ───────────────────────────────────────────────
    fun menuBackground(colorScheme: ColorScheme): Color = colorScheme.surface
    fun menuContent(colorScheme: ColorScheme): Color = colorScheme.onSurface
    fun menuIcon(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant

    // ── Quote / reply ───────────────────────────────────────────────
    fun quoteAuthor(colorScheme: ColorScheme, isPrimary: Boolean): Color =
        if (isPrimary) colorScheme.onPrimaryContainer else colorScheme.primary

    fun quotePreview(colorScheme: ColorScheme, isPrimary: Boolean): Color =
        if (isPrimary) colorScheme.onPrimaryContainer.copy(alpha = 0.82f) else colorScheme.onSurfaceVariant

    fun quoteBackground(colorScheme: ColorScheme, isPrimary: Boolean): Color =
        if (isPrimary) colorScheme.onPrimaryContainer.copy(alpha = 0.12f) else colorScheme.surfaceVariant.copy(alpha = 0.55f)

    // ── Dividers & system tips ──────────────────────────────────────
    fun dividerBackground(colorScheme: ColorScheme): Color = colorScheme.surfaceVariant.copy(alpha = 0.7f)
    fun captionContent(colorScheme: ColorScheme): Color = colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
}