package com.lianyu.ai.feature.companion.ui.screen

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
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.lianyu.ai.feature.companion.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.database.model.ChatGroup
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.viewmodel.CompanionListViewModel
import com.lianyu.ai.uicommon.component.AppListItemLayout
import com.lianyu.ai.uicommon.theme.AppTheme

@Composable
fun ContactsScreen(
    onCompanionClick: (Long) -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (Long) -> Unit,
    onGroupClick: (Long) -> Unit,
    onCreateGroupClick: () -> Unit,
    viewModel: CompanionListViewModel = viewModel(),
    groups: List<com.lianyu.ai.database.model.ChatGroup> = emptyList(),
    isVisible: Boolean = true
) {
    val companions by viewModel.companions.collectAsState(initial = emptyList())
    val colorScheme = MaterialTheme.colorScheme

    Scaffold { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(
                    bottom = paddingValues.calculateBottomPadding()
                )
        ) {
            if (companions.isEmpty() && groups.isEmpty()) {
                EmptyContactsState()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 通讯录标题
                    item {
                        Text(
                            text = "通讯录",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            ),
                            color = colorScheme.onSurface,
                            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp)
                        )
                    }

                    if (groups.isNotEmpty()) {
                        item {
                            Text(
                            text = stringResource(R.string.group_chat),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                        }
                        itemsIndexed(groups) { _, group ->
                            GroupContactItem(
                                group = group,
                                onClick = { onGroupClick(group.id) }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }

                    if (companions.isNotEmpty()) {
                        item {
                            Text(
                            text = stringResource(R.string.friends),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                        }
                        itemsIndexed(companions) { _, companion ->
                            ContactItem(
                                companion = companion,
                                onClick = { onCompanionClick(companion.id) },
                                onLongClick = { onEditClick(companion.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GroupContactItem(
    group: ChatGroup,
    onClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF07C160).copy(alpha = 0.6f),
                                Color(0xFF07C160).copy(alpha = 0.3f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Group,
                    contentDescription = null,
                    tint = colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
        },
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        onClick = onClick,
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = group.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.people_count, group.getCompanionIdList().size),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ContactItem(
    companion: CompanionEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    AppListItemLayout(
        isStartAligned = true,
        startSlot = {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (companion.avatarUrl != null) {
                    AsyncImage(
                        model = companion.avatarUrl,
                        contentDescription = companion.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = AppTheme.colors.captionContent,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        },
        endSlot = {},
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        onClick = onClick,
        onLongClick = onLongClick,
        slotGap = AppTheme.dimens.avatarGap
    ) {
        Text(
            text = companion.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            color = colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun EmptyContactsState() {
    val colorScheme = MaterialTheme.colorScheme

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5E5E5)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = Color(0xFF888888),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.no_contacts),
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.add_hint),
                fontSize = 14.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
    }
}
