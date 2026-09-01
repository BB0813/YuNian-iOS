package com.lianyu.ai.database.repository

/**
 * 摘要用途 —— 区分不同场景的摘要需求，共享同一服务但使用不同参数。
 *
 * - [HISTORY]：实时上下文压缩，将旧对话历史整理为叙事摘要注入当前请求。
 *   按「时间 / 事件 / 人物 / 驱动 / 情绪」组织，不硬限字数。
 * - [MEMORY]：记忆系统压缩，将 WORKING 记忆聚合为 EPISODIC 叙事摘要。
 *   同样五维结构，侧重新信息与可沉淀事实，不硬限字数。
 */
enum class SummaryPurpose {
    HISTORY,
    MEMORY
}

/**
 * 对话摘要提供者接口（Phase 4）。
 *
 * 定义在 core:database 中，避免 core:database → core:network 的依赖违规。
 * 实现类（SummaryService）位于 core:network，通过 app 模块的 ServiceRegistry 注入。
 *
 * 统一摘要服务：同时服务于 AutoContextManager（历史压缩）和
 * UnifiedMemoryRepository（记忆压缩），通过 [SummaryPurpose] 区分参数和模板，
 * 消除两套独立摘要系统的语义不一致和维护冗余。
 *
 * 用途：
 * - [SummaryPurpose.HISTORY]：AutoContextManager 历史消息超预算时压缩
 * - [SummaryPurpose.MEMORY]：WORKING 记忆积累到阈值时压缩为 EPISODIC 摘要
 */
interface SummaryProvider {

    /** 是否支持 AI 摘要（取决于 API 配置是否可用） */
    fun isSummarySupported(): Boolean

    /**
     * 将对话文本整理为叙事摘要（记忆用途，五维结构，不硬限字数）。
     *
     * 等价于 `summarize(conversationText, memoryContext, SummaryPurpose.MEMORY)`。
     *
     * @param conversationText 已格式化的对话文本（如 "用户: xxx\nAI: yyy\n..."）
     * @param memoryContext    当前已有的记忆上下文（避免重复提取）
     * @return 叙事摘要文本，失败时返回 null（调用方回退到本地摘要）
     */
    suspend fun summarize(conversationText: String, memoryContext: String = ""): String? {
        return summarize(conversationText, memoryContext, SummaryPurpose.MEMORY)
    }

    /**
     * 将对话文本整理为叙事摘要，按 [purpose] 区分参数和模板。
     *
     * @param conversationText 已格式化的对话文本
     * @param memoryContext    当前已有的记忆上下文（避免重复提取）
     * @param purpose          摘要用途（HISTORY=上下文叙事 / MEMORY=可沉淀叙事）
     * @return 叙事摘要文本，失败时返回 null（调用方回退到本地摘要）
     */
    suspend fun summarize(
        conversationText: String,
        memoryContext: String,
        purpose: SummaryPurpose
    ): String?

    /**
     * 从单轮/多轮对话中识别值得长期记住的核心记忆（重要事实、用户偏好、人物关系、重要事件）。
     *
     * 与 [summarize]（五维叙事摘要）不同：本方法只输出「【重要记忆】类别|内容」行，
     * 由记忆系统解析后写入高 importance 稳定记忆。不依赖 WORKING 记忆条数阈值，
     * 适合每轮对话后增量识别。
     *
     * @param conversationText 对话文本（如 "用户: xxx\nAI: yyy"）
     * @param memoryContext    已有记忆上下文（避免重复收录）
     * @return 含【重要记忆】段的文本；无收录内容或失败时返回 null
     */
    suspend fun identifyCoreMemories(conversationText: String, memoryContext: String = ""): String? = null
}
