package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
fun VoiceMessageBubble(
    item: ChatListItem.VoiceMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val message = item.message
    val context = LocalContext.current
    val voiceDuration = remember(message.content) { extractVoiceDuration(message.content) }
    val voicePath = remember(message.linkString, message.id, context.cacheDir) {
        message.linkString.ifBlank {
            java.io.File(context.cacheDir, "voice_${message.id}.m4a").absolutePath
        }
    }

    ChatMessageScaffold(
        message = message,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        drawBubble = true
    ) {
        VoiceMessageContent(
            audioPath = voicePath,
            duration = voiceDuration,
            isMine = message.isFromUser
        )
    }
}