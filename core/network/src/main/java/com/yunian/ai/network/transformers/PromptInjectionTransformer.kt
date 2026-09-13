package com.yunian.ai.network.transformers

import com.yunian.ai.domain.EntryRole
import com.yunian.ai.domain.InjectionPosition
import com.yunian.ai.domain.TriggeredEntry
import com.yunian.ai.network.Message
import com.yunian.ai.network.transformers.TransformerContext

/**
 * 世界书（Lorebook）提示词注入转换器
 *
 * 对齐 rikkahub 的 PromptInjectionTransformer 设计：
 * - 纯内容注入，不携带内部元数据（避免污染提示词）
 * - BEFORE/AFTER_SYSTEM_PROMPT 合并进系统提示词文本，而非插入独立 system 消息
 * - TOP_OF_CHAT / BOTTOM_OF_CHAT / AT_DEPTH 按条目 role 注入 user/assistant 消息，同角色合并
 * - AT_DEPTH 按注入深度分组，深度大的先插入（避免索引漂移）
 * - 插入位置避开 user -> assistant(tool) 等工具调用链，防止部分供应商报错
 */
class PromptInjectionTransformer : MessageTransformer {
    override val id = "prompt_injection"
    override val isInput = true
    override val priority = 100

    companion object {
        const val MAX_INJECTION_CHARS = 8000
        const val MAX_TOTAL_INJECTION_CHARS = 20000
    }

    override suspend fun transform(
        context: TransformerContext,
        messages: List<Message>
    ): List<Message> {
        val lorebookProvider = context.lorebookProvider ?: return messages
        if (context.sessionId <= 0) return messages

        // 取最近 50 条消息作为匹配上下文；约定 index 0 为最新（reversed 后）
        val recentMessages = messages
            .takeLast(50)
            .map { uiMsg ->
                com.yunian.ai.domain.ContextMessage(
                    role = when (uiMsg.role) {
                        "user" -> "user"
                        "assistant" -> "assistant"
                        "system" -> "system"
                        "tool" -> "assistant"
                        else -> "user"
                    },
                    content = uiMsg.content ?: "",
                    timestamp = uiMsg.timestamp ?: System.currentTimeMillis()
                )
            }
            .reversed()

        val triggeredEntries = lorebookProvider.getTriggeredEntries(context.sessionId, recentMessages)
        if (triggeredEntries.isEmpty()) return messages

        // 已按 priority 降序排列，这里保持顺序并按注入位置分组
        val byPosition = triggeredEntries.groupBy { it.entry.injectionPosition }

        var result = messages.toMutableList()
        var totalInjectedChars = 0

        // 1. 系统提示词之前/之后：合并进系统消息文本（无系统消息则新建）
        val beforeSystem = byPosition[InjectionPosition.BEFORE_SYSTEM_PROMPT].orEmpty()
        val afterSystem = byPosition[InjectionPosition.AFTER_SYSTEM_PROMPT].orEmpty()
        if (beforeSystem.isNotEmpty() || afterSystem.isNotEmpty()) {
            val beforeContent = buildInjectionContent(beforeSystem, totalInjectedChars)
            val afterContent = buildInjectionContent(afterSystem, totalInjectedChars + beforeContent.length)
            val combined = listOf(beforeContent, afterContent).filter { it.isNotBlank() }.joinToString("\n")
            if (combined.isNotBlank() && totalInjectedChars + combined.length <= MAX_TOTAL_INJECTION_CHARS) {
                val systemIndex = result.indexOfFirst { it.role == "system" }
                if (systemIndex >= 0) {
                    val original = result[systemIndex].content ?: ""
                    val newText = buildString {
                        if (beforeContent.isNotBlank()) {
                            append(beforeContent)
                            append("\n\n")
                        }
                        append(original)
                        if (afterContent.isNotBlank()) {
                            append("\n\n")
                            append(afterContent)
                        }
                    }
                    result[systemIndex] = result[systemIndex].copy(content = newText)
                } else {
                    result.add(0, Message("system", combined))
                }
                totalInjectedChars += combined.length
            }
        }

        // 2. 对话历史顶部：第一条非 system 消息之前
        val topOfChat = byPosition[InjectionPosition.TOP_OF_CHAT].orEmpty()
        if (topOfChat.isNotEmpty()) {
            val injectionMessages = buildRoleMessages(topOfChat, totalInjectedChars)
            val chars = injectionMessages.sumOf { it.content?.length ?: 0 }
            if (injectionMessages.isNotEmpty() && totalInjectedChars + chars <= MAX_TOTAL_INJECTION_CHARS) {
                val insertIndex = findSafeInsertIndex(result, findFirstNonSystemIndex(result))
                result.addAll(insertIndex, injectionMessages)
                totalInjectedChars += chars
            }
        }

        // 3. 对话历史底部：最后一条消息之前（当前用户输入之前）
        val bottomOfChat = byPosition[InjectionPosition.BOTTOM_OF_CHAT].orEmpty()
        if (bottomOfChat.isNotEmpty()) {
            val injectionMessages = buildRoleMessages(bottomOfChat, totalInjectedChars)
            val chars = injectionMessages.sumOf { it.content?.length ?: 0 }
            if (injectionMessages.isNotEmpty() && totalInjectedChars + chars <= MAX_TOTAL_INJECTION_CHARS) {
                val insertIndex = findSafeInsertIndex(result, (result.size - 1).coerceAtLeast(0))
                result.addAll(insertIndex, injectionMessages)
                totalInjectedChars += chars
            }
        }

        // 4. 指定深度：从最新消息往前数第 depth 条之前；按深度降序处理，避免插入后索引漂移
        val atDepth = byPosition[InjectionPosition.AT_DEPTH].orEmpty()
        if (atDepth.isNotEmpty()) {
            atDepth.groupBy { it.entry.injectDepth ?: 4 }.toSortedMap(compareByDescending { it })
                .forEach { (depth, entries) ->
                    val consumed = totalInjectedChars
                    val injectionMessages = buildRoleMessages(entries, consumed)
                    val chars = injectionMessages.sumOf { it.content?.length ?: 0 }
                    if (injectionMessages.isNotEmpty() && consumed + chars <= MAX_TOTAL_INJECTION_CHARS) {
                        // depth=1 表示最后一条消息之前，depth=2 表示倒数第二条之前...
                        val insertIndex = findSafeInsertIndex(
                            result,
                            (result.size - depth.coerceAtLeast(1)).coerceIn(0, result.size)
                        )
                        result.addAll(insertIndex, injectionMessages)
                        totalInjectedChars += chars
                    }
                }
        }

        return result
    }

