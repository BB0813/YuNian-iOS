package com.yunian.ai.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 表情包规则（JSON v2）持久化存储。
 *
 * 纯 Kotlin 实现：不依赖 android.content.Context / android.util.Log，
 * 文件对象由外部注入，便于 JVM 单测。
 *
 * JSON v2 结构（顶层保持 JSONArray，向后兼容 v1 的 {description, fileName}）：
 * 每项 { description, fileName, semantic, aliases, createdAt, source }
 * 读取端全部带默认值 → 旧 JSON 加载行为完全不变。
 */
class StickerRuleStore(private val rulesFile: File) {

    /** 单条表情规则（JSON v2） */
    @Serializable
    data class Entry(
        @SerialName("description") val description: String = "",
        @SerialName("fileName") val fileName: String = "",
        @SerialName("semantic") val semantic: String = "",
        @SerialName("aliases") val aliases: List<String> = emptyList(),
        @SerialName("createdAt") val createdAt: Long = 0L,
        @SerialName("source") val source: String = SOURCE_FILE,
    )

    /** ZIP 合并结果：merged 为合并后的完整列表，added/skipped 用于统计与日志 */
    data class MergeResult(val merged: List<Entry>, val added: Int, val skipped: Int)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读取全部规则；文件不存在 / 内容为空 / 解析失败均返回空列表（不抛异常） */
    fun load(): List<Entry> {
        return try {
            if (!rulesFile.exists()) return emptyList()
            val text = rulesFile.readText()
            if (text.isBlank()) return emptyList()
            json.decodeFromString(ListSerializer(Entry.serializer()), text)
                .filter { it.description.isNotBlank() && it.fileName.isNotBlank() }
        } catch (e: Exception) {
            SecureLog.w("StickerRuleStore", "load rules failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 原子写：先写 .tmp 再 renameTo，避免写一半损坏（修 P2 的写入端）。
     * Windows 上目标文件已存在时 renameTo 会失败，故先删旧文件再 rename，
     * rename 仍失败时退化为直接写（尽力而为）。
     */
    fun save(entries: List<Entry>): Boolean {
        val tmp = File(rulesFile.parentFile, rulesFile.name + ".tmp")
        return try {
            tmp.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
            if (rulesFile.exists() && !rulesFile.delete()) {
                SecureLog.w("StickerRuleStore", "delete old rules file failed: ${rulesFile.name}")
            }
            if (!tmp.renameTo(rulesFile)) {
                // rename 失败兜底：直接落盘正式文件
                rulesFile.writeText(json.encodeToString(ListSerializer(Entry.serializer()), entries))
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            SecureLog.w("StickerRuleStore", "save rules failed: ${e.message}")
            try { tmp.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * ZIP 合并（修 P2 覆盖丢失）：以 description 为 key 做 merge 而非整体覆盖。
     * 同名 → 保留旧文件与旧规则，跳过并计数；新名称 → 追加。
     */
    fun mergeZipRules(incoming: List<Entry>, existing: List<Entry>): MergeResult {
        val byDescription = existing.associateBy { it.description }.toMutableMap()
        var added = 0
        var skipped = 0
        for (entry in incoming) {
            if (entry.description.isBlank() || entry.fileName.isBlank()) continue
            if (byDescription.containsKey(entry.description)) {
                skipped++
            } else {
                // ZIP 来源条目补默认值：无时间戳的取导入时刻，保证新→旧排序稳定
                val normalized = if (entry.createdAt <= 0L) {
                    entry.copy(createdAt = System.currentTimeMillis(), source = SOURCE_ZIP)
                } else {
                    entry.copy(source = SOURCE_ZIP)
                }
                byDescription[entry.description] = normalized
                added++
            }
        }
        return MergeResult(byDescription.values.toList(), added, skipped)
    }

    /** 别名索引：alias → description（loadRules/import/rename/delete 时重建或增量维护） */
    fun buildAliasIndex(entries: List<Entry>): Map<String, String> {
        val index = mutableMapOf<String, String>()
        for (entry in entries) {
            for (alias in entry.aliases) {
                val trimmed = alias.trim()
                if (trimmed.isNotEmpty()) {
                    index.putIfAbsent(trimmed, entry.description)
                }
            }
        }
        return index
    }

    companion object {
        const val SOURCE_FILE = "file"
        const val SOURCE_ZIP = "zip"
        const val RULES_FILE_NAME = "custom_stickers.json"
    }
}

/**
 * 提示词层使用的结构化表情清单（core:network 与 feature:chat 共用）。
 */
data class PromptSticker(
    val name: String,
    val semantic: String = "",
    val aliases: List<String> = emptyList(),
    val isCustom: Boolean = false,
)

/**
 * 自定义表情 E2 提示词段的统一拼装入口（云端 / 本地模型路径共用）。
 * 预算：≤30 条；每条语义 ≤40 字；整段 ≤1200 字符；超限按 createdAt 新→旧截断
 * （调用方传入的列表需已按 createdAt 新→旧排序）。
 */
object CustomStickerPrompt {

    const val MAX_COUNT = 30
    const val MAX_CHARS = 1200
    const val MAX_SEMANTIC_LENGTH = 40

    /**
     * 生成 E2 段内容行：「[名称]=语义（别名：a、b）」。
     * 空列表返回空列表（调用方整段不拼，保证提示词逐字节零变化）。
     */
    fun buildLines(stickers: List<PromptSticker>): List<String> {
        if (stickers.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var budget = MAX_CHARS
        for (sticker in stickers.take(MAX_COUNT)) {
            if (budget <= 0) break
            val semantic = sticker.semantic.trim().take(MAX_SEMANTIC_LENGTH)
            val semanticPart = semantic.ifBlank { "（未提供语义，按名称理解）" }
            val aliasPart = if (sticker.aliases.isNotEmpty()) {
                "（别名：${sticker.aliases.joinToString("、")}）"
            } else ""
            val line = "[${sticker.name}]=$semanticPart$aliasPart"
            if (line.length > budget) break
            lines.add(line)
            budget -= line.length
        }
        return lines
    }
}
