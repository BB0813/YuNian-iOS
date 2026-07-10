package com.lianyu.ai.network

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.SummaryProvider
import com.lianyu.ai.database.repository.SummaryPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 统一对话摘要服务 —— 同时服务于 AutoContextManager（历史压缩）和
 * UnifiedMemoryRepository（记忆压缩）。
 *
 * 通过 [SummaryPurpose] 区分参数和模板，消除两套独立摘要系统的语义不一致：
 * - [SummaryPurpose.HISTORY]：200-400 字叙事摘要，第三人称，保留关键事实/约定/情感/关系进展
 * - [SummaryPurpose.MEMORY]：150 字精简摘要，提取关键话题和新信息
 *
 * 设计原则（与 EmbeddingService 一致）：
 * - 隐私优先：使用用户自己的 API key，不引入第三方服务
 * - 容错降级：API 失败时返回 null，调用方回退到本地规则摘要
 * - 独立 HTTP client：避免与 AiService 的主请求链路竞争
 */
class SummaryService(private val context: Context) : SummaryProvider {

    companion object {
        private const val TAG = "SummaryService"

        /** 摘要用的轻量 HTTP client（短超时，无重试） */
        private val summaryClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)  // 摘要生成可能比 embedding 慢
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        // ── 统一 Prompt 模板（按 purpose 区分） ──

        /** HISTORY 用途：系统角色（叙事摘要，200-400 字） */
        private const val HISTORY_SYSTEM_ROLE = "你是对话摘要助手，擅长将长对话压缩为精炼的叙事摘要。"

        /** HISTORY 用途：用户提示词模板（200-400 字，第三人称叙事） */
        private const val HISTORY_PROMPT_TEMPLATE = """你是对话摘要助手。请将以下对话历史压缩为一段连贯的叙事摘要。

要求：
1. 用第三人称叙述，200-400字
2. 按时间顺序组织，保持叙事连贯性
3. 重点保留：
   - 关键事实（名字、年龄、生日、工作、学校等个人信息）
   - 用户偏好和习惯
   - 约定、承诺、计划（如"约好周末一起"、"答应过生日送礼物"）
   - 情感时刻（表白、争吵、和好、撒娇、感动等）
   - 关系进展和变化
4. 省略寒暄、重复内容和无关紧要的细节
5. 不要编造对话中未出现的内容
6. 直接输出摘要文本，不要加标题、不要用列表格式

%s=== 对话历史 ===
%s"""

        /** MEMORY 用途：系统角色（精简摘要，150 字以内） */
        private const val MEMORY_SYSTEM_PROMPT = "你是一个对话摘要助手，擅长提取关键信息并压缩文本。"

        /** MEMORY 用途：用户提示词模板（150 字以内，提取关键信息） */
        private const val MEMORY_PROMPT_TEMPLATE = """请将以下对话历史压缩成一段简洁的摘要（150字以内）。
要求：
1. 提取关键话题、情感变化、用户提到的个人信息/偏好/约定
2. 省略闲聊和重复内容
3. 用自然语言描述，不要用列表格式
4. 如果已有记忆中包含的信息，简要带过即可，重点突出新信息%s

对话历史：
%s

摘要："""
    }

    private val apiConfigRepository: ApiConfigRepository

    init {
        val database = AppDatabase.getDatabase(context.applicationContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    }

    override fun isSummarySupported(): Boolean {
        // 只要有可用的 API 配置就支持摘要
        // 不像 embedding 需要特定 provider，摘要使用普通 chat completion
        return true
    }

    /**
     * 将对话文本压缩为摘要，按 [purpose] 区分参数和模板。
     *
     * - [SummaryPurpose.HISTORY]：200-400 字叙事摘要，temp=0.3, maxTokens=600
     * - [SummaryPurpose.MEMORY]：150 字精简摘要，temp=0.3, maxTokens=300
     */
    override suspend fun summarize(
        conversationText: String,
        memoryContext: String,
        purpose: SummaryPurpose
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        val keys = config.getAllApiKeys()
        if (keys.isEmpty()) return@withContext null

        // 按 purpose 选择模板和参数
        val systemRole: String
        val promptTemplate: String
        val memoryHint: String
        val maxTokens: Int

        when (purpose) {
            SummaryPurpose.HISTORY -> {
                systemRole = HISTORY_SYSTEM_ROLE
                promptTemplate = HISTORY_PROMPT_TEMPLATE
                // HISTORY: 注入 memoryContext 前 500 字，确保摘要与已有记忆一致
                memoryHint = if (memoryContext.isNotBlank()) {
                    "已知记忆参考（摘要应与这些记忆一致，不要矛盾）：\n${memoryContext.take(500)}\n"
                } else ""
                maxTokens = 600
            }
            SummaryPurpose.MEMORY -> {
                systemRole = MEMORY_SYSTEM_PROMPT
                promptTemplate = MEMORY_PROMPT_TEMPLATE
                // MEMORY: 注入完整 memoryContext，避免重复提取
                memoryHint = if (memoryContext.isNotBlank()) {
                    "\n\n=== 已有的长期记忆（以下内容不需要重复提取，只需关注未记录的新信息） ===\n$memoryContext"
                } else ""
                maxTokens = 300
            }
        }

        val prompt = promptTemplate.format(memoryHint, conversationText)

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        // 构造 OpenAI 兼容的请求体
        val jsonBody = buildString {
            append('{')
            append("\"model\":\"${escapeJson(config.model)}\",")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"${escapeJson(systemRole)}\"},")
            append("{\"role\":\"user\",\"content\":\"${escapeJson(prompt)}\"}")
            append("],")
            append("\"temperature\":0.3,")
            append("\"max_tokens\":$maxTokens")
            append('}')
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${keys.first()}")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            summaryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SecureLog.w(TAG, "Summary API failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val content = parseChatCompletionContent(body)
                if (content.isNullOrBlank()) {
                    SecureLog.w(TAG, "Summary API returned empty content")
                    return@withContext null
                }

                // Clean think tags and whitespace
                val rawCleaned = content.trim()
                    .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                val cleaned = rawCleaned.lines()
                    .firstOrNull { it.isNotBlank() }
                    ?: rawCleaned

                return@withContext cleaned.ifBlank { null }
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Summary generation failed: ${e.message}")
            return@withContext null
        }
    }

    // ── 内部工具 ──

    private suspend fun getConfig(): ApiConfig? {
        return apiConfigRepository.getActiveEnabledConfig()
    }

    /** 规范化 baseUrl：信任用户填写的兼容层级，不猜测追加 /v1。 */
    private fun normalizeBaseUrl(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/')
    }

    /** 简单 JSON 字符串转义 */
    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /** 从 OpenAI 兼容的 chat completion 响应中提取 content */
    private fun parseChatCompletionContent(responseBody: String): String? {
        return try {
            val json = org.json.JSONObject(responseBody)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            val message = firstChoice.optJSONObject("message") ?: return null
            message.optString("content").ifBlank { null }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Failed to parse summary response: ${e.message}")
            null
        }
    }
}
