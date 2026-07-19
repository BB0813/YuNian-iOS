package com.lianyu.ai.uicommon.component

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import com.lianyu.ai.uicommon.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

data class ChatBackgroundOption(
    val key: String,
    val name: String,
    val color: Color,
    val gradient: Brush? = null,
    val isCustom: Boolean = false
)

/**
 * 背景预设（浅色预览色）。
 * 深色主题运行时请走 [resolveBackgroundPalette] / [getMainBackgroundByKey] /
 * [getChatBackgroundByKey]，不要直接拿这里的浅色当页面底。
 */
fun chatBackgroundOptions(context: Context): List<ChatBackgroundOption> {
    return listOf(
        ChatBackgroundOption(
            key = "default",
            name = context.getString(R.string.default_white),
            color = Color(0xFFDEFCF9)
        ),
        ChatBackgroundOption(
            key = "warm_pink",
            name = context.getString(R.string.warm_pink),
            color = Color(0xFFE8F6FC),
            gradient = Brush.verticalGradient(
                colors = listOf(Color(0xFFDEFCF9), Color(0xFFE8F6FC), Color(0xFFCADEFC))
            )
        ),
        ChatBackgroundOption(
            key = "lavender",
            name = context.getString(R.string.lavender),
            color = Color(0xFFE8E4F8),
            gradient = Brush.verticalGradient(
                colors = listOf(Color(0xFFF0ECFC), Color(0xFFE4E0F6), Color(0xFFC3BEF0))
            )
        ),
        ChatBackgroundOption(
            key = "ocean",
            name = context.getString(R.string.ocean),
            color = Color(0xFFE0F0FC),
            gradient = Brush.verticalGradient(
                colors = listOf(Color(0xFFDEFCF9), Color(0xFFCADEFC), Color(0xFFB8D4F8))
            )
        ),
        ChatBackgroundOption(
            key = "forest",
            name = context.getString(R.string.forest),
            color = Color(0xFFE4F8F4),
            gradient = Brush.verticalGradient(
                colors = listOf(Color(0xFFDEFCF9), Color(0xFFD4F4F0), Color(0xFFC8E8E4))
            )
        ),
        ChatBackgroundOption(
            key = "sunset",
            name = context.getString(R.string.sunset),
            color = Color(0xFFF0E8FC),
            gradient = Brush.verticalGradient(
                colors = listOf(Color(0xFFF4ECFC), Color(0xFFE8DCF8), Color(0xFFCCA8E9))
            )
        ),
        ChatBackgroundOption(
            key = "night",
            name = context.getString(R.string.night_sky),
            color = Color(0xFFE8E4F4),
            gradient = Brush.verticalGradient(
                colors = listOf(Color(0xFFEEEAF8), Color(0xFFE0DCF0), Color(0xFFC3BEF0))
            )
        )
    )
}

/**
 * 按主题解析预设背景色板。
 * 深色主题使用独立深色色标，避免浅色预设在暗色 UI 上发白刺眼。
 */
