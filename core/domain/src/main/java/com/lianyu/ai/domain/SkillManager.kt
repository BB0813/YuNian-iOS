package com.lianyu.ai.domain

/**
 * 技能系统领域模型。
 *
 * SKILL.md 文件格式：
 * ---
 * name: skill_name
 * description: 技能描述
 * version: "1.0"
 * author: 作者
 * tags: [tag1, tag2]
 * ---
 * # 技能内容（Markdown）
 * 详细的使用说明、最佳实践、示例等。
 */

/** 技能元数据（SKILL.md frontmatter） */
data class SkillMetadata(
    val name: String,
    val description: String,
    val version: String = "1.0",
    val author: String = "",
    val tags: List<String> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val parameters: Map<String, Any> = emptyMap()
)

/** 技能完整内容 */
data class Skill(
    val metadata: SkillMetadata,
    val content: String,        // Markdown 正文
    val sourcePath: String? = null  // 来源路径（assets/skills/... 或下载路径）
)

/** 技能管理器接口 */
interface SkillManager {
    /** 发现所有可用技能 */
    suspend fun discoverSkills(): List<SkillMetadata>

    /** 加载技能完整内容 */
    suspend fun loadSkill(name: String): Skill?

    /** 搜索技能（按标签/关键词） */
    suspend fun searchSkills(query: String): List<SkillMetadata>

    /** 获取已加载的技能列表 */
    fun getLoadedSkills(): List<Skill>

    /** 卸载技能 */
    suspend fun unloadSkill(name: String): Boolean
}

/** use_skill 工具参数 */
data class UseSkillArgs(
    val name: String,      // 技能名（必填）
    val path: String? = null  // 可选：自定义路径
)