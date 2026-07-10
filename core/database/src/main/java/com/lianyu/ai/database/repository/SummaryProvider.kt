package com.lianyu.ai.database.repository

/**
 * 摘要用途 —— 区分不同场景的摘要需求，共享同一服务但使用不同参数。
 *
 * - [HISTORY]：实时上下文压缩，将旧对话历史压缩为叙事摘要注入当前请求。
 *   200-400 字，第三人称，保留关键事实/约定/情感/关系进展。
 * - [MEMORY]：记忆系统压缩，将 WORKING 记忆聚合为 EPISODIC 摘要。
 *   150 字以内，提取关键话题和新信息。
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
     * 将对话文本压缩为摘要（记忆用途，150 字以内）。
     *
     * 等价于 `summarize(conversationText, memoryContext, SummaryPurpose.MEMORY)`。
     *
     * @param conversationText 已格式化的对话文本（如 "用户: xxx\nAI: yyy\n..."）
     * @param memoryContext    当前已有的记忆上下文（避免重复提取）
     * @return 压缩后的摘要文本，失败时返回 null（调用方回退到本地摘要）
     */
    suspend fun summarize(conversationText: String, memoryContext: String = ""): String? {
        return summarize(conversationText, memoryContext, SummaryPurpose.MEMORY)
    }

    /**
     * 将对话文本压缩为摘要，按 [purpose] 区分参数和模板。
     *
     * @param conversationText 已格式化的对话文本
     * @param memoryContext    当前已有的记忆上下文（避免重复提取）
     * @param purpose          摘要用途（HISTORY=200-400字叙事 / MEMORY=150字精简）
     * @return 压缩后的摘要文本，失败时返回 null（调用方回退到本地摘要）
     */
    suspend fun summarize(
        conversationText: String,
        memoryContext: String,
        purpose: SummaryPurpose
    ): String?
}
