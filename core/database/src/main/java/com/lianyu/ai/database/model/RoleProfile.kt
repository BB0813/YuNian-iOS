package com.lianyu.ai.database.model

import com.lianyu.ai.common.CompanionRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 一个角色类型（女友/男友）对应的完整默认人设。
 *
 * 与 [CompanionEntity] 解耦，便于在切换角色时独立保存/恢复不同角色的设定，
 * 同时不污染单条聊天记录的 companionId，保证聊天记录连续。
 */
@Serializable
@SerialName("RP")
data class RoleProfile(
    val role: CompanionRole = CompanionRole.GIRLFRIEND,
    val name: String,
    val age: Int? = null,
    /** 头像 URI：file 路径 / content:// / android.resource:// 均可，与自定义创建路径一致。 */
    val avatarUrl: String? = null,
    val personality: String,
    val backstory: String? = null,
    val speakingStyle: String? = null,
    val rawPrompt: String? = null,
    val systemPrompt: String? = null,
    val tags: String? = null,
    val bodyType: String? = null,
    val profession: String? = null,
    val personalityTags: String? = null
) {
    /**
     * 将本角色预设应用到已有伴侣实体，保留 id、亲密度、创建时间等用户数据。
     *
     * 注意：角色类型与角色专属字段（身材/职业/性格标签）不写入 [CompanionEntity]，
     * 以保持 susu 数据库版本 v19 不变；它们由 [com.lianyu.ai.database.RolePresetStore]
     * 与 [com.lianyu.ai.database.repository.UserRepository] 单独维护。
     */
    fun applyTo(companion: CompanionEntity): CompanionEntity = companion.copy(
        name = name,
        age = age,
        avatarUrl = avatarUrl ?: companion.avatarUrl,
        personality = personality,
        backstory = backstory,
        speakingStyle = speakingStyle,
        rawPrompt = rawPrompt ?: personality,
        systemPrompt = resolvedSystemPrompt(),
        tags = tags,
        updatedAt = System.currentTimeMillis()
    )

    /**
     * 以本预设创建新的默认伴侣实体（完整自定义角色字段，含头像与 systemPrompt）。
     *
     * 注意：角色类型与角色专属字段（身材/职业/性格标签）不写入 [CompanionEntity]，
     * 以保持 susu 数据库版本 v19 不变。
     */
    fun createCompanion(now: Long = System.currentTimeMillis()): CompanionEntity = CompanionEntity(
        name = name,
        avatarUrl = avatarUrl,
        age = age,
        personality = personality,
        backstory = backstory,
        speakingStyle = speakingStyle,
        tags = tags,
        rawPrompt = rawPrompt ?: personality,
        systemPrompt = resolvedSystemPrompt(),
        createdAt = now,
        updatedAt = now
    )

    /**
     * 始终产出可写入 CompanionEntity.systemPrompt 的完整角色指令。
     * 若预设已显式配置 systemPrompt 则优先使用；否则由结构化字段合成。
     */
    fun resolvedSystemPrompt(): String {
        systemPrompt?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return buildString {
            appendLine("名字：$name")
            age?.let { appendLine("年龄：${it}岁") }
            appendLine("人设：$personality")
            speakingStyle?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("说话风格：$it")
            }
            backstory?.trim()?.takeIf { it.isNotBlank() }?.let {
                appendLine("背景：$it")
            }
            rawPrompt?.trim()?.takeIf {
                it.isNotBlank() && it != personality && !personality.contains(it)
            }?.let {
                appendLine("补充设定：$it")
            }
        }.trim()
    }
}
