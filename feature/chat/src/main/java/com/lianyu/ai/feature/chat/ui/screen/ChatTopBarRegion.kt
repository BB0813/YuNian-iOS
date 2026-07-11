package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.AppTheme

internal object ChatTopBarOverlayDefaults {
    val TopInset = 48.dp
    val BarVerticalPadding = 8.dp
    val ActionSize = 32.dp
    val ContentTopPadding = TopInset + BarVerticalPadding * 2 + ActionSize + 16.dp
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ChatTopBarRegion(
    companionId: Long,
    companionData: CompanionEntity?,
    isLoading: Boolean,
    onBackClick: () -> Unit,
    onDetailClick: (Long) -> Unit,
    adaptiveSizing: AdaptiveSizing,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ChatTopBarOverlayDefaults.ContentTopPadding + 28.dp)
                .background(
                    Brush.verticalGradient(
                        0f to AppTheme.colors.background,
                        0.62f to AppTheme.colors.background.copy(alpha = 0.92f),
                        1f to AppTheme.colors.background.copy(alpha = 0f)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                top = ChatTopBarOverlayDefaults.TopInset,
                start = adaptiveSizing.listHorizontalPadding,
                end = adaptiveSizing.listHorizontalPadding
            ),
            contentAlignment = Alignment.Center
        ) {
            TopBarSurface {
            TopBarIconButton(
                contentDescription = "返回",
                onClick = onBackClick
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = AppTheme.colors.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            ChatTitleSlot(
                companionId = companionId,
                companionData = companionData,
                isLoading = isLoading,
                onDetailClick = onDetailClick,
                modifier = Modifier.weight(1f)
            )
        }
        }
    }
}

@Composable
private fun TopBarSurface(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(28.dp))
            .background(AppTheme.colors.surfaceVariant.copy(alpha = 0.85f))
            .padding(horizontal = 12.dp, vertical = ChatTopBarOverlayDefaults.BarVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        content = content
    )
}

@Composable
private fun TopBarIconButton(
    contentDescription: String,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(ChatTopBarOverlayDefaults.ActionSize)
            .semantics { this.contentDescription = contentDescription }
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun ChatTitleSlot(
    companionId: Long,
    companionData: CompanionEntity?,
    isLoading: Boolean,
    onDetailClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = isLoading,
            transitionSpec = {
                fadeIn(tween(200)) togetherWith fadeOut(tween(200))
            },
            label = "title_switch"
        ) { loading ->
            if (loading) {
                TypingTitle()
            } else {
                CompanionTitle(
                    companionId = companionId,
                    companionData = companionData,
                    onDetailClick = onDetailClick
                )
            }
        }
    }
}

@Composable
private fun TypingTitle() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(10.dp),
            strokeWidth = 1.5.dp,
            color = AppTheme.colors.metadataContent
        )
        Text(
            text = "对方正在输入...",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp
            ),
            color = AppTheme.colors.metadataContent
        )
    }
}

@Composable
private fun CompanionTitle(
    companionId: Long,
    companionData: CompanionEntity?,
    onDetailClick: (Long) -> Unit
) {
    Row(
        modifier = Modifier.clickable { onDetailClick(companionId) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = companionData?.name ?: "加载中",
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            ),
            color = AppTheme.colors.onSurface
        )
    }
}

