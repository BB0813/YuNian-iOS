package com.lianyu.ai.feature.skills.repository

import android.content.Context
import android.content.res.AssetManager
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.domain.Skill
import com.lianyu.ai.domain.SkillManager
import com.lianyu.ai.domain.SkillMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.InputStreamReader

/**
 * 技能管理器实现。
 *
 * 从 assets/skills/ 目录加载 SKILL.md 文件。
 * 支持 YAML frontmatter 解析。
 */
class SkillManagerImpl(
    private val context: Context
) : SkillManager {

    private val json = Json { ignoreUnknownKeys = true }
    private val loadedSkills = mutableMapOf<String, Skill>()
    private var discovered: List<SkillMetadata>? = null

    override suspend fun discoverSkills(): List<SkillMetadata> {
        if (discovered != null) return discovered!!

        return withContext(Dispatchers.IO) {
            val assets = context.assets
            val skillFiles = try {
                assets.list("skills") ?: emptyArray()
            } catch (e: Exception) {
                SecureLog.w("SkillManager", "No skills directory in assets: ${e.message}")
                emptyArray()
            }

            val metadataList = mutableListOf<SkillMetadata>()
            for (fileName in skillFiles) {
                if (fileName.endsWith(".md") || fileName.endsWith(".skill")) {
                    val metadata = parseSkillFile(assets, "skills/$fileName")
                    metadata?.let { metadataList.add(it) }
                }
            }

            discovered = metadataList
            metadataList
        }
    }

    override suspend fun loadSkill(name: String): Skill? {
        // 先检查缓存
        loadedSkills[name]?.let { return it }

        return withContext(Dispatchers.IO) {
            val assets = context.assets
            var inputStream: java.io.InputStream? = null

            // 尝试多个可能的路径
            val possiblePaths = listOf(
                "skills/$name.md",
                "skills/$name.skill",
                "skills/$name/index.md"
            )

            for (path in possiblePaths) {
                try {
                    inputStream = assets.open(path)
                    break
                } catch (e: Exception) {
                    // 继续尝试下一个路径
                }
            }

            inputStream ?: return@withContext null

            val content = InputStreamReader(inputStream).readText()
            val (metadata, markdown) = parseSkillContent(content)
            
            if (metadata.name != name) {
                SecureLog.w("SkillManager", "Skill name mismatch: expected $name, got ${metadata.name}")
            }

            val skill = Skill(
                metadata = metadata,
                content = markdown,
                sourcePath = "assets/skills/$name"
            )
            loadedSkills[name] = skill
            skill
        }
    }

    override suspend fun searchSkills(query: String): List<SkillMetadata> {
        val allSkills = discoverSkills()
        val lowerQuery = query.lowercase()
        return allSkills.filter { skill ->
            skill.name.lowercase().contains(lowerQuery) ||
            skill.description.lowercase().contains(lowerQuery) ||
            skill.tags.any { it.lowercase().contains(lowerQuery) }
        }
    }

    override fun getLoadedSkills(): List<Skill> = loadedSkills.values.toList()

    override suspend fun unloadSkill(name: String): Boolean {
        return loadedSkills.remove(name) != null
    }

    // ═══════════════════════════════════════════════════════════
    // 解析工具
    // ═══════════════════════════════════════════════════════════

    private fun parseSkillFile(assets: AssetManager, path: String): SkillMetadata? {
        try {
            val inputStream = assets.open(path)
            val content = InputStreamReader(inputStream).readText()
            val (metadata, _) = parseSkillContent(content)
            return metadata
        } catch (e: Exception) {
            SecureLog.w("SkillManager", "Failed to parse skill file $path: ${e.message}")
            return null
        }
    }

    /**
     * 解析 SKILL.md 内容，提取 frontmatter 和正文。
     *
     * 格式：
     * ---
     * name: skill_name
     * description: 描述
     * version: "1.0"
     * tags: [tag1, tag2]
     * ---
     * # 正文内容
     */
    private fun parseSkillContent(content: String): Pair<SkillMetadata, String> {
        val lines = content.split("\n")
        
        // 查找 frontmatter 分隔符
        var frontmatterEnd = -1
        if (lines.firstOrNull()?.trim() == "---") {
            for (i in 1 until lines.size) {
                if (lines[i].trim() == "---") {
                    frontmatterEnd = i
                    break
                }
            }
        }

        val metadata: SkillMetadata
        val markdown: String

        if (frontmatterEnd > 0) {
            val frontmatterText = lines.subList(1, frontmatterEnd).joinToString("\n")
            metadata = parseYamlFrontmatter(frontmatterText)
            markdown = lines.subList(frontmatterEnd + 1, lines.size).joinToString("\n").trim()
        } else {
            // 无 frontmatter，从文件名推断
            metadata = SkillMetadata(
                name = "unknown",
                description = "No frontmatter found"
            )
            markdown = content
        }

        return metadata to markdown
    }

    private fun parseYamlFrontmatter(text: String): SkillMetadata {
        // 简单的 YAML 解析（仅支持基本键值对）
        var name = "unknown"
        var description = ""
        var version = "1.0"
        var author = ""
        val tags = mutableListOf<String>()
        var requiresConfirmation = false
        val parameters = mutableMapOf<String, Any>()

        for (line in text.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val colonIndex = trimmed.indexOf(':')
            if (colonIndex <= 0) continue

            val key = trimmed.substring(0, colonIndex).trim().lowercase()
            val value = trimmed.substring(colonIndex + 1).trim()

            when (key) {
                "name" -> name = value.trim('"', '\'')
                "description" -> description = value.trim('"', '\'')
                "version" -> version = value.trim('"', '\'')
                "author" -> author = value.trim('"', '\'')
                "tags" -> {
                    // 解析 [tag1, tag2] 格式
                    val tagsText = value.trim('[', ']', ' ')
                    if (tagsText.isNotBlank()) {
                        tags.addAll(tagsText.split(',').map { it.trim().trim('"', '\'') })
                    }
                }
                "requires_confirmation" -> requiresConfirmation = value.lowercase() == "true"
                else -> parameters[key] = value.trim('"', '\'')
            }
        }

        return SkillMetadata(
            name = name,
            description = description,
            version = version,
            author = author,
            tags = tags,
            requiresConfirmation = requiresConfirmation,
            parameters = parameters
        )
    }
}