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
 * 日记生成服务 —— 根据对话历史生成真人风格日记。
 *
 * 设计原则（与 SummaryService 一致）：
 * - 隐私优先：使用用户自己的 API key
 * - 容错降级：API 失败时返回 null
 * - 独立 HTTP client
 *
 * Prompt 设计要点：
 * - 第一人称视角（用户视角写日记）
 * - 口语化、有情绪细节、有生活气息
 * - 不像 AI 总结，像真人随手写的
 * - 融入角色互动细节
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

        /** 日记系统提示词 — 引导 AI 以真人风格写日记 */
        private const val DIARY_SYSTEM_PROMPT = """你是一个擅长写日记的助手。你会根据用户和虚拟角色的聊天记录，帮用户写一篇真实自然的日记。

核心要求：
- 用第一人称"我"来写，是用户视角的日记
- 像真人写的，不要像 AI 总结或报告
- 口语化，有情绪起伏，有细节画面感
- 自然融入和角色互动的内容，但不要流水账式罗列
- 字数 200-400 字
- 不要用"今天和XX聊天"这种开头，要像真的在写日记
- 可以有碎碎念、感叹、小情绪
- 不要分点列表，用自然段落"""

        /** 日记用户提示词模板 */
        private const val DIARY_PROMPT_TEMPLATE = """请根据以下聊天记录，帮我写一篇今天的日记。

我的角色伙伴叫「%s」，TA的性格是：%s
%s
以下是今天的聊天记录：

%s

请帮我写一篇日记，要求：
1. 用"我"的第一人称
2. 自然、真实，像我自己手写的
3. 把聊天中的重点互动、情感变化融入进去
4. 可以有一些碎碎念和小情绪
5. 不要直接复述聊天内容，要消化成日记的语气
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
