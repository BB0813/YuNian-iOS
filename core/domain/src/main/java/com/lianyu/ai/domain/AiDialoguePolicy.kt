package com.lianyu.ai.domain

/**
 * AI 对话数据策略（架构层，非补丁）。
 *
 * 职责边界：
 * 1. 区分「对话内容」与「运营/配置错误」——后者不得进入消息库，也不得作为 assistant 历史回灌模型。
 * 2. 规范 [AiChatMessage] 角色语义，避免工具结果被当成 AI 自言自语。
 * 3. 在发送前清洗历史，保证模型看到的是合法 user/assistant 交替对话。
 */

/** 消息在 AI 协议中的角色 */
enum class AiMessageRole {
    USER,
    ASSISTANT,
    /** 工具执行结果；序列化时优先映射为 user 侧结构化内容（兼容无 tool 协议的提供商） */
    TOOL,
    SYSTEM
}

/**
 * 运营错误 / 系统提示识别。
 * 这些字符串若曾误写入 DB，必须在构建 AI 上下文时剔除。
 */
object AiOperationalMessages {
    private val exactOperational = setOf(
        "请先配置API：我 → API设置 → 添加密钥",
        "请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。",
        "请先配置并启用可用的API。",
        "模型名未配置，请在「API设置」中重新测试连接以自动选择模型。",
        "模型名未配置。",
        "系统正在加载伴侣信息，请稍后再试",
        "抱歉，找不到角色信息。",
        "抱歉，我无法继续这个话题。"
    )

    private val prefixOperational = listOf(
        "请先配置API",
        "请先配置并启用可用的API",
        "模型名未配置",
        "系统正在加载",
        "API返回空内容",
        "网络连接超时",
        "API认证失败",
        "请求过于频繁",
        "图片识别",
        "视觉识别功能已关闭",
        "API密钥为空",
        "API地址为空",
        "当前模型不支持视觉",
        "账号已被封禁",
        "内容违规",
        "内容已拦截",
        "安全检查异常",
        "消息队列已满",
        "消息发送失败",
        "回复被打断",
        "发送失败"
    )

    private const val TOAST_PREFIX = "[TOAST]"

    fun stripToastPrefix(raw: String): String =
        raw.removePrefix(TOAST_PREFIX).trim()

    fun isToastPrefixed(raw: String): Boolean =
        raw.startsWith(TOAST_PREFIX)

    /** 是否为不应入库、不应回灌模型的运营/错误文案 */
    fun isOperationalContent(content: String): Boolean {
        val text = stripToastPrefix(content).trim()
        if (text.isEmpty()) return false
        if (text in exactOperational) return true
        if (prefixOperational.any { text.startsWith(it) }) return true
        // 工具失败提示也不应作为角色台词
        if (text.startsWith("工具执行失败") || text.startsWith("工具执行超时")) return true
        return false
    }

    /** 从 AI 返回内容中提取应 Toast 的文案；非运营错误返回 null */
    fun asToastMessage(content: String): String? {
        val text = stripToastPrefix(content)
        if (text.isBlank()) return null
        return if (isToastPrefixed(content) || isOperationalContent(text)) text else null
    }
}

/**
 * 对话历史清洗与角色规范化。
 */
object AiDialogueHistoryPolicy {

    /**
     * 清洗供 AI 使用的历史：
     * - 去掉运营错误 / 空 assistant
     * - 去掉纯系统污染
     * - 合并连续同角色（保留时间序）
     * - 确保最后一条尽量为 user（若仅有 assistant 则保留，由上层决定是否调用）
     */
    fun sanitizeForModel(history: List<AiChatMessage>): List<AiChatMessage> {
        if (history.isEmpty()) return emptyList()

        val filtered = history
            .asSequence()
            .map { normalizeRole(it) }
            .filter { msg ->
                val content = msg.content.replace("\u200B", "").trim()
                if (content.isEmpty()) return@filter false
                if (AiOperationalMessages.isOperationalContent(content)) return@filter false
                // 工具结果前缀消息仅在 TOOL 角色下保留
                if (content.startsWith("[工具调用结果]") && msg.role != AiMessageRole.TOOL) {
                    return@filter msg.role == AiMessageRole.USER
                }
                true
            }
            .toList()

        if (filtered.isEmpty()) return emptyList()

        // 合并连续同角色短消息，降低模型“自言自语”概率
        val merged = mutableListOf<AiChatMessage>()
        for (msg in filtered) {
            val last = merged.lastOrNull()
            if (last != null && last.effectiveRole() == msg.effectiveRole() && last.effectiveRole() != AiMessageRole.TOOL) {
                merged[merged.lastIndex] = last.copy(
                    content = last.content.trimEnd() + "\n" + msg.content.trimStart(),
                    timestamp = maxOf(last.timestamp, msg.timestamp)
                )
            } else {
                merged.add(msg)
            }
        }
        return merged
    }

    fun normalizeRole(msg: AiChatMessage): AiChatMessage {
        val content = msg.content
        val role = when {
            msg.role != null -> msg.role
            content.startsWith("[工具调用结果]") -> AiMessageRole.TOOL
            msg.isFromUser -> AiMessageRole.USER
            else -> AiMessageRole.ASSISTANT
        }
        return msg.copy(
            role = role,
            isFromUser = role == AiMessageRole.USER || role == AiMessageRole.TOOL
        )
    }

    private fun AiChatMessage.effectiveRole(): AiMessageRole =
        role ?: if (isFromUser) AiMessageRole.USER else AiMessageRole.ASSISTANT

    /**
     * 构建工具结果消息：必须标记为 TOOL，序列化时映射为 user 侧结构化内容，
     * 绝不能写成 assistant（否则模型会把工具输出当成自己说过的话继续自言自语）。
     */
    fun toolResultMessage(
        toolName: String,
        result: String,
        companionId: Long = 0L,
        timestamp: Long = System.currentTimeMillis()
    ): AiChatMessage = AiChatMessage(
        isFromUser = true,
        content = "[工具调用结果] $toolName:\n$result",
        timestamp = timestamp,
        type = AiMessageType.TEXT,
        companionId = companionId,
        role = AiMessageRole.TOOL,
        toolName = toolName
    )
}