fun resolveBackgroundPalette(key: String, isDark: Boolean): Pair<Color, Brush?> {
    if (!isDark) {
        return when (key) {
            "default" -> Color(0xFFDEFCF9) to null
            "warm_pink" -> Color(0xFFE8F6FC) to Brush.verticalGradient(
                listOf(Color(0xFFDEFCF9), Color(0xFFE8F6FC), Color(0xFFCADEFC))
            )
            "lavender" -> Color(0xFFE8E4F8) to Brush.verticalGradient(
                listOf(Color(0xFFF0ECFC), Color(0xFFE4E0F6), Color(0xFFC3BEF0))
            )
            "ocean" -> Color(0xFFE0F0FC) to Brush.verticalGradient(
                listOf(Color(0xFFDEFCF9), Color(0xFFCADEFC), Color(0xFFB8D4F8))
            )
            "forest" -> Color(0xFFE4F8F4) to Brush.verticalGradient(
                listOf(Color(0xFFDEFCF9), Color(0xFFD4F4F0), Color(0xFFC8E8E4))
            )
            "sunset" -> Color(0xFFF0E8FC) to Brush.verticalGradient(
                listOf(Color(0xFFF4ECFC), Color(0xFFE8DCF8), Color(0xFFCCA8E9))
            )
            "night" -> Color(0xFFE8E4F4) to Brush.verticalGradient(
                listOf(Color(0xFFEEEAF8), Color(0xFFE0DCF0), Color(0xFFC3BEF0))
            )
            else -> Color(0xFFDEFCF9) to null
        }
    }

    return when (key) {
        "default" -> com.lianyu.ai.uicommon.theme.DarkBgDefault to null
        "warm_pink" -> com.lianyu.ai.uicommon.theme.DarkBgWarmPink to Brush.verticalGradient(
            listOf(Color(0xFF2A2034), Color(0xFF221A2C), Color(0xFF1A1424))
        )
        "lavender" -> com.lianyu.ai.uicommon.theme.DarkBgLavender to Brush.verticalGradient(
            listOf(Color(0xFF242030), Color(0xFF1C1828), Color(0xFF161220))
        )
        "ocean" -> com.lianyu.ai.uicommon.theme.DarkBgOcean to Brush.verticalGradient(
            listOf(Color(0xFF1A2430), Color(0xFF141C28), Color(0xFF101820))
        )
        "forest" -> com.lianyu.ai.uicommon.theme.DarkBgForest to Brush.verticalGradient(
            listOf(Color(0xFF1A2420), Color(0xFF141C1A), Color(0xFF101614))
        )
        "sunset" -> com.lianyu.ai.uicommon.theme.DarkBgSunset to Brush.verticalGradient(
            listOf(Color(0xFF2C2030), Color(0xFF241820), Color(0xFF1C1418))
        )
        "night" -> com.lianyu.ai.uicommon.theme.DarkBgNight to Brush.verticalGradient(
            listOf(Color(0xFF16161E), Color(0xFF101018), Color(0xFF0C0C12))
        )
        else -> com.lianyu.ai.uicommon.theme.DarkBgDefault to null
    }
}

private const val CHAT_BG_PREF = "chat_background"
private const val CUSTOM_BG_PREFIX = "custom_"
private const val CUSTOM_BG_DIR = "chat_backgrounds"
private const val MAX_CUSTOM_BG_BYTES = 8L * 1024L * 1024L
private val CUSTOM_BG_FILE_REGEX = Regex("^bg_[0-9a-fA-F-]{36}\\.jpg$")
private val CUSTOM_BG_TEMP_FILE_REGEX = Regex("^\\.tmp_bg_[0-9a-fA-F-]{36}\\.part$")

fun getChatBackgroundKey(context: Context): String {
    return context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
        .getString(CHAT_BG_PREF, "default") ?: "default"
}

fun setChatBackgroundKey(context: Context, key: String) {
    context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString(CHAT_BG_PREF, key)
        .apply()
    // 设置背景时预加载到内存缓存
    if (isCustomBackground(key)) {
        ChatBackgroundCache.preload(context, key)
    }
}

/**
 * 解析最终聊天背景 key：
 * - 使用全局背景时 → 全局 key
 * - 专属背景未设置 / 空白 → 回退全局
 * - 否则使用专属 key
 *
 * 注意：调用方应在页面 ON_RESUME 时重新读取 [globalBackgroundKey]，
 * SharedPreferences 本身不会驱动 Compose 重组。
 */
fun resolveEffectiveChatBackgroundKey(
    useGlobalBackground: Boolean,
    companionBackgroundKey: String?,
    globalBackgroundKey: String
): String {
    if (useGlobalBackground) return globalBackgroundKey
    val companionKey = companionBackgroundKey?.trim().orEmpty()
    return companionKey.ifEmpty { globalBackgroundKey }
}

fun isCustomBackground(key: String): Boolean {
    return key.startsWith(CUSTOM_BG_PREFIX)
}

/**
 * 纯色背景 key：
 * - 新格式：`color_AARRGGBB`（8 位大写 hex，稳定可解析）
 * - 旧格式：`color_<Color.value long>`（兼容历史数据）
 */
