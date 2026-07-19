package com.lianyu.ai.feature.chat.ui.message

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.delay
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

private fun fullSelectionRange(text: String): TextRange {
    if (text.isEmpty()) return TextRange.Zero
    return TextRange(0, text.length)
}

private fun selectedSnippet(text: String, selection: TextRange): String {
    if (selection.collapsed) return ""
    val start = selection.min.coerceIn(0, text.length)
    val end = selection.max.coerceIn(0, text.length)
    if (start >= end) return ""
    return text.substring(start, end)
}

/**
 * 文字消息正文：
 * - 阅读态：普通 Text，宽度按文本真实测量，无 280dp 最小宽
 * - 长按目标是文字本身（不是气泡）：进入选区 + 弹出操作菜单
 * - 选区态：只读 BasicTextField 全选 + 头尾游标；复制取当前选中片段
 * - 阅读/选区都用 TextMeasurer 锁死同一 contentWidth，避免长按后气泡变宽
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun TextMessageContent(
    quotedContent: QuotedTextContent,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing,
    onIntent: (ChatIntent) -> Unit,
    selectionActive: Boolean = false,
    onTextLongClick: () -> Unit = {},
    onSelectedTextChange: (String) -> Unit = {}
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val dimens = AppTheme.dimens
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val quoteAuthor = if (isMine) colors.quotePrimaryAuthor else colors.quoteSecondaryAuthor
    val quotePreview = if (isMine) colors.quotePrimaryPreview else colors.quoteSecondaryPreview
    // 气泡色统一后，正文色也统一走 secondary 标准
    val bodyColor = colors.secondaryBubbleContent
    val bodyStyle = typography.bodyLarge.copy(
        fontSize = adaptiveSizing.fontSizeBody.sp,
        lineHeight = (adaptiveSizing.fontSizeBody * 1.5).sp,
        color = bodyColor
    )
    // 高对比选区：游标/高亮与气泡正文拉开色差（对比色已确认满意）
    val selectionColors = remember(isMine) {
        val handle = if (isMine) Color(0xFFFF6B35) else Color(0xFF2F80ED)
        val bg = if (isMine) Color(0xFFFF6B35).copy(alpha = 0.34f) else Color(0xFF2F80ED).copy(alpha = 0.30f)
        TextSelectionColors(
            handleColor = handle,
            backgroundColor = bg
        )
    }
    val body = quotedContent.body
    var fieldValue by remember(body, selectionActive) {
        mutableStateOf(
            TextFieldValue(
                text = body,
                selection = if (selectionActive) fullSelectionRange(body) else TextRange.Zero
            )
        )
    }
    val selectionFocusRequester = remember(body) { FocusRequester() }

    LaunchedEffect(selectionActive, body, fieldValue.selection) {
        if (selectionActive) {
            onSelectedTextChange(selectedSnippet(body, fieldValue.selection))
        } else {
            onSelectedTextChange("")
        }
    }

    LaunchedEffect(selectionActive, body) {
        if (!selectionActive) return@LaunchedEffect
        // 等选区态挂载后再抢焦点，头尾游标才能显示
        delay(16)
        runCatching { selectionFocusRequester.requestFocus() }
    }

    Column {
        CompositionLocalProvider(
            LocalTextSelectionColors provides selectionColors,
            LocalTextToolbar provides DisabledTextToolbar
        ) {
            BoxWithConstraints {
                // 用 TextMeasurer 按真实文本宽度测量，彻底摆脱 TextField 默认 MinWidth(280.dp)
                val maxWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(0)
                val measured = remember(body, bodyStyle, maxWidthPx) {
                    textMeasurer.measure(
                        text = AnnotatedString(body),
                        style = bodyStyle,
                        constraints = Constraints(maxWidth = maxWidthPx),
                        softWrap = true,
                        overflow = TextOverflow.Clip
                    )
                }
                val contentWidth = with(density) {
                    measured.size.width.toDp().coerceAtMost(maxWidth)
                }

                if (selectionActive) {
                    // 选区态：只读 BasicTextField 全选 + 游标；宽度锁死为文本真实宽
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { next ->
                            // 正文只读：只接受选区变化
                            fieldValue = TextFieldValue(text = body, selection = next.selection)
                        },
                        readOnly = true,
                        enabled = true,
                        textStyle = bodyStyle,
                        cursorBrush = SolidColor(Color.Transparent),
                        modifier = Modifier
                            .width(contentWidth)
                            .focusRequester(selectionFocusRequester)
                    )
                } else {
                    // 阅读态：普通 Text + 文字级长按（对象是文字，不是气泡）
                    Text(
                        text = body,
                        style = bodyStyle,
                        modifier = Modifier
                            .width(contentWidth)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    haptic.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    onTextLongClick()
                                },
                                indication = null,
                                interactionSource = null
                            )
                    )
                }
            }
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

