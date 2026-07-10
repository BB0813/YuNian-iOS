package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem

@Composable
fun SystemTipBubble(item: ChatListItem.SystemTip) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = ChatTheme.metrics.systemTipHorizontalPadding,
                vertical = ChatTheme.metrics.systemTipVerticalPadding
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = item.content,
            style = ChatTheme.typography.systemTip,
            color = ChatTheme.colors.systemTip,
            textAlign = TextAlign.Center
        )
    }
}