private const val COLOR_BG_PREFIX = "color_"
/** 自定义纯色列表持久化键 */
private const val CUSTOM_SOLID_COLORS_PREF = "custom_solid_colors"
private val COLOR_ARGB_HEX_REGEX = Regex("^[0-9A-Fa-f]{6,8}$")

/**
 * 用户保存的自定义纯色背景条目。
 * [key] 使用 [colorBackgroundKey]，与运行时解析路径兼容。
 */
data class CustomSolidColor(
    val key: String,
    val color: Color,
    val name: String
)

fun isColorBackground(key: String): Boolean {
    return key.startsWith(COLOR_BG_PREFIX)
}

/**
 * 生成稳定的纯色背景 key。
 * 使用 ARGB hex，避免 `Color.value` 打包在部分机型/往返后解析成 #000000。
 */
fun colorBackgroundKey(color: Color): String {
    val argb = color.toArgb()
    return COLOR_BG_PREFIX + String.format("%08X", argb)
}

fun parseColorBackground(key: String): Color? {
    if (!isColorBackground(key)) return null
    val raw = key.removePrefix(COLOR_BG_PREFIX).trim()
    if (raw.isEmpty()) return null

    // 新格式：6/8 位 hex（RRGGBB / AARRGGBB）
    if (COLOR_ARGB_HEX_REGEX.matches(raw)) {
        return try {
            val normalized = if (raw.length == 6) "FF$raw" else raw
            Color(normalized.toLong(16).toInt())
        } catch (_: Exception) {
            null
        }
    }

    // 旧格式：十进制 long（Color.value 打包 或 历史 ARGB unsigned）
    return try {
        val n = raw.toLong()
        when {
            // 明确的 32-bit ARGB 范围（含 alpha）
            n in 0L..0xFFFFFFFFL -> Color(n.toInt())
            // 历史 Color.value 打包（有符号 long）
            else -> Color(n)
        }
    } catch (_: Exception) {
        try {
            Color(raw.toULong())
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 读取已保存的自定义纯色列表（按添加顺序）。
 * 存储格式：每行 `key|name`，name 可为空。
 * 读取时会把旧 key 规范成新 ARGB hex key，避免列表显示 #000000。
 */
fun listCustomSolidColors(context: Context): List<CustomSolidColor> {
    val raw = context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
        .getString(CUSTOM_SOLID_COLORS_PREF, null)
        ?: return emptyList()
    if (raw.isBlank()) return emptyList()
    var migrated = false
    val items = raw.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val sep = line.indexOf('|')
            val key = if (sep >= 0) line.substring(0, sep) else line
            val name = if (sep >= 0) line.substring(sep + 1) else ""
            val color = parseColorBackground(key) ?: return@mapNotNull null
            val canonicalKey = colorBackgroundKey(color)
            if (canonicalKey != key) migrated = true
            CustomSolidColor(
                key = canonicalKey,
                color = color,
                name = name.ifBlank { "自定义纯色" }
            )
        }
        .distinctBy { it.key }
        .toList()
    if (migrated && items.isNotEmpty()) {
        persistCustomSolidColors(context, items)
    }
    return items
}

private fun persistCustomSolidColors(context: Context, items: List<CustomSolidColor>) {
    val encoded = items.joinToString("\n") { item ->
        val safeName = item.name
            .replace('\n', ' ')
            .replace('|', '/')
            .trim()
        // 落盘一律用规范 ARGB key
        val key = if (item.key == colorBackgroundKey(item.color)) {
            item.key
        } else {
            colorBackgroundKey(item.color)
        }
        "$key|$safeName"
    }
    context.getSharedPreferences("chat_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString(CUSTOM_SOLID_COLORS_PREF, encoded)
        .apply()
}

/**
 * 新增/按同色更新一条自定义纯色，并返回其 background key。
 * 同色 key 已存在时更新名称；否则追加到列表末尾。
 */
fun saveCustomSolidColor(context: Context, color: Color, name: String): String {
    val key = colorBackgroundKey(color)
    val displayName = name.trim().ifBlank { "自定义纯色" }
    val existing = listCustomSolidColors(context).toMutableList()
    val idx = existing.indexOfFirst { it.key == key }
    val entry = CustomSolidColor(key = key, color = color, name = displayName)
    if (idx >= 0) {
        existing[idx] = entry
    } else {
        existing.add(entry)
    }
    persistCustomSolidColors(context, existing)
    return key
}

/**
 * 编辑已有自定义纯色：替换 [oldKey] 对应条目。
 * 若颜色变化导致 key 变化，会移除旧 key；若新 key 已存在则合并为一条。
 * 返回最终 background key。
 */
fun updateCustomSolidColor(
    context: Context,
    oldKey: String,
    color: Color,
    name: String
): String {
    val newKey = colorBackgroundKey(color)
    val displayName = name.trim().ifBlank { "自定义纯色" }
    val existing = listCustomSolidColors(context).toMutableList()
    val entry = CustomSolidColor(key = newKey, color = color, name = displayName)

    val oldIdx = existing.indexOfFirst { it.key == oldKey }
    val newIdx = existing.indexOfFirst { it.key == newKey }

    when {
        oldIdx >= 0 && newKey == oldKey -> {
            existing[oldIdx] = entry
        }
        oldIdx >= 0 && newIdx < 0 -> {
            existing[oldIdx] = entry
        }
        oldIdx >= 0 && newIdx >= 0 && oldIdx != newIdx -> {
            // 颜色改成了已有色：保留目标位并删旧位
            existing[newIdx] = entry
            existing.removeAt(oldIdx)
        }
        newIdx >= 0 -> {
            existing[newIdx] = entry
        }
        else -> {
            existing.add(entry)
        }
    }
    persistCustomSolidColors(context, existing.distinctBy { it.key })
    return newKey
}

/** 删除一条自定义纯色（不影响当前是否正在使用，由调用方决定回退）。 */
fun deleteCustomSolidColor(context: Context, key: String) {
    if (!isColorBackground(key)) return
    val canonical = parseColorBackground(key)?.let { colorBackgroundKey(it) } ?: key
    val next = listCustomSolidColors(context).filterNot {
        it.key == key || it.key == canonical
    }
    persistCustomSolidColors(context, next)
}

fun getCustomBackgroundFile(context: Context, key: String): File? {
    if (!isCustomBackground(key)) return null
    val fileName = key.removePrefix(CUSTOM_BG_PREFIX)
    if (!CUSTOM_BG_FILE_REGEX.matches(fileName)) return null

    return try {
        val dir = File(context.filesDir, CUSTOM_BG_DIR)
        val canonicalDir = dir.canonicalFile
        val candidate = File(canonicalDir, fileName).canonicalFile
        if (candidate.parentFile != canonicalDir) null else candidate
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}

fun saveCustomBackground(context: Context, uri: Uri): String? {
    var tempFile: File? = null
    var finalFile: File? = null
    return try {
        val dir = File(context.filesDir, CUSTOM_BG_DIR).canonicalFile
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null

        val id = UUID.randomUUID().toString()
        val fileName = "bg_${id}.jpg"
        val tempFileName = ".tmp_bg_${id}.part"
        if (!CUSTOM_BG_FILE_REGEX.matches(fileName)) return null
        if (!CUSTOM_BG_TEMP_FILE_REGEX.matches(tempFileName)) return null
        tempFile = File(dir, tempFileName).canonicalFile
        finalFile = File(dir, fileName).canonicalFile
        val targetTempFile = tempFile
        val targetFinalFile = finalFile
        if (targetTempFile.parentFile != dir || targetFinalFile.parentFile != dir) return null

        var copied = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetTempFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read.toLong()
                    if (copied > MAX_CUSTOM_BG_BYTES) {
                        output.close()
                        targetTempFile.delete()
                        return null
                    }
                    output.write(buffer, 0, read)
                }
                output.fd.sync()
            }
        } ?: return null

        if (!isReadableImage(targetTempFile)) {
            targetTempFile.delete()
            return null
        }

        if (targetFinalFile.exists()) {
            targetTempFile.delete()
            return null
        }

        if (!targetTempFile.renameTo(targetFinalFile)) {
            targetTempFile.delete()
            return null
        }

        CUSTOM_BG_PREFIX + fileName
    } catch (_: IOException) {
        tempFile?.delete()
        finalFile?.delete()
        null
    } catch (_: SecurityException) {
        tempFile?.delete()
        finalFile?.delete()
        null
    }
}

/**
 * 将裁剪后的 Bitmap 保存为自定义背景，返回 background key。
 */
fun saveCustomBackground(context: Context, bitmap: android.graphics.Bitmap): String? {
    var tempFile: File? = null
    var finalFile: File? = null
    return try {
        val dir = File(context.filesDir, CUSTOM_BG_DIR).canonicalFile
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.isDirectory) return null

        val id = UUID.randomUUID().toString()
        val fileName = "bg_${id}.jpg"
        val tempFileName = ".tmp_bg_${id}.part"
        if (!CUSTOM_BG_FILE_REGEX.matches(fileName)) return null
        if (!CUSTOM_BG_TEMP_FILE_REGEX.matches(tempFileName)) return null
        tempFile = File(dir, tempFileName).canonicalFile
        finalFile = File(dir, fileName).canonicalFile
        if (tempFile.parentFile != dir || finalFile.parentFile != dir) return null

        FileOutputStream(tempFile).use { output ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output)) {
                return null
            }
            output.fd.sync()
        }

        if (!isReadableImage(tempFile)) {
            tempFile.delete()
            return null
        }
        if (finalFile.exists()) {
            tempFile.delete()
            return null
        }
        if (!tempFile.renameTo(finalFile)) {
            tempFile.delete()
            return null
        }
        CUSTOM_BG_PREFIX + fileName
    } catch (_: IOException) {
        tempFile?.delete()
        finalFile?.delete()
        null
    } catch (_: SecurityException) {
        tempFile?.delete()
        finalFile?.delete()
        null
    }
}

