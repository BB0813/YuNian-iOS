package com.yunian.ai.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream

class StickerManager(private val context: Context) {

    private val stickersDir = File(context.filesDir, "stickers")
    private val importedDir = File(stickersDir, "imported")
    private val stickerRulesFile = File(importedDir, StickerRuleStore.RULES_FILE_NAME)

    /** JSON v2 持久化（原子写 / merge / aliasIndex 均收口到 store） */
    private val ruleStore = StickerRuleStore(stickerRulesFile)

    /** 内存态：description → StickerRule（保持既有对外结构不变） */
    private var stickerRules: Map<String, StickerRule> = emptyMap()

    /** 内存态：完整 v2 条目（语义 / 别名 / 时间戳 / 来源） */
    private var entries: List<StickerRuleStore.Entry> = emptyList()

    /** 别名索引：alias → description（匹配链回退用，P6） */
    private var aliasIndex: Map<String, String> = emptyMap()

    /** 变更通知：import / rename / delete 后 +1，UI（StickerPanel）订阅刷新（修 P8） */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> get() = _version

    init {
        stickersDir.mkdirs()
        importedDir.mkdirs()
        loadRules()
    }

    data class StickerRule(
        val description: String,
        val fileName: String,
        val path: String
    )

    private fun loadRules() {
        try {
            val loaded = ruleStore.load()
            entries = loaded
            aliasIndex = ruleStore.buildAliasIndex(loaded)
            stickerRules = loaded
                .filter { File(importedDir, it.fileName).exists() }
                .associate { entry ->
                    entry.description to StickerRule(
                        description = entry.description,
                        fileName = entry.fileName,
                        path = File(importedDir, entry.fileName).absolutePath
                    )
                }
            SecureLog.i("StickerManager", "Loaded ${stickerRules.size} sticker rules (${entries.size} entries)")
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to load sticker rules", e)
            stickerRules = emptyMap()
            entries = emptyList()
            aliasIndex = emptyMap()
        }
    }

    /** 内存态统一刷新入口：Map + aliasIndex 重建 + version+1（修 P10：不再每次读盘） */
    private fun refreshState(newEntries: List<StickerRuleStore.Entry>) {
        entries = newEntries
        aliasIndex = ruleStore.buildAliasIndex(newEntries)
        stickerRules = newEntries
            .filter { File(importedDir, it.fileName).exists() }
            .associate { entry ->
                entry.description to StickerRule(
                    description = entry.description,
                    fileName = entry.fileName,
                    path = File(importedDir, entry.fileName).absolutePath
                )
            }
        _version.value += 1
    }

    suspend fun getAllStickers(): List<StickerInfo> = withContext(Dispatchers.IO) {
        val stickers = mutableListOf<StickerInfo>()

        try {
            val assetStickers = context.assets.list("stickers") ?: emptyArray()
            assetStickers.forEach { filename ->
                if (isImageFile(filename)) {
                    stickers.add(StickerInfo(
                        name = filename.substringBeforeLast("."),
                        path = "asset://stickers/$filename",
                        category = "default",
                        isBuiltIn = true
                    ))
                }
            }
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "No assets/stickers found")
        }

        val rules = stickerRules
        importedDir.listFiles()?.forEach { file ->
            if (isImageFile(file.name)) {

                val rule = rules.values.find { it.fileName == file.name }
                stickers.add(StickerInfo(
                    name = rule?.description ?: file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    description = rule?.description,
                    fileName = file.name
                ))
            }
        }

