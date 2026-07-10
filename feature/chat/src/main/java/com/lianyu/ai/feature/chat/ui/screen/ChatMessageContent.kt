package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.feature.chat.ui.theme.withChatSize
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.feature.chat.ui.viewmodel.QuotedTextContent
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.component.VoiceMessageBubble as VoicePlaybackBubble

@Composable
fun TextMessageContent(
    quotedContent: QuotedTextContent,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing,
    onIntent: (ChatIntent) -> Unit
) {
    val colors = ChatTheme.colors
    val typography = ChatTheme.typography
    val metrics = ChatTheme.metrics
    val quoteBackground = if (isMine) colors.quoteUserBackground else colors.quoteAiBackground
    val quoteAuthor = if (isMine) colors.quoteUserAuthor else colors.quoteAiAuthor
    val quotePreview = if (isMine) colors.quoteUserPreview else colors.quoteAiPreview

    Column(
        modifier = Modifier.widthIn(
            max = metrics.textBubbleMaxWidth * adaptiveSizing.messageBubbleMaxWidthRatio / 0.75f
        )
    ) {
        Text(
            text = quotedContent.body,
            style = typography.messageBody.withChatSize(
                fontSize = adaptiveSizing.fontSizeBody.sp,
                lineHeight = (adaptiveSizing.fontSizeBody * 1.5).sp
            ),
            color = if (isMine) colors.userContent else colors.aiContent,
            softWrap = true
        )
        quotedContent.quote?.let { quote ->
            Spacer(modifier = Modifier.height(metrics.quoteBottomGap))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .then(
                        if (quote.messageId > 0) {
                            Modifier.clickable { onIntent(ChatIntent.NavigateToMessage(quote.messageId)) }
                        } else {
                            Modifier
                        }
                    )
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .heightIn(min = 16.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (isMine) colors.userContent.copy(alpha = 0.35f) else colors.aiContent.copy(alpha = 0.35f))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column {
                    Text(
                        text = quote.authorName,
                        style = typography.quoteAuthor,
                        color = quoteAuthor
                    )
                    Text(
                        text = quote.previewText,
                        style = typography.quotePreview,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = quotePreview
                    )
                }
            }
        }
    }
}

@Composable
fun ImageMessageContent(
    imageFile: java.io.File,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing
) {
    val colors = ChatTheme.colors
    val metrics = ChatTheme.metrics
    val shapes = ChatTheme.shapes

    if (imageFile.exists()) {
        AsyncImage(
            model = imageFile,
            contentDescription = "图片",
            modifier = Modifier
                .widthIn(max = metrics.imageMaxWidth)
                .heightIn(max = metrics.imageMaxHeight)
                .clip(RoundedCornerShape(shapes.imageCornerRadius)),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .widthIn(max = metrics.imagePlaceholderMaxWidth)
                .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                .background(if (isMine) colors.userBubble else colors.aiBubble)
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Text(
                text = "📷 图片",
                style = ChatTheme.typography.messageBody,
                color = if (isMine) colors.userContent else colors.aiContent
            )
        }
    }
}

@Composable
fun AttachmentMessageContent(
    icon: ImageVector,
    label: String,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing
) {
    val colors = ChatTheme.colors
    val metrics = ChatTheme.metrics

    Row(
        modifier = Modifier.widthIn(max = metrics.attachmentMaxWidth),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isMine) colors.userContent else colors.aiContent,
            modifier = Modifier.size(metrics.attachmentIconSize)
        )
        Spacer(modifier = Modifier.width(metrics.attachmentIconTextGap))
        Text(
            text = label,
            style = ChatTheme.typography.messageBody.withChatSize(adaptiveSizing.fontSizeBody.sp),
            color = if (isMine) colors.userContent else colors.aiContent
        )
    }
}

@Composable
fun StickerMessageContent(
    stickerName: String,
    modifier: Modifier = Modifier
) {
    StickerContentBubble(
        stickerName = stickerName,
        modifier = modifier
    )
}

@Composable
fun VoiceMessageContent(
    audioPath: String,
    duration: Int,
    isMine: Boolean
) {
    val colors = ChatTheme.colors
    VoicePlaybackBubble(
        audioPath = audioPath,
        duration = duration,
        isUser = isMine,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (isMine) colors.userContent else colors.aiContent
    )
}