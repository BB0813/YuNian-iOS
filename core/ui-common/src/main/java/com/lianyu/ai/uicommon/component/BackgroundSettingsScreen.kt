package com.lianyu.ai.uicommon.component

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.image.cropper.ImageCropperDialog
import com.lianyu.ai.uicommon.picker.ui.CustomImagePicker
import com.lianyu.ai.uicommon.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================================
// SharedPreferences keys
// ============================================================================
private const val BG_PREFS_NAME = "chat_prefs"
private const val MAIN_BG_KEY = "main_background"

fun getMainBackgroundKey(context: android.content.Context): String {
    return context.getSharedPreferences(BG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getString(MAIN_BG_KEY, "default") ?: "default"
}

fun setMainBackgroundKey(context: android.content.Context, key: String) {
    context.getSharedPreferences(BG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(MAIN_BG_KEY, key)
        .apply()
    // 写入后立即同步到窗口层，避免必须返回主 tab / ON_RESUME 才可见
    val activity = context.findActivity()
    if (activity != null) {
        WindowMainBackground.forceApply(
            activity.window,
            activity,
            key,
            WindowMainBackground.resolveIsDarkTheme(activity)
        )
    }
}

private tailrec fun android.content.Context.findActivity(): android.app.Activity? {
    return when (this) {
        is android.app.Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

// ============================================================================
// Main BackgroundSettingsScreen composable
// ============================================================================

/**
 * 全屏背景设置页面。
 * 包含两个区域：「主界面背景」和「聊天背景」，各自独立配置。
 * 每个区域都支持：预设色块、专业取色盘、自定义图片（自研相册 + 裁剪）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackgroundSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors

    var mainBgKey by remember { mutableStateOf(getMainBackgroundKey(context)) }
    var chatBgKey by remember { mutableStateOf(getChatBackgroundKey(context)) }

    var showColorPicker by remember { mutableStateOf(false) }
    var colorPickerTarget by remember { mutableStateOf(BgTarget.Main) }

    // 自研相册 + 裁剪管线
    var showImagePicker by remember { mutableStateOf(false) }
    var imagePickerTarget by remember { mutableStateOf(BgTarget.Main) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val isDark = colorScheme.surface.luminance() < 0.5f
    val presets = remember(isDark) {
        chatBackgroundOptions(context).map { option ->
            val (color, gradient) = resolveBackgroundPalette(option.key, isDark)
            option.copy(color = color, gradient = gradient)
        }
    }

    fun applyBackgroundKey(target: BgTarget, key: String) {
        when (target) {
            BgTarget.Main -> {
                setMainBackgroundKey(context, key)
                mainBgKey = key
            }
            BgTarget.Chat -> {
                setChatBackgroundKey(context, key)
                chatBgKey = key
            }
        }
    }

    val customMainKey = if (isCustomBackground(mainBgKey)) mainBgKey else null
    val customChatKey = if (isCustomBackground(chatBgKey)) chatBgKey else null

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "背景设置",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colorScheme.background
                )
            )
        },
        containerColor = colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item { SectionHeader(title = "主界面背景") }
            item {
                BackgroundPresetGrid(
                    presets = presets,
                    currentKey = mainBgKey,
                    customKey = customMainKey,
                    onSelectPreset = { key -> applyBackgroundKey(BgTarget.Main, key) },
                    onPickColor = {
                        colorPickerTarget = BgTarget.Main
                        showColorPicker = true
                    },
                    onPickImage = {
                        imagePickerTarget = BgTarget.Main
                        showImagePicker = true
                    },
                    onDeleteCustom = { key ->
                        deleteCustomBackground(context, key)
                        applyBackgroundKey(BgTarget.Main, "default")
                    }
                )
            }

            item { SectionHeader(title = "聊天背景") }
            item {
                BackgroundPresetGrid(
                    presets = presets,
                    currentKey = chatBgKey,
                    customKey = customChatKey,
                    onSelectPreset = { key -> applyBackgroundKey(BgTarget.Chat, key) },
                    onPickColor = {
                        colorPickerTarget = BgTarget.Chat
                        showColorPicker = true
                    },
                    onPickImage = {
                        imagePickerTarget = BgTarget.Chat
                        showImagePicker = true
                    },
                    onDeleteCustom = { key ->
                        deleteCustomBackground(context, key)
                        applyBackgroundKey(BgTarget.Chat, "default")
                    }
                )
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    if (showColorPicker) {
        val seedColor = when (colorPickerTarget) {
            BgTarget.Main -> parseColorBackground(mainBgKey)
                ?: presets.find { it.key == mainBgKey }?.color
                ?: Color(0xFFF5F5F5)
            BgTarget.Chat -> parseColorBackground(chatBgKey)
                ?: presets.find { it.key == chatBgKey }?.color
                ?: Color(0xFFF5F5F5)
        }
        ProfessionalColorPickerDialog(
            currentColor = seedColor,
            onColorPicked = { color ->
                applyBackgroundKey(colorPickerTarget, colorBackgroundKey(color))
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }

    if (showImagePicker) {
        CustomImagePicker(
            maxSelection = 1,
            onConfirmed = { uris ->
                showImagePicker = false
                if (uris.isNotEmpty()) {
                    pendingCropUri = uris.first()
                }
            },
            onDismiss = { showImagePicker = false }
        )
    }

    LaunchedEffect(pendingCropUri) {
        val uri = pendingCropUri ?: return@LaunchedEffect
        cropBitmap = withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
        if (cropBitmap == null) {
            pendingCropUri = null
        }
    }

    if (cropBitmap != null) {
        ImageCropperDialog(
            bitmap = cropBitmap!!,
            // 背景使用接近全屏比例，避免强制 1:1 裁切
            cropRatio = 9f / 16f,
            onConfirm = { cropped ->
                val key = saveCustomBackground(context, cropped)
                if (key != null) {
                    applyBackgroundKey(imagePickerTarget, key)
                }
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
// Section Components
// ============================================================================

private enum class BgTarget { Main, Chat }

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        ),
        color = AppTheme.colors.onSurface,
        modifier = Modifier.padding(top = 8.dp)
    )
}

@Composable
private fun BackgroundPresetGrid(
    presets: List<ChatBackgroundOption>,
    currentKey: String,
    customKey: String?,
    onSelectPreset: (String) -> Unit,
    onPickColor: () -> Unit,
    onPickImage: () -> Unit,
    onDeleteCustom: (String) -> Unit
) {
    val colorScheme = AppTheme.colors
    val selectedColor = parseColorBackground(currentKey)

    @Composable
    fun BgBox(bgColor: Color, bgBrush: Brush?) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = bgBrush ?: Brush.verticalGradient(listOf(bgColor, bgColor)),
                    shape = RoundedCornerShape(12.dp)
                )
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colorScheme.surfaceVariant)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val rows = presets.chunked(4)
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                row.forEach { option ->
                    val isSelected = currentKey == option.key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.15f)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (isSelected) Modifier.border(3.dp, AppTheme.colors.success, RoundedCornerShape(12.dp))
                                else Modifier.border(0.5.dp, colorScheme.outline, RoundedCornerShape(12.dp))
                            )
                            .clickable { onSelectPreset(option.key) },
                        contentAlignment = Alignment.Center
                    ) {
                        BgBox(bgColor = option.color, bgBrush = option.gradient)
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(AppTheme.colors.success),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Check,
                                    "已选中",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // 当前纯色预览
        if (selectedColor != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(selectedColor)
                    .border(2.dp, AppTheme.colors.success, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("当前纯色", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onPickColor,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.success)
            ) {
                Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("专业取色", fontSize = 13.sp)
            }

            OutlinedButton(
                onClick = onPickImage,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTheme.colors.success)
            ) {
                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("自定义图片", fontSize = 13.sp)
            }
        }

        if (customKey != null && isCustomBackground(customKey)) {
            val context = LocalContext.current
            val file = remember(customKey, context) { getCustomBackgroundFile(context, customKey) }
            if (file != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, AppTheme.colors.success, RoundedCornerShape(12.dp))
                ) {
                    AsyncImage(
                        model = file,
                        contentDescription = "当前自定义背景",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    IconButton(
                        onClick = { onDeleteCustom(customKey) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Filled.Delete, "删除", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
