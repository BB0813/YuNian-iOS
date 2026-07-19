package com.lianyu.ai.uicommon.component

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.image.cropper.ImageCropperDialog
import com.lianyu.ai.uicommon.image.viewer.FullscreenImageViewer
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

private enum class BgTarget { Main, Chat }

/**
 * 背景设置页（结构性重排）：
 * - 顶行切换：「页面背景 | 聊天背景」
 * - 分区：「纯色背景」/「图片背景」，分割线分隔
 * - 纯色行：色块 + hex/rgb + 启用；末行「添加更多」
 * - 图片行：缩略图 + 启用；点图全屏预览
 * - 统一：单击启用，长按删除（预设不可删）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BackgroundSettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors
    val viewModel: BackgroundSettingsViewModel = viewModel()

    var target by remember { mutableStateOf(BgTarget.Main) }
    val mainBgKey by viewModel.mainBgKey.collectAsState()
    val chatBgKey by viewModel.chatBgKey.collectAsState()
    val customImageKeys by viewModel.customImageKeys.collectAsState()
    val customSolidColors by viewModel.customSolidColors.collectAsState()

    // 取色盘会话：null=关闭；Add=新增；Edit=编辑已有并回填
    var colorPickerSession by remember { mutableStateOf<ColorPickerSession?>(null) }
    var showImagePicker by remember { mutableStateOf(false) }
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    var cropBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    var previewImageModel by remember { mutableStateOf<Any?>(null) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var pendingSolidAction by remember { mutableStateOf<BackgroundEntry.Solid?>(null) }

    val isDark = colorScheme.surface.luminance() < 0.5f
    val presets = remember(isDark) {
        chatBackgroundOptions(context).map { option ->
            val (color, gradient) = resolveBackgroundPalette(option.key, isDark)
            option.copy(color = color, gradient = gradient)
        }
    }

    val currentKey = if (target == BgTarget.Main) mainBgKey else chatBgKey
    val targetMain = target == BgTarget.Main

    fun applyBackgroundKey(key: String) {
        viewModel.applyBackground(targetMain, key)
    }

    fun deleteEntry(entry: BackgroundEntry) {
        when (entry) {
            is BackgroundEntry.Solid -> {
                // 预设不可删；自定义纯色从持久化列表移除
                if (isColorBackground(entry.key)) {
                    viewModel.deleteSolidColor(entry.key, currentKey, targetMain)
                }
            }
            is BackgroundEntry.Image -> {
                viewModel.deleteImageBackground(entry.key, currentKey, targetMain)
            }
        }
    }

    val solidEntries = remember(presets, customSolidColors, currentKey) {
        val presetEntries = presets.map { option ->
            BackgroundEntry.Solid(
                key = option.key,
                color = option.color,
                label = option.name,
                deletable = false
            )
        }
        val customEntries = customSolidColors.map { solid ->
            BackgroundEntry.Solid(
                key = solid.key,
                color = solid.color,
                label = solid.name.ifBlank { "自定义纯色" },
                deletable = true
            )
        }
        // 兼容：若当前启用的是历史 color_ key 但不在列表中，临时展示以便用户可切换/删除
        val orphan = currentKey
            .takeIf { isColorBackground(it) && customSolidColors.none { c -> c.key == it } }
            ?.let { key ->
                parseColorBackground(key)?.let { color ->
                    BackgroundEntry.Solid(
                        key = key,
                        color = color,
                        label = "自定义纯色",
                        deletable = true
                    )
                }
            }
        if (orphan != null) presetEntries + customEntries + orphan else presetEntries + customEntries
    }

    val imageEntries = remember(customImageKeys) {
        customImageKeys.mapNotNull { key ->
            val file = getCustomBackgroundFile(context, key) ?: return@mapNotNull null
            BackgroundEntry.Image(key = key, file = file)
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                TargetToggleRow(
                    selected = target,
                    onSelect = { target = it }
                )
            }

            item {
                SectionLabel(title = "纯色背景")
            }

            items(solidEntries, key = { "solid_${it.key}" }) { entry ->
                SolidBackgroundRow(
                    entry = entry,
                    enabled = currentKey == entry.key,
                    onEnable = { applyBackgroundKey(entry.key) },
                    onLongPress = {
                        if (entry.deletable) {
                            // 自定义纯色：长按弹出 编辑 / 删除
                            pendingSolidAction = entry
                        }
                    }
                )
            }

            item {
                AddMoreSolidRow(
                    onClick = {
                        // 每次「添加更多」开新会话，默认浅灰，不残留上次数据
                        colorPickerSession = ColorPickerSession.Add(
                            sessionId = System.currentTimeMillis()
                        )
                    }
                )
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = colorScheme.outline.copy(alpha = 0.35f)
                )
            }

            item {
                SectionLabel(title = "图片背景")
            }

            items(imageEntries, key = { "image_${it.key}" }) { entry ->
                ImageBackgroundRow(
                    entry = entry,
                    enabled = currentKey == entry.key,
                    onEnable = { applyBackgroundKey(entry.key) },
                    onPreview = { previewImageModel = entry.file },
                    onLongPress = {
                        pendingDelete = PendingDelete(entry, "删除该图片背景？")
                    }
                )
            }

            item {
                AddMoreImageRow(onClick = { showImagePicker = true })
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }

    colorPickerSession?.let { session ->
        val seedColor = when (session) {
            is ColorPickerSession.Add -> Color(0xFFF5F5F5)
            is ColorPickerSession.Edit -> session.color
        }
        val seedName = when (session) {
            is ColorPickerSession.Add -> ""
            is ColorPickerSession.Edit -> session.name
        }
        ProfessionalColorPickerDialog(
            currentColor = seedColor,
            initialName = seedName,
            sessionKey = session.sessionKey,
            onColorPicked = { color, name ->
                val key = when (session) {
                    is ColorPickerSession.Add -> {
                        viewModel.saveSolidColor(color, name)
                    }
                    is ColorPickerSession.Edit -> {
                        viewModel.updateSolidColor(
                            oldKey = session.key,
                            color = color,
                            name = name
                        )
                    }
                }
                applyBackgroundKey(key)
                colorPickerSession = null
            },
            onDismiss = { colorPickerSession = null }
        )
    }

    pendingSolidAction?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingSolidAction = null },
            title = { Text("自定义纯色") },
            text = {
                Text(
                    "${entry.label}\n${formatHex(entry.color)}  ·  ${formatRgb(entry.color)}"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSolidAction = null
                        colorPickerSession = ColorPickerSession.Edit(
                            key = entry.key,
                            color = entry.color,
                            name = entry.label,
                            sessionId = System.currentTimeMillis()
                        )
                    }
                ) {
                    Text("编辑", color = colorScheme.success, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            pendingSolidAction = null
                            pendingDelete = PendingDelete(entry, "删除该自定义纯色？")
                        }
                    ) {
                        Text("删除", color = colorScheme.danger)
                    }
                    TextButton(onClick = { pendingSolidAction = null }) {
                        Text("取消", color = colorScheme.onSurfaceVariant)
                    }
                }
            }
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
            cropRatio = 9f / 16f,
            onConfirm = { cropped ->
                val key = viewModel.saveImageBackground(cropped)
                if (key != null) {
                    applyBackgroundKey(key)
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

    FullscreenImageViewer(
        models = listOfNotNull(previewImageModel),
        initialIndex = 0,
        visible = previewImageModel != null,
        onDismiss = { previewImageModel = null }
    )

    pendingDelete?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除背景") },
            text = { Text(pending.message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteEntry(pending.entry)
                        pendingDelete = null
                    }
                ) {
                    Text("删除", color = colorScheme.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

// ============================================================================
// Models
// ============================================================================

/**
 * 取色盘会话：
 * - Add：添加更多，默认浅灰、空名称，sessionId 保证每次全新状态
 * - Edit：长按编辑，回填颜色与名称
 */
