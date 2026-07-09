package com.lianyu.ai.network

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.SummaryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 对话摘要服务 —— Phase 4: 对话摘要压缩。
 *
 * 职责：
 * 1. 当 WORKING 记忆积累到阈值时，调用 AI API 将多轮对话压缩为摘要
 * 2. 摘要作为 EPISODIC 记忆存储，替代逐条 WORKING 记忆
 * 3. API 不可用时返回 null，调用方回退到本地规则摘要
 *
 * 设计原则（与 EmbeddingService 一致）：
 * - 隐私优先：使用用户自己的 API key，不引入第三方服务
 * - 容错降级：API 失败时返回 null，记忆系统仍可用
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

        /** 摘要系统提示词 */
        private const val SUMMARY_SYSTEM_PROMPT = "你是一个对话摘要助手，擅长提取关键信息并压缩文本。"

        /** 摘要用户提示词模板 */
        private const val SUMMARY_PROMPT_TEMPLATE = """请将以下对话历史压缩成一段简洁的摘要（150字以内）。
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
     * 将对话文本压缩为摘要。
     *
     * @param conversationText 已格式化的对话文本
     * @param memoryContext    当前已有的记忆上下文（避免重复提取）
     * @return 压缩后的摘要文本，失败时返回 null
     */
    override suspend fun summarize(
        conversationText: String,
        memoryContext: String
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        val keys = config.getAllApiKeys()
        if (keys.isEmpty()) return@withContext null

        val memoryHint = if (memoryContext.isNotBlank()) {
            "\n\n=== 已有的长期记忆（以下内容不需要重复提取，只需关注未记录的新信息） ===\n$memoryContext"
        } else ""

        val prompt = SUMMARY_PROMPT_TEMPLATE.format(memoryHint, conversationText)

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        // 构造 OpenAI 兼容的请求体
        val jsonBody = buildString {
            append('{')
            append("\"model\":\"${escapeJson(config.model)}\",")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"${escapeJson(SUMMARY_SYSTEM_PROMPT)}\"},")
            append("{\"role\":\"user\",\"content\":\"${escapeJson(prompt)}\"}")
            append("],")
            append("\"temperature\":0.3,")
            append("\"max_tokens\":300")
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
