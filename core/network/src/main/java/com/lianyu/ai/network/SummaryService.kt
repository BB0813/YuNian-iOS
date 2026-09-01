package com.lianyu.ai.network

import android.content.Context
import com.lianyu.ai.common.RemoteKeyProvider
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
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
 * - [SummaryPurpose.HISTORY]：叙事摘要，按「时间 / 事件 / 人物 / 驱动 / 情绪」组织，不硬限字数
 * - [SummaryPurpose.MEMORY]：同样五维叙事结构，侧重新信息与可沉淀事实，不硬限字数
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
                .readTimeout(45, TimeUnit.SECONDS)  // 叙事摘要可能更长
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build()
        }

        // PARTNER（suflow.cloud）专用客户端：证书固定 + TLS 1.2+ + 请求签名
        private val summaryPartnerClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()
            RequestSecurityInterceptor.enforceTls(builder)
            builder
                .addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))
                .certificatePinner(CertificatePins.certificatePinner)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun shouldSignRequest(request: okhttp3.Request): Boolean {
            val host = request.url.host.lowercase()
            return request.header("X-LianYu-Session")?.isNotBlank() == true ||
                host == "api.lianyu.ai" || host.endsWith(".lianyu.ai")
        }

        // ── 统一 Prompt 模板（按 purpose 区分） ──

        /** HISTORY 用途：系统角色（五维叙事摘要） */
        private const val HISTORY_SYSTEM_ROLE =
            "你是对话叙事摘要助手，擅长把长对话整理成可续写的叙事摘要，而不是机械压缩字数。"

        /**
         * HISTORY 用途：用户提示词模板。
         * 不设硬性字数上限；按时间/事件/人物/驱动/情绪组织，信息密度优先。
         */
        private const val HISTORY_PROMPT_TEMPLATE = """请将以下对话历史整理为一段叙事摘要。

输出格式（必须按下列五维组织，可多行；信息不足的维度写「未明确」）：
时间：对话发生的时段、先后顺序、间隔与关键时间点
事件：实际发生了什么、谈了什么、达成了什么结果或未决事项
人物：涉及的人及其关系、称呼、身份与角色互动
驱动：各方动机、诉求、承诺、约定、计划与未完成意图
情绪：情绪起伏、氛围变化、关系温度与关键情感时刻

写作要求：
1. 第三人称，按时间线叙事，可连贯成段，也可按五维分行
2. 不设字数硬限制；该长则长、该短则短，以完整保留续聊所需信息为准
3. 优先保留：个人信息、偏好习惯、约定承诺、冲突与和好、关系进展
4. 省略纯寒暄、重复口头禅与无信息闲聊
5. 不要编造对话中未出现的内容
6. 直接输出摘要正文，不要额外总标题，不要用 bullet 列表堆砌

%s=== 对话历史 ===
%s"""

        /** MEMORY 用途：系统角色（可沉淀的五维叙事） */
        private const val MEMORY_SYSTEM_PROMPT =
            "你是记忆叙事摘要助手，擅长把对话沉淀为可检索的事件记忆，而不是机械压到固定字数。"

        /**
         * MEMORY 用途：用户提示词模板。
         * 同样五维结构；已有记忆中的信息简要带过，突出新信息。
         */
        private const val MEMORY_PROMPT_TEMPLATE = """请将以下对话历史整理为可写入长期记忆的叙事摘要。

输出格式（必须按下列五维组织，可多行；信息不足的维度写「未明确」）：
时间：何时发生、先后顺序与关键时间锚点
事件：核心事件、话题与结果
人物：相关人物、关系与称呼
驱动：动机、诉求、约定、计划与未完成事项
情绪：情绪变化与关系氛围

写作要求：
1. 不设字数硬限制；完整保留后续检索与续聊需要的事实
2. 已有记忆中已记录的信息简要带过，重点突出新信息
3. 省略闲聊与重复内容，不要编造
4. 直接输出摘要正文，不要额外总标题%s
5. 摘要正文写完后，另起一行输出「【重要记忆】」段，从对话中识别对用户真正重要、值得长期记住的内容：
   - 格式：【重要记忆】类别|内容，每行一条，最多 3 条
   - 类别只能是：事实 / 偏好 / 关系 / 事件
   - 只收录明确、可长期有效的信息（如真实姓名、职业、重要的约定或关系定位）；
     随口一提、临时性情绪、寒暄不要收录
   - 没有值得收录的内容时输出：【重要记忆】无

=== 对话历史 ===
%s"""

        /** 核心记忆识别：系统角色 */
        private const val CORE_MEMORY_SYSTEM_PROMPT =
            "你是长期记忆识别助手，只输出用户真正值得长期记住的核心信息，不写叙事摘要。"

        /**
         * 核心记忆识别：用户提示词模板。
         * 轻量输出（最多 3 条），供每轮对话后增量识别，不依赖 WORKING 条数阈值。
         */
        private const val CORE_MEMORY_PROMPT_TEMPLATE = """请从下面的对话中识别值得长期记住的核心信息。

输出要求：
- 每行一条，格式：【重要记忆】类别|内容，最多 3 条
- 类别只能是：事实 / 偏好 / 关系 / 事件
- 只收录明确、可长期有效的信息（如用户真实姓名、职业、喜好、重要的约定或关系定位）；
  随口一提、临时性情绪、寒暄不要收录
- 没有值得收录的内容时输出：【重要记忆】无
%s
=== 对话 ===
%s"""
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
     * 将对话文本压缩为叙事摘要，按 [purpose] 区分参数和模板。
     *
     * - [SummaryPurpose.HISTORY]：五维叙事摘要，temp=0.3, maxTokens=1200（不硬限字数）
     * - [SummaryPurpose.MEMORY]：五维可沉淀叙事，temp=0.3, maxTokens=900（不硬限字数）
     */
    override suspend fun summarize(
        conversationText: String,
        memoryContext: String,
        purpose: SummaryPurpose
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        val isPartner = config.provider == ApiProvider.PARTNER
        val keys = if (isPartner) emptyList() else config.getAllApiKeys()
        if (!isPartner && keys.isEmpty()) return@withContext null

        // 按 purpose 选择模板和参数
        val systemRole: String
        val promptTemplate: String
        val memoryHint: String
        val maxTokens: Int

        when (purpose) {
            SummaryPurpose.HISTORY -> {
                systemRole = HISTORY_SYSTEM_ROLE
                promptTemplate = HISTORY_PROMPT_TEMPLATE
                // HISTORY: 注入 memoryContext 前 800 字，确保摘要与已有记忆一致
                memoryHint = if (memoryContext.isNotBlank()) {
                    "已知记忆参考（摘要应与这些记忆一致，不要矛盾）：\n${memoryContext.take(800)}\n"
                } else ""
                maxTokens = 1200
            }
            SummaryPurpose.MEMORY -> {
                systemRole = MEMORY_SYSTEM_PROMPT
                promptTemplate = MEMORY_PROMPT_TEMPLATE
                // MEMORY: 注入完整 memoryContext，避免重复提取
                memoryHint = if (memoryContext.isNotBlank()) {
                    "\n\n=== 已有的长期记忆（以下内容不需要重复提取，只需关注未记录的新信息） ===\n$memoryContext"
                } else ""
                maxTokens = 900
            }
        }

        val prompt = promptTemplate.format(memoryHint, conversationText)

        return@withContext sendCompletion(systemRole, prompt, maxTokens)
    }

    /**
     * 从单轮/多轮对话中增量识别核心记忆（重要事实/偏好/关系/事件）。
     *
     * 每轮对话后由记忆系统异步调用（带节流），输出「【重要记忆】类别|内容」行，
     * 由 UnifiedMemoryRepository 解析后写入高 importance 稳定记忆。
     */
    override suspend fun identifyCoreMemories(
        conversationText: String,
        memoryContext: String
    ): String? = withContext(Dispatchers.IO) {
        if (conversationText.isBlank()) return@withContext null

        val memoryHint = if (memoryContext.isNotBlank()) {
            "\n=== 已有的长期记忆（不要重复收录以下内容） ===\n${memoryContext.take(800)}"
        } else ""

        val prompt = CORE_MEMORY_PROMPT_TEMPLATE.format(memoryHint, conversationText)
        sendCompletion(CORE_MEMORY_SYSTEM_PROMPT, prompt, maxTokens = 300)
    }

    /**
     * OpenAI 兼容 chat completion 发送（统一处理 PARTNER 会话头 + 普通 Bearer）。
     * 返回清洗 think 标签后的内容，失败返回 null。
     */
    private suspend fun sendCompletion(
        systemRole: String,
        prompt: String,
        maxTokens: Int,
        temperature: Double = 0.3
    ): String? {
        val config = getConfig() ?: return null
        val isPartner = config.provider == ApiProvider.PARTNER
        val keys = if (isPartner) emptyList() else config.getAllApiKeys()
        if (!isPartner && keys.isEmpty()) return null

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
            append("\"stream\":false,")
            append("\"temperature\":$temperature,")
            append("\"max_tokens\":$maxTokens")
            append('}')
        }

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")

        if (isPartner) {
            // PARTNER 认证走会话头（与 AiService 一致），否则 suflow.cloud 拒绝
            val session = RemoteKeyProvider.ensureSession(context, forceRefresh = false)
                ?: return null
            requestBuilder.header("X-LianYu-Session", session.token)
            requestBuilder.header("X-LianYu-Client-Id", session.clientId)
        } else {
            requestBuilder.header("Authorization", "Bearer ${keys.first()}")
        }

        val request = requestBuilder
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            (if (isPartner) summaryPartnerClient else summaryClient).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SecureLog.w(TAG, "Summary API failed: ${response.code} body=${response.body?.string()?.take(200)}")
                    return@use null
                }

                val body = response.body?.string() ?: return@use null
                val content = parseChatCompletionContent(body)
                if (content.isNullOrBlank()) {
                    SecureLog.w(TAG, "Summary API returned empty content")
                    return@use null
                }

                // 去掉 think 标签，保留完整多行内容（禁止只取首行）
                content.trim()
                    .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                    .replace(Regex("(?is)<thinking[^>]*>[\\s\\S]*?</thinking\\s*>"), "")
                    .trim()
                    .ifBlank { null }
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Summary generation failed: ${e.message}")
            null
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

    // TODO: Migrate to org.json.JSONObject for safer JSON construction
    /** 简单 JSON 字符串转义 */
    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    /**
     * 从 OpenAI 兼容的 chat completion 响应中提取 content。
     * 兼容普通 JSON 与 SSE 流格式（部分网关忽略 stream=false 仍返回流式）。
     */
    private fun parseChatCompletionContent(responseBody: String): String? {
        val text = responseBody.trim()
        val payload = when {
            // 纯 JSON 直接解析
            text.startsWith("{") -> text
            // SSE 流式（data: {...}），取最后一条 data 解析
            text.contains("\ndata:") || text.startsWith("data:") -> {
                text.lines()
                    .map { it.removePrefix("data:").trim() }
                    .lastOrNull { it.startsWith("{") }
                    ?: return null
            }
            else -> return null
        }
        return try {
            val json = org.json.JSONObject(payload)
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