fun deleteCustomBackground(context: Context, key: String) {
    if (!isCustomBackground(key)) return
    getCustomBackgroundFile(context, key)?.delete()
}

private fun isReadableImage(file: File): Boolean {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, options)
    return options.outWidth > 0 && options.outHeight > 0
}

fun getChatBackground(context: Context, isDark: Boolean): Pair<Color, Brush?> {
    val key = getChatBackgroundKey(context)
    return getChatBackgroundByKey(context, key, isDark)
}

fun getChatBackgroundByKey(context: Context, key: String, isDark: Boolean): Pair<Color, Brush?> {
    if (isCustomBackground(key)) {
        val fallback = if (isDark) com.lianyu.ai.uicommon.theme.DarkBgDefault else Color(0xFFF5F5F5)
        return fallback to null
    }
    parseColorBackground(key)?.let { return it to null }
    return resolveBackgroundPalette(key, isDark)
}

/**
 * 解析主界面背景：支持预设 / 纯色 / 自定义图片。
 * 返回 (底色, 渐变, 自定义图片 key?)。
 * 深色主题使用独立深色色板，避免浅色预设透出。
 */
fun getMainBackgroundByKey(context: Context, key: String, isDark: Boolean): Triple<Color, Brush?, String?> {
    if (isCustomBackground(key)) {
        val fallback = if (isDark) com.lianyu.ai.uicommon.theme.DarkBgDefault else Color(0xFFF5F5F5)
        return Triple(fallback, null, key)
    }
    parseColorBackground(key)?.let { return Triple(it, null, null) }
    val (color, gradient) = resolveBackgroundPalette(key, isDark)
    return Triple(color, gradient, null)
}

