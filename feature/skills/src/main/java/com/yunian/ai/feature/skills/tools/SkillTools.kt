package com.yunian.ai.feature.skills.tools

import com.yunian.ai.common.SecureLog
import com.yunian.ai.domain.AiTool
import com.yunian.ai.domain.SkillManager
import com.yunian.ai.domain.SkillMetadata
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.domain.UseSkillArgs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 技能索引缓存：启动时刷新一次，供 systemPrompt() 同步读取 */
object SkillIndexState {
    @Volatile
    var promptText: String = ""
        private set

    suspend fun refresh(skillManager: SkillManager) {
        promptText = buildSkillIndexPrompt(runCatching { skillManager.discoverSkills() }.getOrDefault(emptyList()))
    }
}

private const val MAX_SKILLS_IN_PROMPT = 20
private const val MAX_SKILL_DESC_LENGTH = 120

/**
 * 参考成熟 Agent 的技能触发模式：把技能清单（名称+描述）常驻注入系统提示词，
 * 模型据此自主决定何时调用 use_skill 加载技能正文——解决"AI 不会自觉使用技能"。
 */
private fun buildSkillIndexPrompt(skills: List<SkillMetadata>): String {
    if (skills.isEmpty()) return ""
    val selected = skills.take(MAX_SKILLS_IN_PROMPT)
    val xml = buildString {
        append("<available_skills>\n")
        for (skill in selected) {
            var desc = skill.description
            if (desc.length > MAX_SKILL_DESC_LENGTH) {
                desc = desc.substring(0, MAX_SKILL_DESC_LENGTH) + "…"
            }
            append("  <skill>\n")
            append("    <name>").append(escapeXml(skill.name)).append("</name>\n")
            append("    <description>").append(escapeXml(desc)).append("</description>\n")
            append("  </skill>\n")
        }
        append("</available_skills>")
    }
    val more = skills.size - selected.size
    return buildString {
        append("可用技能：\n")
        append("以下是可复用的技能包。当用户的请求与某个技能匹配时，先调用 use_skill 工具加载该技能的完整说明，再严格按说明执行任务；不要凭空猜测做法。\n\n")
        append(xml)
        if (more > 0) {
            val names = skills.drop(MAX_SKILLS_IN_PROMPT).take(30).joinToString(", ") { it.name }
            append("\n\n还有 $more 个技能未列出：$names。需要时可用 use_skill 直接按名称加载。")
        }
    }
}

private fun escapeXml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

class UseSkillTool(
    private val skillManager: SkillManager
) : AiTool {

    private val json = Json { ignoreUnknownKeys = true }

    override val name = "use_skill"
    override val description = """
        加载并阅读技能文档（SKILL.md）。
        当遇到不熟悉的任务、需要特定领域知识、或系统提示词中的"可用技能"与当前请求匹配时，必须先调用本工具加载技能，再按文档指导执行。
    """.trimIndent()
    override val parametersJsonSchema = """
        {"type":"object","properties":{"name":{"type":"string","description":"技能名称（必填）"},"path":{"type":"string","description":"可选：自定义技能路径"}},"required":["name"]}
    """.trimIndent()

    override fun systemPrompt(): String = buildString {
        append("use_skill: 加载技能文档。参数 {name: string, path?: string}。返回技能的完整 Markdown 内容。")
        SkillIndexState.promptText.takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
    }

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

fun registerSkillTools(skillManager: SkillManager) {
    // ⚠️ Q6 技能收敛：`use_skill` 已退役，不再注册。
    //
    // 原因（计划文档 §1.5 / R22）：本地 `use_skill`（走 ToolRegistry，受 useTools 门控）与
    // Rust `load_skill`（AgentToolHost 特判，无条件可用）并存时，模型可能**同时调用**二者 →
    // 重复加载同一技能正文 + 提示词污染 + token 浪费。
    //
    // 技能正文改由 Rust `SkillSelector` 按需读取（渐进式披露 L1 目录 / L2 `load_skill`），
    // 其 SkillStore 回调由 [com.yunian.ai.feature.skills.repository.SkillStoreAdapter]
    // 桥接到本地 assets/skills + external_skills（装配于默认蓝图，早于首次 Agent 回合）。
    @Suppress("UNUSED_EXPRESSION")
    skillManager
}

/** 启动后调用：刷新技能索引缓存，使系统提示词中出现可用技能清单 */
suspend fun refreshSkillIndex(skillManager: SkillManager) {
    SkillIndexState.refresh(skillManager)
    SecureLog.i("SkillTools", "Skill index refreshed: ${SkillIndexState.promptText.length} chars")
}
