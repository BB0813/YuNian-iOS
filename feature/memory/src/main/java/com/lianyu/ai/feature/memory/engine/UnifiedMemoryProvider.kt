package com.lianyu.ai.feature.memory.engine

import android.content.Context
import android.util.Log
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.DiaryEntry
import com.lianyu.ai.database.model.MemoryScope
import com.lianyu.ai.database.model.MemorySource
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.DiaryProvider
import com.lianyu.ai.database.repository.EmbeddingProvider
import com.lianyu.ai.database.repository.SummaryProvider
import com.lianyu.ai.database.repository.UnifiedMemoryRepository
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.ServiceRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一记忆提供者 —— 现代化记忆系统的 [MemoryProvider] 实现。
 *
 * 基于 [UnifiedMemoryRepository]（Room 数据库），替代旧的 [MemoryManager]（JSON 文件）。
 *
 * 核心改进：
 * 1. 时间感知：记忆按 decayScore 排序，注入时附带"3小时前"等时间标签
 * 2. 7 种记忆类型：WORKING/EPISODIC/SEMANTIC/PREFERENCE/RELATIONSHIP/PROCEDURAL/FUZZY
 * 3. 去重融合：bigram Jaccard ≥ 0.75 时合并而非重复插入
 * 4. 软删除 + 过期清理：WORKING 记忆自动 TTL 过期
 * 5. 语义检索（Phase 3）：embedding 向量 cosine similarity
 * 6. 摘要压缩（Phase 4）：WORKING 记忆 ≥ 阈值时 AI 摘要为 EPISODIC 记忆
 *
 * 通过 ServiceRegistry 注册，被 AiService 和 GroupChatViewModel 消费。
 */
class UnifiedMemoryProvider(
    context: Context,
    private val deviceId: String,
    embeddingProvider: EmbeddingProvider? = null,
    summaryProvider: SummaryProvider? = null
) : MemoryProvider {

    companion object {
        private const val TAG = "UnifiedMemoryProvider"
            private const val DIARY_TAG = "ai_generated,conversation_summary"
    }

    private val repository: UnifiedMemoryRepository
        private val database = AppDatabase.getDatabase(context.applicationContext)
        private val diaryDao = database.diaryDao()
        private val companionRepository = CompanionRepository(database.companionDao())

    init {
        repository = UnifiedMemoryRepository(database.unifiedMemoryDao(), deviceId, embeddingProvider, summaryProvider)
    }

    override fun initialize() {
        // Room 数据库是懒加载的，无需显式初始化
        // 过期的工作记忆会在首次写入时自动清理
        Log.d(TAG, "UnifiedMemoryProvider initialized (deviceId=${deviceId.take(8)})")
    }

    /**
     * 获取记忆上下文（注入 AI 对话）。
     *
     * 查询策略：
     * 1. 始终查询 GLOBAL scope 的全局记忆
     * 2. 单聊：查询 COMPANION scope
     * 3. 群聊：查询 GROUP scope
     * 4. 合并后按 decayScore + 关键词匹配排序
     */
    override suspend fun getMemoryContext(
        companionId: Long?,
        groupId: Long?,
        query: String,
        limit: Int
    ): String {
        return runCatching {
            val parts = mutableListOf<String>()

            // 1. 全局记忆（用户级偏好、事实）
            val globalContext = repository.buildMemoryContext(
                scope = MemoryScope.GLOBAL,
                sourceId = 0L,
                userQuery = query,
                limit = limit / 2  // 全局记忆占一半配额
            )
            if (globalContext.isNotBlank()) parts.add(globalContext)

            // 2. 作用域记忆
            val scopeContext = when {
                companionId != null -> repository.buildMemoryContext(
                    scope = MemoryScope.COMPANION,
                    sourceId = companionId,
                    userQuery = query,
                    limit = limit
                )
                groupId != null -> repository.buildMemoryContext(
                    scope = MemoryScope.GROUP,
                    sourceId = groupId,
                    userQuery = query,
                    limit = limit
                )
                else -> ""
            }
            if (scopeContext.isNotBlank()) parts.add(scopeContext)

            if (parts.isEmpty()) "" else parts.joinToString("\n")
        }.onFailure { Log.e(TAG, "获取记忆上下文失败", it) }
            .getOrElse { "" }
    }

    /**
     * 从对话中提取并保存记忆。
     *
     * 委托给 [UnifiedMemoryRepository.extractAndSaveMemories]，
     * 自动识别 7 种记忆类型并分别存储。
     */
    override suspend fun extractAndSaveFromConversation(
        userInput: String,
        aiResponse: String,
        companionId: Long,
        groupId: Long?
    ) {
        runCatching {
            val scope = if (groupId != null) MemoryScope.GROUP else MemoryScope.COMPANION
            val sourceId = groupId ?: companionId

            repository.extractAndSaveMemories(
                scope = scope,
                sourceId = sourceId,
                userInput = userInput,
                aiResponse = aiResponse
            )

            if (groupId == null) {
                generateConversationDiary(
                    companionId = companionId,
                    userInput = userInput,
                    aiResponse = aiResponse
                )
            }

            // 同时提取全局记忆（用户级偏好/事实，跨角色共享）
            if (scope != MemoryScope.GLOBAL) {
                repository.extractAndSaveMemories(
                    scope = MemoryScope.GLOBAL,
                    sourceId = 0L,
                    userInput = userInput,
                    aiResponse = aiResponse
                )
            }
        }.onFailure { Log.e(TAG, "提取记忆失败", it) }
    }

    private suspend fun generateConversationDiary(
        companionId: Long,
        userInput: String,
        aiResponse: String
    ) {
        val diaryProvider = ServiceRegistry.get(DiaryProvider::class.java) ?: return
        val companion = companionRepository.getCompanionById(companionId) ?: return

        val conversationSummary = buildConversationSummary(companion.name, userInput, aiResponse)
        val memoryContext = repository.buildMemoryContext(
            scope = MemoryScope.COMPANION,
            sourceId = companionId,
            userQuery = userInput,
            limit = 8
        )
        val diaryContent = diaryProvider.generateDiary(companion, conversationSummary, memoryContext)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        diaryDao.insertDiary(
            DiaryEntry(
                companionId = companionId,
                title = buildDiaryTitle(),
                content = diaryContent,
                mood = 2,
                date = System.currentTimeMillis(),
                tags = DIARY_TAG,
                deviceId = deviceId
            )
        )
    }

    private fun buildConversationSummary(
        companionName: String,
        userInput: String,
        aiResponse: String
    ): String {
        return buildString {
            append("用户表达：").append(userInput.trim()).append('\n')
            append(companionName).append("回应：").append(aiResponse.trim()).append('\n')
            append("请从这次互动里提炼用户的情绪变化、被触动的点、未说出口的期待和亲密感。")
        }
    }

    private fun buildDiaryTitle(): String {
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return "聊天后的心情 ${formatter.format(Date())}"
    }
}
