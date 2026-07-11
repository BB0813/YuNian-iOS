package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
fun ChatListItemRenderer(
    item: ChatListItem,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    onCompanionAvatarClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(LocalCompanionAvatarClick provides onCompanionAvatarClick) {
    Box(modifier = modifier) {
        when (item) {
            is ChatListItem.TimeDivider -> TimeDividerItem(item = item)
            is ChatListItem.SystemTip -> SystemTipItem(item = item)
            is ChatListItem.TextMessage -> TextMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
            is ChatListItem.ImageMessage -> ImageMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.VoiceMessage -> VoiceMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.StickerMessage -> StickerMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.VideoMessage -> VideoMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
            is ChatListItem.FileMessage -> FileMessageItem(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
        }
    }
    }
}

