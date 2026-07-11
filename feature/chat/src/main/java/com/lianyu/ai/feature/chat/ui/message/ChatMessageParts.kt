package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.uicommon.component.AppMessageAvatar
import com.lianyu.ai.uicommon.component.AppMessageTimestamp
import com.lianyu.ai.uicommon.component.formatAppMessageTime
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.AppTheme

val LocalCompanionAvatarClick = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun ChatMessageAvatar(
    isMine: Boolean,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    adaptiveSizing: AdaptiveSizing
) {
    val companionAvatarClick = LocalCompanionAvatarClick.current
    androidx.compose.foundation.layout.Box(
        modifier = if (!isMine && companionAvatarClick != null) {
            Modifier.clickable(onClick = companionAvatarClick)
        } else Modifier
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
        style = AppTheme.typography.labelSmall.copy(fontSize = adaptiveSizing.fontSizeCaption.sp),
        color = AppTheme.colors.metadataContent
    )
}

fun formatChatMessageTime(timestamp: Long): String = formatAppMessageTime(timestamp)

