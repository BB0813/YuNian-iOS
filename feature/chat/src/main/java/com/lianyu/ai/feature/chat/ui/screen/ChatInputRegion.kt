package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.feature.chat.ui.message.QuoteMediaThumbnail
import com.lianyu.ai.feature.chat.ui.viewmodel.QuoteReply
import com.lianyu.ai.feature.chat.voice.ChatTtsState
import com.lianyu.ai.uicommon.component.ChatInputExtensionPanel
import com.lianyu.ai.uicommon.component.StickerPanel
import com.lianyu.ai.uicommon.component.WeChatChatInputBar
import com.lianyu.ai.uicommon.model.ApiProviderInfo
import com.lianyu.ai.uicommon.theme.AppTheme

@Composable
fun ChatInputRegion(
    isBlocked: Boolean,
    isLoading: Boolean,
    quoteReply: QuoteReply?,
    ttsState: ChatTtsState,
    showStickerPanel: Boolean,
    showExtensionPanel: Boolean,
    availableApis: List<ApiProviderInfo>,
    currentApi: ApiProviderInfo?,
    onStopTtsClick: () -> Unit,
    onStickerClick: (StickerInfo) -> Unit,
    onImportStickersClick: () -> Unit,
    onDeleteAllStickersClick: () -> Unit,
    onSwitchApi: (ApiProviderInfo) -> Unit,
    onClearQuoteReply: () -> Unit,
    onAlbumClick: () -> Unit,
    onCameraClick: () -> Unit,
    onVideoCallClick: () -> Unit,
    onVoiceCallClick: () -> Unit,
    onTtsModeClick: () -> Unit,
    onLocationClick: () -> Unit,
    onVoiceInputClick: () -> Unit,
    onStickerPanelClick: () -> Unit,
    onSendMessage: (String) -> Unit,
    onPlusClick: () -> Unit,
    onVoiceRecordStart: () -> Unit,
    onVoiceRecordStop: () -> Unit,
    onVoiceRecordCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        if (ttsState == ChatTtsState.SPEAKING) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.primary.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI 正在朗读...",
                        fontSize = 12.sp,
                        color = colors.primary
                    )
                }
                IconButton(
                    onClick = onStopTtsClick,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "停止朗读",
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // 输入框在上，扩展/表情面板在下：点“+”后面板向下展开，输入框随高度升起
        if (isBlocked) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.95f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "你已拉黑该联系人",
                    fontSize = 14.sp,
                    color = colors.error,
                    fontWeight = FontWeight.Medium
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.95f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    WeChatChatInputBar(
                        onSendMessage = onSendMessage,
                        isLoading = isLoading,
                        availableApis = availableApis,
                        currentApi = currentApi,
                        onSwitchApi = onSwitchApi,
                        onPlusClick = onPlusClick,
                        onVoiceRecordStart = onVoiceRecordStart,
                        onVoiceRecordStop = onVoiceRecordStop,
                        onVoiceRecordCancel = onVoiceRecordCancel,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (quoteReply != null) {
                        QuoteReplyPreview(
                            quoteReply = quoteReply,
                            onClearClick = onClearQuoteReply
                        )
                    }
                }
            }
        }

        StickerPanel(
            isVisible = showStickerPanel,
            onStickerClick = onStickerClick,
            onImportClick = onImportStickersClick,
            onDeleteAllClick = onDeleteAllStickersClick
        )

        ChatInputExtensionPanel(
            isVisible = showExtensionPanel,
            availableApis = availableApis,
            currentApi = currentApi,
            onSwitchApi = onSwitchApi,
            onAlbumClick = onAlbumClick,
            onCameraClick = onCameraClick,
            onVideoCallClick = onVideoCallClick,
            onVoiceCallClick = onVoiceCallClick,
            onTtsModeClick = onTtsModeClick,
            onLocationClick = onLocationClick,
            onVoiceInputClick = onVoiceInputClick,
            onStickerClick = onStickerPanelClick
        )
    }
}

@Composable
private fun QuoteReplyPreview(
    quoteReply: QuoteReply,
    onClearClick: () -> Unit
) {
    val colors = AppTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surface.copy(alpha = 0.72f))
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (quoteReply.hasMediaThumbnail) {
            QuoteMediaThumbnail(
                quote = quoteReply,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "引用 ${quoteReply.authorName}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = colors.primary
            )
            Text(
                text = quoteReply.previewText,
                fontSize = 12.sp,
                color = colors.metadataContent,
                maxLines = 1
            )
        }
        IconButton(
            onClick = onClearClick,
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "取消引用",
                tint = colors.metadataContent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
