package com.lianyu.ai.uicommon.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

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
    fun primaryBubbleBackground(colorScheme: ColorScheme): Color = colorScheme.primaryContainer
    fun primaryBubbleContent(colorScheme: ColorScheme): Color = colorScheme.onPrimaryContainer
    fun secondaryBubbleBackground(colorScheme: ColorScheme): Color = colorScheme.surface
    fun secondaryBubbleContent(colorScheme: ColorScheme): Color = colorScheme.onSurface
    fun secondaryBubbleBorder(colorScheme: ColorScheme): Color = colorScheme.outline.copy(alpha = 0.22f)

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