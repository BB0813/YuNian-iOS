package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
    onRetryBody: (Long) -> Unit = {},
    onCompanionAvatarClick: () -> Unit = {},
    onUserAvatarClick: () -> Unit = {},
    autoCollapseReasoning: Boolean = true,
    modifier: Modifier = Modifier
) {
    CompositionLocalProvider(
        LocalCompanionAvatarClick provides onCompanionAvatarClick,
        LocalUserAvatarClick provides onUserAvatarClick
    ) {
    // 列表项必须占满行宽，AppListItemLayout 才能正确扣掉头像列
    Box(modifier = modifier.fillMaxWidth()) {
        when (item) {
            is ChatListItem.BodyLoading -> BodyStateItem(isError = false)
            is ChatListItem.BodyError -> BodyStateItem(
                isError = true,
                onClick = { onRetryBody(item.metadata.id) }
            )
            is ChatListItem.TimeDivider -> TimeDividerItem(item = item)
            is ChatListItem.SystemTip -> SystemTipItem(item = item)
            is ChatListItem.ReasoningMessage -> ReasoningItem(
                reasoningText = item.text,
                adaptiveSizing = adaptiveSizing,
                companionData = companionData,
                userAvatar = userAvatar,
                userName = userName,
                autoCollapse = autoCollapseReasoning,
                isStreaming = item.isStreaming,
                durationMs = item.durationMs,
            )
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

@Composable
private fun BodyStateItem(isError: Boolean, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isError, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isError) {
            Text("正文加载失败，点击重试")
        } else {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
}

