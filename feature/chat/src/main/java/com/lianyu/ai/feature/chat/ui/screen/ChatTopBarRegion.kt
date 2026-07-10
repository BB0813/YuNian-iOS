package com.lianyu.ai.feature.chat.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.feature.chat.ui.theme.ChatTheme
import com.lianyu.ai.network.tts.ChatTtsMode
import com.lianyu.ai.uicommon.component.CompanionAvatar
import com.lianyu.ai.uicommon.theme.AdaptiveSizing

internal object ChatTopBarOverlayDefaults {
    val TopInset = 48.dp
    val BarVerticalPadding = 8.dp
    val ActionSize = 32.dp
    val ContentTopPadding = TopInset + BarVerticalPadding * 2 + ActionSize + 16.dp
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
fun ChatTopBarRegion(
    companionId: Long,
    companionData: CompanionEntity?,
    isLoading: Boolean,
    isDarkTheme: Boolean,
    currentTtsMode: ChatTtsMode,
    isTtsModeMenuExpanded: Boolean,
    onBackClick: () -> Unit,
    onDetailClick: (Long) -> Unit,
    onTtsModeMenuExpandedChange: (Boolean) -> Unit,
    onCycleTtsModeClick: () -> Unit,
    onTtsModeSelected: (ChatTtsMode) -> Unit,
    onVoiceCallClick: () -> Unit,
    adaptiveSizing: AdaptiveSizing,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
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
                    tint = ChatTheme.colors.topBarIcon,
                    modifier = Modifier.size(20.dp)
                )
            }

            ChatTitleSlot(
                companionId = companionId,
                companionData = companionData,
                isLoading = isLoading,
                isDarkTheme = isDarkTheme,
                onDetailClick = onDetailClick,
                modifier = Modifier.weight(1f)
            )

            TtsModeButton(
                currentTtsMode = currentTtsMode,
                isExpanded = isTtsModeMenuExpanded,
                onExpandedChange = onTtsModeMenuExpandedChange,
                onCycleClick = onCycleTtsModeClick,
                onModeSelected = onTtsModeSelected
            )

            TopBarIconButton(
                contentDescription = "语音通话",
                onClick = onVoiceCallClick
            ) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = null,
                    tint = ChatTheme.colors.topBarAccent,
                    modifier = Modifier.size(20.dp)
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
            .background(ChatTheme.colors.topBarBackground.copy(alpha = 0.85f))
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
    isDarkTheme: Boolean,
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
                    isDarkTheme = isDarkTheme,
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
            color = ChatTheme.colors.metadata
        )
        Text(
            text = "对方正在输入...",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp
            ),
            color = ChatTheme.colors.metadata
        )
    }
}

@Composable
private fun CompanionTitle(
    companionId: Long,
    companionData: CompanionEntity?,
    isDarkTheme: Boolean,
    onDetailClick: (Long) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CompanionAvatar(
            avatarUrl = companionData?.avatarUrl,
            name = companionData?.name,
            size = 24.dp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = companionData?.name ?: "",
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            ),
            color = ChatTheme.colors.topBarTitle
        )
        Spacer(modifier = Modifier.width(6.dp))
        AiGeneratedBadge(isDarkTheme = isDarkTheme)
        Spacer(modifier = Modifier.width(6.dp))
        TopBarIconButton(
            contentDescription = "详情",
            onClick = { onDetailClick(companionId) }
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = ChatTheme.colors.topBarIcon
            )
        }
    }
}

@Composable
private fun AiGeneratedBadge(isDarkTheme: Boolean) {
    Box(
        modifier = Modifier
            .widthIn(max = 72.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(
                ChatTheme.colors.topBarAccent.copy(
                    alpha = if (isDarkTheme) 0.2f else 0.12f
                )
            )
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = "AI生成仅供参考",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = ChatTheme.colors.topBarAccent
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TtsModeButton(
    currentTtsMode: ChatTtsMode,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onCycleClick: () -> Unit,
    onModeSelected: (ChatTtsMode) -> Unit
) {
    Box(
        modifier = Modifier
            .size(ChatTopBarOverlayDefaults.ActionSize)
            .combinedClickable(
                onClick = onCycleClick,
                onLongClick = { onExpandedChange(true) }
            ),
        contentAlignment = Alignment.Center
    ) {
        TtsModeIcon(
            mode = currentTtsMode,
            isSelected = currentTtsMode != ChatTtsMode.SILENT,
            contentDescription = "朗读模式"
        )
        TtsModeMenu(
            currentTtsMode = currentTtsMode,
            expanded = isExpanded,
            onDismiss = { onExpandedChange(false) },
            onModeSelected = {
                onModeSelected(it)
                onExpandedChange(false)
            }
        )
    }
}

@Composable
private fun TtsModeMenu(
    currentTtsMode: ChatTtsMode,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onModeSelected: (ChatTtsMode) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(ChatTheme.colors.menuBackground)
    ) {
        ChatTtsMode.entries.forEach { mode ->
            TtsModeMenuItem(
                mode = mode,
                selected = mode == currentTtsMode,
                onClick = { onModeSelected(mode) }
            )
        }
    }
}

@Composable
private fun TtsModeMenuItem(
    mode: ChatTtsMode,
    selected: Boolean,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Column {
                Text(
                    text = mode.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ChatTheme.colors.menuContent
                )
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatTheme.colors.metadata
                )
            }
        },
        onClick = onClick,
        leadingIcon = {
            TtsModeIcon(
                mode = mode,
                isSelected = selected,
                contentDescription = null
            )
        }
    )
}

@Composable
private fun TtsModeIcon(
    mode: ChatTtsMode,
    isSelected: Boolean,
    contentDescription: String?
) {
    Icon(
        imageVector = if (mode == ChatTtsMode.SILENT)
            Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
        contentDescription = contentDescription,
        tint = if (isSelected) ChatTheme.colors.topBarAccent
            else ChatTheme.colors.metadata,
        modifier = Modifier.size(20.dp)
    )
}