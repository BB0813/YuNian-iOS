package com.lianyu.ai.network

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.model.ApiProvider

/**
 * ModelContextRegistry — 模型上下文窗口注册表。
 *
 * 根据模型名称（或 provider）返回该模型的上下文窗口大小（token 数）。
 * 用于 AutoContextManager 的 token 预算计算。
 *
 * 数据来源：各模型官方文档（截至 2025-01）。
 * 未识别的模型返回安全默认值 32K。
 */
object ModelContextRegistry {

    private const val DEFAULT_CONTEXT_WINDOW = 32_000

    // 模型名片段 → 上下文窗口大小（token）
    // 按前缀匹配，更具体的模式优先
    private val modelPatterns = listOf(
        // === Gemini 系列 ===
        "gemini-1.5-pro" to 2_097_152,      // 2M
        "gemini-2.5-pro" to 2_097_152,      // 2M
        "gemini-1.5-flash" to 1_048_576,   // 1M
        "gemini-2.5-flash" to 1_048_576,   // 1M
        "gemini-2.0-flash" to 1_048_576,   // 1M
        "gemini" to 1_048_576,             // 1M fallback

        // === Claude 系列 ===
        "claude-3-5-sonnet" to 200_000,
        "claude-3-5-haiku" to 200_000,
        "claude-3-7-sonnet" to 200_000,
        "claude-3-opus" to 200_000,
        "claude-sonnet-4" to 200_000,
        "claude-opus-4" to 200_000,
        "claude" to 200_000,

        // === GPT 系列 ===
        "gpt-4o-mini" to 128_000,
        "gpt-4o" to 128_000,
        "gpt-4-turbo" to 128_000,
        "gpt-4.1" to 1_048_576,
        "o1" to 200_000,
        "o3" to 200_000,
        "o4-mini" to 200_000,
        "gpt-4" to 8_192,
        "gpt-3.5" to 16_385,

        // === DeepSeek 系列 ===
        "deepseek-v3" to 64_000,
        "deepseek-v4" to 128_000,
        "deepseek-r1" to 64_000,
        "deepseek" to 64_000,

        // === Qwen / 通义千问 ===
        "qwen-max" to 32_768,
        "qwen-plus" to 131_072,
        "qwen-turbo" to 1_000_000,
        "qwen2.5" to 131_072,
        "qwen3" to 131_072,
        "qwen" to 131_072,

        // === Kimi ===
        "kimi-k2" to 131_072,
        "kimi" to 131_072,
        "moonshot" to 131_072,

        // === 智谱 GLM ===
        "glm-4" to 128_000,
        "glm" to 128_000,

        // === 小米 MiMo ===
        "mimo" to 131_072,

        // === Llama / Groq ===
        "llama-3.1" to 131_072,
        "llama-3.3" to 131_072,
        "llama" to 8_192,

        // === 硅基流动常见模型 ===
        "Qwen/Qwen2.5-7B" to 32_768,
        "Qwen/Qwen2.5-14B" to 32_768,
        "Qwen/Qwen2.5-72B" to 131_072,
        "deepseek-ai/DeepSeek-V3" to 64_000,
        "deepseek-ai/DeepSeek-R1" to 64_000,
    )

    // Provider 默认上下文窗口（当模型名无法识别时使用）
    private val providerDefaults = mapOf(
        ApiProvider.GEMINI to 1_048_576,
        ApiProvider.ANTHROPIC to 200_000,
        ApiProvider.OPENAI to 128_000,
        ApiProvider.DEEPSEEK to 64_000,
        ApiProvider.DASHSCOPE to 131_072,
        ApiProvider.KIMI to 131_072,
        ApiProvider.ZHIPU to 128_000,
        ApiProvider.XIAOMI to 131_072,
        ApiProvider.SILICONFLOW to 32_768,
        ApiProvider.OPENROUTER to 128_000,
        ApiProvider.GROQ to 131_072,
        ApiProvider.PARTNER to 128_000,
        ApiProvider.IFLYTEK to 8_192,
        ApiProvider.CUSTOM to DEFAULT_CONTEXT_WINDOW,
    )

    /**
     * 根据模型名获取上下文窗口大小。
     * 先按模型名模式匹配，未命中则返回 [DEFAULT_CONTEXT_WINDOW]。
     */
    fun getContextWindow(model: String): Int {
        if (model.isBlank()) return DEFAULT_CONTEXT_WINDOW

        val modelLower = model.lowercase()
        for ((pattern, window) in modelPatterns) {
            if (modelLower.contains(pattern.lowercase())) {
                return window
            }
        }
        SecureLog.d("ModelContextRegistry", "Unknown model '$model', using default $DEFAULT_CONTEXT_WINDOW")
        return DEFAULT_CONTEXT_WINDOW
    }

    /**
     * 根据模型名和 provider 获取上下文窗口大小。
     * 先按模型名匹配，未命中时按 provider 默认值，最终回退到默认值。
     */
    fun getContextWindow(model: String, provider: ApiProvider?): Int {
        if (model.isBlank()) {
            return providerDefaults[provider] ?: DEFAULT_CONTEXT_WINDOW
        }

        val modelLower = model.lowercase()
        for ((pattern, window) in modelPatterns) {
            if (modelLower.contains(pattern.lowercase())) {
                return window
            }
        }

        // 模型名未识别，用 provider 默认值
        return providerDefaults[provider] ?: DEFAULT_CONTEXT_WINDOW
    }
}
