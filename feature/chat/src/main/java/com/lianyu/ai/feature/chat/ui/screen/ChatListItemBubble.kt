package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
fun ChatListItemBubble(
    item: ChatListItem,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        when (item) {
            is ChatListItem.TimeDivider -> TimeDividerBubble(item = item)
            is ChatListItem.SystemTip -> SystemTipBubble(item = item)
            is ChatListItem.TextMessage -> TextMessageBubble(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
            is ChatListItem.ImageMessage -> ImageMessageBubble(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.VoiceMessage -> VoiceMessageBubble(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.StickerMessage -> StickerMessageBubble(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing
            )
            is ChatListItem.VideoMessage -> VideoMessageBubble(
                item = item,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                onIntent = onIntent,
                adaptiveSizing = adaptiveSizing,
                isDarkTheme = isDarkTheme
            )
            is ChatListItem.FileMessage -> FileMessageBubble(
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