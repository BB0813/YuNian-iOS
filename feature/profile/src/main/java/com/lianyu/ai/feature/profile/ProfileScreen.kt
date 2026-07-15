package com.lianyu.ai.feature.profile

import com.lianyu.ai.uicommon.theme.AppTheme
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.feature.profile.R
import com.lianyu.ai.uicommon.component.ChatBackgroundPickerDialog
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import com.lianyu.ai.uicommon.component.setChatBackgroundKey

/**
 * 个人中心主页面 — 仅保留核心入口，其余设置收进"总设置"页。
 *
 * 分层策略：
 *   外层（本页） — 高频核心入口：记忆、API、主题/背景、总设置入口、关于
 *   内层         — 总设置页收纳：语言、帧率、思考、TTS、Token、更新、权限、微信/QQ
 */
@Composable
fun ProfileScreen(
    // 记忆与管理
    onMemoryClick: () -> Unit,
    // AI配置
    onSettingsClick: () -> Unit,
    // 外观
    onThemeClick: () -> Unit,
    // 总设置
    onGeneralSettingsClick: () -> Unit,
    // 角色管理
    onRoleManagerClick: () -> Unit,
    // 关于与支持
    onTeamClick: () -> Unit = {},
    onSupportClick: () -> Unit = {},
    onThanksClick: () -> Unit = {},
    onAboutClick: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val selectedRole by viewModel.selectedRole.collectAsState()
    val userStatus by viewModel.userStatus.collectAsState()
    var isEditingName by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }
    var isEditingStatus by remember { mutableStateOf(false) }
    var editStatus by remember { mutableStateOf(userStatus) }

    var showBackgroundDialog by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.updateUserAvatar(it.toString()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // 顶部用户信息区域（头像左 + 名称&状态右）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colorScheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像（左侧）
            Box(modifier = Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
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
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 名称 + 状态（右侧）
            Column(modifier = Modifier.weight(1f)) {
                if (isEditingName) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.success,
                            unfocusedBorderColor = colorScheme.outline,
                            focusedContainerColor = colorScheme.surface,
                            unfocusedContainerColor = colorScheme.surface
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                if (editName.isNotBlank()) viewModel.updateUserName(editName.trim())
                                isEditingName = false
                            }) {
                                Icon(Icons.Filled.Edit, stringResource(R.string.profile_save), tint = AppTheme.colors.success)
                            }
                        }
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isEditingName = true; editName = userName }
                    ) {
                        Text(
                            text = userName,
                            style = MaterialTheme.typography.headlineSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 20.sp
                            ),
                            color = colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Edit,
                            stringResource(R.string.profile_edit),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 状态行
                if (isEditingStatus) {
                    OutlinedTextField(
                        value = editStatus,
                        onValueChange = { editStatus = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("输入状态，如：睡觉、工作、开心...", fontSize = 12.sp) },
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppTheme.colors.success,
                            unfocusedBorderColor = colorScheme.outline,
                            focusedContainerColor = colorScheme.surface,
                            unfocusedContainerColor = colorScheme.surface
                        ),
                        trailingIcon = {
                            IconButton(onClick = {
                                viewModel.updateUserStatus(editStatus.trim())
                                isEditingStatus = false
                            }) {
                                Icon(Icons.Filled.Edit, stringResource(R.string.profile_save), tint = AppTheme.colors.success, modifier = Modifier.size(18.dp))
                            }
                        }
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { isEditingStatus = true; editStatus = userStatus }
                    ) {
                        Text(
                            text = userStatus.ifBlank { "设置状态" },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp
                            ),
                            color = if (userStatus.isBlank()) colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Filled.Edit,
                            stringResource(R.string.profile_edit),
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // === 第一组：角色管理 ===
        val roleSubtitle = when (selectedRole) {
            com.lianyu.ai.common.CompanionRole.GIRLFRIEND -> stringResource(R.string.role_manager_desc_girlfriend)
            com.lianyu.ai.common.CompanionRole.BOYFRIEND -> stringResource(R.string.role_manager_desc_boyfriend)
        }
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Favorite, stringResource(R.string.role_manager), roleSubtitle, onRoleManagerClick)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 第二组：记忆与管理 ===
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Memory, stringResource(R.string.memory_management), stringResource(R.string.memory_management_desc), onMemoryClick)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 第三组：AI配置 ===
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Settings, stringResource(R.string.api_settings), stringResource(R.string.api_settings_desc), onSettingsClick)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 第四组：外观 ===
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Brush, stringResource(R.string.theme_mode), stringResource(R.string.theme_mode_desc), onThemeClick),
                MenuItemData(Icons.Filled.Palette, stringResource(R.string.chat_background), stringResource(R.string.chat_background_desc)) {
                    showBackgroundDialog = true
                }
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 第五组：总设置入口 ===
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Settings, stringResource(R.string.general_settings), stringResource(R.string.general_settings_desc), onGeneralSettingsClick)
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        // === 第六组：关于与支持 ===
        SolidMenuGroup(
            items = listOf(
                MenuItemData(Icons.Filled.Groups, stringResource(R.string.dev_team), stringResource(R.string.dev_team_desc), onTeamClick),
                MenuItemData(Icons.Filled.Favorite, stringResource(R.string.support_us), stringResource(R.string.support_us_desc), onSupportClick),
                MenuItemData(Icons.Filled.ThumbUp, stringResource(R.string.thanks_title), stringResource(R.string.thanks_card_desc), onThanksClick),
                MenuItemData(Icons.Filled.Info, stringResource(R.string.about_app), stringResource(R.string.about_app_desc), onAboutClick)
            )
        )

        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showBackgroundDialog) {
        ChatBackgroundPickerDialog(
            currentKey = getChatBackgroundKey(context),
            onDismiss = { showBackgroundDialog = false },
            onSelect = { key -> setChatBackgroundKey(context, key); showBackgroundDialog = false }
        )
    }
}

// ============================================================================
// 通用菜单组件
// ============================================================================

internal data class MenuItemData(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
internal fun SolidMenuGroup(items: List<MenuItemData>) {
    val colorScheme = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        items.forEachIndexed { index, item ->
            SolidMenuItem(item.icon, item.title, item.subtitle, item.onClick, index < items.size - 1)
        }
    }
}

@Composable
internal fun SolidMenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit, showDivider: Boolean) {
    val colorScheme = AppTheme.colors
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, title, Modifier.size(24.dp), tint = AppTheme.colors.success)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp), color = colorScheme.onSurface)
                if (subtitle.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colorScheme.outline).padding(start = 36.dp))
        }
    }
}
