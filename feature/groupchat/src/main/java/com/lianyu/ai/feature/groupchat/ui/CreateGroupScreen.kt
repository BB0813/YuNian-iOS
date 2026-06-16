package com.lianyu.ai.feature.groupchat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.lianyu.ai.feature.groupchat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkCard
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary
import com.lianyu.ai.uicommon.theme.WeChatGreen
import com.lianyu.ai.uicommon.theme.WeChatLightBackground
import com.lianyu.ai.uicommon.theme.WeChatLightTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatLightTextSecondary
import com.lianyu.ai.database.viewmodel.ChatGroupViewModel
import com.lianyu.ai.database.viewmodel.CompanionListViewModel
import kotlinx.coroutines.delay
import com.lianyu.ai.uicommon.theme.ThemeViewModel
import com.lianyu.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(
    onNavigateBack: () -> Unit,
    companionListViewModel: CompanionListViewModel = viewModel(),
    groupViewModel: ChatGroupViewModel = viewModel()
) {
    val companions by companionListViewModel.companions.collectAsState(initial = emptyList())
    var groupName by remember { mutableStateOf("") }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var isVisible by remember { mutableStateOf(false) }
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val backgroundColor = if (isDarkTheme) WeChatDarkBackground else WeChatLightBackground
    val cardColor = if (isDarkTheme) WeChatDarkCard else Color.White
    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDarkTheme) WeChatDarkTextSecondary else WeChatLightTextSecondary

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                    text = stringResource(R.string.create_group),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    ),
                    color = textPrimary
                )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.group_chat),
                        tint = textPrimary
                    )
                    }
                },
                actions = {
                    val canCreate = groupName.isNotBlank() && selectedIds.isNotEmpty()
                    Text(
                        text = stringResource(R.string.create),
                        color = if (canCreate) WeChatGreen else textSecondary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable(enabled = canCreate) {
                                groupViewModel.createGroup(groupName.trim(), selectedIds.toList())
                                onNavigateBack()
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = backgroundColor
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(top = paddingValues.calculateTopPadding())
                .padding(horizontal = 16.dp)
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }
            ) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.group_name), color = textSecondary) },
                    placeholder = { Text(stringResource(R.string.group_name_hint), color = textSecondary.copy(alpha = 0.6f)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = WeChatGreen,
                        unfocusedBorderColor = if (isDarkTheme) Color(0xFF38383A) else Color(0xFFE5E5E5),
                        focusedContainerColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White,
                        unfocusedContainerColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color.White,
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.select_companions, selectedIds.size, companions.size),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (companions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_companions),
                        color = textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(companions) { companion ->
                        val isSelected = selectedIds.contains(companion.id)
                        CompanionSelectItem(
                            companion = companion,
                            isSelected = isSelected,
                            isDarkTheme = isDarkTheme,
                            onClick = {
                                if (isSelected) {
                                    selectedIds.remove(companion.id)
                                } else {
                                    selectedIds.add(companion.id)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CompanionSelectItem(
    companion: CompanionEntity,
    isSelected: Boolean,
    isDarkTheme: Boolean,
    onClick: () -> Unit
) {
    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val cardColor = if (isDarkTheme) WeChatDarkCard else Color.White

    Box(
        modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(cardColor)
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE5E5E5)),
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
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = companion.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = textPrimary,
                modifier = Modifier.weight(1f)
            )

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(WeChatGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE5E5E5))
                )
            }
        }
    }
}
