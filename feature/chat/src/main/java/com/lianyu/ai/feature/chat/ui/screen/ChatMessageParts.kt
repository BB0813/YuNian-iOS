package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.feature.chat.ui.theme.withChatSize
import com.lianyu.ai.uicommon.component.AppMessageAvatar
import com.lianyu.ai.uicommon.component.AppMessageTimestamp
import com.lianyu.ai.uicommon.component.formatAppMessageTime
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
fun ChatMessageAvatar(
    isMine: Boolean,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    adaptiveSizing: AdaptiveSizing
) {
    AppMessageAvatar(
        isMine = isMine,
        companionAvatarUrl = companionData?.avatarUrl,
        companionName = companionData?.name,
        userAvatarUrl = userAvatar,
        userName = userName,
        size = adaptiveSizing.avatarSize
    )
}

@Composable
fun ChatMessageTimestamp(
    time: String,
    isMine: Boolean,
    adaptiveSizing: AdaptiveSizing
) {
    AppMessageTimestamp(
        time = time,
        isMine = isMine,
        style = ChatTheme.typography.timestamp.withChatSize(adaptiveSizing.fontSizeCaption.sp),
        color = ChatTheme.colors.metadata
    )
}

fun formatChatMessageTime(timestamp: Long): String = formatAppMessageTime(timestamp)