package com.lianyu.ai.feature.profile

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.rememberAdaptiveSizing
import com.lianyu.ai.database.viewmodel.ChatGroupViewModel
import com.lianyu.ai.uicommon.component.AppListItemLayout
import com.lianyu.ai.uicommon.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val chatListState by viewModel.chatListState.collectAsState()
    val groups by groupViewModel.groups.collectAsState()
    var selectedTab by remember { mutableStateOf(HomeTab.ALL) }
    val adaptiveSizing = rememberAdaptiveSizing()
    val colorScheme = AppTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 列表区域透出 MainScreen 主界面背景层；顶栏单独铺不透明底
            .background(Color.Transparent)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 不透明顶部导航栏：标题「恋语」+ 两个操作图标
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp)
                    .padding(top = 4.dp, bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 左侧占位，与右侧按钮对称，使标题居中
                    Box(modifier = Modifier.weight(1f))

                    Text(
                        text = "恋语",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = colorScheme.onSurface
                    )

                    // 右侧操作按钮（2个），右对齐
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(colorScheme.surfaceVariant)
                                    .clickable { onCreateGroupClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Group,
                                    contentDescription = "创建群聊",
                                    modifier = Modifier.size(20.dp),
                                    tint = colorScheme.onSurface
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(colorScheme.surfaceVariant)
                                    .clickable { onAddClick() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Person,
                                    contentDescription = "添加好友",
                                    modifier = Modifier.size(20.dp),
                                    tint = colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 胶囊标签切换
                HomeTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 统计信息（放在导航栏下方）
                val chatCount = when (chatListState) {
                    is HomeViewModel.UiState.Ready ->
                        (chatListState as HomeViewModel.UiState.Ready).items.size
                    else -> 0
                }
                Text(
                    text = "${chatCount} 个会话 · ${groups.size} 个群聊",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }

            // 内容列表
            val displayGroups = when (selectedTab) {
                HomeTab.ALL, HomeTab.GROUP -> groups
                HomeTab.FRIEND -> emptyList()
            }
            val displayChats = when {
                chatListState is HomeViewModel.UiState.Ready -> {
                    val items = (chatListState as HomeViewModel.UiState.Ready).items
                    when (selectedTab) {
                        HomeTab.ALL, HomeTab.FRIEND -> items
                        HomeTab.GROUP -> emptyList()
                    }
                }
                else -> emptyList()
            }

            when {
                chatListState is HomeViewModel.UiState.Loading && groups.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = colorScheme.primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                displayGroups.isEmpty() && displayChats.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyHomeState()
                    }
                }
                else -> {
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
                                title = "群聊"
                            )
                        }
                        itemsIndexed(displayGroups) { _, group ->
                            GroupListItem(
                                group = group,
                                onClick = { onGroupClick(group.id) },
                                adaptiveSizing = adaptiveSizing
                            )
                        }
                    }

                    if (displayChats.isNotEmpty()) {
                        item {
                            SectionTitle(
                                title = "好友"
                            )
                        }
                        itemsIndexed(displayChats) { _, item ->
                            ChatListItem(
                                companion = item.companion,
                                lastMessage = item.lastMessage,
                                hasUnread = item.hasUnread,
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
    title: String
) {
    val colorScheme = AppTheme.colors

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
            color = colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun HomeTabBar(
    selectedTab: HomeTab,
    onTabSelected: (HomeTab) -> Unit
) {
    val colorScheme = AppTheme.colors
    val selectedBg = AppTheme.colors.primaryContainer

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(AppTheme.colors.surface)
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
                    color = if (selected) PinkPrimary else colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun GroupListItem(
    group: ChatGroup,
    onClick: () -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val colorScheme = AppTheme.colors

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {
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
                            tint = colorScheme.onPrimary,
                            modifier = Modifier.size(adaptiveSizing.iconSize)
                        )
                    }
                }
            }
        },
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Normal,
                    fontSize = adaptiveSizing.fontSizeBody.sp
                ),
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${group.getCompanionIdList().size} 人",
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (adaptiveSizing.fontSizeBody - 1).sp,
                    lineHeight = 20.sp
                ),
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ChatListItem(
    companion: CompanionEntity,
    lastMessage: ChatMessage?,
    hasUnread: Boolean = false,
    onClick: () -> Unit,
    adaptiveSizing: AdaptiveSizing
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val time = lastMessage?.let { dateFormat.format(Date(it.timestamp)) } ?: ""

    val colorScheme = AppTheme.colors

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {
            Box(
                modifier = Modifier.size(adaptiveSizing.avatarSize),
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = Modifier
                        .size(adaptiveSizing.avatarSize)
                        .clip(CircleShape)
                        .background(colorScheme.surface),
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
                            tint = AppTheme.colors.captionContent,
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
        },
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
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
                        color = colorScheme.onSurface,
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
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = lastMessage?.content ?: "还没有聊天记录，开始聊天吧",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = (adaptiveSizing.fontSizeBody - 1).sp,
                        lineHeight = 20.sp
                    ),
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
        }
    }
}

@Composable
fun EmptyHomeState() {
    val colorScheme = AppTheme.colors

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "还没有聊天记录",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium
                ),
                color = colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "去通讯录找你的女友聊天吧",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}
