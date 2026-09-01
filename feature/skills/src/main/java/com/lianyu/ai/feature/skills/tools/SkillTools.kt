package com.lianyu.ai.feature.skills.tools

import com.lianyu.ai.domain.AiTool
import com.lianyu.ai.domain.SkillManager
import com.lianyu.ai.domain.ToolRegistry
import com.lianyu.ai.domain.UseSkillArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * use_skill 工具：AI 调用此工具加载技能文档。
 *
 * 参数：
 * - name: 技能名（必填）
 * - path: 可选的自定义路径
 *
 * 返回：技能的完整 Markdown 内容
 */
class UseSkillTool(
    private val skillManager: SkillManager
) : AiTool {

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "use_skill"
    override val description = """
        加载并阅读技能文档（SKILL.md）。
        当遇到不熟悉的任务、需要特定领域知识、或用户明确要求使用某技能时调用。
        技能文档包含详细的使用说明、最佳实践、代码示例等。
        加载后请仔细阅读并按文档指导执行任务。
    """.trimIndent()
    override val parametersJsonSchema = """
        {"type":"object","properties":{"name":{"type":"string","description":"技能名称（必填）"},"path":{"type":"string","description":"可选：自定义技能路径"}},"required":["name"]}
    """.trimIndent()

    override fun systemPrompt(): String =
        "use_skill: 加载技能文档。参数 {name: string, path?: string}。返回技能的完整 Markdown 内容。"

    override val requiresConfirmation = false

    override suspend fun execute(argumentsJson: String): String {
        val args = try {
            json.decodeFromString<UseSkillArgs>(argumentsJson)
        } catch (e: Exception) {
            return buildError("Invalid arguments: ${e.message}")
        }

        if (args.name.isBlank()) {
            return buildError("Skill name is required")
        }

        return withContext(Dispatchers.IO) {
            val skill = skillManager.loadSkill(args.name)
                ?: return@withContext buildError("Skill not found: ${args.name}")

            buildJsonObject {
                put("ok", true)
                put("name", skill.metadata.name)
                put("description", skill.metadata.description)
                put("version", skill.metadata.version)
                put("tags", json.encodeToString(skill.metadata.tags))
                put("content", skill.content)
            }.toString()
        }
    }

    private fun buildError(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()
}

/** 注册技能工具 */
fun registerSkillTools(skillManager: SkillManager) {
    ToolRegistry.register(UseSkillTool(skillManager))
}