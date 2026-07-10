package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.feature.chat.ui.theme.ChatColors
import com.lianyu.ai.feature.chat.ui.theme.ChatDimens
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem

@Composable
fun SystemTipBubble(item: ChatListItem.SystemTip) {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ChatDimens.SystemTipHorizontalPadding, vertical = ChatDimens.SystemTipVerticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = item.content,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = ChatDimens.SystemTipFontSize),
            color = ChatColors.systemTipContent(colorScheme),
            textAlign = TextAlign.Center
        )
    }
}