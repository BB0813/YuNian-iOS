package com.lianyu.ai.database.repository

/**
 * 对话摘要提供者接口（Phase 4）。
 *
 * 定义在 core:database 中，避免 core:database → core:network 的依赖违规。
 * 实现类（SummaryService）位于 core:network，通过 app 模块的 ServiceRegistry 注入。
 *
 * 用途：当 WORKING 记忆积累到阈值时，调用 AI API 将多轮对话压缩为一条
 * EPISODIC 摘要记忆，减少上下文膨胀同时保留关键信息。
 */
interface SummaryProvider {

    /** 是否支持 AI 摘要（取决于 API 配置是否可用） */
    fun isSummarySupported(): Boolean

    /**
     * 将对话文本压缩为摘要。
     *
     * @param conversationText 已格式化的对话文本（如 "用户: xxx\nAI: yyy\n..."）
     * @param memoryContext    当前已有的记忆上下文（避免重复提取）
     * @return 压缩后的摘要文本，失败时返回 null（调用方回退到本地摘要）
     */
    suspend fun summarize(conversationText: String, memoryContext: String = ""): String?
}
