package com.yunian.ai.feature.settings.worldbook

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 世界书导入/导出的 JSON 传输格式
 * 参考 rikkahub 的 ExportSerializer 模式：格式标识 + 版本号 + 数据体
 */
@Serializable
data class WorldbookEntryDto(
    val keywords: List<String> = emptyList(),
    val content: String = "",
    val injectionPosition: String = "AFTER_SYSTEM_PROMPT",
    val role: String = "SYSTEM",
    val priority: Int = 0,
    val injectDepth: Int? = null,
    val scanDepth: Int = 10,
    val caseSensitive: Boolean = false,
    val useRegex: Boolean = false,
    val constantActive: Boolean = false,
    val enabled: Boolean = true,
    val sortOrder: Int = 0
)

@Serializable
data class WorldbookExportDto(
    val format: String = FORMAT,
    val version: Int = 1,
    val name: String = "",
    val description: String = "",
    val entries: List<WorldbookEntryDto> = emptyList()
) {
    companion object {
        const val FORMAT = "lianyu-worldbook"
    }
}

object WorldbookTransfer {

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun serialize(dto: WorldbookExportDto): String = json.encodeToString(WorldbookExportDto.serializer(), dto)

    /** 解析导入文件内容；校验格式标识（容许缺省以兼容手工编辑的文件） */
    fun parse(text: String): Result<WorldbookExportDto> = runCatching {
        val dto = json.decodeFromString(WorldbookExportDto.serializer(), text)
        require(dto.name.isNotBlank()) { "世界书名称为空" }
        require(dto.entries.isNotEmpty()) { "文件中没有条目" }
        dto
    }

    fun defaultFileName(name: String): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        return "lianyu_worldbook_${safe}.json"
    }
}
