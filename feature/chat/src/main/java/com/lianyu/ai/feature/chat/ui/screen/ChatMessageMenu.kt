package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent

@Composable
fun ChatMessageMenu(
    expanded: Boolean,
    message: ChatMessage,
    onDismiss: () -> Unit,
    onIntent: (ChatIntent) -> Unit,
    copyText: String? = null
) {
    val colors = ChatTheme.colors
    val metrics = ChatTheme.metrics
    val menuBg = colors.menuBackground
    val contentColor = colors.menuContent

    MaterialTheme(
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.background(menuBg),
            offset = DpOffset(x = 0.dp, y = metrics.menuOffsetY)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                ChatMenuItem(
                    text = "引用",
                    icon = Icons.Outlined.FormatQuote,
                    contentColor = contentColor,
                    onClick = {
                        onDismiss()
                        onIntent(ChatIntent.QuoteReply(message))
                    }
                )
                if (!message.isFromUser) {
                    ChatMenuItem(
                        text = "重新生成",
                        icon = Icons.Outlined.Refresh,
                        contentColor = contentColor,
                        onClick = {
                            onDismiss()
                            onIntent(ChatIntent.Regenerate(message))
                        }
                    )
                }
                if (copyText != null) {
                    ChatMenuItem(
                        text = "复制",
                        icon = Icons.Outlined.ContentCopy,
                        contentColor = contentColor,
                        onClick = {
                            onDismiss()
                            onIntent(ChatIntent.CopyText(copyText))
                        }
                    )
                }
                ChatMenuItem(
                    text = "撤回消息",
                    icon = Icons.Outlined.DeleteOutline,
                    contentColor = contentColor,
                    onClick = {
                        onDismiss()
                        onIntent(ChatIntent.Recall(message))
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatMenuItem(
    text: String,
    icon: ImageVector,
    contentColor: Color,
    onClick: () -> Unit
) {
    val typography = ChatTheme.typography
    val metrics = ChatTheme.metrics

    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = contentColor,
            modifier = Modifier.size(metrics.menuIconSize)
        )
        Text(
            text = text,
            style = typography.menu.copy(fontSize = 11.sp),
            color = contentColor,
            textAlign = TextAlign.Center
        )
    }
}