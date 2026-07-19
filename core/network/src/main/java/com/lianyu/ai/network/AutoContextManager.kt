package com.lianyu.ai.network

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.domain.AiOperationalMessages

/**
 * AutoContextManager — 自适应上下文管理器。
 *
 * 替代旧的"按消息条数截断"方案，改为基于 token 预算的自动管理：
 *
 * 1. **模型感知**：根据当前 API 配置的模型名，自动获取上下文窗口大小。
 * 2. **预算分配**：总预算 = 上下文窗口 - 输出预留 - 安全余量。
 *    分配优先级：系统提示词（固定）→ 记忆上下文（上限 20%）→ 历史消息（剩余）。
 * 3. **自动压缩**：当历史消息 token 超过分配预算时，自动触发 AI 摘要压缩，
 *    保留最近的消息 + 将旧消息压缩为连贯的叙事摘要。
 *    AI 摘要失败时自动降级为本地正则摘要。
 *
 * 线程安全：`build()` 是 suspend 函数，协程自然向上传递，无 runBlocking。
 * `aiSummarizer` 也是 suspend 类型，调用方用 viewModelScope.launch 即可。
 *
 * 用户无需手动配置任何参数——一切自动完成。
 *
 * @param aiSummarizer 可选的 AI 摘要函数（suspend）。传入旧消息列表，返回连贯的叙事摘要文本。
 *                     为 null 时使用本地正则摘要作为降级方案。
 */
