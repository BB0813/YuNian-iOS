package com.lianyu.ai.uicommon.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App-level design tokens for dimensions and spacing.
 *
 * These values are shared across all list-item / bubble-style UIs.
 * Feature modules can reference them directly or through their own
 * bridge objects for backward compatibility.
 */
object AppDimens {
    // ── Layout ──────────────────────────────────────────────────────
    val AvatarGap = 8.dp
    val BubbleTimestampGap = 4.dp
    val BubbleBorderWidth = 0.6.dp

    // ── Text bubble ─────────────────────────────────────────────────
    val TextBubbleMaxWidth = 268.dp
    val QuoteCornerRadius = 12.dp
    val QuoteHorizontalPadding = 11.dp
    val QuoteVerticalPadding = 8.dp
    val QuoteBottomGap = 8.dp
    val QuoteAuthorFontSize = 11.sp
    val QuotePreviewFontSize = 12.sp

    // ── Image bubble ────────────────────────────────────────────────
    val ImageMaxWidth = 220.dp
    val ImageMaxHeight = 280.dp
    val ImageCornerRadius = 14.dp
    val ImagePlaceholderMaxWidth = 200.dp
    val ImagePlaceholderHorizontalPadding = 16.dp
    val ImagePlaceholderVerticalPadding = 24.dp

    // ── Attachment bubble ───────────────────────────────────────────
    val AttachmentMaxWidth = 220.dp
    val AttachmentHorizontalPadding = 16.dp
    val AttachmentVerticalPadding = 14.dp
    val AttachmentIconSize = 22.dp
    val AttachmentIconTextGap = 8.dp

    // ── Context menu ────────────────────────────────────────────────
    val MenuIconSize = 20.dp
    val MenuTextFontSize = 14.sp

    // ── Time divider ────────────────────────────────────────────────
    val TimeDividerVerticalPadding = 8.dp
    val TimeDividerCornerRadius = 8.dp
    val TimeDividerHorizontalPadding = 10.dp
    val TimeDividerInnerVerticalPadding = 4.dp
    val TimeDividerFontSize = 11.sp

    // ── System tip ──────────────────────────────────────────────────
    val SystemTipHorizontalPadding = 24.dp
    val SystemTipVerticalPadding = 6.dp
    val SystemTipFontSize = 12.sp
}