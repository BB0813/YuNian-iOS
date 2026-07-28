package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem
import com.lianyu.ai.feature.chat.ui.viewmodel.isAssistantVoiceBarMessage
import com.lianyu.ai.feature.chat.ui.viewmodel.parseQuotedTextContent
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
fun VoiceMessageItem(
    item: ChatListItem.VoiceMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val message = item.message
    val context = LocalContext.current
    val showTranscript = remember(message) {
        message.isAssistantVoiceBarMessage() ||
            (!message.isFromUser &&
                message.linkString.isNotBlank() &&
                !message.content.startsWith("[语音]") &&
                message.content.isNotBlank())
    }
    val voiceDuration = remember(message.content, message.durationMs, message.linkString) {
        resolveVoiceBarDurationSeconds(message)
    }
    val voicePath = remember(message.linkString, message.id, context.cacheDir) {
        message.linkString.ifBlank {
            java.io.File(context.cacheDir, "voice_${message.id}.m4a").absolutePath
        }
    }

    // 双显：语音条 + 文字时，长按复制目标是正文选区
    var selectedText by remember(message.content, showTranscript) { mutableStateOf("") }
    val quotedContent = remember(message.content, showTranscript) {
        if (showTranscript) parseQuotedTextContent(message.content) else null
    }

    ChatMessageScaffold(
        message = message,
        companionData = companionData,
        userAvatar = userAvatar,
        userName = userName,
        onIntent = onIntent,
        adaptiveSizing = adaptiveSizing,
        enableFrameLongClick = !showTranscript,
        copyText = if (showTranscript) selectedText.takeIf { it.isNotEmpty() } else null
    ) { menuExpanded, openMenu ->
        Column {
            VoiceMessageContent(
                audioPath = voicePath,
                duration = voiceDuration,
                isMine = message.isFromUser
            )
            val body = quotedContent?.body.orEmpty()
            if (showTranscript && body.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                TextMessageContent(
                    quotedContent = quotedContent!!,
                    isMine = message.isFromUser,
                    adaptiveSizing = adaptiveSizing,
                    onIntent = onIntent,
                    selectionActive = menuExpanded,
                    onTextLongClick = openMenu,
                    onSelectedTextChange = { selectedText = it }
                )
            }
        }
    }
}

/** 优先 durationMs，其次 content 中的「[语音] N"」，默认 1 秒 */
private fun resolveVoiceBarDurationSeconds(message: com.lianyu.ai.database.model.ChatMessage): Int {
    message.durationMs?.takeIf { it > 0L }?.let { ms ->
        return ((ms + 999L) / 1000L).toInt().coerceAtLeast(1)
    }
    return extractVoiceDuration(message.content)
}

