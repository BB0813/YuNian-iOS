package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.uicommon.theme.AppTheme

/**
 * 消息长按操作菜单。
 *
 * 布局：竖直列表；每一行左侧图标、右侧操作名。
 * 图标 18dp、文字 13sp，严格控制视觉比例。
 */
@Composable
fun ChatMessageMenu(
    expanded: Boolean,
    message: ChatMessage,
    onDismiss: () -> Unit,
    onIntent: (ChatIntent) -> Unit,
    copyText: String? = null
) {
    val colors = AppTheme.colors
    val dimens = AppTheme.dimens
    val menuBg = colors.menuBackground
    val contentColor = colors.menuContent
    val iconColor = colors.menuIcon

    // 严格控制：图标 18dp、文字 13sp（来自 AppTheme.dimens）
    val iconSize = dimens.menuIconSize
    val labelSize = dimens.menuTextFontSize
    val itemHorizontalPadding = 14.dp
    val itemVerticalPadding = 11.dp
    val iconTextGap = 12.dp
    val menuMinWidth = 148.dp
    val menuMaxWidth = 176.dp

    MaterialTheme(
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(12.dp))
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier
                .widthIn(min = menuMinWidth, max = menuMaxWidth)
                .clip(RoundedCornerShape(12.dp))
                .background(menuBg),
            offset = DpOffset(x = 0.dp, y = (-6).dp)
        ) {
            val actions = buildList {
                add(
                    MenuAction(
                        text = "引用",
                        icon = Icons.Outlined.FormatQuote,
                        contentColor = contentColor,
                        iconColor = iconColor
                    ) { onIntent(ChatIntent.QuoteReply(message)) }
                )
                if (!message.isFromUser) {
                    add(
                        MenuAction(
                            text = "重新生成",
                            icon = Icons.Outlined.Refresh,
                            contentColor = contentColor,
                            iconColor = iconColor
                        ) { onIntent(ChatIntent.Regenerate(message)) }
                    )
                }
                if (copyText != null) {
                    add(
                        MenuAction(
                            text = "复制",
                            icon = Icons.Outlined.ContentCopy,
                            contentColor = contentColor,
                            iconColor = iconColor
                        ) { onIntent(ChatIntent.CopyText(copyText)) }
                    )
                }
                add(
                    MenuAction(
                        text = "撤回",
                        icon = Icons.Outlined.DeleteOutline,
                        contentColor = colors.danger,
                        iconColor = colors.danger
                    ) { onIntent(ChatIntent.Recall(message)) }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            ) {
                actions.forEach { action ->
                    ChatMenuItem(
                        text = action.text,
                        icon = action.icon,
                        contentColor = action.contentColor,
                        iconColor = action.iconColor,
                        iconSize = iconSize,
                        labelSize = labelSize,
                        horizontalPadding = itemHorizontalPadding,
                        verticalPadding = itemVerticalPadding,
                        iconTextGap = iconTextGap
                    ) {
                        onDismiss()
                        action.onClick()
                    }
                }
            }
        }
    }
}

private data class MenuAction(
    val text: String,
    val icon: ImageVector,
    val contentColor: Color,
    val iconColor: Color,
    val onClick: () -> Unit
)

@Composable
private fun ChatMenuItem(
    text: String,
    icon: ImageVector,
    contentColor: Color,
    iconColor: Color,
    iconSize: Dp,
    labelSize: TextUnit,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    iconTextGap: Dp,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(iconSize)
        )
        Spacer(modifier = Modifier.width(iconTextGap))
        Text(
            text = text,
            color = contentColor,
            fontSize = labelSize,
            lineHeight = 18.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
