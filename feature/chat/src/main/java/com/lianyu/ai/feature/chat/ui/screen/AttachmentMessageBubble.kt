package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.uicommon.component.CompanionAvatar
import com.lianyu.ai.uicommon.component.UserAvatar
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun AttachmentMessageBubble(
    message: ChatMessage,
    companionData: CompanionModel?,
    userAvatar: String?,
    userName: String,
    onIntent: (ChatIntent) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean,
    icon: ImageVector,
    label: String
) {
    val isUser = message.isFromUser
    val time = remember(message.timestamp) {
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .format(java.time.Instant.ofEpochMilli(message.timestamp).atZone(java.time.ZoneId.systemDefault()))
    }
    var showMenu by remember { mutableStateOf(false) }

    val userBubbleColor = MaterialTheme.colorScheme.primary
    val aiBubbleColor = MaterialTheme.colorScheme.surfaceVariant
    val aiBorderColor = MaterialTheme.colorScheme.outline

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true }
                ),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (!isUser) {
                CompanionAvatar(
                    avatarUrl = companionData?.avatarUrl,
                    name = companionData?.name,
                    size = adaptiveSizing.avatarSize
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 200.dp)
                        .clip(RoundedCornerShape(adaptiveSizing.cornerRadius))
                        .background(if (isUser) userBubbleColor else aiBubbleColor)
                        .then(
                            if (isUser || isDarkTheme) Modifier else Modifier.drawBehind {
                                drawRoundRect(
                                    color = aiBorderColor,
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                        adaptiveSizing.cornerRadius.toPx(),
                                        adaptiveSizing.cornerRadius.toPx()
                                    ),
                                    style = Stroke(width = 0.8f)
                                )
                            }
                        )
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp),
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = adaptiveSizing.fontSizeCaption.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = if (isUser) TextAlign.End else TextAlign.Start
                )
            }

            if (isUser) {
                Spacer(modifier = Modifier.width(8.dp))
                UserAvatar(
                    avatarUrl = userAvatar,
                    name = userName,
                    size = adaptiveSizing.avatarSize
                )
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            modifier = Modifier.background(MaterialTheme.colorScheme.surface),
            offset = DpOffset(x = 0.dp, y = 0.dp)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = "引用",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    showMenu = false
                    onIntent(ChatIntent.QuoteReply(message))
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.FormatQuote,
                        contentDescription = "引用",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
            if (!isUser) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = "重新生成",
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        showMenu = false
                        onIntent(ChatIntent.Regenerate(message))
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "重新生成",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = "撤回消息",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                onClick = {
                    showMenu = false
                    onIntent(ChatIntent.Recall(message))
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "撤回",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
    }
}