class AutoContextManager(
    private val aiSummarizer: (suspend (messages: List<ChatMessage>, companionNameMap: Map<Long, String>, memoryContext: String) -> String?)? = null
) {

    /**
     * AI 摘要 LRU 缓存：避免对同一批消息重复调用 API。
     *
     * 使用 synchronized LinkedHashMap + removeEldestEntry 实现 LRU 淘汰，
 * 而非暴力 clear()，避免高并发下缓存抖动。
     * 上限 20 条，超过时淘汰最久未访问的条目。
     */
    private val summaryCache: MutableMap<String, String> =
        java.util.Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>?): Boolean {
                return size > 20
            }
        })

    /**
     * 上下文配置：描述当前请求的约束条件。
     *
     * @param model 模型名（用于查询上下文窗口大小）
     * @param provider API 提供商（模型名无法识别时的回退）
     * @param maxOutputTokens 预留给 AI 输出的 token 数（默认 4096）
     * @param safetyMargin 安全余量 token 数（默认 512，防止估算误差导致溢出）
     */
    data class ContextConfig(
        val model: String,
        val provider: ApiProvider? = null,
        val maxOutputTokens: Int = 4096,
        val safetyMargin: Int = 512
    )

    /**
     * 构建发送给 API 的消息列表。
     *
     * @param history 已排序的历史消息（按时间正序）
     * @param systemPrompt 系统提示词（已构建完成）
     * @param memoryContext 记忆上下文文本
     * @param lastUserMessage 最后一条用户消息（用于 fallback）
     * @param companionNameMap 角色 ID → 名称映射（用于压缩摘要）
     * @param config 上下文配置
     * @return 组装好的 Message 列表
     */
    suspend fun build(
        history: List<ChatMessage>,
        systemPrompt: String,
        memoryContext: String,
        lastUserMessage: String,
        companionNameMap: Map<Long, String> = emptyMap(),
        config: ContextConfig
    ): List<Message> {
        // 1. 获取模型上下文窗口大小
        val contextWindow = ModelContextRegistry.getContextWindow(config.model, config.provider)

        // 2. 计算总预算
        val totalBudget = contextWindow - config.maxOutputTokens - config.safetyMargin
        if (totalBudget <= 0) {
            SecureLog.w("AutoContextManager", "Budget too small: window=$contextWindow, output=${config.maxOutputTokens}, margin=${config.safetyMargin}")
            // 极端情况：只发系统提示词 + 最后一条用户消息
            return buildMinimalMessages(systemPrompt, history, lastUserMessage)
        }

        // 3. 系统提示词（固定优先级，不压缩）
        val systemTokens = TokenEstimator.estimate(systemPrompt)
        val systemPromptHash = systemPrompt.hashCode()
        val systemMessage = Message("system", systemPrompt)
        var remainingBudget = totalBudget - systemTokens

        val messages = mutableListOf(systemMessage)

        if (remainingBudget <= 0) {
            SecureLog.w("AutoContextManager", "System prompt alone exceeds budget ($systemTokens > $totalBudget)")
            // 系统提示词太长，只发系统 + 最后用户消息
            appendLastUserMessage(messages, history, lastUserMessage)
            return messages
        }

        // 4. 记忆上下文（动态预算分配）
        // 不再固定 20% 上限，而是根据实际 token 数动态分配：
        // - 如果记忆上下文较小（≤ 剩余预算的 30%），完整放入
        // - 如果记忆上下文较大（> 剩余预算的 30%），截断到 30% 预算
        //   30% 是记忆与历史之间的平衡点，确保历史消息有足够空间
        val memoryBudget = (remainingBudget * 0.3).toInt()
        val memoryTokens = TokenEstimator.estimate(memoryContext)
        if (memoryContext.isNotBlank() && memoryTokens <= memoryBudget) {
            // 记忆上下文可以完整放入
            messages.add(Message("system", memoryContext))
            remainingBudget -= memoryTokens
        } else if (memoryContext.isNotBlank()) {
            // 记忆上下文过长，截断到预算内
            val truncatedMemory = truncateToTokens(memoryContext, memoryBudget)
            if (truncatedMemory.isNotBlank()) {
                messages.add(Message("system", truncatedMemory))
                remainingBudget -= memoryBudget
                SecureLog.d("AutoContextManager", "Memory context truncated: $memoryTokens -> $memoryBudget tokens")
            }
        }

        // 5. 历史消息（使用剩余预算）
        val historyBudget = remainingBudget
        val historyMessages = buildHistoryMessages(history, historyBudget, companionNameMap, memoryContext, systemPromptHash)
        messages.addAll(historyMessages)

        // 6. 确保最后一条是 user 消息
        appendLastUserMessage(messages, history, lastUserMessage)

        SecureLog.api("CONTEXT", "AutoContext: window=$contextWindow, budget=$totalBudget, system=$systemTokens, " +
                "history=${historyMessages.size} msgs, total=${messages.size} msgs")

        return messages
    }

    /**
     * 在 token 预算内构建历史消息列表。
     * 如果全部消息超出预算，触发自动压缩。
     *
     * @param systemPromptHash 系统提示词哈希，参与摘要缓存 key，
     *   确保角色设定变更时摘要重新生成。
     */
    private suspend fun buildHistoryMessages(
        history: List<ChatMessage>,
        budgetTokens: Int,
        companionNameMap: Map<Long, String>,
        memoryContext: String,
        systemPromptHash: Int = 0
    ): List<Message> {
        if (history.isEmpty()) return emptyList()

        // 过滤：空 assistant、运营错误文案（曾误入库的配置/网络提示不得回灌模型）
        val filtered = history.filterNot { msg ->
            val text = msg.content.replace("\u200B", "").trim()
            text.isBlank() || AiOperationalMessages.isOperationalContent(text)
        }
        if (filtered.isEmpty()) return emptyList()

        // 估算全部历史消息的 token 数
        // 工具结果（[工具调用结果]）映射为 user，禁止当 assistant（否则模型会自言自语）
        val allMessages = filtered.map { msg ->
            val content = formatMessageContent(msg)
            val role = when {
                content.startsWith("[工具调用结果]") -> "user"
                msg.isFromUser -> "user"
                else -> "assistant"
            }
            Message(role = role, content = content)
        }
        val totalTokens = TokenEstimator.estimate(allMessages)

        if (totalTokens <= budgetTokens) {
            // 全部放入，无需压缩
            return allMessages
        }

        // 需要压缩：从最近的开始保留，直到用完预算
        val keepMessages = mutableListOf<Message>()
        var usedTokens = 0

        // 从最新往回取，保证最近的对话完整
        for (msg in allMessages.reversed()) {
            val msgTokens = TokenEstimator.estimate(listOf(msg))
            if (usedTokens + msgTokens > budgetTokens) break
            keepMessages.add(0, msg)
            usedTokens += msgTokens
        }

        // 被压缩的旧消息
        val compressedCount = allMessages.size - keepMessages.size
        if (compressedCount > 0) {
            val oldChatMessages = filtered.dropLast(keepMessages.size)
            val summary = summarizeMessages(oldChatMessages, companionNameMap, memoryContext, systemPromptHash)
            if (summary.isNotBlank()) {
                val summaryMessage = Message("system", summary)
                val summaryTokens = TokenEstimator.estimate(listOf(summaryMessage))
                // 摘要也要占预算，检查是否还有空间
                if (usedTokens + summaryTokens <= budgetTokens) {
                    SecureLog.api("CONTEXT", "Auto-compressed $compressedCount old messages into summary (${summary.length} chars)")
                    return listOf(summaryMessage) + keepMessages
                } else {
                    // 摘要放不下，只保留最近消息
                    SecureLog.w("AutoContextManager", "Summary too large to fit, dropping compression")
                    return keepMessages
                }
            }
        }

        return keepMessages
    }

    /**
     * 摘要消息列表：优先使用 AI 摘要，失败时降级为本地正则摘要。
     * 带缓存：同一批消息不会重复调用 AI。
     *
     * 缓存 key 包含：lastMsg.id + content.hash + msgCount + memoryContext.hash + systemPromptHash
     * - memoryContext 参与摘要 prompt 生成，其变化时摘要需重新生成
     * - systemPromptHash 确保角色设定/系统提示变更时摘要重新生成
     */
    private suspend fun summarizeMessages(
        messages: List<ChatMessage>,
        companionNameMap: Map<Long, String>,
        memoryContext: String,
        systemPromptHash: Int = 0
    ): String {
        if (messages.isEmpty()) return ""

        // 构建缓存 key：最后一条消息的 ID + 内容哈希 + 消息数量 + memoryContext 哈希 + systemPrompt 哈希
        // memoryContext 参与摘要 prompt 生成，其变化时摘要需重新生成
        // systemPromptHash 确保角色设定变更时摘要重新生成
        val lastMsg = messages.last()
        val memHash = memoryContext.hashCode()
        val cacheKey = "${lastMsg.id ?: "noid"}_${lastMsg.content.hashCode()}_${messages.size}_${memHash}_${systemPromptHash}"

        // 先查缓存
        summaryCache[cacheKey]?.let {
            SecureLog.d("AutoContextManager", "Summary cache hit: $cacheKey")
            return it
        }

        // 尝试 AI 摘要
        if (aiSummarizer != null) {
            try {
                val aiSummary = aiSummarizer(messages, companionNameMap, memoryContext)
                if (!aiSummary.isNullOrBlank()) {
                    // 格式化为系统消息
                    val formatted = "=== 早期对话摘要（AI生成，已压缩${messages.size}条消息） ===\n$aiSummary"
                    summaryCache[cacheKey] = formatted
                    SecureLog.api("CONTEXT", "AI summary generated: ${aiSummary.length} chars")
                    return formatted
                }
            } catch (e: Exception) {
                SecureLog.w("AutoContextManager", "AI summary failed, falling back to local: ${e.message}")
            }
        }

        // 降级：本地正则摘要
        val localSummary = AiContextTools.buildLocalSummary(messages, companionNameMap, memoryContext)
        if (localSummary.isNotBlank()) {
            summaryCache[cacheKey] = localSummary
        }
        return localSummary
    }

    /** 格式化消息内容（处理表情包等特殊格式） */
    private fun formatMessageContent(msg: ChatMessage): String {
        return if (msg.isFromUser && msg.content.startsWith("[") && msg.content.endsWith("]")) {
            val inner = msg.content.removeSurrounding("[", "]")
            val label = when {
                inner.startsWith("sticker_", ignoreCase = true) -> "表情包"
                inner.length > 20 -> "表情包"
                else -> inner
            }
            "用户发送了一个表情包：[$label]"
        } else {
            msg.content
        }
    }

    /** 将文本截断到指定 token 预算内 */
    private fun truncateToTokens(text: String, maxTokens: Int): String {
        if (text.isBlank() || maxTokens <= 0) return ""
        val estimated = TokenEstimator.estimate(text)
        if (estimated <= maxTokens) return text

        // 按比例截断（保守估算）
        val ratio = maxTokens.toFloat() / estimated
        val targetLength = (text.length * ratio * 0.9).toInt().coerceAtLeast(1) // 0.9 留余量
        return text.take(targetLength)
    }

    /** 构建最小消息列表（极端情况） */
    private fun buildMinimalMessages(
        systemPrompt: String,
        history: List<ChatMessage>,
        lastUserMessage: String
    ): List<Message> {
        val messages = mutableListOf(Message("system", systemPrompt))
        appendLastUserMessage(messages, history, lastUserMessage)
        return messages
    }

    /** 确保消息列表以 user 消息结尾 */
    private fun appendLastUserMessage(
        messages: MutableList<Message>,
        history: List<ChatMessage>,
        lastUserMessage: String
    ) {
        val lastMsg = messages.lastOrNull()
        val isLastFromUser = lastMsg?.role == "user"
        if (!isLastFromUser && lastUserMessage.isNotBlank()) {
            messages.add(Message("user", lastUserMessage))
        }
    }
}