private sealed class ColorPickerSession {
    abstract val sessionKey: String

    data class Add(val sessionId: Long) : ColorPickerSession() {
        override val sessionKey: String = "add_$sessionId"
    }

    data class Edit(
        val key: String,
        val color: Color,
        val name: String,
        val sessionId: Long
    ) : ColorPickerSession() {
        override val sessionKey: String = "edit_${key}_$sessionId"
    }
}

private data class PendingDelete(
    val entry: BackgroundEntry,
    val message: String
)

private sealed class BackgroundEntry {
    abstract val key: String

    data class Solid(
        override val key: String,
        val color: Color,
        val label: String,
        val deletable: Boolean
    ) : BackgroundEntry()

    data class Image(
        override val key: String,
        val file: java.io.File
    ) : BackgroundEntry()
}

// ============================================================================
// Section Components
// ============================================================================

@Composable
private fun TargetToggleRow(
    selected: BgTarget,
    onSelect: (BgTarget) -> Unit
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TargetToggleChip(
            label = "页面背景",
            selected = selected == BgTarget.Main,
            onClick = { onSelect(BgTarget.Main) },
            modifier = Modifier.weight(1f)
        )
        TargetToggleChip(
            label = "聊天背景",
            selected = selected == BgTarget.Chat,
            onClick = { onSelect(BgTarget.Chat) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TargetToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) colors.surface else Color.Transparent,
        onClick = onClick
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    fontSize = 14.sp
                ),
                color = if (selected) colors.onSurface else colors.metadataContent
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        ),
        color = AppTheme.colors.onSurface,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SolidBackgroundRow(
    entry: BackgroundEntry.Solid,
    enabled: Boolean,
    onEnable: () -> Unit,
    onLongPress: () -> Unit
) {
    val colors = AppTheme.colors
    val hex = formatHex(entry.color)
    val rgb = formatRgb(entry.color)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .combinedClickable(
                onClick = onEnable,
                onLongClick = onLongPress
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(entry.color)
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.label,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "$hex  ·  $rgb",
                style = MaterialTheme.typography.bodySmall,
                color = colors.metadataContent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        RadioButton(
            selected = enabled,
            onClick = onEnable,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.success,
                unselectedColor = colors.outline
            )
        )
    }
}

@Composable
private fun AddMoreSolidRow(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Palette,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = "添加更多",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "添加纯色",
            tint = colors.success,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageBackgroundRow(
    entry: BackgroundEntry.Image,
    enabled: Boolean,
    onEnable: () -> Unit,
    onPreview: () -> Unit,
    onLongPress: () -> Unit
) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .combinedClickable(
                onClick = onEnable,
                onLongClick = onLongPress
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                .clickable(onClick = onPreview)
        ) {
            AsyncImage(
                model = entry.file,
                contentDescription = "背景预览",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "自定义图片",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = colors.onSurface
            )
            Text(
                text = "点击缩略图全屏查看",
                style = MaterialTheme.typography.bodySmall,
                color = colors.metadataContent
            )
        }
        RadioButton(
            selected = enabled,
            onClick = onEnable,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.success,
                unselectedColor = colors.outline
            )
        )
    }
}

@Composable
private fun AddMoreImageRow(onClick: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.outline.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Image,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = "添加图片",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
            color = colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "添加图片",
            tint = colors.success,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun formatHex(color: Color): String {
    val argb = color.toArgb()
    return String.format("#%06X", argb and 0xFFFFFF)
}

private fun formatRgb(color: Color): String {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "rgb($r, $g, $b)"
}
