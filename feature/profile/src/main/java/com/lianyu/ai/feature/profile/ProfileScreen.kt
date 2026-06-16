package com.lianyu.ai.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.lianyu.ai.uicommon.theme.ThemeViewModel
import com.lianyu.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.lianyu.ai.feature.profile.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkCard
import com.lianyu.ai.uicommon.theme.WeChatDarkDivider
import com.lianyu.ai.uicommon.theme.WeChatDarkSurface
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary
import com.lianyu.ai.uicommon.theme.WeChatLightBackground
import com.lianyu.ai.uicommon.theme.WeChatLightCard
import com.lianyu.ai.uicommon.theme.WeChatLightDivider
import com.lianyu.ai.uicommon.theme.WeChatLightSurface
import com.lianyu.ai.uicommon.theme.WeChatLightTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatLightTextSecondary
import com.lianyu.ai.common.BatteryOptimizationHelper
import com.lianyu.ai.uicommon.component.ChatBackgroundPickerDialog
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import com.lianyu.ai.uicommon.component.setChatBackgroundKey
import kotlinx.coroutines.delay

@Composable
fun ProfileScreen(
    onSettingsClick: () -> Unit,
    onMemoryClick: () -> Unit,
    onThemeClick: () -> Unit,
    onLanguageClick: () -> Unit,
    onCheckUpdateClick: () -> Unit,
    onAboutClick: () -> Unit,
    onBackgroundClick: () -> Unit = {},
    onFrameRateClick: () -> Unit = {},
    onTeamClick: () -> Unit = {},
    onSupportClick: () -> Unit = {},
    onAppSettingsClick: () -> Unit = {},
    onWeChatClick: () -> Unit = {},
    onContextMemoryClick: () -> Unit = {},
    viewModel: ProfileViewModel = viewModel()
) {
    val context = LocalContext.current
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val backgroundColor = if (isDark) WeChatDarkBackground else WeChatLightBackground
    val surfaceColor = if (isDark) WeChatDarkSurface else WeChatLightSurface
    val cardColor = if (isDark) WeChatDarkCard else Color.White
    val textPrimary = if (isDark) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDark) WeChatDarkTextSecondary else WeChatLightTextSecondary
    val dividerColor = if (isDark) WeChatDarkDivider else WeChatLightDivider

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    var isEditingName by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }
    var isVisible by remember { mutableStateOf(false) }

    var showBackgroundDialog by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.updateUserAvatar(it.toString()) }
    }

    LaunchedEffect(Unit) {
        delay(100)
        isVisible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 顶部用户信息区域 - 居中大卡片
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(cardColor)
                    .clickable { imagePicker.launch("image/*") }
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 头像
                Box(
                    modifier = Modifier.size(84.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5E5E5))
                            .clickable { imagePicker.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (userAvatar != null) {
                            AsyncImage(
                                model = userAvatar,
                                contentDescription = stringResource(R.string.profile_avatar),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = stringResource(R.string.profile_avatar),
                                tint = Color(0xFFAAAAAA),
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 名字
                if (isEditingName) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth(0.8f),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF07C160),
                            unfocusedBorderColor = dividerColor,
                            focusedContainerColor = surfaceColor,
                            unfocusedContainerColor = surfaceColor
                        ),
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (editName.isNotBlank()) {
                                        viewModel.updateUserName(editName.trim())
                                    }
                                    isEditingName = false
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = stringResource(R.string.profile_save),
                                    tint = Color(0xFF07C160)
                                )
                            }
                        }
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            isEditingName = true
                            editName = userName
                        }
                    ) {
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 22.sp
                            ),
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = stringResource(R.string.profile_edit),
                            tint = textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 签名
                Text(
                    text = stringResource(R.string.profile_avatar_hint),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 12.sp
                    ),
                    color = textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 第一组：记忆管理 + 上下文记忆
        SolidMenuGroup(
            items = listOf(
                MenuItemData(
                    icon = Icons.Filled.Memory,
                    title = stringResource(R.string.memory_management),
                    subtitle = stringResource(R.string.memory_management_desc),
                    onClick = onMemoryClick
                ),
                MenuItemData(
                    icon = Icons.Filled.Memory,
                    title = "上下文记忆",
                    subtitle = "设置 AI 对话记忆条数",
                    onClick = onContextMemoryClick
                )
            ),
            isVisible = isVisible,
            delayMillis = 100,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            dividerColor = dividerColor,
            isDark = isDark
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 第二组：API/主题/背景
        SolidMenuGroup(
            items = listOf(
                MenuItemData(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.api_settings),
                    subtitle = stringResource(R.string.api_settings_desc),
                    onClick = onSettingsClick
                ),
                MenuItemData(
                    icon = Icons.Filled.Brush,
                    title = stringResource(R.string.theme_mode),
                    subtitle = stringResource(R.string.theme_mode_desc),
                    onClick = onThemeClick
                ),
                MenuItemData(
                    icon = Icons.Filled.Palette,
                    title = stringResource(R.string.chat_background),
                    subtitle = stringResource(R.string.chat_background_desc),
                    onClick = { showBackgroundDialog = true }
                ),
                MenuItemData(
                    icon = Icons.Filled.ChatBubble,
                    title = stringResource(R.string.wechat_settings),
                    subtitle = stringResource(R.string.wechat_settings_desc),
                    onClick = onWeChatClick
                )
            ),
            isVisible = isVisible,
            delayMillis = 200,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            dividerColor = dividerColor,
            isDark = isDark
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 第三组：开发团队/支持我们/关于应用
        SolidMenuGroup(
            items = listOf(
                MenuItemData(
                    icon = Icons.Filled.Groups,
                    title = stringResource(R.string.dev_team),
                    subtitle = stringResource(R.string.dev_team_desc),
                    onClick = onTeamClick
                ),
                MenuItemData(
                    icon = Icons.Filled.Favorite,
                    title = stringResource(R.string.support_us),
                    subtitle = stringResource(R.string.support_us_desc),
                    onClick = onSupportClick
                ),
                MenuItemData(
                    icon = Icons.Filled.Info,
                    title = stringResource(R.string.about_app),
                    subtitle = stringResource(R.string.about_app_desc),
                    onClick = onAboutClick
                )
            ),
            isVisible = isVisible,
            delayMillis = 300,
            textPrimary = textPrimary,
            textSecondary = textSecondary,
            dividerColor = dividerColor,
            isDark = isDark
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 第四组：设置（点击进入专属页面）
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(400, delayMillis = 400)) +
                    slideInVertically(tween(400, delayMillis = 400)) { it / 4 }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cardColor)
                    .clickable(onClick = onAppSettingsClick)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings),
                        modifier = Modifier.size(24.dp),
                        tint = Color(0xFF07C160)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp
                            ),
                            color = textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.settings_desc),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 12.sp
                            ),
                            color = textSecondary
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showBackgroundDialog) {
        ChatBackgroundPickerDialog(
            currentKey = getChatBackgroundKey(context),
            onDismiss = { showBackgroundDialog = false },
            onSelect = { key ->
                setChatBackgroundKey(context, key)
                showBackgroundDialog = false
            }
        )
    }
}

data class MenuItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
fun SolidMenuGroup(
    items: List<MenuItemData>,
    isVisible: Boolean,
    delayMillis: Int,
    textPrimary: Color,
    textSecondary: Color,
    dividerColor: Color,
    isDark: Boolean
) {
    val cardColor = if (isDark) WeChatDarkCard else Color.White
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(400, delayMillis = delayMillis)) +
                slideInVertically(tween(400, delayMillis = delayMillis)) { it / 4 }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cardColor)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items.forEachIndexed { index, item ->
                SolidMenuItem(
                    icon = item.icon,
                    title = item.title,
                    subtitle = item.subtitle,
                    onClick = item.onClick,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    showDivider = index < items.size - 1,
                    dividerColor = dividerColor
                )
            }
        }
    }
}

@Composable
fun SolidMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    textPrimary: Color,
    textSecondary: Color,
    showDivider: Boolean,
    dividerColor: Color
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                modifier = Modifier.size(24.dp),
                tint = Color(0xFF07C160)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    ),
                    color = textPrimary
                )
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp
                        ),
                        color = textSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(dividerColor)
                    .padding(start = 36.dp)
            )
        }
    }
}
