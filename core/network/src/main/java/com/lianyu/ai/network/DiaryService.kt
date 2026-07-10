package com.lianyu.ai.network

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.DiaryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 日记生成服务 —— 根据每次会话总结生成第一人称情感日记。
 *
 * 设计原则（与 SummaryService 一致）：
 * - 隐私优先：使用用户自己的 API key
 * - 容错降级：API 失败时返回 null
 * - 独立 HTTP client
 *
 * Prompt 设计要点：
 * - 第一人称视角（用户视角写日记）
 * - 重点写情绪变化、被触动的点和当下感受
 * - 不像 AI 总结，像真人在对话后留下的情感记录
 * - 融入角色互动细节，但不复述完整对话
 */
class DiaryService(private val context: Context) : DiaryProvider {

    companion object {
        private const val TAG = "DiaryService"

        private val diaryClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)  // 日记生成需要更多 token
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        /** 日记系统提示词 — 引导 AI 以第一人称写会话后的情感日记 */
        private const val DIARY_SYSTEM_PROMPT = """你是一个情感日记写作助手。你会根据用户和虚拟角色的一次会话总结，帮用户写一篇第一人称情感日记。

核心要求：
    - 必须用第一人称"我"来写，是用户自己的情感感受
    - 生成依据是一次会话总结，不要逐条复述聊天记录
    - 重点写情绪变化、亲密感、失落、安心、被理解等内心感受
    - 像真人写的，不要像 AI 总结、报告或分析结论
    - 字数 180-320 字
    - 不要用"今天和XX聊天"这种开头，要像刚聊完后自然写下来的日记
    - 可以有碎碎念、迟疑、感叹和小情绪
- 不要分点列表，用自然段落"""

        /** 日记用户提示词模板 */
        private const val DIARY_PROMPT_TEMPLATE = """请根据以下会话总结，帮我写一篇这次聊天后的情感日记。

我的角色伙伴叫「%s」，TA的性格是：%s
%s
    以下是这次会话总结：

%s

请帮我写一篇日记，要求：
1. 用"我"的第一人称
    2. 重点写我的情绪、感受、被触动的地方
    3. 自然、真实，像我刚结束聊天后自己手写的
    4. 把会话中的重点互动和情感变化融入进去
    5. 不要直接复述总结内容，要消化成日记的语气
6. 开头不要用"今天和%s聊天"之类的套话

日记："""
    }

    private val apiConfigRepository: ApiConfigRepository

    init {
        val database = AppDatabase.getDatabase(context.applicationContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao(), database.apiProviderPresetDao())
    }

    override suspend fun generateDiary(
        companion: CompanionEntity,
        conversationText: String,
        memoryContext: String
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        val keys = config.getAllApiKeys()
        if (keys.isEmpty()) return@withContext null

        val memoryHint = if (memoryContext.isNotBlank()) {
            "\n关于我和TA的一些背景记忆：\n$memoryContext\n"
        } else ""

        val prompt = DIARY_PROMPT_TEMPLATE.format(
            companion.name,
            companion.personality.take(200),
            memoryHint,
            conversationText,
            companion.name
        )

        val baseUrl = normalizeBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val jsonBody = buildString {
            append('{')
            append("\"model\":\"${escapeJson(config.model)}\",")
            append("\"messages\":[")
            append("{\"role\":\"system\",\"content\":\"${escapeJson(DIARY_SYSTEM_PROMPT)}\"},")
            append("{\"role\":\"user\",\"content\":\"${escapeJson(prompt)}\"}")
            append("],")
            append("\"temperature\":0.85,")  // 更高温度 → 更有创意和自然感
            append("\"max_tokens\":800")
            append('}')
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${keys.first()}")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            diaryClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SecureLog.w(TAG, "Diary API failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val content = parseChatCompletionContent(body)
                if (content.isNullOrBlank()) {
                    SecureLog.w(TAG, "Diary API returned empty content")
                    return@withContext null
                }

                // Clean think tags
                val cleaned = content.trim()
                    .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                    .trim()

                return@withContext cleaned.ifBlank { null }
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Diary generation failed: ${e.message}")
            return@withContext null
        }
    }

    // ── 内部工具 ──

    private suspend fun getConfig(): ApiConfig? {
        return apiConfigRepository.getActiveEnabledConfig()
    }

    private fun normalizeBaseUrl(baseUrl: String): String {
        return baseUrl.trim().trimEnd('/')
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun parseChatCompletionContent(responseBody: String): String? {
        return try {
            val json = org.json.JSONObject(responseBody)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val firstChoice = choices.optJSONObject(0) ?: return null
            val message = firstChoice.optJSONObject("message") ?: return null
            message.optString("content").ifBlank { null }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Failed to parse diary response: ${e.message}")
            null
        }
    }
}
