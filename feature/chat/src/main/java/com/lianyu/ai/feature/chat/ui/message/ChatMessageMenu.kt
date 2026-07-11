package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.uicommon.theme.AppTheme

/**
 * 微信风格长按消息菜单 — 横向排列的圆角操作栏。
 *
 * 设计原则：
 * - 单行横向排列，图标在上、文字在下，与微信长按菜单一致
 * - 圆角胶囊容器，背景使用 menuBackground
 * - 撤回操作使用 danger 色，其余使用 menuContent
 * - 操作之间用竖向分隔线区分
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
    val menuBg = colors.menuBackground
    val contentColor = colors.menuContent

    MaterialTheme(
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
    ) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier
                .background(Color.Transparent)
                .clip(RoundedCornerShape(16.dp)),
            offset = DpOffset(x = 0.dp, y = (-8).dp)
        ) {
            val actions = buildList {
                add(MenuAction("引用", Icons.Outlined.FormatQuote, contentColor) { onIntent(ChatIntent.QuoteReply(message)) })
                if (!message.isFromUser) add(MenuAction("重新生成", Icons.Outlined.Refresh, contentColor) { onIntent(ChatIntent.Regenerate(message)) })
                if (copyText != null) add(MenuAction("复制", Icons.Outlined.ContentCopy, contentColor) { onIntent(ChatIntent.CopyText(copyText)) })
                add(MenuAction("撤回", Icons.Outlined.DeleteOutline, colors.danger) { onIntent(ChatIntent.Recall(message)) })
            }

            Row(
                modifier = Modifier
                    .background(menuBg)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEachIndexed { index, action ->
                    ChatMenuItem(action.text, action.icon, action.color) {
                        onDismiss()
                        action.onClick()
                    }
                    if (index < actions.size - 1) {
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(32.dp)
                                .background(colors.outlineVariant.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }
    }
}

private data class MenuAction(
    val text: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
private fun ChatMenuItem(
    text: String,
    icon: ImageVector,
    contentColor: Color,
    onClick: () -> Unit
) {
    val typography = AppTheme.typography

    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = contentColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = typography.bodyMedium.copy(fontSize = 11.sp),
            color = contentColor,
            textAlign = TextAlign.Center
        )
    }
}

