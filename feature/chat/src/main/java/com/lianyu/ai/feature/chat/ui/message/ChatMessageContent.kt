package com.lianyu.ai.feature.chat.ui.message

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.feature.chat.ui.viewmodel.QuoteMediaType
import com.lianyu.ai.feature.chat.ui.viewmodel.QuoteReply
import com.lianyu.ai.feature.chat.ui.viewmodel.QuotedTextContent
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.AppTheme
import com.lianyu.ai.uicommon.component.VoiceMessageBubble as VoicePlaybackBubble
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 屏蔽系统文字工具栏，避免与自定义长按菜单叠层冲突。 */
private object DisabledTextToolbar : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden
    override fun hide() = Unit
    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) = Unit
}

@Composable
fun TextMessageContent(
    quotedContent: QuotedTextContent,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing,
    onIntent: (ChatIntent) -> Unit,
    textSelectable: Boolean = false
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val dimens = AppTheme.dimens
    val quoteAuthor = if (isMine) colors.quotePrimaryAuthor else colors.quoteSecondaryAuthor
    val quotePreview = if (isMine) colors.quotePrimaryPreview else colors.quoteSecondaryPreview
    val bodyColor = if (isMine) colors.primaryBubbleContent else colors.secondaryBubbleContent
    val bodyStyle = typography.bodyLarge.copy(
        fontSize = adaptiveSizing.fontSizeBody.sp,
        lineHeight = (adaptiveSizing.fontSizeBody * 1.5).sp,
        color = bodyColor
    )
    val selectionColors = remember(colors.primary) {
        TextSelectionColors(
            handleColor = colors.primary,
            backgroundColor = colors.primary.copy(alpha = 0.28f)
        )
    }
    val body = quotedContent.body
    var selectionValue by remember(body) {
        mutableStateOf(
            TextFieldValue(
                text = body,
                selection = TextRange(0, body.length)
            )
        )
    }

    LaunchedEffect(textSelectable, body) {
        if (textSelectable) {
            // 长按进入菜单时默认全选，用户可拖动手柄调整
            selectionValue = TextFieldValue(
                text = body,
                selection = TextRange(0, body.length)
            )
        }
    }

    Column {
        if (textSelectable) {
            CompositionLocalProvider(
                LocalTextSelectionColors provides selectionColors,
                LocalTextToolbar provides DisabledTextToolbar
            ) {
                BasicTextField(
                    value = selectionValue,
                    onValueChange = { next ->
                        // 只允许调整选区，不允许改写正文
                        selectionValue = TextFieldValue(
                            text = body,
                            selection = next.selection
                        )
                    },
                    readOnly = true,
                    textStyle = bodyStyle,
                    cursorBrush = SolidColor(Color.Transparent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Text(
                text = body,
                style = bodyStyle,
                color = bodyColor,
                softWrap = true
            )
        }
        quotedContent.quote?.let { quote ->
            Spacer(modifier = Modifier.height(dimens.quoteBottomGap))
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
                        .background(if (isMine) colors.primaryBubbleContent.copy(alpha = 0.35f) else colors.secondaryBubbleContent.copy(alpha = 0.35f))
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (quote.hasMediaThumbnail) {
                    QuoteMediaThumbnail(
                        quote = quote,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = quote.authorName,
                        style = typography.labelSmall.copy(fontSize = dimens.quoteAuthorFontSize),
                        color = quoteAuthor
                    )
                    Text(
                        text = quote.previewText,
                        style = typography.bodySmall.copy(fontSize = dimens.quotePreviewFontSize),
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
fun QuoteMediaThumbnail(
    quote: QuoteReply,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageModel: Any? = remember(quote.mediaPath, quote.mediaType) {
        if (quote.mediaType != QuoteMediaType.IMAGE || quote.mediaPath.isBlank()) {
            null
        } else {
            when {
                quote.mediaPath.startsWith("content://", ignoreCase = true) ||
                    quote.mediaPath.startsWith("file://", ignoreCase = true) ||
                    quote.mediaPath.startsWith("http", ignoreCase = true) -> quote.mediaPath
                else -> File(quote.mediaPath)
            }
        }
    }

    var videoFrame by remember(quote.mediaPath, quote.mediaType) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(quote.mediaPath, quote.mediaType) {
        if (quote.mediaType != QuoteMediaType.VIDEO || quote.mediaPath.isBlank()) {
            videoFrame = null
            return@LaunchedEffect
        }
        videoFrame = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val path = quote.mediaPath
                when {
                    path.startsWith("content://", ignoreCase = true) ||
                        path.startsWith("file://", ignoreCase = true) ->
                        retriever.setDataSource(context, Uri.parse(path))
                    else -> retriever.setDataSource(path)
                }
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(AppTheme.colors.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (quote.mediaType) {
            QuoteMediaType.IMAGE -> {
                if (imageModel != null) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = quote.previewText,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("📷", fontSize = 14.sp)
                }
            }
            QuoteMediaType.VIDEO -> {
                val frame = videoFrame
                if (frame != null) {
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = quote.previewText,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text("🎬", fontSize = 14.sp)
                }
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
fun ImageMessageContent(
    imageFile: java.io.File,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val dimens = AppTheme.dimens

    if (imageFile.exists()) {
        AsyncImage(
            model = imageFile,
            contentDescription = "图片",
            modifier = Modifier
                .widthIn(max = dimens.imageMaxWidth)
                .heightIn(max = dimens.imageMaxHeight)
                .clip(RoundedCornerShape(dimens.imageCornerRadius)),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = Modifier
                .widthIn(max = dimens.imagePlaceholderMaxWidth)
                .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                .background(if (isMine) colors.primaryBubbleBackground else colors.secondaryBubbleBackground)
                .padding(horizontal = 16.dp, vertical = 24.dp)
        ) {
            Text(
                text = "📷 图片",
                style = typography.bodyLarge,
                color = if (isMine) colors.primaryBubbleContent else colors.secondaryBubbleContent
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
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val dimens = AppTheme.dimens

    Row(
        modifier = Modifier.widthIn(max = dimens.attachmentMaxWidth),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isMine) colors.primaryBubbleContent else colors.secondaryBubbleContent,
            modifier = Modifier.size(dimens.attachmentIconSize)
        )
        Spacer(modifier = Modifier.width(dimens.attachmentIconTextGap))
        Text(
            text = label,
            style = typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp),
            color = if (isMine) colors.primaryBubbleContent else colors.secondaryBubbleContent
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
    val colors = AppTheme.colors
    VoicePlaybackBubble(
        audioPath = audioPath,
        duration = duration,
        isUser = isMine,
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (isMine) colors.primaryBubbleContent else colors.secondaryBubbleContent
    )
}