fun getCustomBackgroundUri(context: Context, key: String): Uri? {
    val file = getCustomBackgroundFile(context, key) ?: return null
    return Uri.fromFile(file)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatBackgroundPickerDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val context = LocalContext.current
    var selectedKey by remember { mutableStateOf(currentKey) }
    var customKeys by remember {
        mutableStateOf(loadCustomBackgroundKeys(context))
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val key = saveCustomBackground(context, it)
            key?.let { newKey ->
                customKeys = customKeys + newKey
                selectedKey = newKey
                // 选中新图后交给调用方决定写全局还是单聊专属
                onSelect(newKey)
            }
        }
    }

    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val options = remember(isDark) {
        chatBackgroundOptions(context).map { option ->
            val (color, gradient) = resolveBackgroundPalette(option.key, isDark)
            option.copy(color = color, gradient = gradient)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.select_chat_bg),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.select_bg_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 预设背景（预览色按当前深浅主题解析）
                    options.forEach { option ->
                        val isSelected = option.key == selectedKey
                        BackgroundOptionItem(
                            name = option.name,
                            isSelected = isSelected,
                            color = option.color,
                            gradient = option.gradient,
                            onClick = {
                                selectedKey = option.key
                                // 仅回调，不写全局 prefs：
                                // 全局聊天背景由「背景设置」负责；此处供单聊专属背景选择。
                                onSelect(option.key)
                            }
                        )
                    }

                    // 自定义背景图片
                    customKeys.forEach { key ->
                        val isSelected = key == selectedKey
                        val uri = getCustomBackgroundUri(context, key)
                        CustomBackgroundItem(
                            uri = uri,
                            isSelected = isSelected,
                            onClick = {
                                selectedKey = key
                                onSelect(key)
                            },
                            onDelete = {
                                deleteCustomBackground(context, key)
                                customKeys = customKeys - key
                                if (selectedKey == key) {
                                    selectedKey = "default"
                                    onSelect("default")
                                }
                            }
                        )
                    }

                    // 添加自定义背景按钮
                    AddCustomBackgroundItem {
                        imagePicker.launch("image/*")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.primary)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
private fun BackgroundOptionItem(
    name: String,
    isSelected: Boolean,
    color: Color,
    gradient: Brush?,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) primary.copy(alpha = 0.8f) else Color.Transparent,
                    shape = RoundedCornerShape(14.dp)
                )
                .background(
                    if (gradient != null) gradient
                    else Brush.linearGradient(listOf(color, color))
                )
                .clickable { onClick() }
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp
            ),
            color = if (isSelected) primary else onSurfaceVariant
        )
    }
}

