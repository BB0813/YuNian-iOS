package com.lianyu.ai.feature.chat.ui.message

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.uicommon.component.CompanionAvatar
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.AppTheme

@Composable
fun TypingIndicatorItem(
    companionData: CompanionModel?,
    typingText: String,
    adaptiveSizing: AdaptiveSizing,
    isDarkTheme: Boolean
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val displayText = typingText.trim()

    if (displayText.isEmpty()) return

    ChatMessageFrame(
        isMine = false,
        adaptiveSizing = adaptiveSizing,
        isDarkTheme = isDarkTheme,
        avatar = {
            CompanionAvatar(
                avatarUrl = companionData?.avatarUrl,
                name = companionData?.name,
                size = adaptiveSizing.avatarSize
            )
        },
        timestamp = {}
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(
                text = displayText,
                style = typography.bodyLarge.copy(fontSize = adaptiveSizing.fontSizeBody.sp, lineHeight = 20.sp),
                color = colors.secondaryBubbleContent
            )
        }
    }
}

@Composable
fun RegeneratingItem(
    companionData: CompanionModel?,
    adaptiveSizing: AdaptiveSizing
) {
    val colors = AppTheme.colors
    val typography = AppTheme.typography
    val infiniteTransition = rememberInfiniteTransition(label = "regenerate_dots")
    val dot1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400), RepeatMode.Reverse), label = "dot1"
    )
    val dot2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, 150), RepeatMode.Reverse), label = "dot2"
    )
    val dot3Alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(400, 300), RepeatMode.Reverse), label = "dot3"
    )

    ChatMessageFrame(
        isMine = false,
        adaptiveSizing = adaptiveSizing,
        avatar = {
            CompanionAvatar(
                avatarUrl = companionData?.avatarUrl,
                name = companionData?.name,
                size = adaptiveSizing.avatarSize
            )
        },
        timestamp = {}
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "正在重新生成",
                style = typography.bodyLarge.copy(fontSize = 14.sp),
                color = colors.secondaryBubbleContent
            )
            Spacer(modifier = Modifier.width(4.dp))
            repeat(3) { index ->
                val alpha = listOf(dot1Alpha, dot2Alpha, dot3Alpha)[index]
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .alpha(alpha)
                        .clip(CircleShape)
                        .background(colors.metadataContent)
                )
                if (index < 2) {
                    Spacer(modifier = Modifier.width(3.dp))
                }
            }
        }
    }
}

@Composable
fun ReasoningItem(
    reasoningText: String,
    adaptiveSizing: AdaptiveSizing
) {
    var expanded by remember { mutableStateOf(false) }
    val colors = AppTheme.colors
    val typography = AppTheme.typography

    ChatMessageFrame(
        isMine = false,
        adaptiveSizing = adaptiveSizing,
        avatar = { Spacer(modifier = Modifier.size(adaptiveSizing.avatarSize)) },
        timestamp = {}
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { expanded = !expanded }
            ) {
                Text(
                    text = if (expanded) "思考中 ▼" else "已思考 ▶",
                    style = typography.labelSmall.copy(fontSize = 12.sp),
                    color = colors.metadataContent
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = reasoningText,
                    style = typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.metadataContent
                )
            }
        }
    }
}

