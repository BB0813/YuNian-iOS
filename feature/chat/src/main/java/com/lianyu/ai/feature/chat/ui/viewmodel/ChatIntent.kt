package com.lianyu.ai.feature.chat.ui.viewmodel

import androidx.compose.runtime.Stable
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.uicommon.model.ApiProviderInfo

@Stable
sealed interface ChatIntent {
    data class SendText(val content: String) : ChatIntent
    data class SendImage(val imagePath: String) : ChatIntent
    data class SendVideo(val videoPath: String) : ChatIntent
    data class SendVoice(val audioPath: String, val duration: Int) : ChatIntent
    data class SendSticker(val sticker: StickerInfo) : ChatIntent
    data class SwitchApi(val provider: ApiProviderInfo) : ChatIntent
    data object LoadEarlier : ChatIntent
    data class QuoteReply(val message: ChatMessage) : ChatIntent
    data class Recall(val message: ChatMessage) : ChatIntent
    data class Regenerate(val message: ChatMessage) : ChatIntent
}