        stickers
    }

    fun findStickerByDescriptionExact(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null

        val rules = stickerRules

        rules[keyword]?.let { rule ->
            return StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            )
        }

        return null
    }

    /**
     * 别名精确匹配（P6 新增）：aliasIndex 命中后转精确查找。
     * 模型输出「绷不住了」能命中名为「裂开」的表情。
     */
    fun findStickerByAliases(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null
        val description = aliasIndex[keyword.trim()] ?: return null
        return findStickerByDescriptionExact(description)
    }

    fun findStickerByDescription(keyword: String): StickerInfo? {
        if (keyword.isBlank()) return null

        val rules = stickerRules

        val directFile = File(importedDir, keyword)
        if (directFile.exists()) {
            val rule = rules.values.find { it.fileName == keyword }
            return StickerInfo(
                name = rule?.description ?: keyword.substringBeforeLast("."),
                path = directFile.absolutePath,
                category = "imported",
                isBuiltIn = false,
                description = rule?.description,
                fileName = keyword
            )
        }

        rules[keyword]?.let { rule ->
            return StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            )
        }

        rules.entries.find { it.key.contains(keyword) || keyword.contains(it.key) }?.let { entry ->
            return StickerInfo(
                name = entry.value.description,
                path = entry.value.path,
                category = "imported",
                isBuiltIn = false,
                description = entry.value.description,
                fileName = entry.value.fileName
            )
        }

        importedDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension == keyword || file.name == keyword) {
                val rule = rules.values.find { it.fileName == file.name }
                return StickerInfo(
                    name = rule?.description ?: file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    description = rule?.description,
                    fileName = file.name
                )
            }
        }

        return null
    }

    /** 读内存，不再每次重读磁盘（修 P10） */
    fun getAllRules(): List<StickerRule> {
        return stickerRules.values.toList()
    }

    /** 名称唯一性查询（命名框实时校验用，修 P4） */
    fun isNameTaken(name: String): Boolean {
        return name.isNotBlank() && stickerRules.containsKey(name.trim())
    }

    /** 最新导入的文件名（StickerPanel 果冻入场定位用） */
    fun newestImportedFileName(): String? {
        return entries.maxByOrNull { it.createdAt }?.fileName
    }

    /** 按 fileName 查完整 v2 条目（重命名表单回填语义 / 别名用） */
    fun getEntry(fileName: String): StickerRuleStore.Entry? {
        return entries.find { it.fileName == fileName }
    }

    /** 给提示词层用的结构化清单（按 createdAt 新→旧排序，供预算截断） */
    fun getPromptStickers(): List<PromptSticker> {
        return entries
            .sortedByDescending { it.createdAt }
            .map { PromptSticker(name = it.description, semantic = it.semantic, aliases = it.aliases, isCustom = true) }
    }

    /**
     * 单文件导入（P0 新增）：从 SAF / 相册 / 拖放 Uri 拷贝到 importedDir，写 JSON v2 条目。
     * 失败原因：名称重复 / 非图片 / 读取失败 / 超过软上限。
     */
    suspend fun importStickerFile(
        uri: Uri,
        customName: String,
        semantic: String = "",
        aliases: List<String> = emptyList(),
    ): Result<StickerInfo> = withContext(Dispatchers.IO) {
        try {
            val name = sanitizeStickerName(customName)
            if (name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("表情名称不能为空"))
            }
            if (isNameTaken(name)) {
                return@withContext Result.failure(IllegalStateException("已有同名表情"))
            }
            if (entries.size >= MAX_IMPORTED_COUNT) {
                return@withContext Result.failure(IllegalStateException("自定义表情已达上限 ${MAX_IMPORTED_COUNT} 个，请先清理不需要的表情"))
            }
            val extension = resolveImageExtension(uri)
                ?: return@withContext Result.failure(IllegalArgumentException("仅支持图片文件（png/jpg/gif/webp）"))

            val fileName = "custom_${System.currentTimeMillis()}_${(0..999).random()}.$extension"
            val destFile = File(importedDir, fileName)
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("读取图片失败"))
            input.use { source ->
                destFile.outputStream().use { output ->
                    source.copyTo(output)
                }
            }

            val entry = StickerRuleStore.Entry(
                description = name,
                fileName = fileName,
                semantic = sanitizeSemantic(semantic),
                aliases = sanitizeAliases(aliases),
                createdAt = System.currentTimeMillis(),
                source = StickerRuleStore.SOURCE_FILE,
            )
            if (!ruleStore.save(entries + entry)) {
                // JSON 落盘失败：回滚图片，避免规则孤儿
                destFile.delete()
                return@withContext Result.failure(IllegalStateException("保存表情规则失败"))
            }
            refreshState(entries + entry)
            SecureLog.i("StickerManager", "Imported sticker file: $name -> $fileName")
            Result.success(StickerInfo(
                name = name,
                path = destFile.absolutePath,
                category = "imported",
                isBuiltIn = false,
                description = name,
                fileName = fileName
            ))
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "importStickerFile failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 重命名（含语义 / 别名编辑）：同步更新 Map key、aliasIndex、JSON（修 P4 / P8）。
     * 失败：名称重复 / 条目不存在。
     */
    suspend fun renameImportedSticker(
        fileName: String,
        newName: String,
        semantic: String? = null,
        aliases: List<String>? = null,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val name = sanitizeStickerName(newName)
            if (name.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("表情名称不能为空"))
            }
            val target = entries.find { it.fileName == fileName }
                ?: return@withContext Result.failure(NoSuchElementException("表情条目不存在"))
            // 重命名时原名本身不算重复
            if (name != target.description && isNameTaken(name)) {
                return@withContext Result.failure(IllegalStateException("已有同名表情"))
            }
            val updated = target.copy(
                description = name,
                semantic = semantic?.let { sanitizeSemantic(it) } ?: target.semantic,
                aliases = aliases?.let { sanitizeAliases(it) } ?: target.aliases,
            )
            val newEntries = entries.map { if (it.fileName == fileName) updated else it }
            if (!ruleStore.save(newEntries)) {
                return@withContext Result.failure(IllegalStateException("保存表情规则失败"))
            }
            refreshState(newEntries)
            SecureLog.i("StickerManager", "Renamed sticker: $fileName -> $name")
            Result.success(Unit)
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "renameImportedSticker failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * 修复版删除（修 P3）：删图片 + 同步移除 JSON 条目 + 刷内存。
     */
    suspend fun deleteImportedSticker(fileName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(importedDir, fileName)
            val fileDeleted = if (file.exists()) file.delete() else false
            val target = entries.find { it.fileName == fileName }
            if (target != null) {
                val newEntries = entries.filterNot { it.fileName == fileName }
                if (!ruleStore.save(newEntries)) {
                    SecureLog.w("StickerManager", "Failed to save rules after delete: $fileName")
                    return@withContext false
                }
                refreshState(newEntries)
            }
            SecureLog.i("StickerManager", "Deleted sticker: $fileName (file=$fileDeleted, rule=${target != null})")
            fileDeleted || target != null
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to delete sticker", e)
            false
        }
    }

    /**
     * 删除全部导入表情（修 P1）：只删图片文件，写回空 JSON，保留目录与元数据文件本体。
     */
    suspend fun deleteAllImportedStickers(): Boolean = withContext(Dispatchers.IO) {
        try {
            var deleted = 0
            importedDir.listFiles()?.forEach { file ->
                // 仅清理图片，不动 custom_stickers.json 等元数据文件
                if (isImageFile(file.name) && file.delete()) deleted++
            }
            val saved = ruleStore.save(emptyList())
            if (saved) {
                refreshState(emptyList())
            }
            SecureLog.i("StickerManager", "Deleted all $deleted imported stickers (jsonSaved=$saved)")
            saved
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to delete all stickers", e)
            false
        }
    }

    /**
     * ZIP 导入（修 P2）：图片照旧解压；custom_stickers.json 不再整体覆盖写入，
     * 而是解析后与现有规则按 description 做 merge，防止二次导入丢掉旧规则。
     */
    suspend fun importStickerZip(zipPath: String): Int = withContext(Dispatchers.IO) {
        var count = 0
        var rulesFileExtracted = false
        try {
            val zipFile = File(zipPath)
            if (!zipFile.exists()) {
                SecureLog.e("StickerManager", "Zip file not found: $zipPath")
                return@withContext 0
            }

            var incomingEntries: List<StickerRuleStore.Entry> = emptyList()
            var tmpRulesFile: File? = null

            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.substringAfterLast("/")
                    when {
                        entryName == StickerRuleStore.RULES_FILE_NAME -> {
                            // 先解到临时文件，merge 成功后再写正式 JSON（不再直接覆盖）
                            val tmp = File(context.cacheDir, "sticker_rules_${System.currentTimeMillis()}.tmp")
                            tmp.outputStream().use { output -> zis.copyTo(output) }
                            tmpRulesFile = tmp
                            rulesFileExtracted = true
                        }

                        !entry.isDirectory && isImageFile(entryName) -> {
                            val destFile = File(importedDir, entryName)
                            destFile.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                            count++
                        }
                    }
                    entry = zis.nextEntry
                }
            }

            if (rulesFileExtracted) {
                incomingEntries = tmpRulesFile?.let { StickerRuleStore(it).load() } ?: emptyList()
                tmpRulesFile?.delete()
                val mergeResult = ruleStore.mergeZipRules(incomingEntries, entries)
                if (ruleStore.save(mergeResult.merged)) {
                    refreshState(mergeResult.merged)
                    SecureLog.i("StickerManager", "Zip rules merged: added=${mergeResult.added}, skipped=${mergeResult.skipped}")
                } else {
                    SecureLog.w("StickerManager", "Zip rules merge save failed")
                }
            } else {
                SecureLog.w("StickerManager", "No rules file found in zip!")
                // 图片解出来了但没有规则：刷新内存，让新图片以文件名退化展示
                refreshState(entries)
            }

            SecureLog.i("StickerManager", "Import complete: $count stickers, rules=$rulesFileExtracted")
            count
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to import zip", e)
            count
        }
    }

    suspend fun loadStickerBitmap(stickerPath: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            when {
                stickerPath.startsWith("asset://") -> {
                    val assetPath = stickerPath.removePrefix("asset://")
                    context.assets.open(assetPath).use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }
                else -> {
                    BitmapFactory.decodeFile(stickerPath)
                }
            }
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to load bitmap", e)
            null
        }
    }

    /** 降采样加载（P11）：网格 / 气泡显示尺寸 ≤512px 即可，避免全尺寸位图内存抖动 */
    fun loadStickerBitmapSampled(stickerPath: String, maxDimension: Int = 512): Bitmap? {
        return try {
            if (stickerPath.startsWith("asset://")) {
                decodeSampledAsset(stickerPath.removePrefix("asset://"), maxDimension)
            } else {
                decodeSampledFile(stickerPath, maxDimension)
            }
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "Failed to load sampled bitmap", e)
            null
        }
    }

    /** 文件降采样解码：两遍解码先读 bounds，按目标尺寸算 inSampleSize */
    fun decodeSampledFile(path: String, maxDimension: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            BitmapFactory.decodeFile(path, options)
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "decodeSampledFile failed: ${e.message}")
            null
        }
    }

    /** assets 降采样解码：assets 流不可重读，需 open 两次 */
    fun decodeSampledAsset(assetPath: String, maxDimension: Int = 512): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            context.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        } catch (e: Exception) {
            SecureLog.e("StickerManager", "decodeSampledAsset failed: ${e.message}")
            null
        }
    }

    private fun calcInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= maxDimension || height / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    fun getImportedDir(): String = importedDir.absolutePath

    fun pickStickerForMood(text: String): StickerInfo? {
        if (text.isBlank()) return null

        val lowerText = text.lowercase()
        val rules = stickerRules
        val allAvailableStickers = mutableListOf<StickerInfo>()

        rules.values.forEach { rule ->
            allAvailableStickers.add(StickerInfo(
                name = rule.description,
                path = rule.path,
                category = "imported",
                isBuiltIn = false,
                description = rule.description,
                fileName = rule.fileName
            ))
        }

        importedDir.listFiles()?.filter { isImageFile(it.name) }?.forEach { file ->
            val existingRule = rules.values.find { it.fileName == file.name }
            if (existingRule == null) {
                allAvailableStickers.add(StickerInfo(
                    name = file.nameWithoutExtension,
                    path = file.absolutePath,
                    category = "imported",
                    isBuiltIn = false,
                    fileName = file.name
                ))
            }
        }

        try {
            context.assets.list("stickers")?.filter { isImageFile(it) }?.forEach { filename ->
                allAvailableStickers.add(StickerInfo(
                    name = filename.substringBeforeLast("."),
                    path = "asset://stickers/$filename",
                    category = "default",
                    isBuiltIn = true
                ))
            }
        } catch (_: Exception) {}

        if (allAvailableStickers.isEmpty()) return null

        val moodKeywords = listOf(
            "开心" to listOf("开心", "高兴", "快乐", "笑", "哈哈", "嘻嘻", "嘿嘿", "好耶", "太棒了", "棒", "赞", "好", "nice"),
            "委屈" to listOf("委屈", "难过", "伤心", "哭", "呜呜", "呜", "眼泪", "可怜", "心疼", "好惨", "不想", "难过"),
            "生气" to listOf("生气", "气", "哼", "烦", "讨厌", "滚", "不理你", "不想理", "气鼓鼓", "怒"),
            "害羞" to listOf("害羞", "脸红", "不好意思", "羞", "呀", "哎呀", "啊这", "那个", "嗯..."),
            "惊讶" to listOf("惊讶", "哇", "天哪", "什么", "真的吗", "不会吧", "居然", "竟然", "啊？"),
            "撒娇" to listOf("撒娇", "嘛", "啦", "呢", "呀", "人家", "求你", "好不好", "嘛~", "拜托", "亲"),
            "可爱" to listOf("可爱", "萌", "乖", "乖巧", "听话", "小", "宝贝", "宝宝", "亲亲", "抱抱", "喜欢"),
            "无奈" to listOf("无奈", "算了", "服了", "无语", "行吧", "好吧", "随你", "随便", "额"),
            "思考" to listOf("思考", "想想", "嗯", "让我想", "不知道", "好像", "也许", "可能", "这个"),
            "困倦" to listOf("困", "累", "睡", "瞌睡", "哈欠", "晚安", "早安", " tired ")
        )

        for ((mood, keywords) in moodKeywords) {
            for (keyword in keywords) {
                if (lowerText.contains(keyword)) {
                    val matched = allAvailableStickers.find {
                        (it.description?.contains(mood) == true) ||
                        (it.name.contains(mood)) ||
                        (it.fileName?.contains(mood) == true)
                    } ?: allAvailableStickers.find {
                        (it.description?.contains(keyword) == true) ||
                        (it.name.contains(keyword))
                    }
                    if (matched != null) {
                        SecureLog.d("StickerManager", "Mood match: keyword='$keyword' → sticker='${matched.name}'")
                        return matched
                    }
                }
            }
        }

        val picked = allAvailableStickers.random()
        SecureLog.d("StickerManager", "Random pick from ${allAvailableStickers.size} stickers: '${picked.name}'")
        return picked
    }

    /** 名称清洗：去协议方括号 / 换行，压缩空白，≤20 字 */
    private fun sanitizeStickerName(raw: String): String {
        return raw
            .replace(Regex("[\\[\\]\\n\\r\\t]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(20)
    }

    /** 语义描述清洗：去换行，≤40 字 */
    private fun sanitizeSemantic(raw: String): String {
        return raw.replace(Regex("[\\n\\r]"), " ").trim().take(40)
    }

    /** 别名清洗：去空白 / 方括号，去重，≤5 个每个 ≤12 字 */
    private fun sanitizeAliases(raw: List<String>): List<String> {
        return raw
            .map { it.replace(Regex("[\\[\\]\\n\\r\\t]"), "").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .map { it.take(12) }
    }

    /** 解析导入图片的扩展名：优先 DISPLAY_NAME 后缀，其次 MIME 映射 */
    private fun resolveImageExtension(uri: Uri): String? {
        var extension: String? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val candidate = cursor.getString(idx)
                            ?.substringAfterLast('.', "")
                            ?.lowercase()
                        if (!candidate.isNullOrBlank() && candidate in IMAGE_EXTENSIONS) {
                            extension = candidate
                        }
                    }
                }
            }
        } catch (e: Exception) {
            SecureLog.w("StickerManager", "query display name failed: ${e.message}")
        }
        if (extension == null) {
            val mime = try { context.contentResolver.getType(uri) } catch (e: Exception) { null }
            extension = when (mime) {
                "image/png" -> "png"
                "image/jpeg", "image/jpg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> null
            }
        }
        return extension
    }

    private fun isImageFile(filename: String): Boolean {
        val ext = filename.substringAfterLast(".", "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }

    companion object {
        /** 自定义表情落盘软上限（主理人拍板：200 条） */
        const val MAX_IMPORTED_COUNT = 200

        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

        @Volatile
        private var instance: StickerManager? = null

        fun getInstance(context: Context): StickerManager {
            return instance ?: synchronized(this) {
                instance ?: StickerManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

data class StickerInfo(
    val name: String,
    val path: String,
    val category: String = "default",
    val isBuiltIn: Boolean = true,
    val description: String? = null,
    val fileName: String? = null
)
