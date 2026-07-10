package com.lianyu.ai.feature.chat.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.lianyu.ai.uicommon.theme.AppDimens
import com.lianyu.ai.uicommon.theme.AppTheme

data class ChatThemeColors(
    val userBubble: Color,
    val userContent: Color,
    val aiBubble: Color,
    val aiContent: Color,
    val aiBorder: Color,
    val metadata: Color,
    val menuBackground: Color,
    val menuContent: Color,
    val menuIcon: Color,
    val inputBackground: Color,
    val inputQuoteBackground: Color,
    val inputAccent: Color,
    val inputAccentContainer: Color,
    val inputBlockedContent: Color,
    val topBarBackground: Color,
    val topBarTitle: Color,
    val topBarIcon: Color,
    val topBarAccent: Color,
    val topBarAccentContainer: Color,
    val screenBackground: Color,
    val surfaceBackground: Color,
    val surfaceVariant: Color,
    val surfaceContent: Color,
    val backgroundContent: Color,
    val divider: Color,
    val outline: Color,
    val accentContent: Color,
    val dialogScrim: Color,
    val inverseSurface: Color,
    val inverseOnSurface: Color,
    val destructive: Color,
    val destructiveContent: Color,
    val success: Color,
    val successContent: Color,
    val warning: Color,
    val quoteUserBackground: Color,
    val quoteAiBackground: Color,
    val quoteUserAuthor: Color,
    val quoteAiAuthor: Color,
    val quoteUserPreview: Color,
    val quoteAiPreview: Color,
    val timeDividerBackground: Color,
    val systemTip: Color
)

data class ChatTypography(
    val messageBody: TextStyle,
    val timestamp: TextStyle,
    val menu: TextStyle,
    val quoteAuthor: TextStyle,
    val quotePreview: TextStyle,
    val timeDivider: TextStyle,
    val systemTip: TextStyle
)

data class ChatMetrics(
    val avatarGap: Dp = AppDimens.AvatarGap,
    val bubbleTimestampGap: Dp = AppDimens.BubbleTimestampGap,
    val bubbleBorderWidth: Dp = AppDimens.BubbleBorderWidth,
    val textBubbleMaxWidth: Dp = AppDimens.TextBubbleMaxWidth,
    val imageMaxWidth: Dp = AppDimens.ImageMaxWidth,
    val imageMaxHeight: Dp = AppDimens.ImageMaxHeight,
    val imagePlaceholderMaxWidth: Dp = AppDimens.ImagePlaceholderMaxWidth,
    val attachmentMaxWidth: Dp = AppDimens.AttachmentMaxWidth,
    val quoteHorizontalPadding: Dp = AppDimens.QuoteHorizontalPadding,
    val quoteVerticalPadding: Dp = AppDimens.QuoteVerticalPadding,
    val quoteBottomGap: Dp = AppDimens.QuoteBottomGap,
    val attachmentHorizontalPadding: Dp = AppDimens.AttachmentHorizontalPadding,
    val attachmentVerticalPadding: Dp = AppDimens.AttachmentVerticalPadding,
    val attachmentIconSize: Dp = AppDimens.AttachmentIconSize,
    val attachmentIconTextGap: Dp = AppDimens.AttachmentIconTextGap,
    val menuIconSize: Dp = AppDimens.MenuIconSize,
    val menuOffsetY: Dp = (-8).dp,
    val timeDividerVerticalPadding: Dp = AppDimens.TimeDividerVerticalPadding,
    val timeDividerHorizontalPadding: Dp = AppDimens.TimeDividerHorizontalPadding,
    val timeDividerInnerVerticalPadding: Dp = AppDimens.TimeDividerInnerVerticalPadding,
    val systemTipHorizontalPadding: Dp = AppDimens.SystemTipHorizontalPadding,
    val systemTipVerticalPadding: Dp = AppDimens.SystemTipVerticalPadding
)

data class ChatShapeTokens(
    val quoteCornerRadius: Dp = AppDimens.QuoteCornerRadius,
    val imageCornerRadius: Dp = AppDimens.ImageCornerRadius,
    val timeDividerCornerRadius: Dp = AppDimens.TimeDividerCornerRadius,
    val bubbleArrowWidth: Dp = 5.dp,
    val bubbleArrowHeight: Dp = 8.dp,
    val bubbleArrowOffsetY: Dp = 14.dp
)

