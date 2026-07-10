package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.feature.chat.ui.theme.withChatSize
import com.lianyu.ai.uicommon.component.CompanionAvatar
import com.lianyu.ai.uicommon.component.UserAvatar
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
fun ChatMessageAvatar(
    isMine: Boolean,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    adaptiveSizing: AdaptiveSizing
) {
    if (isMine) {
        UserAvatar(
            avatarUrl = userAvatar,
            name = userName,
            size = adaptiveSizing.avatarSize
        )
    } else {
        CompanionAvatar(
            avatarUrl = companionData?.avatarUrl,
            name = companionData?.name,
            size = adaptiveSizing.avatarSize
        )
    }
}

@Composable
fun ChatMessageTimestamp(
    time: String,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing
) {
    Text(
        text = time,
        style = ChatTheme.typography.timestamp.withChatSize(adaptiveSizing.fontSizeCaption.sp),
        color = ChatTheme.colors.metadata,
        textAlign = if (isMine) TextAlign.End else TextAlign.Start
    )
}

fun formatChatMessageTime(timestamp: Long): String = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    .format(java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()))