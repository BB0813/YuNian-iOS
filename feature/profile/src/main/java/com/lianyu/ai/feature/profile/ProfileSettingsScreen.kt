package com.lianyu.ai.feature.profile

import com.lianyu.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.feature.profile.R
import com.lianyu.ai.uicommon.picker.ui.CustomImagePicker
import com.lianyu.ai.uicommon.image.cropper.ImageCropperDialog
import com.lianyu.ai.uicommon.component.bounceVerticalScroll
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current

    val userName by viewModel.userName.collectAsState()
    val userAvatar by viewModel.userAvatar.collectAsState()
    val userSignature by viewModel.userSignature.collectAsState()
    val userStatus by viewModel.userStatus.collectAsState()
    val userGender by viewModel.userGender.collectAsState()

    val genderLabel = when (userGender) {
        "male" -> stringResource(R.string.profile_gender_male)
        "female" -> stringResource(R.string.profile_gender_female)
        else -> stringResource(R.string.profile_not_set)
    }

    // --- 编辑对话框状态 ---
    var showNameDialog by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf(userName) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var editSignature by remember { mutableStateOf(userSignature) }
    var showStatusDialog by remember { mutableStateOf(false) }
    var editStatus by remember { mutableStateOf(userStatus) }
    var showGenderDialog by remember { mutableStateOf(false) }
    var showRegionDialog by remember { mutableStateOf(false) }
    var editRegion by remember { mutableStateOf("") }
    var showAvatarFullscreen by remember { mutableStateOf(false) }

    // --- 自研图片选择器 ---
    var showPicker by remember { mutableStateOf(false) }
    // --- 图片裁剪器 ---
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.profile_settings_title),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onSurface
                )
                // 默认 windowInsets 保留状态栏留白，避免与系统状态栏重合
            )
        },
        containerColor = colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .bounceVerticalScroll(resistance = 0.38f, maxOverscrollDp = 88f)
                .verticalScroll(rememberScrollState())
        ) {
            // === 头像 — 复用 ProfileSectionRow 原子 ===
            // 整行点击打开相册；头像区域单独点击全屏查看
            // 布局：标签 | 头像 | 箭头
            ProfileSectionRow(onClick = { showPicker = true }) {
                Text(
                    stringResource(R.string.profile_avatar),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                    color = colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                // 头像图片（点击全屏查看，不使用圆形涟漪）
                val avatarInteraction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceVariant)
                        .clickable(
                            interactionSource = avatarInteraction,
                            indication = null,
                            onClick = { showAvatarFullscreen = true }
                        ),
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
                            Icons.Filled.Person,
                            stringResource(R.string.profile_avatar),
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            ProfileSectionDivider()

            // === 名字 ===
            ProfileLabelValueRow(
                label = stringResource(R.string.profile_name),
                value = userName.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userName.isBlank(),
                onClick = { editName = userName; showNameDialog = true }
            )
            ProfileSectionDivider()

            // === 性别 ===
            ProfileLabelValueRow(
                label = stringResource(R.string.profile_gender),
                value = genderLabel,
                dimmed = userGender.isBlank(),
                onClick = { showGenderDialog = true }
            )
            ProfileSectionDivider()

            // === 地区 ===
            ProfileLabelValueRow(
                label = stringResource(R.string.profile_region),
                value = editRegion.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = editRegion.isBlank(),
                onClick = { showRegionDialog = true }
            )
            ProfileSectionDivider()

            // === 状态 ===
            ProfileLabelValueRow(
                label = stringResource(R.string.profile_status),
                value = userStatus.ifBlank { stringResource(R.string.profile_not_set) },
                dimmed = userStatus.isBlank(),
                onClick = { editStatus = userStatus; showStatusDialog = true }
            )
            ProfileSectionDivider()

            // === 签名 ===
            ProfileLabelValueRow(
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
    // 状态编辑对话框
    // ================================================================
    if (showStatusDialog) {
        BottomLineEditDialog(
            title = stringResource(R.string.profile_status),
            value = editStatus,
            onValueChange = { if (it.length <= 16) editStatus = it },
            placeholder = stringResource(R.string.profile_status),
            maxLength = 16,
            onConfirm = {
                viewModel.updateUserStatus(editStatus.trim())
                showStatusDialog = false
            },
            onDismiss = { showStatusDialog = false }
        )
    }

    // ================================================================
    // 性别选择 — 上下滑动滚轮单选
    // ================================================================
    if (showGenderDialog) {
        GenderPickerDialog(
            initialGender = userGender,
            onConfirm = { selectedGender ->
                viewModel.updateUserGender(selectedGender)
                showGenderDialog = false
            },
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

    // ================================================================
    // 自研图片选择器（替换系统相册）
    // ================================================================
    if (showPicker) {
        CustomImagePicker(
            maxSelection = 1,
            onConfirmed = { uris ->
                showPicker = false
                if (uris.isNotEmpty()) {
                    pendingCropUri = uris.first()
                }
            },
            onDismiss = { showPicker = false }
        )
    }

    // 加载待裁剪图片
    LaunchedEffect(pendingCropUri) {
        val uri = pendingCropUri ?: return@LaunchedEffect
        val ctx = context
        cropBitmap = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val bmp = BitmapFactory.decodeStream(stream)
                    bmp?.asImageBitmap()
                }
            } catch (_: Exception) { null }
        }
    }

    // 图片裁剪器
    if (cropBitmap != null) {
        ImageCropperDialog(
            bitmap = cropBitmap!!,
            cropRatio = 1f,
            onConfirm = { cropped ->
                // 保存裁剪结果到缓存文件
                val cacheFile = java.io.File(context.cacheDir, "avatar_cropped_${System.currentTimeMillis()}.jpg")
                try {
                    java.io.FileOutputStream(cacheFile).use { out ->
                        cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    viewModel.updateUserAvatar(cacheFile.absolutePath)
                } catch (_: Exception) { }
                cropBitmap = null
                pendingCropUri = null
            },
            onDismiss = {
                cropBitmap = null
                pendingCropUri = null
            }
        )
    }
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
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            decorFitsSystemWindows = false
        )
    ) {
        // ═══ 边缘到边缘 ═══
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.addFlags(
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                )
                w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                WindowInsetsControllerCompat(w, w.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

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

            // 关闭按钮
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                Icon(
                    Icons.Filled.Close,
                    "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
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
// 性别上下滑动滚轮单选
// ============================================================================

@Composable
private fun GenderPickerDialog(
    initialGender: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "male" to stringResource(R.string.profile_gender_male),
        "female" to stringResource(R.string.profile_gender_female)
    )
    val initialIndex = options.indexOfFirst { it.first == initialGender }.let { if (it >= 0) it else 0 }
    val itemHeight = 48.dp
    val visibleCount = 3
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val itemHeightPx = with(density) { itemHeight.toPx() }

    val selectedIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visible = layoutInfo.visibleItemsInfo
            if (visible.isEmpty()) {
                listState.firstVisibleItemIndex.coerceIn(0, options.lastIndex)
            } else {
                val viewportCenter =
                    layoutInfo.viewportStartOffset +
                        (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset) / 2
                visible.minByOrNull { info ->
                    abs((info.offset + info.size / 2) - viewportCenter)
                }?.index?.coerceIn(0, options.lastIndex)
                    ?: listState.firstVisibleItemIndex.coerceIn(0, options.lastIndex)
            }
        }
    }

    // 打开时滚到当前已保存项，保证居中高亮
    LaunchedEffect(initialIndex) {
        listState.scrollToItem(initialIndex)
    }

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
                    .height(itemHeight * visibleCount),
                contentAlignment = Alignment.Center
            ) {
                // 中间选中高亮带
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppTheme.colors.success.copy(alpha = 0.12f))
                )

                LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    contentPadding = PaddingValues(vertical = itemHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(options.size, key = { options[it].first }) { index ->
                        val isSelected = index == selectedIndex
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    scope.launch {
                                        listState.animateScrollToItem(index)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                options[index].second,
                                fontSize = if (isSelected) 20.sp else 16.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) {
                                    AppTheme.colors.success
                                } else {
                                    AppTheme.colors.onSurface.copy(alpha = 0.45f)
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 若仍在惯性滚动，按当前最近项 + 偏移估算最终选中
                    val index = if (listState.isScrollInProgress) {
                        val offsetBias = if (listState.firstVisibleItemScrollOffset > itemHeightPx / 2) 1 else 0
                        (listState.firstVisibleItemIndex + offsetBias).coerceIn(0, options.lastIndex)
                    } else {
                        selectedIndex
                    }
                    onConfirm(options[index].first)
                }
            ) {
                Text(stringResource(R.string.profile_confirm), color = AppTheme.colors.success)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.profile_cancel))
            }
        }
    )
}
