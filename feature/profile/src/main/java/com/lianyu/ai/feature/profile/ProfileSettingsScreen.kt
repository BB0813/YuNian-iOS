package com.lianyu.ai.feature.profile

import com.lianyu.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.feature.profile.R

// ============================================================================
// 个人资料设置页 — 头像/名字/性别/地区/签名，分行显示，可编辑
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProfileViewModel = viewModel()
) {
    val colorScheme = AppTheme.colors

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val userSignature by viewModel.userSignature.collectAsState()

    // --- 编辑对话框状态 ---
    var showNameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var editSignature by remember { mutableStateOf(userSignature) }
    var showGenderDialog by remember { mutableStateOf(false) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var editRegion by remember { mutableStateOf("") }
    var showAvatarFullscreen by remember { mutableStateOf(false) }

    // --- 头像选择：原子化权限中间件 → 直接打开系统相册 ---
    val galleryLauncher = rememberGalleryPermissionLauncher { uri ->
        viewModel.updateUserAvatar(uri.toString())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.profile_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onSurface
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        containerColor = colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // === 头像 ===
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧标签 + 箭头（点击打开相册）
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { galleryLauncher.pickImage() }
                        .padding(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.profile_avatar),
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                        tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }

                // 右侧头像图片（点击全屏查看）
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE8E8E8))
                        .clickable { showAvatarFullscreen = true },
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
                        Icon(Icons.Filled.Person, stringResource(R.string.profile_avatar),
                            tint = Color(0xFFCCCCCC), modifier = Modifier.size(28.dp))
                    }
                }
            }

            SectionDivider()

            // === 名字 ===
            SectionRow(
                label = stringResource(R.string.profile_name),
                value = userName.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userName.isBlank(),
                onClick = { editName = userName; showNameDialog = true }
            )
            SectionDivider()

            // === 性别 ===
            SectionRow(
                label = stringResource(R.string.profile_gender),
                value = stringResource(R.string.profile_not_set),
                dimmed = true,
                onClick = { showGenderDialog = true }
            )
            SectionDivider()

            // === 地区 ===
            SectionRow(
                label = stringResource(R.string.profile_region),
                value = editRegion.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = editRegion.isBlank(),
                onClick = { showRegionDialog = true }
            )
            SectionDivider()

            // === 签名 ===
            SectionRow(
                label = stringResource(R.string.profile_signature),
                value = userSignature.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userSignature.isBlank(),
                onClick = { editSignature = userSignature; showSignatureDialog = true }
            )
        }
    }

    // ================================================================
    // 头像全屏预览
    // ================================================================
    if (showAvatarFullscreen && userAvatar != null) {
        AvatarFullscreenDialog(
            avatarUri = userAvatar!!,
            onDismiss = { showAvatarFullscreen = false }
        )
    }

    // ================================================================
    // 名字编辑对话框 — 底边线输入
    // ================================================================
    if (showNameDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_name),
            value = editName,
            onValueChange = { editName = it },
            placeholder = stringResource(R.string.profile_name),
            onConfirm = {
                if (editName.isNotBlank()) viewModel.updateUserName(editName.trim())
                showNameDialog = false
            },
            onDismiss = { showNameDialog = false }
        )
    }

    // ================================================================
    // 签名编辑对话框 — 底边线输入 + 字数统计
    // ================================================================
    if (showSignatureDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_signature),
            value = editSignature,
            onValueChange = { if (it.length <= 30) editSignature = it },
            placeholder = stringResource(R.string.profile_signature),
            maxLength = 30,
            onConfirm = {
                viewModel.updateUserSignature(editSignature.trim())
                showSignatureDialog = false
            },
            onDismiss = { showSignatureDialog = false }
        )
    }

    // ================================================================
    // 性别选择 — 垂直滚动选择器
    // ================================================================
    if (showGenderDialog) {
        GenderPickerDialog(
            onConfirm = { showGenderDialog = false },
            onDismiss = { showGenderDialog = false }
        )
    }

    // ================================================================
    // 地区编辑对话框 — 底边线输入
    // ================================================================
    if (showRegionDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_region),
            value = editRegion,
            onValueChange = { editRegion = it },
            placeholder = "如：广东·深圳",
            onConfirm = { showRegionDialog = false },
            onDismiss = { showRegionDialog = false }
        )
    }
}

// ============================================================================
// 辅助组件
// ============================================================================

@Composable
private fun SectionRow(
    label: String,
    value: String,
    dimmed: Boolean = false,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp), color = colorScheme.onSurface, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = if (dimmed) colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(0.5.dp)
            .background(AppTheme.colors.outline.copy(alpha = 0.3f))
    )
}

// ============================================================================
// 头像全屏预览弹窗
// ============================================================================

@Composable
private fun AvatarFullscreenDialog(
    avatarUri: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = avatarUri,
                contentDescription = "头像大图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

// ============================================================================
// 底边线输入对话框 — 无边框，仅底部线条，文字紧贴底线
// ============================================================================

@Composable
private fun BottomLineEditDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    maxLength: Int = Int.MAX_VALUE,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val lineColor = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f)
    val accentColor = AppTheme.colors.success

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Medium) },
        text = {
            Column {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.4f),
                            fontSize = 16.sp
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 16.sp,
                            color = AppTheme.colors.onSurface
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        cursorBrush = SolidColor(accentColor)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                // 底部线条
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(lineColor)
                )
                if (maxLength < Int.MAX_VALUE) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${value.length}/$maxLength",
                        fontSize = 12.sp,
                        color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认", color = accentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ============================================================================
// 性别垂直滚动选择器
// ============================================================================

@Composable
private fun GenderPickerDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        stringResource(R.string.profile_gender_male),
        stringResource(R.string.profile_gender_female)
    )
    var selectedIndex by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.profile_gender),
                fontWeight = FontWeight.Medium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    options.forEachIndexed { index, label ->
                        val isSelected = index == selectedIndex
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index }
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                fontSize = if (isSelected) 18.sp else 16.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) AppTheme.colors.success
                                    else AppTheme.colors.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        if (index < options.size - 1) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.4f)
                                    .height(0.5.dp)
                                    .background(AppTheme.colors.onSurfaceVariant.copy(alpha = 0.15f))
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("确认", color = AppTheme.colors.success)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
