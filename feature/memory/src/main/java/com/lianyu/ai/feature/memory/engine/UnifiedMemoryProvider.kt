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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
        private const val CORE_RECOGNITION_INTERVAL_MS = 3 * 60_000L
    }

    /** 核心记忆识别的节流记录：scope:sourceId → 上次调用时间戳 */
    private val lastCoreRecognition = ConcurrentHashMap<String, Long>()

    /**
     * 记忆沉淀后台作用域：embedding 回填 / AI 摘要 / 日记生成等网络调用
     * 全部在此异步执行，不占用对话回复路径的 5s 后处理超时窗。
     */
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
     * 分段执行：
     * 1. 同步快速部分（本地 DB）：WORKING 写入 + 正则稳定记忆 + 过期清理，
     *    必须在调用方（对话后处理 5s 超时窗）内完成
     * 2. 异步慢部分（网络）：embedding 回填、WORKING→EPISODIC AI 摘要、日记生成，
     *    移入 [memoryScope] 后台协程，各自独立容错，不再受对话流程超时影响
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
        val scope = if (groupId != null) MemoryScope.GROUP else MemoryScope.COMPANION
        val sourceId = groupId ?: companionId

        // ── 1. 同步快速部分：本地写入（WORKING + 六类正则稳定记忆） ──
        runCatching {
            repository.extractAndSaveMemories(
                scope = scope,
                sourceId = sourceId,
                userInput = userInput,
                aiResponse = aiResponse
            )
        }.onFailure { Log.e(TAG, "提取记忆失败", it) }

        // 同时提取全局记忆（用户级偏好/事实，跨角色共享）
        if (scope != MemoryScope.GLOBAL) {
            runCatching {
                repository.extractAndSaveMemories(
                    scope = MemoryScope.GLOBAL,
                    sourceId = 0L,
                    userInput = userInput,
                    aiResponse = aiResponse
                )
            }.onFailure { Log.e(TAG, "提取全局记忆失败", it) }
        }

        // ── 2. 异步慢部分：embedding / AI 摘要 / 核心记忆识别 / 日记，独立后台协程 ──
        memoryScope.launch {
            runCatching { repository.postProcessMemories(scope, sourceId) }
                .onFailure { Log.e(TAG, "记忆后处理失败", it) }
            // 每轮对话增量识别核心记忆（3 分钟节流），不依赖 WORKING 条数阈值
            recognizeCoreMemoriesThrottled(userInput, aiResponse, scope, sourceId)
            if (scope != MemoryScope.GLOBAL) {
                runCatching { repository.postProcessMemories(MemoryScope.GLOBAL, 0L) }
                    .onFailure { Log.e(TAG, "全局记忆后处理失败", it) }
                recognizeCoreMemoriesThrottled(userInput, aiResponse, MemoryScope.GLOBAL, 0L)
            }
            if (groupId == null) {
                runCatching {
                    generateConversationDiary(
                        companionId = companionId,
                        userInput = userInput,
                        aiResponse = aiResponse
                    )
                }.onFailure { Log.e(TAG, "日记生成失败", it) }
            }
        }
    }

    /**
     * 核心记忆识别（节流版）：同 scope 每 3 分钟最多一次，
     * 把当前轮对话交给 AI 识别值得长期记住的事实/偏好/关系/事件。
     */
    private suspend fun recognizeCoreMemoriesThrottled(
        userInput: String,
        aiResponse: String,
        scope: MemoryScope,
        sourceId: Long
    ) {
        val key = "$scope:$sourceId"
        val now = System.currentTimeMillis()
        val last = lastCoreRecognition[key] ?: 0L
        if (now - last < CORE_RECOGNITION_INTERVAL_MS) return
        lastCoreRecognition[key] = now

        runCatching {
            repository.recognizeCoreMemories(
                conversationText = "用户: $userInput\nAI: $aiResponse",
                scope = scope,
                sourceId = sourceId
            )
        }.onFailure { Log.e(TAG, "核心记忆识别失败", it) }
    }

    private suspend fun generateConversationDiary(
        companionId: Long,
        userInput: String,
        aiResponse: String
    ) {
        val diaryProvider = ServiceRegistry.get(DiaryProvider::class.java) ?: return
        val companion = companionRepository.getCompanionById(companionId) ?: return

        // 当天（本地时区）已自动生成过日记 → 跳过，每天最多自动沉淀 1 篇
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        val todayStart = calendar.timeInMillis
        val existingToday = diaryDao.getDiariesForCompanionSync(companionId, deviceId)
            .firstOrNull { it.date in todayStart until (todayStart + 86_400_000L) && it.tags.contains(DIARY_TAG) }
        if (existingToday != null) return

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