    /**
     * 按优先级顺序拼接条目纯内容（不携带任何内部元数据）
     * 返回空字符串表示全部条目超出单组字符上限
     */
    private fun buildInjectionContent(entries: List<TriggeredEntry>, consumedChars: Int): String {
        if (entries.isEmpty()) return ""
        val builder = StringBuilder()
        var totalChars = 0
        for (triggered in entries) {
            val content = triggered.entry.content.trim()
            if (content.isEmpty()) continue
            if (totalChars + content.length > MAX_INJECTION_CHARS) break
            if (builder.isNotEmpty()) builder.append("\n")
            builder.append(content)
            totalChars += content.length
        }
        if (builder.isEmpty() || consumedChars + totalChars > MAX_TOTAL_INJECTION_CHARS) return ""
        return builder.toString()
    }

    /**
     * 将条目按 role 合并为注入消息：USER/ASSISTANT 各自合并成一条，SYSTEM 归入 USER
     */
    private fun buildRoleMessages(entries: List<TriggeredEntry>, consumedChars: Int): List<Message> {
        val contentByRole = mutableMapOf<String, StringBuilder>()
        var totalChars = 0
        for (triggered in entries) {
            val content = triggered.entry.content.trim()
            if (content.isEmpty()) continue
            if (totalChars + content.length > MAX_INJECTION_CHARS) break
            val role = when (triggered.entry.role) {
                EntryRole.ASSISTANT -> "assistant"
                else -> "user"
            }
            val builder = contentByRole.getOrPut(role) { StringBuilder() }
            if (builder.isNotEmpty()) builder.append("\n")
            builder.append(content)
            totalChars += content.length
        }
        if (contentByRole.isEmpty() || consumedChars + totalChars > MAX_TOTAL_INJECTION_CHARS) return emptyList()
        // user 在前，assistant 在后，保证对话顺序自然
        return listOf("user", "assistant").mapNotNull { role ->
            contentByRole[role]?.toString()?.takeIf { it.isNotBlank() }?.let { Message(role, it) }
        }
    }

    private fun findFirstNonSystemIndex(messages: List<Message>): Int {
        return messages.indexOfFirst { it.role != "system" }.let { if (it >= 0) it else messages.size }
    }

    private fun findSafeInsertIndex(messages: List<Message>, targetIndex: Int): Int {
        var idx = targetIndex.coerceIn(0, messages.size)

        while (idx > 0 && idx < messages.size) {
            val prev = messages[idx - 1]
            val curr = messages[idx]

            // 不能插进 user -> assistant(tool_calls) 之间（deepseek 等要求 user 后紧跟带工具的 assistant）
            if (prev.role == "user" && curr.role == "assistant" && curr.tool_calls?.isNotEmpty() == true) {
                idx--
                continue
            }

            if (prev.role == "assistant" && prev.tool_calls?.isNotEmpty() == true && curr.role == "tool") {
                idx--
                continue
            }

            if (prev.role == "tool" && curr.role == "assistant") {
                idx--
                continue
            }

            if (prev.role == "assistant" && curr.role == "assistant") {
                idx--
                continue
            }

            break
        }

        return idx
    }
}
