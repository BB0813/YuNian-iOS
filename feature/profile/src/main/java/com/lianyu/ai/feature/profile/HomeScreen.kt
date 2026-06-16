package com.lianyu.ai.feature.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lianyu.ai.database.model.ChatGroup
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.uicommon.theme.PinkMuted
import com.lianyu.ai.uicommon.theme.PinkPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkCard
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary
import com.lianyu.ai.uicommon.theme.WeChatLightBackground
import com.lianyu.ai.uicommon.theme.WeChatLightTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatLightTextSecondary
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.rememberAdaptiveSizing
import com.lianyu.ai.database.viewmodel.ChatGroupViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.lianyu.ai.uicommon.theme.ThemeViewModel
import com.lianyu.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme

enum class HomeTab {
    ALL, GROUP, FRIEND
}

@Composable
fun HomeScreen(
    onCompanionClick: (Long) -> Unit,
    onGroupClick: (Long) -> Unit,
    onAddClick: () -> Unit = {},
    onCreateGroupClick: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
    groupViewModel: ChatGroupViewModel = viewModel()
) {
    val chatList by viewModel.chatList.collectAsState(initial = emptyList())
    val groups by groupViewModel.groups.collectAsState(initial = emptyList())
    var isVisible by remember { mutableStateOf(false) }
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    var selectedTab by remember { mutableStateOf(HomeTab.ALL) }
    val adaptiveSizing = rememberAdaptiveSizing()

    val backgroundColor = if (isDarkTheme) WeChatDarkBackground else WeChatLightBackground
    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDarkTheme) WeChatDarkTextSecondary else WeChatLightTextSecondary

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部标题 + 统计信息 + 操作按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "消息",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${chatList.size} 个会话 · ${groups.size} 个群聊",
                            fontSize = 12.sp,
                            color = textSecondary
                        )
                    }

                    // 右侧操作按钮（2个）
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (isDarkTheme) WeChatDarkCard else Color(0xFFF2F2F2))
                                .clickable { onCreateGroupClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Group,
                                contentDescription = "创建群聊",
                                modifier = Modifier.size(20.dp),
                                tint = textPrimary
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(if (isDarkTheme) WeChatDarkCard else Color(0xFFF2F2F2))
                                .clickable { onAddClick() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = "添加女友",
                                modifier = Modifier.size(20.dp),
                                tint = textPrimary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 胶囊标签切换
                HomeTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    isDarkTheme = isDarkTheme
                )
            }

            // 内容列表
            val displayGroups = when (selectedTab) {
                HomeTab.ALL, HomeTab.GROUP -> groups
                HomeTab.FRIEND -> emptyList()
            }
            val displayChats = when (selectedTab) {
                HomeTab.ALL, HomeTab.FRIEND -> chatList
                HomeTab.GROUP -> emptyList()
            }

            if (displayGroups.isEmpty() && displayChats.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyHomeState(isDarkTheme = isDarkTheme)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 80.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (displayGroups.isNotEmpty()) {
                        item {
                            SectionTitle(
                                title = "群聊",
                                isDarkTheme = isDarkTheme
                            )
                        }
                        itemsIndexed(displayGroups) { index, group ->
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(
                                    animationSpec = tween(300, delayMillis = index * 60)
                                ) + slideInVertically(
                                    animationSpec = tween(300, delayMillis = index * 60),
                                    initialOffsetY = { it / 3 }
                                )
                            ) {
                                GroupListItem(
                                group = group,
                                isDarkTheme = isDarkTheme,
                                onClick = { onGroupClick(group.id) },
                                adaptiveSizing = adaptiveSizing
                            )
                            }
                        }
                    }

                    if (displayChats.isNotEmpty()) {
                        item {
                            SectionTitle(
                                title = "好友",
                                isDarkTheme = isDarkTheme
                            )
                        }
                        itemsIndexed(displayChats) { index, item ->
                            AnimatedVisibility(
                                visible = isVisible,
                                enter = fadeIn(
                                    animationSpec = tween(300, delayMillis = (index + displayGroups.size) * 60)
                                ) + slideInVertically(
                                    animationSpec = tween(300, delayMillis = (index + displayGroups.size) * 60),
                                    initialOffsetY = { it / 3 }
                                )
                            ) {
                                ChatListItem(
                                    companion = item.companion,
                                    lastMessage = item.lastMessage,
                                    hasUnread = item.hasUnread,
                                    isDarkTheme = isDarkTheme,
                                    onClick = { onCompanionClick(item.companion.id) },
                                    adaptiveSizing = adaptiveSizing
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(
    title: String,
    isDarkTheme: Boolean
) {
    val textSecondary = if (isDarkTheme) WeChatDarkTextSecondary else WeChatLightTextSecondary

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(PinkPrimary)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = textSecondary
        )
    }
}

@Composable
fun HomeTabBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit,
    isDarkTheme: Boolean
) {
    val textSecondary = if (isDarkTheme) Color(0xFF8A727C) else Color(0xFFB89AA2)
    val selectedBg = if (isDarkTheme) PinkMuted.copy(alpha = 0.5f) else PinkPrimary.copy(alpha = 0.15f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (isDarkTheme) Color(0xFF241B20) else Color(0xFFF2F2F2))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        HomeTab.values().forEach { tab ->
            val selected = selectedTab == tab
            val label = when (tab) {
                HomeTab.ALL -> "全部"
                HomeTab.GROUP -> "群聊"
                HomeTab.FRIEND -> "好友"
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) selectedBg else Color.Transparent)
                    .clickable { onTabSelected(tab) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) PinkPrimary else textSecondary
                )
            }
        }
    }
}

