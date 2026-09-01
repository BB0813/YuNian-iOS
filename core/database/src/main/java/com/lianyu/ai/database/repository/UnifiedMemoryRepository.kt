package com.lianyu.ai.database.repository

import com.lianyu.ai.database.dao.UnifiedMemoryDao
import com.lianyu.ai.database.model.MemoryRecord
import com.lianyu.ai.database.model.MemoryScope
import com.lianyu.ai.database.model.MemorySource
import com.lianyu.ai.database.model.MemoryType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 统一记忆仓库 —— 现代化记忆系统的核心业务逻辑层。
 *
 * 职责：
 * 1. 记忆 CRUD + 加密/解密
 * 2. 从对话中提取记忆（extractAndSave）—— 7 种类型映射
 * 3. 构建注入 prompt 的记忆上下文（buildMemoryContext）—— 时间感知排序
 * 4. 去重（大 bigram Jaccard）+ 融合（importance 累加）
 * 5. 时间衰减检索评分（decayScore）
 *
 * 与旧 MemoryRepository 的关系：
 * - MemoryRepository 只保留遗留导出/调试路径
 * - 管理页、AI prompt 注入、工具召回和自动提取统一走本仓库或 MemoryProvider
 * - 两者共享 deviceId，但操作不同的表
 */