@Composable
private fun CustomBackgroundItem(
    uri: Uri?,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) primary.copy(alpha = 0.8f) else Color.Transparent,
                    shape = RoundedCornerShape(14.dp)
                )
                .clickable { onClick() }
        ) {
            if (uri != null) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(R.string.custom),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("?", color = onSurfaceVariant)
                }
            }

            // 删除按钮
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.error)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(10.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.custom),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp
            ),
            color = if (isSelected) primary else onSurfaceVariant
        )
    }
}

@Composable
private fun AddCustomBackgroundItem(
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(
                    width = 1.dp,
                    color = primary.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp)
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.add_custom_bg),
                tint = primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.upload_image),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp
            ),
            color = primary
        )
    }
}

/**
 * 列出本地已保存的自定义图片背景 key（custom_bg_*.jpg）。
 * 供背景设置页「图片背景」分区复用。
 */
fun listCustomBackgroundKeys(context: Context): List<String> {
    val dir = File(context.filesDir, CUSTOM_BG_DIR)
    if (!dir.exists()) return emptyList()
    return dir.listFiles()
        ?.asSequence()
        ?.onEach { if (CUSTOM_BG_TEMP_FILE_REGEX.matches(it.name)) it.delete() }
        ?.map { it.name }
        ?.filter { CUSTOM_BG_FILE_REGEX.matches(it) }
        ?.map { CUSTOM_BG_PREFIX + it }
        ?.sorted()
        ?.toList()
        ?: emptyList()
}

private fun loadCustomBackgroundKeys(context: Context): List<String> {
    return listCustomBackgroundKeys(context)
}