@Composable
fun GroupListItem(
    group: ChatGroup,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDarkTheme) WeChatDarkTextSecondary else WeChatLightTextSecondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (isDarkTheme) WeChatDarkCard else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(adaptiveSizing.avatarSize),
                contentAlignment = Alignment.TopEnd
            ) {
                if (group.avatarUrl != null) {
                    AsyncImage(
                        model = group.avatarUrl,
                        contentDescription = group.name,
                        modifier = Modifier
                            .size(adaptiveSizing.avatarSize)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(adaptiveSizing.avatarSize)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        PinkPrimary.copy(alpha = 0.6f),
                                        PinkPrimary.copy(alpha = 0.3f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Group,
                            contentDescription = group.name,
                            tint = Color.White,
                            modifier = Modifier.size(adaptiveSizing.iconSize)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = group.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = adaptiveSizing.fontSizeBody.sp
                        ),
                        color = textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    text = "${group.getCompanionIdList().size} 人",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (adaptiveSizing.fontSizeBody - 1).sp,
                        lineHeight = 20.sp
                    ),
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ChatListItem(
    companion: CompanionEntity,
    lastMessage: ChatMessage?,
    hasUnread: Boolean = false,
    isDarkTheme: Boolean,
    onClick: () -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val time = lastMessage?.let { dateFormat.format(Date(it.timestamp)) } ?: ""

    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDarkTheme) WeChatDarkTextSecondary else WeChatLightTextSecondary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (isDarkTheme) WeChatDarkCard else Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(adaptiveSizing.avatarSize),
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(adaptiveSizing.avatarSize)
                        .clip(CircleShape)
                        .background(Color(0xFFE0E0E0)),
                    contentAlignment = Alignment.Center
                ) {
                    if (companion.avatarUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(companion.avatarUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = companion.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = Color(0xFF888888),
                            modifier = Modifier.size((adaptiveSizing.avatarSize * 0.58f))
                        )
                    }
                }
                if (hasUnread) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(PinkPrimary)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = companion.name,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Normal,
                            fontSize = adaptiveSizing.fontSizeBody.sp
                        ),
                        color = textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (time.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = time,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = adaptiveSizing.fontSizeSmall.sp
                            ),
                            color = textSecondary
                        )
                    }
                }
                Text(
                    text = lastMessage?.content ?: "还没有聊天记录，开始聊天吧",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (adaptiveSizing.fontSizeBody - 1).sp,
                        lineHeight = 20.sp
                    ),
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun EmptyHomeState(isDarkTheme: Boolean) {
    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDarkTheme) WeChatDarkTextSecondary else WeChatLightTextSecondary

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "还没有聊天记录",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = textPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "去通讯录找你的女友聊天吧",
                style = MaterialTheme.typography.bodyMedium,
                color = textSecondary
            )
        }
    }
}
