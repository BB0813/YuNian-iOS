package com.lianyu.ai.feature.companion.ui.screen

import android.icu.text.Transliterator
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlinx.coroutines.launch

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
    val colorScheme = AppTheme.colors
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val visibleCompanions = remember(companions, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) companions else companions.filter {
            it.name.contains(query, ignoreCase = true)
        }
    }
    val sortedCompanions = remember(visibleCompanions) {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
        visibleCompanions.sortedWith(compareBy(collator) { it.name })
    }
    val groupedCompanions = remember(sortedCompanions) {
        sortedCompanions.groupBy { getSectionKey(it.name) }
    }
    val sectionKeys = remember(groupedCompanions) {
        groupedCompanions.keys.sorted()
    }

    // 计算每个 section 在 LazyColumn 中的索引（用于侧边栏跳转）
    val sectionIndexMap = remember(groups, groupedCompanions, sectionKeys) {
        val map = mutableMapOf<String, Int>()
        var idx = 1 // title
        if (groups.isNotEmpty()) {
            idx += groups.size + 2 // group header + items + spacer
        }
        idx += 1 // friends header
        for (key in sectionKeys) {
            map[key] = idx
            idx += groupedCompanions[key].orEmpty().size + 1
        }
        map
    }

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
            LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp, horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 通讯录标题（居中 + 搜索/添加按钮）
                    item(key = "title") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, bottom = 8.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.weight(1f))

                            if (isSearching) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.weight(2f),
                                    singleLine = true,
                                    placeholder = { Text("搜索好友") },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = colorScheme.primary,
                                        unfocusedBorderColor = colorScheme.outline
                                    )
                                )
                            } else {
                                Text(
                                    text = "通讯录",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 22.sp
                                    ),
                                    color = colorScheme.onSurface
                                )
                            }

                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(colorScheme.surfaceVariant)
                                            .clickable {
                                                isSearching = !isSearching
                                                if (!isSearching) searchQuery = ""
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = if (isSearching) Icons.Outlined.Close else Icons.Outlined.Search,
                                            contentDescription = if (isSearching) "关闭搜索" else "搜索好友",
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
                                            imageVector = Icons.Outlined.PersonAdd,
                                            contentDescription = "添加好友",
                                            modifier = Modifier.size(20.dp),
                                            tint = colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (groups.isNotEmpty()) {
                        item(key = "groups_header") {
                            Text(
                                text = stringResource(R.string.group_chat),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }
                        itemsIndexed(groups, key = { _, g -> "group_${g.id}" }) { _, group ->
                            GroupContactItem(
                                group = group,
                                onClick = { onGroupClick(group.id) }
                            )
                        }
                        item(key = "groups_spacer") { Spacer(modifier = Modifier.height(8.dp)) }
                    }

                    // 好友列表（按首字符分组）
                    if (companions.isNotEmpty()) {
                        item(key = "friends_header") {
                            Text(
                                text = stringResource(R.string.friends),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            )
                        }

                        sectionKeys.forEach { key ->
                            val itemsInSection = groupedCompanions[key] ?: return@forEach
                            item(key = "section_$key") {
                                Text(
                                    text = key,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(colorScheme.primary.copy(alpha = 0.08f))
                                        .padding(horizontal = 12.dp, vertical = 4.dp)
                                )
                            }
                            itemsIndexed(itemsInSection, key = { _, c -> "companion_${c.id}" }) { _, companion ->
                                ContactItem(
                                    companion = companion,
                                    onClick = { onCompanionClick(companion.id) },
                                    onLongClick = { onEditClick(companion.id) }
                                )
                            }
                        }
                    }

                    if (companions.isEmpty() && groups.isEmpty()) {
                        item(key = "empty_contacts") { EmptyContactsState() }
                    } else if (isSearching && visibleCompanions.isEmpty()) {
                        item(key = "empty_search") { EmptyContactsState() }
                    }
                }

                // 字母侧边栏
                if (sectionKeys.size > 1) {
                    AlphabetSidebar(
                        letters = sectionKeys,
                        modifier = Modifier.align(Alignment.CenterEnd),
                        onLetterClick = { letter ->
                            sectionIndexMap[letter]?.let { index ->
                                scope.launch {
                                    listState.animateScrollToItem(index)
                                }
                            }
                        }
                    )
                }
        }
    }
}

@Composable
fun GroupContactItem(
    group: ChatGroup,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors

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
                                AppTheme.colors.success.copy(alpha = 0.6f),
                                AppTheme.colors.success.copy(alpha = 0.3f)
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
    val colorScheme = AppTheme.colors

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

/**
 * 获取名字拼音的首字母作为分组 key。
 */
private fun getSectionKey(name: String): String {
     val normalized = PINYIN_TRANSLITERATOR.transliterate(name.trim())
     val first = normalized.firstOrNull() ?: return "#"
    return when {
        first in 'A'..'Z' || first in 'a'..'z' -> first.uppercase()
        first in '0'..'9' -> "#"
        else -> "#"
    }
}

private val PINYIN_TRANSLITERATOR: Transliterator by lazy {
    Transliterator.getInstance("Han-Latin; Latin-ASCII")
}

/**
 * 字母侧边栏，点击字母可跳转到对应 section。
 */
@Composable
private fun AlphabetSidebar(
    letters: List<String>,
    modifier: Modifier = Modifier,
    onLetterClick: (String) -> Unit
) {
    val colorScheme = AppTheme.colors

    Column(
        modifier = modifier
            .padding(end = 2.dp)
            .pointerInput(letters) {
                detectTapGestures { offset ->
                    val letterHeight = size.height.toFloat() / letters.size
                    val index = (offset.y / letterHeight).toInt().coerceIn(0, letters.size - 1)
                    onLetterClick(letters[index])
                }
            },
        verticalArrangement = Arrangement.Center
    ) {
        letters.forEach { letter ->
            Text(
                text = letter,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = 1.dp, horizontal = 4.dp)
            )
        }
    }
}
