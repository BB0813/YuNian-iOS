package com.lianyu.ai.uicommon.component

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.theme.AppTheme

// ============================================================================
// SharedPreferences keys
// ============================================================================
private const val BG_PREFS_NAME = "chat_prefs"
private const val MAIN_BG_KEY = "main_background"
private const val CHAT_BG_KEY = "chat_background"

fun getMainBackgroundKey(context: android.content.Context): String {
    return context.getSharedPreferences(BG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .getString(MAIN_BG_KEY, "default") ?: "default"
}

fun setMainBackgroundKey(context: android.content.Context, key: String) {
    context.getSharedPreferences(BG_PREFS_NAME, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(MAIN_BG_KEY, key)
        .apply()
}

// ============================================================================
// Main BackgroundSettingsScreen composable
// ============================================================================

/**
 * 全屏背景设置页面。
 * 包含两个区域：「主界面背景」和「聊天背景」，各自独立配置。
 * 每个区域都支持：预设色块、纯色取色盘、自定义图片导入。
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

    // Color picker dialog state
    var showColorPicker by remember { mutableStateOf(false) }
    var colorPickerTarget by remember { mutableStateOf<BgTarget>(BgTarget.Main) }

    val presets = remember { chatBackgroundOptions(context) }

    // Image picker for custom backgrounds
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val savedKey = saveCustomBackground(context, it)
            if (savedKey != null) {
                when (colorPickerTarget) {
                    BgTarget.Main -> {
                        setMainBackgroundKey(context, savedKey)
                        mainBgKey = savedKey
                    }
                    BgTarget.Chat -> {
                        setChatBackgroundKey(context, savedKey)
                        chatBgKey = savedKey
                    }
                }
            }
        }
    }

    // Collect custom background keys currently in use
    val customMainKey = if (isCustomBackground(mainBgKey)) mainBgKey else null
    val customChatKey = if (isCustomBackground(chatBgKey)) chatBgKey else null

    Scaffold(
        topBar = {
            TopAppBar(
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
                colors = TopAppBarDefaults.topAppBarColors(
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
            // ====== 主界面背景 ======
            item {
                SectionHeader(title = "主界面背景")
            }
            item {
                BackgroundPresetGrid(
                    presets = presets,
                    currentKey = mainBgKey,
                    customKey = customMainKey,
                    onSelectPreset = { key ->
                        setMainBackgroundKey(context, key)
                        mainBgKey = key
                    },
                    onPickColor = {
                        colorPickerTarget = BgTarget.Main
                        showColorPicker = true
                    },
                    onPickImage = {
                        colorPickerTarget = BgTarget.Main
                        imagePicker.launch("image/*")
                    },
                    onDeleteCustom = { key ->
                        deleteCustomBackground(context, key)
                        setMainBackgroundKey(context, "default")
                        mainBgKey = "default"
                    }
                )
            }

            // ====== 聊天背景 ======
            item {
                SectionHeader(title = "聊天背景")
            }
            item {
                BackgroundPresetGrid(
                    presets = presets,
                    currentKey = chatBgKey,
                    customKey = customChatKey,
                    onSelectPreset = { key ->
                        setChatBackgroundKey(context, key)
                        chatBgKey = key
                    },
                    onPickColor = {
                        colorPickerTarget = BgTarget.Chat
                        showColorPicker = true
                    },
                    onPickImage = {
                        colorPickerTarget = BgTarget.Chat
                        imagePicker.launch("image/*")
                    },
                    onDeleteCustom = { key ->
                        deleteCustomBackground(context, key)
                        setChatBackgroundKey(context, "default")
                        chatBgKey = "default"
                    }
                )
            }

            // Bottom spacer
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    // ==== Color Picker Dialog ====
    if (showColorPicker) {
        ColorPickerDialog(
            currentColor = when (colorPickerTarget) {
                BgTarget.Main -> presets.find { it.key == mainBgKey }?.color ?: Color(0xFFF5F5F5)
                BgTarget.Chat -> presets.find { it.key == chatBgKey }?.color ?: Color(0xFFF5F5F5)
            },
            onColorPicked = { color ->
                // Create a custom preset from picked color
                val key = "color_${color.value.toLong()}"
                // Save as a custom color key
                val customOption = ChatBackgroundOption(
                    key = key,
                    name = "自定义颜色",
                    color = color,
                    gradient = null,
                    isCustom = true
                )
                if (colorPickerTarget == BgTarget.Main) {
                    setMainBackgroundKey(context, key)
                    mainBgKey = key
                } else {
                    setChatBackgroundKey(context, key)
                    chatBgKey = key
                }
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
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

    // Helper function for background render
    @Composable
    fun BgBox(bgColor: Color, bgBrush: Brush?) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = bgBrush ?: Brush.verticalGradient(
                        listOf(bgColor, bgColor)
                    ),
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
        // Preset color chips in a grid
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
                // Fill remaining space if row has fewer items
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        // Action buttons row: color picker + image picker
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Color picker button
            OutlinedButton(
                onClick = onPickColor,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AppTheme.colors.success
                )
            ) {
                Icon(
                    Icons.Filled.Check, // Placeholder for palette icon
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("取色盘", fontSize = 13.sp)
            }

            // Image picker button
            OutlinedButton(
                onClick = onPickImage,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = AppTheme.colors.success
                )
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("自定义图片", fontSize = 13.sp)
            }
        }

        // Custom image preview (if any)
        if (customKey != null && isCustomBackground(customKey)) {
            val context = LocalContext.current
            val file = remember(customKey) {
                getCustomBackgroundFile(context, customKey)
            }

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
                    // Delete button
                    IconButton(
                        onClick = { onDeleteCustom(customKey) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.5f))
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            "删除",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// Color Picker Dialog
// ============================================================================

// 预设色板：18 种精选颜色
private val colorPalette = listOf(
    Color(0xFFFF6B6B), Color(0xFFEE5A24), Color(0xFFFF9FF3), Color(0xFFF368E0),
    Color(0xFFA29BFE), Color(0xFF6C5CE7), Color(0xFF74B9FF), Color(0xFF0984E3),
    Color(0xFF00CEC9), Color(0xFF00B894), Color(0xFF55EFC4), Color(0xFF27AE60),
    Color(0xFFFFEAA7), Color(0xFFFDCB6E), Color(0xFFF39C12), Color(0xFFE17055),
    Color(0xFFDFE6E9), Color(0xFFB2BEC3)
)

@Composable
private fun ColorPickerDialog(
    currentColor: Color,
    onColorPicked: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf(currentColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "取色盘",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Preview of selected color
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(selectedColor)
                        .border(1.dp, AppTheme.colors.outline, RoundedCornerShape(12.dp))
                )

                // Color grid
                val rows = colorPalette.chunked(6)
                rows.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { color ->
                            val isSelected = selectedColor == color
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (isSelected) Modifier.border(3.dp, Color.White, CircleShape)
                                            .border(4.dp, AppTheme.colors.success, CircleShape)
                                        else Modifier
                                    )
                                    .clickable { selectedColor = color }
                            )
                        }
                        // Fill remaining
                        repeat(6 - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorPicked(selectedColor) }) {
                Text("确定", color = AppTheme.colors.success, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = AppTheme.colors.onSurfaceVariant)
            }
        },
        containerColor = AppTheme.colors.surface
    )
}