class UnifiedMemoryRepository(
    private val dao: UnifiedMemoryDao,
    private val deviceId: String,
    private val embeddingProvider: EmbeddingProvider? = null,
    private val summaryProvider: SummaryProvider? = null
) {

    // ═══════════════════════════════════════════════════════════
    // 查询（Flow）
    // ═══════════════════════════════════════════════════════════

    /** 获取某个 scope 下所有活跃记忆（非工作记忆） */
    fun getStableMemories(scope: MemoryScope, sourceId: Long): Flow<List<MemoryRecord>> {
        return dao.getByScope(deviceId, scope, sourceId)
            .map { list -> list.filter { it.memoryType != MemoryType.WORKING } }
    }

    /** 获取某个 scope 下的工作记忆，用于记忆管理 UI 展示短期上下文。 */
    fun getWorkingMemories(scope: MemoryScope, sourceId: Long, limit: Int = 50): Flow<List<MemoryRecord>> {
        return dao.getWorkingMemoriesFlow(deviceId, scope, sourceId, limit)
    }

    /** 获取所有偏好记忆（跨 scope） */
    fun getPreferences(): Flow<List<MemoryRecord>> {
        return dao.getByType(deviceId, MemoryType.PREFERENCE)
    }

    /** 获取所有关系记忆 */
    fun getRelationships(): Flow<List<MemoryRecord>> {
        return dao.getByType(deviceId, MemoryType.RELATIONSHIP)
    }

    // ═══════════════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════════════

    /**
     * 添加一条记忆，自动去重。
     *
     * 去重逻辑：
     * 1. 精确 content 匹配 → 直接跳过
     * 2. 大 bigram Jaccard ≥ 0.75 → 视为相似记忆，累加 importance
     * 3. 否则新建记忆
     *
     * @return 记忆 ID（新增或合并后的）
     */
    suspend fun addMemory(
        content: String,
        type: MemoryType = MemoryType.SEMANTIC,
        scope: MemoryScope = MemoryScope.COMPANION,
        sourceId: Long = 0L,
        source: MemorySource = MemorySource.CHAT,
        importance: Float = 0.5f,
        confidence: Float = 1.0f,
        summary: String = "",
        tags: String = "",
        expiresAt: Long? = null,
        observedAt: Long = System.currentTimeMillis()
    ): Long {
        // 1. 精确匹配
        val exact = dao.findByContent(deviceId, content)
        if (exact != null) {
            // 已有完全相同的记忆 → 提升权重
            dao.updateImportance(
                exact.id,
                minOf(1.0f, exact.importance + 0.05f)
            )
            dao.touch(exact.id)
            return exact.id
        }

        // 2. 模糊去重：与同 scope 下现有记忆做 Jaccard 比较
        val existing = dao.getByScopeSync(deviceId, scope, sourceId)
        val similar = existing.firstOrNull { existingMemory ->
            jaccardSimilarityBigram(content, existingMemory.content) > 0.75f
        }

        if (similar != null) {
            // 合并：提升 importance，累加 accessCount，记录 mergedFrom
            val newImportance = minOf(1.0f, similar.importance + importance * 0.2f)
            dao.updateImportance(similar.id, newImportance)
            dao.touch(similar.id)
            return similar.id
        }

        // 3. 新建
        val record = MemoryRecord(
            memoryType = type,
            scope = scope,
            source = source,
            content = content,
            summary = summary,
            confidence = confidence,
            importance = importance,
            sourceId = sourceId,
            observedAt = observedAt,
            expiresAt = expiresAt,
            tags = tags,
            deviceId = deviceId
        )
        val newId = dao.insert(record)

        // 4. embedding 由后台 postProcessMemories 异步回填（backfillEmbeddings），
        //    不在对话写入路径同步调用网络，避免阻塞/超时对话后处理
        return newId
    }

    /** 更新一条已有记忆，保持统一记忆表作为管理页和 AI 召回的共同数据源。 */
    suspend fun updateMemory(record: MemoryRecord) {
        dao.insert(record.copy(version = record.version + 1, updatedAt = System.currentTimeMillis()))
    }

    /**
     * 添加工作记忆（短期，默认 2 小时 TTL）。
     * TTL 从 30 分钟延长至 2 小时，避免用户短暂离开后上下文断裂。
     */
    suspend fun addWorkingMemory(
        content: String,
        scope: MemoryScope,
        sourceId: Long,
        ttlMs: Long = 7_200_000L  // 2 小时
    ): Long {
        val record = MemoryRecord(
            memoryType = MemoryType.WORKING,
            scope = scope,
            source = MemorySource.CHAT,
            content = content,
            confidence = 0.5f,
            importance = 0.2f,
            sourceId = sourceId,
            expiresAt = System.currentTimeMillis() + ttlMs,
            deviceId = deviceId
        )
        return dao.insert(record)
    }

    // ═══════════════════════════════════════════════════════════
    // 从对话提取记忆
    // ═══════════════════════════════════════════════════════════

    /**
     * 从用户输入和 AI 回复中提取记忆。
     *
     * 提取策略（按优先级）：
     * 1. 自我声明 → SEMANTIC 记忆（高置信度）
     * 2. 偏好表达 → PREFERENCE 记忆
     * 3. 情绪表达 → EPISODIC 记忆（附带时间锚点）
     * 4. 事件描述 → EPISODIC 记忆
     * 5. 关系表达 → RELATIONSHIP 记忆
     * 6. 交互模式 → PROCEDURAL 记忆（低置信度，多次确认后提升）
     * 7. 对话上下文 → WORKING 记忆
     */
    suspend fun extractAndSaveMemories(
        scope: MemoryScope,
        sourceId: Long,
        userInput: String,
        aiResponse: String? = null
    ) {
        val trimmed = userInput.trim()
        if (trimmed.length < 2) return

        // ── WORKING: 始终保存当前对话上下文 ──
        if (aiResponse != null) {
            addWorkingMemory(
                content = "用户: $trimmed | AI: ${aiResponse.take(200)}",
                scope = scope,
                sourceId = sourceId
            )
            // 清理过期工作记忆
            dao.cleanupExpiredWorkingMemories()
        }

        if (aiResponse == null) return

        val now = System.currentTimeMillis()

        // ── SEMANTIC: 自我声明/事实 ──
        if (matchesAny(trimmed, SEMANTIC_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.SEMANTIC,
                scope = scope,
                sourceId = sourceId,
                importance = 0.8f,
                confidence = 0.85f,
                tags = "self-declaration"
            )
        }

        // ── PREFERENCE: 偏好表达 ──
        if (matchesAny(trimmed, PREFERENCE_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.PREFERENCE,
                scope = scope,
                sourceId = sourceId,
                importance = 0.7f,
                confidence = 0.8f,
                tags = "preference"
            )
        }

        // ── EPISODIC: 情绪表达 ──
        if (matchesAny(trimmed, EMOTION_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.EPISODIC,
                scope = scope,
                sourceId = sourceId,
                importance = 0.65f,
                confidence = 0.75f,
                tags = "emotion",
                observedAt = now
            )
        }

        // ── EPISODIC: 事件描述 ──
        if (matchesAny(trimmed, EVENT_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.EPISODIC,
                scope = scope,
                sourceId = sourceId,
                importance = 0.6f,
                confidence = 0.7f,
                tags = "event",
                observedAt = now
            )
        }

        // ── RELATIONSHIP: 关系表达 ──
        if (matchesAny(trimmed, RELATIONSHIP_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.RELATIONSHIP,
                scope = scope,
                sourceId = sourceId,
                importance = 0.7f,
                confidence = 0.7f,
                tags = "relationship"
            )
        }

        // ── PROCEDURAL: 交互模式（低置信度，等待多次确认） ──
        if (matchesAny(trimmed, PROCEDURAL_PATTERNS)) {
            addMemory(
                content = trimmed,
                type = MemoryType.PROCEDURAL,
                scope = scope,
                sourceId = sourceId,
                importance = 0.35f,
                confidence = 0.4f,
                tags = "interaction-pattern"
            )
        }
    }

    /**
     * 异步后处理：embedding 回填 + 工作记忆沉淀摘要。
     *
     * 包含网络调用（embedding / AI 摘要），必须在后台协程执行，
     * 不能放在对话回复的同步路径中，否则会阻塞/超时对话后处理。
     */
    suspend fun postProcessMemories(scope: MemoryScope, sourceId: Long) {
        // ── Phase 3: 异步补全 embedding ──
        if (embeddingProvider != null) {
            runCatching { backfillEmbeddings(batchSize = 5) }
        }

        // ── Phase 4: 对话摘要压缩（WORKING 记忆 ≥ 阈值时触发） ──
        runCatching { summarizeAndCompress(scope, sourceId) }
    }

    // ═══════════════════════════════════════════════════════════
    // 构建注入 prompt 的记忆上下文
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建用于注入 system prompt 的记忆上下文。
     *
     * 排序规则（时间感知）：
     * 1. 按 decayScore 降序（综合考虑 importance、recency、frequency）
     * 2. 截取 contextLimit 条
     * 3. 按类型分组格式化输出
     *
     * @param scope      记忆作用域
     * @param sourceId   来源 ID
     * @param userQuery  用户当前查询（用于关键词匹配）
     * @param limit      最大返回条数
     * @return 格式化的记忆上下文字符串
     */
    suspend fun buildMemoryContext(
        scope: MemoryScope,
        sourceId: Long,
        userQuery: String,
        limit: Int = 10
    ): String {
        val now = System.currentTimeMillis()

        // 1. 获取作用域内所有活跃的稳定记忆
        val allMemories = dao.getByScopeSync(deviceId, scope, sourceId)
            .filter { it.memoryType != MemoryType.WORKING }

        if (allMemories.isEmpty()) return ""

        // 2. 语义检索加分（如果 embedding 可用）
        val semanticBoosts = mutableMapOf<Long, Float>()
        if (embeddingProvider != null && userQuery.length >= 2) {
            val semanticResults = runCatching {
                semanticSearch(scope, sourceId, userQuery, limit * 2)
            }.getOrDefault(emptyList())
            semanticResults.forEachIndexed { index, memory ->
                // 语义匹配的加分：排名越靠前加分越多
                val boost = (limit - index).coerceAtLeast(0).toFloat() * 0.15f
                semanticBoosts[memory.id] = boost
            }
        }

        // 3. 关键词匹配加权
        val queryTokens = tokenize(userQuery)

        // 4. 计算综合评分：decayScore + 关键词加分 + 语义加分
        data class Scored(val memory: MemoryRecord, val score: Float)

        val scored = allMemories.map { memory ->
            var score = decayScore(memory, now)

            // 关键词加分：每个匹配的 token +0.1
            val matchCount = queryTokens.count { token ->
                memory.content.contains(token, ignoreCase = true) ||
                memory.summary.contains(token, ignoreCase = true) ||
                memory.tags.contains(token, ignoreCase = true)
            }
            score += matchCount * 0.1f

            // 语义加分
            score += semanticBoosts[memory.id] ?: 0f

            Scored(memory, score)
        }

        // 5. 排序并取 top-N
        val top = scored
            .sortedByDescending { it.score }
            .take(limit)

        if (top.isEmpty()) return ""

        // 6. 格式化输出
        return top.joinToString("\n") { (memory, _) ->
            val timeAgo = formatTimeAgo(now - memory.observedAt)
            val typeLabel = when (memory.memoryType) {
                MemoryType.SEMANTIC -> "事实"
                MemoryType.EPISODIC -> "记忆"
                MemoryType.PREFERENCE -> "偏好"
                MemoryType.RELATIONSHIP -> "关系"
                MemoryType.PROCEDURAL -> "模式"
                else -> "记忆"
            }
            "[$typeLabel | $timeAgo] ${memory.content}"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 语义检索（Phase 3）
    // ═══════════════════════════════════════════════════════════

    /**
     * 语义检索：通过 embedding 向量相似度搜索记忆。
     *
     * 两阶段策略：
     * 1. 粗筛：从 DB 获取已有 embedding 的记忆，按 importance + 时间衰减排序
     * 2. 精排：对 query embedding 做 cosine similarity 排序
     *
     * 如果 embeddingProvider 不可用或 query embedding 生成失败，
     * 回退到关键词搜索（[search]）。
     *
     * @param scope      记忆作用域
     * @param sourceId   来源 ID
     * @param query      查询文本
     * @param limit      最大返回条数
     * @return 匹配的记忆列表，按语义相似度降序
     */
    suspend fun semanticSearch(
        scope: MemoryScope,
        sourceId: Long,
        query: String,
        limit: Int = 10
    ): List<MemoryRecord> {
        val provider = embeddingProvider ?: return dao.searchInScope(deviceId, scope, sourceId, query, limit)

        // 1. 生成 query embedding
        val queryEmbedding = provider.embed(query) ?: return dao.searchInScope(deviceId, scope, sourceId, query, limit)

        // 2. 获取该 scope 下所有有 embedding 的记忆
        val candidates = dao.getWithEmbeddings(deviceId, scope, sourceId)
        if (candidates.isEmpty()) return dao.searchInScope(deviceId, scope, sourceId, query, limit)

        // 3. 粗筛：按 decayScore 排序，取 top 2×limit 作为候选池
        val now = System.currentTimeMillis()
        val coarseFiltered = candidates
            .sortedByDescending { decayScore(it, now) }
            .take(limit * 2)

        // 4. 精排：cosine similarity
        data class SemanticResult(val memory: MemoryRecord, val similarity: Float)

        val ranked = coarseFiltered.mapNotNull { memory ->
            val embeddingBytes = memory.embedding ?: return@mapNotNull null
            val memoryEmbedding = provider.bytesToFloats(embeddingBytes)
            val similarity = provider.cosineSimilarity(queryEmbedding, memoryEmbedding)
            SemanticResult(memory, similarity)
        }
            .sortedByDescending { it.similarity }
            .take(limit)

        // 5. touch 被检索到的记忆（提升 accessCount）
        ranked.forEach { dao.touch(it.memory.id) }

        return ranked.map { it.memory }
    }

    /**
     * 为指定记忆生成并存储 embedding。
     * 如果 embeddingProvider 不可用或 API 不支持，静默跳过。
     */
    suspend fun generateEmbeddingForMemory(memoryId: Long) {
        val provider = embeddingProvider ?: return
        if (!provider.isEmbeddingSupported()) return

        // 获取记忆内容
        val all = dao.getAllActiveSync(deviceId)
        val memory = all.find { it.id == memoryId } ?: return
        if (memory.embedding != null) return  // 已有 embedding，跳过

        val embedding = provider.embed(memory.content) ?: return
        val modelName = provider.getEmbeddingModelName()
        dao.updateEmbedding(memoryId, provider.floatsToBytes(embedding), modelName)
    }

    /**
     * 批量为缺少 embedding 的记忆补全向量。
     * 在后台异步执行，不阻塞对话流程。
     *
     * @param batchSize 每次处理的最大数量
     */
    suspend fun backfillEmbeddings(batchSize: Int = 20): Int {
        val provider = embeddingProvider ?: return 0
        if (!provider.isEmbeddingSupported()) return 0

        val pending = dao.getWithoutEmbeddings(deviceId, batchSize)
        if (pending.isEmpty()) return 0

        val modelName = provider.getEmbeddingModelName()
        var count = 0
        for (memory in pending) {
            val embedding = provider.embed(memory.content) ?: continue
            dao.updateEmbedding(memory.id, provider.floatsToBytes(embedding), modelName)
            count++
        }
        return count
    }

    // ═══════════════════════════════════════════════════════════
    // 对话摘要压缩（Phase 4）
    // ═══════════════════════════════════════════════════════════

    /**
     * 对话摘要压缩 —— 将积累的 WORKING 记忆压缩为 EPISODIC 叙事摘要记忆。
     *
     * 触发条件：WORKING 记忆数 ≥ [threshold]（默认 10 条）
     * 压缩流程：
     * 1. 获取最早的 [compressCount] 条 WORKING 记忆
     * 2. 拼接为对话文本
     * 3. 调用 [SummaryProvider] 生成五维叙事摘要（失败时回退到本地规则摘要）
     * 4. 将摘要存为 EPISODIC 记忆：
     *    - content = 完整叙事正文（检索/注入主字段）
     *    - summary = 同一叙事正文（UI/关键词检索副字段，避免空 summary）
     *    - tags = conversation-summary
     * 5. 软删除已摘要的 WORKING 记忆
     *
     * 存储说明：
     * - 叙事摘要落在 [MemoryRecord.content] / [MemoryRecord.summary]，类型 EPISODIC
     * - 不写入 conversation_summary 表（那是首页 lastMessage 预览，不是 AI 叙事）
     * - HISTORY 用途摘要仅注入当前请求上下文，默认不落库（AutoContextManager 内存 LRU）
     *
     * @param scope          记忆作用域
     * @param sourceId       来源 ID
     * @param threshold      触发阈值（WORKING 记忆数 ≥ 此值才执行）
     * @param compressCount  每次压缩的记忆条数
     * @return 是否执行了压缩
     */
    suspend fun summarizeAndCompress(
        scope: MemoryScope,
        sourceId: Long,
        threshold: Int = 5,
        compressCount: Int = 8
    ): Boolean {
        val workingCount = dao.countWorkingMemories(deviceId, scope, sourceId)
        if (workingCount < threshold) return false

        // 1. 获取最早的 WORKING 记忆
        val oldMemories = dao.getOldestWorkingMemories(deviceId, scope, sourceId, compressCount)
        if (oldMemories.isEmpty()) return false

        // 2. 拼接为对话文本
        val conversationText = oldMemories.joinToString("\n") { it.content }

        // 3. 获取已有记忆上下文（避免摘要重复提取）
        val memoryContext = buildMemoryContext(scope, sourceId, "", limit = 5)

        // 4. 生成摘要（统一摘要服务，MEMORY：五维叙事，不硬限字数）
        val summary = if (summaryProvider != null && summaryProvider.isSummarySupported()) {
            summaryProvider.summarize(conversationText, memoryContext, SummaryPurpose.MEMORY)
        } else null

        // 回退到本地规则摘要（五维骨架）
        var finalSummary = summary ?: buildLocalSummaryFallback(oldMemories)

        if (finalSummary.isBlank()) return false

        // 4.1 AI 核心记忆识别：解析【重要记忆】段，写入高 importance 稳定记忆
        //（仅 AI 摘要才有该段；本地回退摘要不解析）
        if (summary != null) {
            finalSummary = saveAiIdentifiedCoreMemories(summary, scope, sourceId)
        }

        // 5. 存为 EPISODIC 叙事摘要记忆（content + summary 双写，保证检索/UI 都能命中）
        addMemory(
            content = finalSummary,
            type = MemoryType.EPISODIC,
            scope = scope,
            sourceId = sourceId,
            importance = 0.55f,
            confidence = 0.7f,
            summary = finalSummary,
            tags = "conversation-summary",
            observedAt = System.currentTimeMillis()
        )

        // 6. 软删除已摘要的 WORKING 记忆
        val idsToDelete = oldMemories.map { it.id }
        dao.softDeleteByIds(idsToDelete)

        return true
    }

    /**
     * 从单轮对话增量识别核心记忆（每轮对话后异步调用，调用方负责节流）。
     *
     * 不依赖 WORKING 记忆条数阈值：直接把当前轮对话交给 AI，
     * 识别值得长期记住的事实/偏好/关系/事件，写入高 importance 稳定记忆。
     */
    suspend fun recognizeCoreMemories(
        conversationText: String,
        scope: MemoryScope,
        sourceId: Long
    ) {
        val provider = summaryProvider ?: return
        if (conversationText.isBlank()) return
        val memoryContext = buildMemoryContext(scope, sourceId, "", limit = 5)
        val summary = provider.identifyCoreMemories(conversationText, memoryContext) ?: return
        if (!summary.contains("【重要记忆】")) return
        saveAiIdentifiedCoreMemories(summary, scope, sourceId)
    }

    /**
     * 解析 AI 摘要中的「【重要记忆】」段，将重要事实/偏好/关系写入高 importance 稳定记忆，
     * 并从叙事摘要文本中剥离该段（叙事只保留五维正文）。
     *
     * 行格式：【重要记忆】类别|内容（类别 ∈ 事实/偏好/关系/事件）
     *
     * @return 剥离后的叙事摘要文本
     */
    private suspend fun saveAiIdentifiedCoreMemories(summary: String, scope: MemoryScope, sourceId: Long): String {
        val section = Regex("【重要记忆】([\\s\\S]*?)(?=【|$)").find(summary)?.groupValues?.get(1)?.trim()
        if (section.isNullOrBlank()) return summary

        section.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(3)
            .forEach { line ->
                val parts = line.split("|", limit = 2)
                val category = parts.getOrNull(0)?.trim().orEmpty()
                val content = parts.getOrNull(1)?.trim().orEmpty()
                if (content.length < 2) return@forEach

                val (type, importance) = when (category) {
                    "偏好" -> MemoryType.PREFERENCE to 0.85f
                    "关系" -> MemoryType.RELATIONSHIP to 0.85f
                    "事件" -> MemoryType.EPISODIC to 0.7f
                    else -> MemoryType.SEMANTIC to 0.85f
                }
                runCatching {
                    addMemory(
                        content = content,
                        type = type,
                        scope = scope,
                        sourceId = sourceId,
                        importance = importance,
                        confidence = 0.85f,
                        tags = "ai-identified-core",
                        observedAt = System.currentTimeMillis()
                    )
                }
            }

        // 剥离【重要记忆】段，只保留叙事正文
        return summary
            .replace(Regex("【重要记忆】([\\s\\S]*?)(?=【|$)"), "")
            .trim()
    }

    /**
     * 本地规则摘要回退（当 AI API 不可用时使用）。
     * 按「时间 / 事件 / 人物 / 驱动 / 情绪」骨架组织，不硬截到固定字数。
     */
    private fun buildLocalSummaryFallback(memories: List<MemoryRecord>): String {
        if (memories.isEmpty()) return ""

        val userMentions = mutableListOf<String>()
        val aiMentions = mutableListOf<String>()
        val earliest = memories.minOfOrNull { it.observedAt } ?: 0L
        val latest = memories.maxOfOrNull { it.observedAt } ?: 0L

        memories.forEach { mem ->
            val content = mem.content
            when {
                content.startsWith("用户:") ->
                    userMentions.add(content.removePrefix("用户:").trim())
                content.startsWith("AI:") ->
                    aiMentions.add(content.removePrefix("AI:").trim())
                else -> userMentions.add(content.trim())
            }
        }

        val timeSpan = when {
            earliest <= 0L || latest <= 0L -> "未明确"
            earliest == latest -> formatTimeAgo(System.currentTimeMillis() - latest)
            else -> {
                val spanMin = ((latest - earliest) / 60000L).coerceAtLeast(0)
                "约${spanMin}分钟跨度，最近 ${formatTimeAgo(System.currentTimeMillis() - latest)}"
            }
        }

        return buildString {
            appendLine("时间：$timeSpan；覆盖 ${memories.size} 条工作记忆")
            appendLine(
                "事件：" + if (userMentions.isNotEmpty()) {
                    userMentions.distinct().take(6).joinToString("；")
                } else "未明确"
            )
            appendLine("人物：用户与 AI 对话")
            appendLine(
                "驱动：" + if (aiMentions.isNotEmpty()) {
                    aiMentions.distinct().take(4).joinToString("；")
                } else "未明确"
            )
            append("情绪：未明确（本地回退摘要）")
        }.trim()
    }

    // ═══════════════════════════════════════════════════════════
    // 时间衰减评分
    // ═══════════════════════════════════════════════════════════

    /**
     * 计算记忆的衰减评分。
     *
     * 公式：
     *   rawScore = importance × (1 + log10(accessCount + 1) × 0.2)
     *   decayFactor = e^(-λ × daysSinceObserved)
     *   decayScore = rawScore × decayFactor
     *
     * 其中 λ = 0.05（默认），即约 14 天后衰减到 ~50%，约 46 天后衰减到 ~10%。
     */
    private fun decayScore(memory: MemoryRecord, now: Long): Float {
        val importance = memory.importance.coerceIn(0f, 1f)
        val accessBonus = 1f + kotlin.math.log10((memory.accessCount + 1).toFloat()) * 0.2f
        val rawScore = importance * accessBonus

        val ageMs = now - memory.observedAt
        val ageDays = (ageMs / 86_400_000f).coerceAtLeast(0f)
        val lambda = 0.05f
        val decayFactor = kotlin.math.exp(-lambda * ageDays)

        return rawScore * decayFactor
    }

    // ═══════════════════════════════════════════════════════════
    // 软删除与管理
    // ═══════════════════════════════════════════════════════════

    suspend fun softDelete(id: Long) {
        dao.softDelete(id)
    }

    suspend fun softDeleteByScopeAndSource(scope: MemoryScope, sourceId: Long, source: MemorySource) {
        dao.softDeleteByScopeAndSource(deviceId, scope, sourceId, source)
    }

    suspend fun hardDeleteByScope(scope: MemoryScope, sourceId: Long) {
        dao.hardDeleteByScope(deviceId, scope, sourceId)
    }

    suspend fun cleanupExpired() {
        dao.cleanupExpiredWorkingMemories()
    }

    suspend fun count(scope: MemoryScope, sourceId: Long): Int {
        return dao.countByScope(deviceId, scope, sourceId)
    }

    // ═══════════════════════════════════════════════════════════
    // 私有工具方法
    // ═══════════════════════════════════════════════════════════

    /** 简单中文分词（字符级 token） */
    private fun tokenize(text: String): List<String> {
        return text.split("""\s+""".toRegex())
            .flatMap { word ->
                // 对中文：按 2-gram 分词
                if (word.any { it in '\u4e00'..'\u9fff' }) {
                    word.windowed(2, 1).filter { it.length == 2 }
                } else {
                    listOf(word.lowercase())
                }
            }
            .filter { it.length >= 2 }
    }

    /** 大 bigram Jaccard 相似度（中文友好） */
    private fun jaccardSimilarityBigram(a: String, b: String): Float {
        if (a.length < 2 || b.length < 2) {
            return if (a == b) 1.0f else 0.0f
        }
        val bigrams1 = a.windowed(2).toSet()
        val bigrams2 = b.windowed(2).toSet()
        val intersection = bigrams1.intersect(bigrams2).size
        val union = bigrams1.union(bigrams2).size
        return if (union == 0) 0.0f else intersection.toFloat() / union
    }

    /** 关键词匹配 */
    private fun matchesAny(text: String, patterns: List<String>): Boolean {
        return patterns.any { text.contains(it) }
    }

    /** 人性化时间间隔 */
    private fun formatTimeAgo(deltaMs: Long): String {
        val seconds = deltaMs / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24
        return when {
            seconds < 60 -> "刚刚"
            minutes < 60 -> "${minutes}分钟前"
            hours < 24 -> "${hours}小时前"
            days < 7 -> "${days}天前"
            days < 30 -> "${days / 7}周前"
            days < 365 -> "${days / 30}个月前"
            else -> "${days / 365}年前"
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 提取关键词表
    // ═══════════════════════════════════════════════════════════

    companion object {
        /** 自我声明/事实关键词 */
        val SEMANTIC_PATTERNS = listOf(
            "我叫", "我是", "我来自", "我工作", "职业是", "我的", "我姓",
            "我住在", "我住", "我学", "专业是", "我是做", "我在",
            "年龄", "岁", "生日", "星座", "血型", "身高", "体重",
            "电话", "微信", "qq", "邮箱", "地址", "公司", "学校"
        )

        /** 偏好关键词（收紧版，避免过度提取） */
        val PREFERENCE_PATTERNS = listOf(
            "我喜欢", "我讨厌", "我爱吃", "我不爱吃", "我最爱", "我不喜欢",
            "我最讨厌", "我反感", "我厌恶", "我热衷", "我痴迷", "我感兴趣", "我没兴趣",
            "好吃", "难吃", "好看", "难看", "好听", "好玩", "无聊"
        )

        /** 情绪关键词 */
        val EMOTION_PATTERNS = listOf(
            "我很开心", "我很难过", "我很生气", "我很感动", "我很兴奋",
            "好开心", "好难过", "好生气", "好感动", "好失望",
            "好累", "好烦", "好爽", "好委屈", "好害怕", "好担心",
            "压力大", "心情不好", "心情很好", "情绪不好",
            "想哭", "哭了", "笑死", "笑哭了"
        )

        /** 事件关键词 */
        val EVENT_PATTERNS = listOf(
            "今天", "昨天", "明天", "上周", "下周", "周末", "放假", "考试",
            "出差", "旅行", "聚会", "约会", "面试", "入职", "离职", "搬家"
        )

        /** 关系关键词 */
        val RELATIONSHIP_PATTERNS = listOf(
            "你是我的", "你是我", "我们之间", "我觉得你", "你对我",
            "最好的朋友", "男朋友", "女朋友", "老公", "老婆", "宝贝",
            "我想你", "我爱你", "我喜欢你", "我离不开你"
        )

        /** 交互模式关键词 */
        val PROCEDURAL_PATTERNS = listOf(
            "你总是", "你每次都", "你从来不", "你一直", "你别再",
            "你应该", "你不要", "你能不能不"
        )
    }
}
