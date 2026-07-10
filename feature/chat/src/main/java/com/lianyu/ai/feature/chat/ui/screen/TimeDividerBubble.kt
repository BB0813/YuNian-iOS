package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TimeDividerBubble(item: ChatListItem.TimeDivider) {
    val label = remember(item.timestamp) { formatTimeDividerLabel(item.timestamp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = ChatTheme.metrics.timeDividerVerticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            modifier = Modifier
                .clip(RoundedCornerShape(ChatTheme.shapes.timeDividerCornerRadius))
                .background(ChatTheme.colors.timeDividerBackground)
                .padding(
                    horizontal = ChatTheme.metrics.timeDividerHorizontalPadding,
                    vertical = ChatTheme.metrics.timeDividerInnerVerticalPadding
                ),
            style = ChatTheme.typography.timeDivider,
            color = ChatTheme.colors.metadata,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatTimeDividerLabel(timestamp: Long): String {
    val zoneId = ZoneId.systemDefault()
    val dateTime = Instant.ofEpochMilli(timestamp).atZone(zoneId).toLocalDateTime()
    val today = LocalDate.now(zoneId)
    val timeText = dateTime.format(DateTimeFormatter.ofPattern("HH:mm"))

    return when (dateTime.toLocalDate()) {
        today -> timeText
        today.minusDays(1) -> "昨天 $timeText"
        else -> dateTime.format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm"))
    }
}