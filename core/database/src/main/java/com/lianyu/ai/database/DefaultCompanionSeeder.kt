package com.lianyu.ai.database

import android.content.Context
import com.lianyu.ai.database.dao.CompanionDao
import com.lianyu.ai.database.model.CompanionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 默认体验角色「小鱼」种子。
 *
 * 与自定义创建路径对齐：写入完整 CompanionEntity 字段
 * （name / age / avatarUrl / personality / backstory / speakingStyle /
 * tags / rawPrompt / systemPrompt），内容以 [RolePresets.girlfriend] 为唯一来源。
 */
object DefaultCompanionSeeder {
    private const val PREFS_NAME = "default_companion"
    private const val KEY_DELETED_BY_USER = "deleted_by_user"
    private val seedMutex = Mutex()

    /** 默认体验伴侣稳定标签，角色切换 / 删除标记依赖此值。 */
    const val defaultExperienceCompanionTag: String = "default-experience-companion"

    /**
     * 列表展示用标签：体验、默认 + 稳定 tag。
     * getDefaultExperienceCompanion 只匹配 [defaultExperienceCompanionTag]。
     */
    const val DEFAULT_COMPANION_TAGS: String =
        "体验,默认,$defaultExperienceCompanionTag"

    /** 小鱼默认头像（app 合并资源，Coil 可直接加载）。 */
    const val DEFAULT_GIRLFRIEND_AVATAR_URL: String =
        "android.resource://com.lianyu.ai/drawable/avatar_xiaoyu"

    /** 阿泽默认头像。 */
    const val DEFAULT_BOYFRIEND_AVATAR_URL: String =
        "android.resource://com.lianyu.ai/drawable/avatar_aze"

    private const val DEFAULT_NAME = "小鱼"

    /**
     * 检查默认伴侣是否被用户删除，若未删除则确保存在且字段完整。
     * 必须在后台协程中调用，禁止在主线程同步执行数据库 IO。
     */
    suspend fun seedIfNeeded(context: Context) = seedMutex.withLock {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DELETED_BY_USER, false)) return@withLock
        runCatching {
            val db = AppDatabase.getDatabase(context.applicationContext)
            ensureDefaultTestCompanion(db.companionDao())
        }
    }

    /**
     * 按自定义角色完整字段构造默认「小鱼」。
     * 人设/提示词来自 [RolePresets.girlfriend]，并补齐头像与体验标签。
     */
    fun createDefaultTestCompanion(now: Long = System.currentTimeMillis()): CompanionEntity {
        val profile = RolePresets.girlfriend
        return profile.createCompanion(now = now).copy(
            tags = DEFAULT_COMPANION_TAGS,
            avatarUrl = profile.avatarUrl ?: DEFAULT_GIRLFRIEND_AVATAR_URL
        )
    }

    /**
     * 确保默认体验伴侣存在：
     * - 旧版「测试小鱼」→ 全量升级为完整自定义字段
     * - 已存在但不完整（缺 systemPrompt / 头像 / 仍是旧体验文案）→ 就地升级
     * - 不存在 → 插入
     * 保留 id / intimacy / createdAt，避免聊天记录断链。
     */
    suspend fun ensureDefaultTestCompanion(companionDao: CompanionDao): Long? {
        val companions = companionDao.getAllCompanionsSync()

        companions.firstOrNull { it.isLegacyDefaultTestCompanion() }?.let { legacy ->
            companionDao.updateCompanion(
                upgradeToFullDefaultCompanion(legacy)
            )
            return null
        }

        val existing = companions.firstOrNull { it.isDefaultExperienceCompanion() }
        if (existing != null) {
            if (existing.needsFullDefaultUpgrade()) {
                companionDao.updateCompanion(upgradeToFullDefaultCompanion(existing))
            }
            return null
        }

        return companionDao.insertCompanion(createDefaultTestCompanion())
    }

    /**
     * 将已有默认伴侣升级为完整自定义角色字段，保留用户数据锚点。
     */
    fun upgradeToFullDefaultCompanion(
        existing: CompanionEntity,
        now: Long = System.currentTimeMillis()
    ): CompanionEntity {
        val seed = createDefaultTestCompanion(now = existing.createdAt)
        val keepUserAvatar = existing.avatarUrl
            ?.takeIf { it.isNotBlank() && !isStockDefaultAvatar(it) && it != seed.avatarUrl }
        return if (existing.looksLikeStockDefaultSeed()) {
            seed.copy(
                id = existing.id,
                intimacy = existing.intimacy,
                createdAt = existing.createdAt,
                updatedAt = now,
                avatarUrl = keepUserAvatar ?: seed.avatarUrl
            )
        } else {
            existing.copy(
                avatarUrl = existing.avatarUrl?.takeIf { it.isNotBlank() } ?: seed.avatarUrl,
                age = existing.age ?: seed.age,
                backstory = existing.backstory?.takeIf { it.isNotBlank() } ?: seed.backstory,
                speakingStyle = existing.speakingStyle?.takeIf { it.isNotBlank() }
                    ?: seed.speakingStyle,
                tags = existing.tags?.takeIf { it.contains(defaultExperienceCompanionTag) }
                    ?: seed.tags,
                rawPrompt = existing.rawPrompt?.takeIf { it.isNotBlank() } ?: seed.rawPrompt,
                systemPrompt = existing.systemPrompt?.takeIf { it.isNotBlank() }
                    ?: seed.systemPrompt,
                updatedAt = now
            )
        }
    }

    private fun CompanionEntity.isDefaultExperienceCompanion(): Boolean {
        return name == DEFAULT_NAME ||
            name == LEGACY_NAME ||
            tags.orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it == defaultExperienceCompanionTag || it == LEGACY_TAG }
    }

    private fun CompanionEntity.isLegacyDefaultTestCompanion(): Boolean {
        return name == LEGACY_NAME ||
            tags.orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it == LEGACY_TAG }
    }

    private fun CompanionEntity.needsFullDefaultUpgrade(): Boolean {
        if (!isDefaultExperienceCompanion()) return false
        // 仅在字段不完整或仍是旧版种子时升级；完整 RolePresets 种子不再每次启动重写。
        // 库存默认头像 URI 不变时，APK 内 avatar_xiaoyu 资源更新即可换图，无需改库。
        return name == LEGACY_NAME ||
            personality.contains("体验角色") ||
            avatarUrl.isNullOrBlank() ||
            systemPrompt.isNullOrBlank() ||
            rawPrompt.isNullOrBlank() ||
            backstory.isNullOrBlank() ||
            speakingStyle.isNullOrBlank() ||
            !tags.orEmpty().contains(defaultExperienceCompanionTag)
    }

    /**
     * 可安全全量覆盖为 RolePresets 的库存/旧种子。
     * 用户已改过人设且关键字段齐全时返回 false，只补缺不冲掉自定义。
     */
    private fun CompanionEntity.looksLikeStockDefaultSeed(): Boolean {
        return name == LEGACY_NAME ||
            personality.contains("体验角色") ||
            systemPrompt.isNullOrBlank() ||
            rawPrompt.isNullOrBlank()
    }

    private fun isStockDefaultAvatar(url: String): Boolean {
        return url == DEFAULT_GIRLFRIEND_AVATAR_URL ||
            url == DEFAULT_BOYFRIEND_AVATAR_URL ||
            url.contains("avatar_xiaoyu") ||
            url.contains("avatar_aze")
    }

    const val LEGACY_TAG = "default-test-companion"
    private const val LEGACY_NAME = "测试小鱼"
}