private val LocalChatThemeColors = compositionLocalOf<ChatThemeColors?> { null }
private val LocalChatTypography = compositionLocalOf<ChatTypography?> { null }
private val LocalChatMetrics = compositionLocalOf { ChatMetrics() }
private val LocalChatShapes = compositionLocalOf { ChatShapeTokens() }

object ChatTheme {
    val colors: ChatThemeColors
        @Composable get() = LocalChatThemeColors.current ?: materialChatThemeColors(MaterialTheme.colorScheme)

    val typography: ChatTypography
        @Composable get() = LocalChatTypography.current ?: materialChatTypography()

    val metrics: ChatMetrics
        @Composable get() = LocalChatMetrics.current

    val shapes: ChatShapeTokens
        @Composable get() = LocalChatShapes.current
}

@Composable
fun ChatTheme(
    colors: ChatThemeColors = materialChatThemeColors(MaterialTheme.colorScheme),
    typography: ChatTypography = materialChatTypography(),
    metrics: ChatMetrics = ChatMetrics(),
    shapes: ChatShapeTokens = ChatShapeTokens(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalChatThemeColors provides colors,
        LocalChatTypography provides typography,
        LocalChatMetrics provides metrics,
        LocalChatShapes provides shapes,
        content = content
    )
}

@Composable
private fun materialChatTypography(): ChatTypography {
    val typography = AppTheme.typography
    return ChatTypography(
        messageBody = typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif),
        timestamp = typography.labelSmall,
        menu = typography.bodyMedium.copy(fontSize = AppDimens.MenuTextFontSize),
        quoteAuthor = typography.labelSmall.copy(fontSize = AppDimens.QuoteAuthorFontSize, fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif),
        quotePreview = typography.bodySmall.copy(fontSize = AppDimens.QuotePreviewFontSize, fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif),
        timeDivider = typography.labelSmall.copy(fontSize = AppDimens.TimeDividerFontSize),
        systemTip = typography.bodySmall.copy(fontSize = AppDimens.SystemTipFontSize)
    )
}

@Composable
private fun materialChatThemeColors(colorScheme: ColorScheme): ChatThemeColors {
    val colors = AppTheme.colors
    return ChatThemeColors(
        userBubble = colors.primaryBubbleBackground,
        userContent = colors.primaryBubbleContent,
        aiBubble = colors.secondaryBubbleBackground,
        aiContent = colors.secondaryBubbleContent,
        aiBorder = colors.secondaryBubbleBorder,
        metadata = colors.metadataContent,
        menuBackground = colors.menuBackground,
        menuContent = colors.menuContent,
        menuIcon = colors.menuIcon,
        inputBackground = colorScheme.surfaceVariant,
        inputQuoteBackground = colorScheme.surface,
        inputAccent = colorScheme.primary,
        inputAccentContainer = colorScheme.primary.copy(alpha = 0.12f),
        inputBlockedContent = colorScheme.error,
        topBarBackground = colorScheme.surfaceVariant,
        topBarTitle = colorScheme.onSurface,
        topBarIcon = colorScheme.onSurface,
        topBarAccent = colorScheme.primary,
        topBarAccentContainer = colorScheme.primary.copy(alpha = 0.12f),
        screenBackground = colorScheme.background,
        surfaceBackground = colorScheme.surface,
        surfaceVariant = colorScheme.surfaceVariant,
        surfaceContent = colorScheme.onSurface,
        backgroundContent = colorScheme.onBackground,
        divider = colorScheme.outlineVariant,
        outline = colorScheme.outline,
        accentContent = colorScheme.onPrimary,
        dialogScrim = colorScheme.scrim,
        inverseSurface = colorScheme.inverseSurface,
        inverseOnSurface = colorScheme.inverseOnSurface,
        destructive = colorScheme.error,
        destructiveContent = colorScheme.onError,
        success = Color(0xFF34C759),
        successContent = colorScheme.onPrimary,
        warning = Color(0xFFFF9500),
        quoteUserBackground = colors.quotePrimaryBackground,
        quoteAiBackground = colors.quoteSecondaryBackground,
        quoteUserAuthor = colors.quotePrimaryAuthor,
        quoteAiAuthor = colors.quoteSecondaryAuthor,
        quoteUserPreview = colors.quotePrimaryPreview,
        quoteAiPreview = colors.quoteSecondaryPreview,
        timeDividerBackground = colors.dividerBackground,
        systemTip = colors.captionContent
    )
}

fun TextStyle.withChatSize(fontSize: TextUnit, lineHeight: TextUnit = this.lineHeight): TextStyle = copy(
    fontSize = fontSize,
    lineHeight = lineHeight
)