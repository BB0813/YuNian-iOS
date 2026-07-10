package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
internal fun ChatMessageScaffold(
    message: ChatMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean = true,
    drawBubble: Boolean = true,
    onClick: (() -> Unit)? = null,
    copyText: String? = null,
    content: @Composable () -> Unit
) {
    val isMine = message.isFromUser
    val time = remember(message.timestamp) { formatChatMessageTime(message.timestamp) }
    var showMenu by remember { mutableStateOf(false) }

    Box {
        ChatMessageFrame(
            isMine = isMine,
            adaptiveSizing = adaptiveSizing,
            avatar = {
                ChatMessageAvatar(
                    isMine = isMine,
                    companionData = companionData,
                    userAvatar = userAvatar,
                    userName = userName,
                    adaptiveSizing = adaptiveSizing
                )
            },
            timestamp = {
                ChatMessageTimestamp(
                    time = time,
                    isMine = isMine,
                    adaptiveSizing = adaptiveSizing
                )
            },
            drawBubble = drawBubble,
            isDarkTheme = isDarkTheme,
            onClick = onClick,
            onLongClick = { showMenu = true },
            content = content
        )

        ChatMessageMenu(
            expanded = showMenu,
            message = message,
            onDismiss = { showMenu = false },
            onIntent = onIntent,
            copyText = copyText
        )
    }
}