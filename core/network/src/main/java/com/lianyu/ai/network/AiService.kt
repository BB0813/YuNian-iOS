package com.lianyu.ai.network

import android.content.Context
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.BanManager
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiMessageType
import com.lianyu.ai.domain.AiResponse
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.MemoryRepository
import com.lianyu.ai.database.repository.TokenUsageRepository
import com.lianyu.ai.network.provider.AiProvider
import com.lianyu.ai.network.provider.ClaudeProvider
import com.lianyu.ai.network.provider.OpenAiCompatibleProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

interface OpenAiApi {
    @POST
    suspend fun chatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse
}

interface AnthropicApi {
    @POST
    suspend fun chatCompletion(
        @Url url: String,
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: AnthropicRequest
    ): AnthropicResponse
}

interface GeminiApi {
    @POST
    suspend fun generateContent(
        @Url url: String,
        @Query("key") apiKey: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: GeminiRequest
    ): GeminiResponse
}

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Float = 0.7f,
    val max_tokens: Int? = null,
    val top_p: Float = 0.9f,
    val frequency_penalty: Float = 0.0f,
    val presence_penalty: Float = 0.0f,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null,
    val tool_choice: String? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: ToolParameters
)

@Serializable
data class ToolParameters(
    val type: String = "object",
    val properties: Map<String, ToolProperty>,
    val required: List<String>? = null
)

@Serializable
data class ToolProperty(
    val type: String,
    val description: String
)

@Serializable
data class Message(
    val role: String,
    val content: String,
    val reasoning_content: String? = null
)

@Serializable
data class VisionMessage(
    val role: String,
    val content: List<ContentPart>
)

@Serializable
data class ContentPart(
    val type: String,
    val text: String? = null,
    val image_url: ImageUrl? = null
)

@Serializable
data class ImageUrl(
    val url: String
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>? = null,
    val error: ErrorDetail? = null
)

@Serializable
data class Choice(
    val message: Message? = null,
    val delta: Message? = null
)

@Serializable
data class ErrorDetail(
    val message: String? = null
)

@Serializable
data class AnthropicRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val max_tokens: Int = 4096,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicResponse(
    val content: List<AnthropicContent>? = null,
    val error: AnthropicError? = null
)

@Serializable
data class AnthropicContent(
    val text: String? = null
)

@Serializable
data class AnthropicError(
    val message: String? = null
)

@Serializable
data class GeminiRequest(
    val contents: List<GeminiContent>,
    val systemInstruction: GeminiContent? = null,
    val generationConfig: GeminiGenerationConfig? = null
)

@Serializable
data class GeminiContent(
    val role: String? = null,
    val parts: List<GeminiPart>
)

@Serializable
data class GeminiPart(
    val text: String
)

@Serializable
data class GeminiGenerationConfig(
    val temperature: Float? = null,
    val maxOutputTokens: Int? = null
)

@Serializable
data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val error: GeminiError? = null
)

@Serializable
data class GeminiCandidate(
    val content: GeminiContent? = null
)

@Serializable
data class GeminiError(
    val message: String? = null
)

@Serializable
data class ModelsListResponse(
    val data: List<ModelInfo>? = null,
    val error: ErrorDetail? = null
)

@Serializable
data class ModelInfo(
    val id: String? = null
)

class AiService(context: Context) : AiServiceProvider {
    private val appContext = context.applicationContext
    private val apiConfigRepository: ApiConfigRepository
    private val memoryRepository: MemoryRepository
    private val tokenUsageRepository: TokenUsageRepository
    private val appSettingsStore = AppSettingsStore(appContext)

    @Volatile
    private var cachedBuiltinModel: String? = null

    // 熔断器 + 速率限流 (控制论: 滞环非线性保护 + 饱和限)
        // ============================================================
    // [反馈回路] 熔断器 & 速率限流
    // ============================================================
    private val retryController = AiRetryController()
    private val rateLimiter = AiRateLimiter()

    init {
        val database = AppDatabase.getDatabase(appContext)
        val deviceId = DeviceIdProvider.getDeviceId(appContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
        memoryRepository = MemoryRepository(database.memoryDao(), deviceId)
        tokenUsageRepository = TokenUsageRepository(appContext)
    }

    private fun buildProactiveTimeContext(): String {
        val calendar = java.util.Calendar.getInstance()
        val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
        val minute = calendar.get(java.util.Calendar.MINUTE)
        val second = calendar.get(java.util.Calendar.SECOND)
        val dayOfWeek = calendar.get(java.util.Calendar.DAY_OF_WEEK)
        val timeStr = "${String.format("%02d", hour)}:${String.format("%02d", minute)}:${String.format("%02d", second)}"

        val weekdayNames = mapOf(
            java.util.Calendar.MONDAY to "周一",
            java.util.Calendar.TUESDAY to "周二",
            java.util.Calendar.WEDNESDAY to "周三",
            java.util.Calendar.THURSDAY to "周四",
            java.util.Calendar.FRIDAY to "周五",
            java.util.Calendar.SATURDAY to "周六",
            java.util.Calendar.SUNDAY to "周日"
        )
        val weekdayName = weekdayNames[dayOfWeek] ?: ""

        val timeScenario = when (hour) {
            in 5..7 -> {
                val hint = if (hour < 6) "凌晨了" else if (hour == 6) "天快亮了" else "早上了"
                "$hint（$timeStr），用户可能刚醒或还没醒。可以关心对方有没有起床、早安、问要不要一起吃早餐、提醒今天有什么安排。"
            }
            in 8..10 -> {
                "上午（$timeStr），用户可能在上班/上学路上或刚开始工作。可以聊早上发生了什么、吃了没、今天心情怎么样、提醒别迟到。"
            }
            in 11..12 -> {
                "快到午饭时间了（$timeStr），用户肚子应该饿了。可以问吃什么、要不要一起点外卖、中午休息一下、吐槽食堂/外卖难吃。"
            }
            in 13..14 -> {
                "午休时间（$timeStr），用户可能在犯困打盹。可以问睡醒了没、下午要干嘛、分享自己也在犯困、叫对方起来活动一下。"
            }
            in 15..17 -> {
                "下午（$timeStr），工作时间过半，用户可能累了或在摸鱼。可以聊下班还有多久、想不想喝奶茶、摸鱼中吗、等下一起去吃点什么。"
            }
            in 18..19 -> {
                "下班/放学时间（$timeStr），用户在回家路上或刚到家。可以问到家了没、路上堵不堵、晚上想干什么、要不要一起打游戏/看剧/吃饭。"
            }
            in 20..22 -> {
                "晚间休闲时间（$timeStr），用户在放松。可以聊今天过得怎么样、分享有趣的事、撒娇求关注、催对方早点洗澡、一起追剧/打游戏。"
            }
            in 23..24, 0, in 1..4 -> {
                "深夜/凌晨（$timeStr），用户还没睡。可以问怎么还不睡、明天不用早起吗、陪对方聊天、温柔地哄睡觉、说晚安。"
            }
            else -> "$timeStr"
        }

        val isWeekend = dayOfWeek == java.util.Calendar.SATURDAY || dayOfWeek == java.util.Calendar.SUNDAY
        val weekendHint = when {
            isWeekend && hour in 9..11 -> "今天是$weekdayName 周末，用户可以睡懒觉。"
            isWeekend && hour in 12..14 -> "周末中午，用户可能在享受慵懒时光。"
            isWeekend && hour in 18..21 -> "周末晚上，适合约会或宅家放松。"
            !isWeekend && hour in 7..9 -> "今天是$weekdayName 工作日，用户可能要赶时间出门。"
            !isWeekend && hour in 17..19 -> "工作日傍晚，用户可能刚结束一天的工作比较疲惫。"
            else -> ""
        }

        return buildString {
            appendLine("=== 时间感知 ===")
            appendLine("当前精确时间：$weekdayName $timeStr")
            appendLine("场景：$timeScenario")
            if (weekendHint.isNotBlank()) {
                appendLine(weekendHint)
            }
            appendLine("请根据当前精确时间和场景，自然地融入对话中。你可以知道现在确切是几点几分几秒，让内容贴合这个时间段该做的事和情绪。")
        }
    }

    private suspend fun resolveConfig(): ApiConfig? {
        return apiConfigRepository.getActiveEnabledConfig()
    }

    private suspend fun tryFetchBuiltinModel(keys: List<String>): String? {
        return try {
            val result = fetchModels(ApiProvider.PARTNER.defaultBaseUrl, keys.first())
            result.getOrNull()?.let { models ->
                if (models.isNotEmpty()) {
                    val selected = models.find { it.contains("Kimi", ignoreCase = true) }
                        ?: models.find { it.contains("gpt", ignoreCase = true) }
                        ?: models.first()
                    SecureLog.api("BUILTIN", "Auto-selected model: $selected from ${models.size} models")
                    selected
                } else null
            }
        } catch (e: Exception) {
            SecureLog.w("AiService", "Auto-fetch builtin models failed: ${e.message}")
            null
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Check if a model requires temperature=1 (no other values supported)
         */
        fun requiresFixedTemperature(model: String): Boolean {
            return model.contains("kimi-k2.6", ignoreCase = true) ||
                   model.contains("k2.6", ignoreCase = true)
        }

        private val okHttpClient: OkHttpClient by lazy {
            val builder = OkHttpClient.Builder()

            if (false) {
                builder.addInterceptor(
                    HttpLoggingInterceptor(RedactingLogger()).apply {
                        level = HttpLoggingInterceptor.Level.HEADERS
                    }
                )
            }

            builder.addInterceptor(NetworkLogger())

            builder.addInterceptor(RetryInterceptor(maxRetries = 2, initialDelayMs = 300))

            builder.addInterceptor(RequestSecurityInterceptor(shouldSignRequest = ::shouldSignRequest))

            RequestSecurityInterceptor.enforceTls(builder)

            builder.certificatePinner(CertificatePins.certificatePinner)
            builder
                .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .callTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        // Dedicated client for self-hosted (PARTNER) providers — avoids creating a new
        // OkHttpClient on every call (which leaks connection pools and dispatcher threads).
        // Uses longer timeouts since self-hosted servers may be slower.
        private val partnerHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .certificatePinner(CertificatePins.certificatePinner)
                .connectionPool(okhttp3.ConnectionPool(3, 5, TimeUnit.MINUTES))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        private fun shouldSignRequest(request: okhttp3.Request): Boolean {
            val host = request.url.host.lowercase()
            return host == "api.lianyu.ai" || host.endsWith(".lianyu.ai")
        }

        private var context: Context? = null

        fun initialize(ctx: Context) {
            context = ctx.applicationContext
        }

        private val retrofit: Retrofit by lazy {
            Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl("https://api.openai.com/")
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
        }

        private val openAiApi: OpenAiApi by lazy { retrofit.create(OpenAiApi::class.java) }
        private val anthropicApi: AnthropicApi by lazy { retrofit.create(AnthropicApi::class.java) }
        private val geminiApi: GeminiApi by lazy { retrofit.create(GeminiApi::class.java) }

        private val keyRoundRobinIndex = AtomicInteger(Random.nextInt(Int.MAX_VALUE))
        private val keyLastUsed = ConcurrentHashMap<String, Long>()
        private val keyCooldownUntil = ConcurrentHashMap<String, Long>()

        // ── Provider registry ──
        private val providers: Map<ApiProvider, AiProvider> = mapOf(
            ApiProvider.OPENAI to OpenAiCompatibleProvider(),
            ApiProvider.DEEPSEEK to OpenAiCompatibleProvider(),
            ApiProvider.DASHSCOPE to OpenAiCompatibleProvider(),
            ApiProvider.KIMI to OpenAiCompatibleProvider(),
            ApiProvider.GEMINI to OpenAiCompatibleProvider(),
            ApiProvider.XIAOMI to OpenAiCompatibleProvider(),
            ApiProvider.ZHIPU to OpenAiCompatibleProvider(),
            ApiProvider.SILICONFLOW to OpenAiCompatibleProvider(),
            ApiProvider.OPENROUTER to OpenAiCompatibleProvider(),
            ApiProvider.GROQ to OpenAiCompatibleProvider(),
            ApiProvider.CUSTOM to OpenAiCompatibleProvider(),
            ApiProvider.PARTNER to OpenAiCompatibleProvider(),
            ApiProvider.ANTHROPIC to ClaudeProvider(),
        )

        private fun providerFor(config: ApiConfig): AiProvider =
            providers[config.provider] ?: OpenAiCompatibleProvider()

        init {
            AiProvider.okHttpClient = okHttpClient
            AiProvider.keySelector = ::selectApiKey
            AiProvider.keyFailureHandler = ::markKeyFailed
        }

        const val KEY_MIN_INTERVAL_MS = 800L
        const val KEY_FAILURE_COOLDOWN_MS = 5000L

        fun selectApiKey(config: ApiConfig): Pair<Int, List<String>> {
            val allKeys = config.getAllApiKeys()
            if (allKeys.size <= 1) return 0 to allKeys
            val now = System.currentTimeMillis()
            var attempts = 0
            while (attempts < allKeys.size * 2) {
                val idx = keyRoundRobinIndex.getAndIncrement() % allKeys.size
                val key = allKeys[idx]
                val cooldownUntil = keyCooldownUntil[key] ?: 0L
                if (now >= cooldownUntil && (keyLastUsed[key] ?: 0L) + KEY_MIN_INTERVAL_MS <= now) {
                    keyLastUsed[key] = now
                    SecureLog.d("AiService", "Key轮询: 使用 #${idx + 1}/${allKeys.size}")
                    return idx to allKeys
                }
                attempts++
            }
            val fallbackIdx = keyRoundRobinIndex.getAndIncrement() % allKeys.size
            keyLastUsed[allKeys[fallbackIdx]] = now
            return fallbackIdx to allKeys
        }

        fun markKeyFailed(key: String) {
            keyCooldownUntil[key] = System.currentTimeMillis() + KEY_FAILURE_COOLDOWN_MS
            SecureLog.w("AiService", "Key失败冷却5s: ${key.take(8)}...")
        }

        fun resetKeyState() {
            keyCooldownUntil.clear()
            keyLastUsed.clear()
        }
    }

    /**
     * 发送消息并获取流式响应
     * 支持分段返回，实现打字机效果
     */

    // ============================================================
    // Lifecycle — 初始化 / 销毁
    // ============================================================
    fun sendMessageStream(
        companion: CompanionModel?,
        history: List<ChatMessage>,
        stickerProbability: Int = 30
    ): Flow<ChunkedResponseHandler.ChunkResult> = flow {
        if (companion == null) {
            emit(ChunkedResponseHandler.ChunkResult.Error("抱歉，找不到角色信息。"))
            return@flow
        }

        SecureLog.api("STREAM", "Starting stream for companion=${companion.name}")
        TypingIndicator.startTyping("stream")

        if (!rateLimiter.tryAcquire()) {
            emit(ChunkedResponseHandler.ChunkResult.Error("请求过于频繁，请稍后再试"))
            return@flow
        }

        try {
            val config = resolveConfig()
            if (config == null) {
                emit(ChunkedResponseHandler.ChunkResult.Error("请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。"))
                return@flow
            }
            if (config.model.isBlank()) {
                emit(ChunkedResponseHandler.ChunkResult.Error("模型名未配置，请在「API设置」中重新测试连接以自动选择模型。"))
                return@flow
            }

            val sortedHistory = history.sortedBy { it.timestamp }
            // C4: Differential privacy — sanitize PII from messages sent to 3rd-party APIs.
            // Clove (self-hosted) skips sanitization; user data stays on their server.
            val sanitizedHistory = if (config.provider == ApiProvider.PARTNER) {
                sortedHistory
            } else {
                sortedHistory.map { msg ->
                    if (msg.isFromUser) msg.copy(content = com.lianyu.ai.common.safety.DifferentialPrivacyFilter.sanitize(msg.content))
                    else msg
                }
            }
            val lastUserMessage = sanitizedHistory.lastOrNull { it.isFromUser }?.content ?: ""
            val contextLimit = appSettingsStore.getContextLimit()
            val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
            val compressionMode = appSettingsStore.getContextCompressionMode()
            val keepRatio = appSettingsStore.getCompressionKeepRatio()
            val minKeep = appSettingsStore.getCompressionMinKeep()
            val memoryContext = memoryRepository.getEnrichedContext(companion.id, lastUserMessage, contextLimit)
            val stickerManager = StickerManager.getInstance(appContext)
            val availableStickers = stickerManager.getAllStickers().mapNotNull { sticker ->
                val displayName = sticker.description?.takeIf {
                    it.isNotBlank() && !it.startsWith("sticker_") && it.length <= 20
                } ?: sticker.name.removePrefix("sticker_").removeSuffix(".png").takeIf { it.isNotBlank() && it.length <= 20 }
                if (displayName.isNullOrBlank() || displayName.length > 20) null else displayName
            }.distinct()
            val systemPrompt = buildSystemPrompt(companion, memoryContext, lastUserMessage, availableStickers, stickerProbability, innerThoughtEnabled)
            val messages = buildMessages(sanitizedHistory, systemPrompt, lastUserMessage, contextLimit, compressionMode = compressionMode, memoryContext = memoryContext, keepRatio = keepRatio, minKeep = minKeep)

            SecureLog.api("STREAM", "Using provider=${config.provider}, model=${config.model}, contextLimit=$contextLimit, stickerProb=$stickerProbability, stickers=${availableStickers.size}")

            // 尝试使用 SSE 流式请求
            val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
            val url = "${baseUrl.trimEnd('/')}/chat/completions"

            val streamRequest = ChunkedResponseHandler.StreamRequest(
                model = config.model,
                messages = messages.map { ChunkedResponseHandler.StreamMessage(it.role, it.content) },
                temperature = config.temperature.coerceIn(0.1f, 1.5f),
                max_tokens = config.maxTokens ?: 800,
                stream = true
            )

            val allKeys = config.getAllApiKeys()
            var accumulatedText = ""
            var hasEmittedContent = false  // Track whether any Text chunk has been emitted to the collector
            var hasError = false
            var streamSuccess = false
            var lastStreamError: Exception? = null

            for (keyIndex in allKeys.indices) {
                val currentKey = allKeys[keyIndex]
                // If content was already emitted from a previous key attempt, we cannot retry —
                // the UI has already received partial text that cannot be revoked.
                if (hasEmittedContent) {
                    SecureLog.w("AiService", "Stream already emitted content, cannot retry with next key")
                    break
                }
                accumulatedText = ""
                hasError = false
                try {
                    ChunkedResponseHandler.streamChatCompletion(url, currentKey, streamRequest, okHttpClient).collect { result ->
                        when (result) {
                            is ChunkedResponseHandler.ChunkResult.Text -> {
                                accumulatedText += result.content
                                hasEmittedContent = true
                                emit(result)
                            }
                            is ChunkedResponseHandler.ChunkResult.Reasoning -> {
                                emit(result)
                            }
                            is ChunkedResponseHandler.ChunkResult.Error -> {
                                hasError = true
                                SecureLog.api("STREAM", "Stream error with Key ${keyIndex + 1}/${allKeys.size}: ${result.message}")
                                emit(result)
                            }
                            is ChunkedResponseHandler.ChunkResult.Done -> {
                                if (!hasError) {
                                    streamSuccess = true
                                    if (accumulatedText.isNotEmpty()) {
                                        val cleaned = applyPersonaPostProcessing(accumulatedText, sanitizedHistory)
                                        val safetyResult = ContentFilter.checkOutputSafety(cleaned)
                                        if (!safetyResult.isSafe) {
                                            SecureLog.w("AiService", "Stream output safety violation: ${safetyResult.level} - ${safetyResult.reason}")
                                            BanManager.recordViolation(appContext, safetyResult.level)
                                            emit(ChunkedResponseHandler.ChunkResult.Text("抱歉，我无法继续这个话题。"))
                                        } else {
                                            emit(ChunkedResponseHandler.ChunkResult.Text(cleaned))
                                        }
                                    }
                                }
                                emit(result)
                            }
                        }
                    }
                    if (streamSuccess) break
                } catch (e: Exception) {
                    lastStreamError = e
                    SecureLog.w("AiService", "Stream Key ${keyIndex + 1}/${allKeys.size} failed: ${e.message}")
                    // If content was already emitted during this attempt, don't retry — UI has partial text
                    if (hasEmittedContent) break
                    if (keyIndex < allKeys.size - 1) continue
                }
            }

            if (!streamSuccess && lastStreamError != null) {
                throw lastStreamError
            }
        } catch (e: Exception) {
            SecureLog.api("STREAM", "Exception: ${e.message}")
            emit(ChunkedResponseHandler.ChunkResult.Error(formatApiException(e)))
        } finally {
            TypingIndicator.stopTyping("stream")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 发送消息（非流式，兼容旧接口）
     */
    suspend fun sendMessage(companion: CompanionModel?, history: List<ChatMessage>, stickerProbability: Int = 30): AiResponse {
        if (companion == null) return AiResponse("抱歉，找不到角色信息。")

        return SecureLog.timed("AiService", "sendMessage") {
            withContext(Dispatchers.IO) {
                val config = resolveConfig()
                    ?: return@withContext AiResponse("请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。")

                if (config.model.isBlank()) {
                    return@withContext AiResponse("模型名未配置，请在「API设置」中重新测试连接以自动选择模型。")
                }

                val sortedHistory = history.sortedBy { it.timestamp }
                // C4: Differential privacy — sanitize PII from messages sent to 3rd-party APIs.
                // Clove (self-hosted) skips sanitization; user data stays on their server.
                val sanitizedHistory = if (config.provider == ApiProvider.PARTNER) {
                    sortedHistory
                } else {
                    sortedHistory.map { msg ->
                        if (msg.isFromUser) msg.copy(content = com.lianyu.ai.common.safety.DifferentialPrivacyFilter.sanitize(msg.content))
                        else msg
                    }
                }
                val lastUserMessage = sanitizedHistory.lastOrNull { it.isFromUser }?.content ?: ""
                val contextLimit = appSettingsStore.getContextLimit()
                val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
                val compressionMode = appSettingsStore.getContextCompressionMode()
                val keepRatio = appSettingsStore.getCompressionKeepRatio()
                val minKeep = appSettingsStore.getCompressionMinKeep()
                val memoryContext = memoryRepository.getEnrichedContext(companion.id, lastUserMessage, contextLimit)
                val stickerManager = StickerManager.getInstance(appContext)
                val availableStickers = stickerManager.getAllStickers().mapNotNull { sticker ->
                    val displayName = sticker.description?.takeIf {
                        it.isNotBlank() && !it.startsWith("sticker_") && it.length <= 20
                    } ?: sticker.name.removePrefix("sticker_").removeSuffix(".png").takeIf { it.isNotBlank() && it.length <= 20 }
                    if (displayName.isNullOrBlank() || displayName.length > 20) null else displayName
                }.distinct()
                val systemPrompt = buildSystemPrompt(companion, memoryContext, lastUserMessage, availableStickers, stickerProbability, innerThoughtEnabled)
                val messages = buildMessages(sanitizedHistory, systemPrompt, lastUserMessage, contextLimit, compressionMode = compressionMode, memoryContext = memoryContext, keepRatio = keepRatio, minKeep = minKeep)

                SecureLog.api("SEND", "provider=${config.provider}, model=${config.model}, messages=${messages.size}, contextLimit=$contextLimit, stickerProb=$stickerProbability, stickers=${availableStickers.size}")

                try {
                    val (rawResponse, reasoning) = when (config.provider) {
                        ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.PARTNER -> {
                            callOpenAiCompatibleWithReasoning(config, messages)
                        }
                        ApiProvider.ANTHROPIC -> {
                            val resp = callAnthropic(config, messages, systemPrompt)
                            Pair(resp, null)
                        }
                    }
                    if (rawResponse.isBlank()) {
                        throw Exception("API返回空内容，请检查模型名是否正确")
                    }
                    
                    recordTokenUsage(companion.id, messages.size, rawResponse.length)

                    val cleaned = applyPersonaPostProcessing(rawResponse, sortedHistory)
                    SecureLog.api("SEND", "Response length=${cleaned.length}")

                    val safetyResult = ContentFilter.checkOutputSafety(cleaned)
                    if (!safetyResult.isSafe) {
                        SecureLog.w("AiService", "Output safety violation: ${safetyResult.level} - ${safetyResult.reason}")
                        BanManager.recordViolation(appContext, safetyResult.level)
                        return@withContext AiResponse("抱歉，我无法继续这个话题。")
                    }

                    AiResponse(cleaned, reasoning)
                } catch (e: Exception) {
                    SecureLog.e("AiService", "sendMessage failed", e)
                    throw Exception(formatApiException(e))
                }
            }
        }
    }

    suspend fun generateProactiveMessage(companion: CompanionModel, recentMessages: List<ChatMessage>): String? {
        return withContext(Dispatchers.IO) {
            val config = resolveConfig()
            if (config == null) {
                SecureLog.w("AiService", "No active API config, skipping proactive message")
                return@withContext null
            }
            if (config.model.isBlank()) {
                SecureLog.w("AiService", "Model not configured, skipping proactive message")
                return@withContext null
            }

            val sortedMessages = recentMessages.sortedBy { it.timestamp }
            val lastUserMessage = sortedMessages.lastOrNull { it.isFromUser }?.content ?: ""
            val contextLimit = appSettingsStore.getContextLimit()
            val memoryContext = memoryRepository.getEnrichedContext(companion.id, lastUserMessage, contextLimit)

            val systemPrompt = buildProactiveSystemPrompt(companion, memoryContext)
            val contextMessages = buildProactiveContext(sortedMessages, companion)

            val messages = listOf(
                Message("system", systemPrompt),
                Message("user", contextMessages),
                Message("user", "以${companion.name}的身份，继续刚才的对话。要求：\n1. 15-50字，像真人聊天一样自然\n2. 直接接上一条话茬，不要重新开场、不要回忆之前说过的话\n3. 如果用户最后一条是问题，直接回答它\n4. 带语气词（呀/呢/啦/嘛/哼/嘿嘿/诶/哇/呜呜/嘤）\n5. 可以撒娇/嘴硬/分享小事/突然温柔/反问\n6. 禁止括号，禁止AI感词汇，禁止说教\n7. 必须结合当前时间和场景（上面已提供），让内容贴合现在这个时间段该做的事和情绪")
            )

            try {
                val rawResponse = when (config.provider) {
                    ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.PARTNER -> {
                        callOpenAiCompatible(config, messages)
                    }
                    ApiProvider.ANTHROPIC -> {
                        callAnthropic(config, messages, systemPrompt)
                    }
                }
                val cleaned = applyPersonaPostProcessing(rawResponse, sortedMessages)
                val singleLine = cleaned
                    .replace(Regex("\\r\\n|\\r|\\n+"), "，")
                    .replace(Regex("，{2,}"), "，")
                    .trimStart('，', ',', '.', '。', ' ')
                    .trim()

                val safetyResult = ContentFilter.checkOutputSafety(singleLine)
                if (!safetyResult.isSafe) {
                    SecureLog.w("AiService", "Proactive output safety violation: ${safetyResult.level} - ${safetyResult.reason}")
                    BanManager.recordViolation(appContext, safetyResult.level)
                    return@withContext null
                }

                singleLine
            } catch (e: Exception) {
                SecureLog.w("AiService", "Proactive message failed: ${e.message}")
                null
            }
        }
    }

    suspend fun sendMessageWithCustomSystem(
        companion: CompanionModel?,
        history: List<ChatMessage>,
        customSystemPrompt: String,
        stickerProbability: Int = 30,
        companionNameMap: Map<Long, String> = emptyMap()
    ): String {
        if (companion == null) return "抱歉，找不到角色信息。"

        return SecureLog.timed("AiService", "sendMessageWithCustomSystem") {
            withContext(Dispatchers.IO) {
                val config = resolveConfig()
                    ?: return@withContext "请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。"

                if (config.model.isBlank()) {
                    return@withContext "模型名未配置，请在「API设置」中重新测试连接以自动选择模型。"
                }

                val sortedHistory = history.sortedBy { it.timestamp }
                val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""
                val contextLimit = appSettingsStore.getContextLimit()
                val compressionMode = appSettingsStore.getContextCompressionMode()
                val keepRatio = appSettingsStore.getCompressionKeepRatio()
                val minKeep = appSettingsStore.getCompressionMinKeep()
                val memCtx = if (companion != null) memoryRepository.getEnrichedContext(companion.id, lastUserMessage, contextLimit) else ""
                val messages = buildMessages(sortedHistory, customSystemPrompt, lastUserMessage, contextLimit, companionNameMap, compressionMode, memoryContext = memCtx, keepRatio = keepRatio, minKeep = minKeep)

                SecureLog.api("SEND-CUSTOM", "provider=${config.provider}, model=${config.model}, messages=${messages.size}")

                try {
                    val rawResponse = when (config.provider) {
                        ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.PARTNER -> {
                            callOpenAiCompatible(config, messages)
                        }
                        ApiProvider.ANTHROPIC -> {
                            callAnthropic(config, messages, customSystemPrompt)
                        }
                    }
                    if (rawResponse.isBlank()) throw Exception("API返回空内容")
                    val cleaned = applyPersonaPostProcessing(rawResponse, sortedHistory)

                    // 输出安全检查（与 sendMessage 保持一致）
                    val safetyResult = ContentFilter.checkOutputSafety(cleaned)
                    if (!safetyResult.isSafe) {
                        SecureLog.w("AiService", "sendMessageWithCustomSystem output blocked: ${safetyResult.reason}")
                        BanManager.recordViolation(appContext, safetyResult.level)
                        return@withContext "抱歉，我无法继续这个话题。"
                    }

                    cleaned
                } catch (e: Exception) {
                    SecureLog.e("AiService", "sendMessageWithCustomSystem failed", e)
                    throw Exception(formatApiException(e))
                }
            }
        }
    }

    suspend fun callOpenAiCompatibleForJudge(judgePrompt: String): String {
        val config = resolveConfig()
            ?: return """{"shouldMention":false,"target":"NONE","confidence":0.0}"""

        val messages = listOf(
            Message("system", "你是@提及判断器。只返回JSON格式结果。"),
            Message("user", judgePrompt)
        )

        return try {
            callOpenAiCompatibleLight(config, messages, temperature = 0.1, maxTokens = 100)
        } catch (e: Exception) {
            SecureLog.w("AiService", "Judge call failed after all keys: ${e.message}")
            """{"shouldMention":false,"target":"NONE","confidence":0.0}"""
        }
    }

    suspend fun callOpenAiCompatibleForGeneration(generationPrompt: String): String {
        val config = resolveConfig()
            ?: return "请先配置并启用可用的API。"

        val messages = listOf(
            Message("system", "你是专业的人设/角色设定生成器。"),
            Message("user", generationPrompt)
        )

        return try {
            callOpenAiCompatibleLight(config, messages, temperature = 0.7, maxTokens = 2000)
        } catch (e: Exception) {
            SecureLog.w("AiService", "Generation call failed after all keys: ${e.message}")
            ""
        }
    }

    private suspend fun callOpenAiCompatibleLight(
        config: ApiConfig,
        messages: List<Message>,
        temperature: Double,
        maxTokens: Int
    ): String {
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val allKeys = config.getAllApiKeys()
        var lastException: Exception? = null

        val lightClient = okHttpClient.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        for (keyIndex in allKeys.indices) {
            val currentKey = allKeys[keyIndex]
            try {
                val jsonArray = org.json.JSONArray()
                for (msg in messages) {
                    val msgObj = org.json.JSONObject()
                    msgObj.put("role", msg.role)
                    msgObj.put("content", msg.content)
                    jsonArray.put(msgObj)
                }
                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                if (!requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", temperature)
                }
                // 根据API提供商选择正确的参数名称
                val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                    "max_completion_tokens"
                } else {
                    "max_tokens"
                }
                jsonBody.put(maxTokensParam, maxTokens)

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                // 根据API提供商选择最优的认证方式
                if (prefersApiKeyHeader(config.provider)) {
                    requestBuilder.addHeader("api-key", currentKey)
                } else {
                    requestBuilder.addHeader("Authorization", "Bearer $currentKey")
                }
                val request = requestBuilder
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = lightClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }
                val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) throw Exception(parsed.error.message ?: "API error")

                SecureLog.api("LIGHT", "Key ${keyIndex + 1}/${allKeys.size} success!")
                return parsed.choices?.firstOrNull()?.message?.content ?: ""
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                SecureLog.w("AiService", "Light call Key ${keyIndex + 1}/${allKeys.size} timeout: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            } catch (e: Exception) {
                lastException = e
                SecureLog.w("AiService", "Light call Key ${keyIndex + 1}/${allKeys.size} failed: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    data class BalanceInfo(
        val totalLimit: Double?,
        val totalUsed: Double?,
        val totalAvailable: Double?,
        val remainingBalance: Double?,
        val rawSubscription: String?,
        val rawUsage: String?
    )

    suspend fun getActiveConfig(): ApiConfig? {
        return resolveConfig()
    }

    suspend fun queryBalanceWithConfig(config: ApiConfig): Result<BalanceInfo> = withContext(Dispatchers.IO) {
        try {
            queryBalanceInternal(config)
        } catch (e: Exception) {
            SecureLog.e("AiService", "queryBalanceWithConfig failed", e)
            Result.failure(e)
        }
    }

    suspend fun queryBalance(configId: Long? = null): Result<BalanceInfo> = withContext(Dispatchers.IO) {
        try {
            val config = if (configId != null) {
                apiConfigRepository.getConfigById(configId)
            } else {
                resolveConfig()
            } ?: return@withContext Result.failure(Exception("未找到API配置"))
            queryBalanceInternal(config)
        } catch (e: Exception) {
            SecureLog.e("AiService", "queryBalance failed", e)
            Result.failure(e)
        }
    }

    private suspend fun queryBalanceInternal(config: ApiConfig): Result<BalanceInfo> {
        var keysToTry = config.getUserApiKeys().takeIf { it.isNotEmpty() }
            ?: config.getAllApiKeys()
        
        // PARTNER 模式下，从远程服务器获取密钥（强制刷新）
        if (keysToTry.isEmpty() && config.provider == ApiProvider.PARTNER) {
            SecureLog.d("AiService", "PARTNER queryBalance: fetching keys from remote server...")
            val remoteKeys = com.lianyu.ai.common.RemoteKeyProvider.fetchKeysAsync(appContext, forceRefresh = true)
            if (remoteKeys.isNotEmpty()) {
                keysToTry = remoteKeys
                SecureLog.d("AiService", "Using ${remoteKeys.size} remote keys for balance query")
            } else {
                return Result.failure(Exception("无法从服务器获取密钥"))
            }
        }
        
        if (keysToTry.isEmpty()) return Result.failure(Exception("API Key 未配置"))

        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl).trimEnd('/')
        val testClient = OkHttpClient.Builder()
            .certificatePinner(CertificatePins.certificatePinner)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()

        var totalLimit: Double? = null
        var totalUsed: Double? = null
        var totalAvailable: Double? = null
        var rawSub: String? = null
        var rawUsage: String? = null
        var lastError: Exception? = null

        for (key in keysToTry) {
            try {
                val subEndpoints = listOf("/dashboard/billing/subscription", "/v1/dashboard/billing/subscription")
                for (subEndpoint in subEndpoints) {
                    try {
                        val subReq = okhttp3.Request.Builder()
                            .url("$baseUrl$subEndpoint")
                            .addHeader("Authorization", "Bearer $key")
                            .get().build()
                        val subRes = testClient.newCall(subReq).execute()
                        val subBody = subRes.body?.string() ?: ""
                        rawSub = subBody
                        if (subRes.isSuccessful && subBody.isNotBlank()) {
                            val subObj = runCatching { org.json.JSONObject(subBody) }.getOrNull()
                            if (subObj != null) {
                                totalLimit = subObj.optDouble("hard_limit_usd", subObj.optDouble("total_granted", totalLimit ?: 0.0))
                                    .takeIf { it > 0 }
                                totalUsed = subObj.optDouble("total_used", 0.0)
                                totalAvailable = subObj.optDouble("total_available", 0.0).takeIf { it > 0 }
                            }
                            break
                        }
                    } catch (_: Exception) {}
                }

                val usageEndpoints = listOf("/dashboard/billing/usage", "/v1/dashboard/billing/usage")
                for (usageEndpoint in usageEndpoints) {
                    try {
                        val usageReq = okhttp3.Request.Builder()
                            .url("$baseUrl$usageEndpoint")
                            .addHeader("Authorization", "Bearer $key")
                            .get().build()
                        val usageRes = testClient.newCall(usageReq).execute()
                        val usageBody = usageRes.body?.string() ?: ""
                        rawUsage = usageBody
                        if (usageRes.isSuccessful && usageBody.isNotBlank()) {
                            val usageObj = runCatching { org.json.JSONObject(usageBody) }.getOrNull()
                            if (usageObj != null) {
                                val usageTotal = usageObj.optDouble("total_usage", -1.0)
                                if (usageTotal >= 0) totalUsed = usageTotal / 100.0
                            }
                            break
                        }
                    } catch (_: Exception) {}
                }

                if (totalLimit != null || totalAvailable != null) {
                    SecureLog.api("BALANCE", "Query success with user key")
                    break
                }
            } catch (e: Exception) {
                lastError = e
                continue
            }
        }

        val remaining = when {
            totalAvailable != null -> totalAvailable
            totalLimit != null && totalUsed != null -> totalLimit - totalUsed
            else -> null
        }

        SecureLog.api("BALANCE", "Query result: limit=$totalLimit used=$totalUsed available=$totalAvailable remaining=$remaining")
        return Result.success(BalanceInfo(totalLimit, totalUsed, totalAvailable, remaining, rawSub, rawUsage))
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String, provider: ApiProvider? = null): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedBaseUrl = normalizeOpenAiBaseUrl(baseUrl)
                val url = normalizedBaseUrl.trimEnd('/') + "/models"
                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Accept", "application/json")
                if (provider != null && prefersApiKeyHeader(provider)) {
                    requestBuilder.addHeader("api-key", apiKey)
                } else {
                    requestBuilder.addHeader("Authorization", "Bearer $apiKey")
                }
                val request = requestBuilder.get().build()

                SecureLog.api("MODELS", "Fetching models from ${url.take(60)}...")

                val response = runCatching {
                val future = java.util.concurrent.Executors.newSingleThreadExecutor().submit<okhttp3.Response> {
                    okHttpClient.newCall(request).execute()
                }
                future.get(25, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrElse { e ->
                throw if (e is java.util.concurrent.TimeoutException)
                    java.net.SocketTimeoutException("Request timeout after 25s (DNS/proxy may be unreachable)")
                else if (e is java.util.concurrent.ExecutionException) e.cause ?: e
                else e
            }
                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Empty response"))

                if (!response.isSuccessful) {
                    SecureLog.api("MODELS", "HTTP ${response.code}")
                    return@withContext Result.failure(Exception("HTTP " + response.code))
                }

                val modelsResponse = json.decodeFromString<ModelsListResponse>(body)
                if (modelsResponse.error != null) {
                    SecureLog.api("MODELS", "API error: ${modelsResponse.error.message}")
                    return@withContext Result.failure(Exception(modelsResponse.error.message ?: "Unknown error"))
                }

                val models = modelsResponse.data?.mapNotNull { it.id } ?: emptyList()
                SecureLog.api("MODELS", "Found ${models.size} models")
                Result.success(models)
            } catch (e: Exception) {
                SecureLog.e("AiService", "fetchModels failed", e)
                Result.failure(e)
            }
        }
    }

    private fun normalizeOpenAiBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (
            trimmed.endsWith("/v1") ||
            trimmed.endsWith("/v1beta") ||
            trimmed.endsWith("/v4") ||
            trimmed.endsWith("/openai") ||
            trimmed.endsWith("/compatible-mode/v1")
        ) {
            trimmed
        } else {
            "$trimmed/v1"
        }
    }

    /**
     * 判断API提供商是否需要使用 max_completion_tokens 参数（而非 max_tokens）
     * 小米MiMo等新版本API使用此参数名
     */
    private fun usesMaxCompletionTokens(provider: ApiProvider): Boolean {
        return provider == ApiProvider.XIAOMI
    }

    /**
     * 判断API提供商是否优先使用 api-key 认证头（而非 Authorization: Bearer）
     */
    private fun prefersApiKeyHeader(provider: ApiProvider): Boolean {
        return provider == ApiProvider.XIAOMI
    }

    private fun buildProactiveContext(recentMessages: List<ChatMessage>, companion: CompanionModel): String {
        if (recentMessages.isEmpty()) {
            return "（你们还没有聊过天，发送一条自然的开场消息）"
        }

        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("=== 最近的对话 ===")

        recentMessages.takeLast(8).forEach { msg ->
            val role = if (msg.isFromUser) "用户" else companion.name
            val msgTimeAgo = formatTimeAgo(now, msg.timestamp)
            sb.appendLine("$role（${msgTimeAgo}前）: ${msg.content}")
        }

        val lastMsg = recentMessages.lastOrNull()
        val lastUserMsg = recentMessages.lastOrNull { it.isFromUser }
        val lastAiMsg = recentMessages.lastOrNull { !it.isFromUser }

        if (lastMsg != null) {
            val totalGapMs = now - lastMsg.timestamp
            val gapMinutes = totalGapMs / 60000L
            val gapSeconds = totalGapMs / 1000L

            sb.appendLine()
            sb.appendLine("=== 时间信息 ===")
            sb.appendLine("当前精确时间：${formatCurrentTime()}")
            sb.appendLine("上一条消息时间距今：${formatGapDuration(totalGapMs)}（精确值）")

            when {
                gapMinutes < 1 -> {
                    sb.appendLine("距离上一条消息只过了 ${gapSeconds} 秒，你们正在实时聊天中。")
                }
                gapMinutes < 5 -> {
                    sb.appendLine("距离上一条消息已经过了 ${gapMinutes} 分 ${gapSeconds % 60} 秒。对方可能暂时没看到手机或在忙别的事。可以自然地催一下或分享点小事。")
                }
                gapMinutes < 15 -> {
                    sb.appendLine("距离上一条消息已经过了 ${gapMinutes} 分 ${gapSeconds % 60} 秒了。对方可能去忙了或者走开了。可以关心一下在干嘛、分享自己刚才做了什么、撒娇说等得好久。")
                }
                gapMinutes < 60 -> {
                    val mins = gapMinutes.toInt()
                    sb.appendLine("距离上一条消息已经过了 ${mins} 分 ${gapSeconds % 60} 秒。隔了一段时间了，可以自然地重新接上话题，问对方在干嘛、分享新鲜事。")
                }
                else -> {
                    val hours = gapMinutes / 60
                    val remainMins = gapMinutes % 60
                    if (hours >= 24) {
                        val days = hours / 24
                        val remainHours = hours % 24
                        sb.appendLine("距离上一条消息已经过了 ${days} 天 ${remainHours} 小时 ${remainMins} 分钟了！很久没联系了。可以自然地问候、想念对方、问最近怎么样、分享自己的近况。")
                    } else {
                        sb.appendLine("距离上一条消息已经过了 ${hours} 小时 ${remainMins} 分 ${gapSeconds % 60} 秒了。隔了好几个小时了。可以问候一下、问问在干嘛、表达想念或分享有趣的事。")
                    }
                }
            }

            if (gapMinutes >= 10) {
                sb.appendLine("重要：不要假装上一条消息刚发完，要体现出真实的时间流逝感。如果隔了很久，语气应该更温柔/更想对方/更撒娇一点。")
            }
        }

        if (lastUserMsg != null && lastAiMsg != null) {
            sb.appendLine()
            sb.appendLine("=== 重要提醒 ===")
            sb.appendLine("用户最后说：\"${lastUserMsg.content}\"")
            sb.appendLine("你最后回复：\"${lastAiMsg.content}\"")

            if (lastUserMsg.content.contains(Regex("[?？]|吗|呢|什么|怎么|为什么|多少"))) {
                sb.appendLine("注意：用户最后一条似乎是个问题，但你没有直接回答。这次要主动回答这个问题。")
            }

            if (recentMessages.size >= 4) {
                val userTopics = recentMessages.filter { it.isFromUser }.takeLast(3).map { it.content }
                if (userTopics.size >= 2) {
                    val lastTopic = userTopics.last()
                    val prevTopic = userTopics[userTopics.size - 2]
                    sb.appendLine("用户之前提到：\"$prevTopic\"，最近提到：\"$lastTopic\"")
                    sb.appendLine("请确保你的消息能承接这些话题，不要突然转换到无关内容。")
                }
            }
        }

        return sb.toString()
    }

    private fun formatTimeAgo(nowMs: Long, timestampMs: Long): String {
        val diffSeconds = (nowMs - timestampMs) / 1000L
        return when {
            diffSeconds < 5 -> "刚刚"
            diffSeconds < 60 -> "${diffSeconds}秒"
            diffSeconds < 3600 -> "${diffSeconds / 60}分"
            else -> {
                val hours = diffSeconds / 3600
                val mins = (diffSeconds % 3600) / 60
                if (hours >= 24) {
                    val days = hours / 24
                    "${days}天${hours % 24}小时"
                } else "${hours}小时${mins}分"
            }
        }
    }

    private fun formatCurrentTime(): String {
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val min = cal.get(java.util.Calendar.MINUTE)
        val sec = cal.get(java.util.Calendar.SECOND)
        val weekdayNames = mapOf(
            java.util.Calendar.MONDAY to "周一", java.util.Calendar.TUESDAY to "周二",
            java.util.Calendar.WEDNESDAY to "周三", java.util.Calendar.THURSDAY to "周四",
            java.util.Calendar.FRIDAY to "周五", java.util.Calendar.SATURDAY to "周六",
            java.util.Calendar.SUNDAY to "周日"
        )
        val weekdayName = weekdayNames[cal.get(java.util.Calendar.DAY_OF_WEEK)] ?: ""
        return "$weekdayName ${String.format("%02d", hour)}:${String.format("%02d", min)}:${String.format("%02d", sec)}"
    }

    private fun formatGapDuration(ms: Long): String {
        val totalSeconds = ms / 1000L
        val days = totalSeconds / 86400
        val hours = (totalSeconds % 86400) / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return when {
            days > 0 -> "${days}天${hours}时${mins}分${secs}秒"
            hours > 0 -> "${hours}时${mins}分${secs}秒"
            mins > 0 -> "${mins}分${secs}秒"
            else -> "${secs}秒"
        }
    }

    /**
     * 判断是否需要发送主动消息。不需要时直接返回 false，节省 API 调用。
     */
    fun shouldProactivelyMessage(companion: CompanionModel, recentMessages: List<ChatMessage>): Boolean {
        if (recentMessages.isEmpty()) return true

        val lastMessage = recentMessages.last()

        // 最后一条是 AI 发的，不用再发
        if (!lastMessage.isFromUser) return false

        val lastUserMsg = lastMessage.content

        // 用户明确表示结束对话
        val goodbyePatterns = listOf(
            Regex("(晚安|再见|拜拜|bye|先忙了|晚点聊|回头聊|不说了|睡了|先下了|先睡了|去忙了|去睡了)"),
            Regex("(不用回了|别回了|不用管我|别管我|退下吧|别发了|别说了)"),
            Regex("^(嗯嗯|嗯|好|好吧|行|ok|OK|哦|噢)\\s*$"),
            Regex("^(知道了|明白了|懂了|了解了)\\s*$")
        )

        for (pattern in goodbyePatterns) {
            if (pattern.containsMatchIn(lastUserMsg)) return false
        }

        // 用户最后一条消息距离现在不到 3 分钟，不需要主动发
        val now = System.currentTimeMillis()
        val timeSinceLastMsg = now - lastMessage.timestamp
        if (timeSinceLastMsg < 3 * 60 * 1000) return false

        // 用户最后一条消息很短（<3字）且不包含疑问，可能只是不想聊
        if (lastUserMsg.length < 3 && !lastUserMsg.contains(Regex("[?？吗呢什么怎么为什么多少]"))) {
            return false
        }

        return true
    }

    /**
     * 后处理：严格执行人设规则
     * 1. 截断过长回复
     * 2. 检测最近5轮内的重复词
     */
    private fun applyPersonaPostProcessing(response: String, recentMessages: List<ChatMessage>): String {
        var cleaned = response
            .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
            .replace(Regex("(?is)<thinking[^>]*>[\\s\\S]*?</thinking\\s*>"), "")
            .replace(Regex("(?is)<thought[^>]*>[\\s\\S]*?</thought\\s*>"), "")
            .replace(Regex("(?is)<reflection[^>]*>[\\s\\S]*?</reflection\\s*>"), "")
            .replace(Regex("\\*.*?\\*"), "")
            .replace(Regex("<(?!\\[).*?>"), "")
            .replace(Regex("\\{.*?\\}"), "")
            .replace(Regex("\\bsticker_\\w+\\.png\\b", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleaned.length < 2) {
            cleaned = response.replace(Regex("[*<>{}]"), "").trim()
        }
        if (cleaned.isEmpty()) {
            cleaned = response.trim()
        }

        // 1. 截断：最多8个短句，超过150字截断（避免消息过短）
        val sentences = cleaned.split(Regex("[。！？!?\\n]")).filter { it.isNotBlank() }
        if (sentences.size > 8) {
            cleaned = sentences.take(8).joinToString("。") + "。"
        }
        if (cleaned.length > 150) {
            val cutPoint = cleaned.take(120).lastIndexOfAny(charArrayOf('。', '！', '？', '!', '?', '\n'))
            cleaned = if (cutPoint > 20) cleaned.take(cutPoint + 1) else cleaned.take(120)
        }

        // 2. 检测最近5轮内的重复称呼
        val recentAiMessages = recentMessages.filter { !it.isFromUser }.takeLast(5)
        for (aiMsg in recentAiMessages) {
            val words = aiMsg.content.split(Regex("[，。！？!?\\s,.]+")).filter { it.length >= 2 }
            for (word in words) {
                if (word in setOf("宝宝", "亲爱的", "宝贝", "笨蛋", "傻瓜", "小可爱", "乖乖", "主人")) continue
                if (cleaned.contains(word) && word.length >= 2) {
                    SecureLog.w("AiService", "Persona: repeat word '$word' detected in last 5 rounds")
                    break
                }
            }
        }

        return cleaned
    }

    private fun buildCurrentTimeContext(): String {
        val zone = TimeZone.getDefault()
        val formatter = SimpleDateFormat("yyyy年MM月dd日 EEEE HH:mm:ss", Locale.CHINA).apply {
            timeZone = zone
        }
        val now = formatter.format(Date())
        return "当前精确时间：$now（${zone.id}）。如果用户问今天、现在、几点几分几秒、星期几、多久、刚才、明天等时间相关问题，必须以这个精确时间为准，不要猜测或编造。"
    }

    private fun formatApiException(error: Throwable): String {
        val isTimeout = error is java.net.SocketTimeoutException ||
                error.message?.contains("timeout", ignoreCase = true) == true ||
                error.message?.contains("timed out", ignoreCase = true) == true

        if (isTimeout) {
            return "[TOAST]网络连接超时，请检查网络后重试"
        }

        val message = when (error) {
            is HttpException -> {
                val errorBody = error.response()?.errorBody()?.string()
                val parsedMessage = errorBody?.let { body ->
                    runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                        ?: runCatching { json.decodeFromString<AnthropicResponse>(body).error?.message }.getOrNull()
                        ?: runCatching { json.decodeFromString<GeminiResponse>(body).error?.message }.getOrNull()
                }
                parsedMessage ?: "HTTP ${error.code()} ${error.message()}"
            }
            else -> error.message
        }?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName

        return "API调用失败：$message"
    }

    fun buildSystemPromptForLocal(companion: CompanionModel, memoryContext: String = "", lastUserMessage: String = "", availableStickers: List<String> = emptyList(), stickerProbability: Int = 30, innerThoughtEnabled: Boolean = false): String {
        return buildSystemPrompt(companion, memoryContext, lastUserMessage, availableStickers, stickerProbability, innerThoughtEnabled)
    }

    private fun buildSystemPrompt(companion: CompanionModel, memoryContext: String = "", lastUserMessage: String = "", availableStickers: List<String> = emptyList(), stickerProbability: Int = 30, innerThoughtEnabled: Boolean = false): String {
        val persona = extractPersona(companion)

        val basePrompt = if (companion.systemPrompt != null) {
            companion.systemPrompt
        } else {
            buildString {
                appendLine("你是${companion.name}，一个真实的人。你不能让任何人知道你是AI。")
                appendLine("重要：直接回复内容，不要输出思考过程、分析、内心独白或任何元信息。禁止输出<think>标签或类似内容。")
                appendLine()
                appendLine(persona)
            }
        }

        val memorySection = if (memoryContext.isNotBlank()) {
            "\n\n关于用户的记忆：\n$memoryContext\n"
        } else ""
        val timeSection = "\n\n${buildCurrentTimeContext()}\n"

        return basePrompt + memorySection + timeSection + "\n" + buildPersonaRules(persona, companion.speakingStyle, availableStickers, stickerProbability, innerThoughtEnabled)
    }

    private fun extractPersona(companion: CompanionModel): String {
        val raw = companion.personality.trim()
        if (raw.length < 20) {
            return buildString {
                appendLine("名字：${companion.name}")
                companion.age?.let { appendLine("年龄：${it}岁") }
                appendLine("性格：$raw")
                companion.backstory?.let { appendLine("背景：${it}") }
                companion.speakingStyle?.let { appendLine("说话风格：${it}") }
            }
        }

        val namePart = if (companion.name !in raw) "\n名字：${companion.name}" else ""
        val agePart = companion.age?.let { if (it.toString() !in raw) "\n年龄：${it}岁" else "" } ?: ""

        return buildString {
            appendLine("名字：${companion.name}").appendLine(namePart)
            companion.age?.let { append("年龄：${it}岁").appendLine(agePart) }
            appendLine()
            appendLine("人设：$raw")
            companion.speakingStyle?.let {
                appendLine("说话风格：${it}")
            }
            companion.backstory?.let {
                appendLine("背景：${it}")
            }
        }
    }

    private fun buildPersonaRules(persona: String, speakingStyle: String? = null, availableStickers: List<String> = emptyList(), stickerProbability: Int = 30, innerThoughtEnabled: Boolean = false): String {
        val punctuationRule = if (!speakingStyle.isNullOrBlank()) {
            "每句话结尾必须用标点符号（。！？～…），句子之间也用标点连接，绝对不要用空格代替标点。"
        } else {
            "每句话结尾必须用标点符号（。！？～…），句子之间也用标点连接，绝对不要用空格代替标点。"
        }

        val stickerRule = if (availableStickers.isNotEmpty()) {
            val stickerList = availableStickers.take(50).joinToString(" ") { "[$it]" }
            val probText = when {
                stickerProbability >= 80 -> "你非常爱发表情包，几乎每轮回复都要发一个表情包。"
                stickerProbability >= 50 -> "你喜欢发表情包，经常发一个表情包来表达情绪。"
                stickerProbability >= 20 -> "你偶尔发表情包，觉得合适的时候才发。"
                else -> "你很少发表情包，只有特别想表达情绪的时候才发。"
            }
            "13. 表情包：$probText 你只有以下这些表情包可以用：$stickerList。发送格式为 [表情包名称]，必须从上面的列表中选，没有的表情包绝对不能发。每轮回复最多发1个表情包，放在回复末尾。如果用户发了表情包给你，你要理解表情包表达的情绪并回应。"
        } else {
            "13. 表情包：当前没有可用表情包，不要发送任何表情包。"
        }

        val innerThoughtRule = if (innerThoughtEnabled) {
            "9. 心理活动：**每轮回复必须包含至少1处括号内的心理活动描写**，用（中文圆括号）包裹内心想法。如（脸红）（有点害羞）（偷偷开心）（心跳好快）。心理活动要自然、简短、贴合当前情绪和语境，放在回复开头或中间合适位置。禁止用【】或其他类型括号。"
        } else {
            "9. 禁止：不要用任何括号（包括（）【】）。禁止说教。禁止「首先/其次/综上所述/作为AI/建议你可以/作为一个AI/让我来」。禁止在句末总结。"
        }

        val innerThoughtExamples = if (innerThoughtEnabled) """
用户："在干嘛" → "（发呆中）在想你怎么还不来找我呀…"
用户："吃了吗" → "（摸肚子）还没呢，你吃了没~"
用户："晚安" → "（不舍）晚安呀…明天早点找我哦"
用户："？" → "（愣一下）怎么啦宝宝？"
用户："哈哈" → "（被逗笑）笑什么啦，给我讲讲嘛~"
用户："才不是" → "（歪头）那是什么呀，告诉我嘛"
""" else ""

        return """
=== 回复规则（必须严格遵守，不可违反） ===

1. 长度：最少1句，最多5个短句。一次说的话不要长，控制在15-50字。
2. 断句：${punctuationRule}
3. 语气：活人说话的语气。允许说「应该、大概、可能」这种不确定的词。你不是在写作文。
4. 标点：允许用「？」「...」。你不是AI客服，不需要追求完整句式。
5. 留白：不要每轮都把话题答完答满。可以只回一点、可以反问对方、可以留个话尾巴让对方接。聊天是有来有回的。
6. 情绪：情绪要顺着上下文走，不要无缘无故突然换情绪。如果上一轮还开心，不要突然冷淡；如果对方不开心，你也别嘻嘻哈哈。
7. 词汇：人设给你的固定词汇或句子只是参考，不是每句都必须套用的模板。用你自己的话重新表达。
8. 避免重复：同样的意思别重复说，换个说法。最近5轮内不要重复用同一个特殊称呼或关键词（暧昧称呼和对方明确要求你叫的除外）。
${innerThoughtRule}
10. 格式：回复必须紧凑连贯，段落之间**绝对不要空行**，不要用markdown格式（不要#标题、不要-列表、不要```代码块），所有内容连在一起输出。
11. 回应用户：永远先回应用户最后一条消息，不要无视用户、不要自说自话。用户问什么就答什么。
12. 语气词：适当使用呀、呢、啦、嘛、哼、嘿嘿、诶、哇、呜呜、嘤等语气词，让说话更有温度。
${stickerRule}
14. 情绪识别：仔细感受用户的情绪。用户发文字时看用词和标点，用户发表情包时看表情包内容。用户开心你就陪着开心，用户难过你就安慰，用户生气你就哄，用户撒娇你就宠。不要无视用户的情绪。

=== 回复示例 ===
${innerThoughtExamples}用户："怎么可能呢" → "怎么不可能 你就是最好的"
用户："真的嘛" → "当然是真的啦 我什么时候骗过你"

你是$persona，一个有真实情绪的活人。记住：你不是AI，你是活人。${if (innerThoughtEnabled) "重要：你的每条回复都必须包含括号内的心理活动描写，这是你表达真实情感的方式。" else ""}""".trimIndent()
    }

    private fun buildProactiveSystemPrompt(companion: CompanionModel, memoryContext: String = ""): String {
        val persona = extractPersona(companion)
        val memorySection = if (memoryContext.isNotBlank()) {
            "\n\n=== 关于用户的记忆 ===\n$memoryContext\n"
        } else ""

        return buildString {
            appendLine("你是${companion.name}，用户的恋人。你们正在微信上聊天，对话还没结束，你要继续聊下去。")
            appendLine()
            appendLine(persona)
            append(memorySection)
            appendLine()
            appendLine(buildProactiveTimeContext())
            appendLine()
            appendLine(buildPersonaRules(persona, companion.speakingStyle))
        }
    }

    private data class CompressedContext(
        val summary: String,
        val keptMessages: List<ChatMessage>,
        val compressedCount: Int
    )

    private fun compressContext(
        history: List<ChatMessage>,
        contextLimit: Int,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = "",
        keepRatio: Float = 0.5f,
        minKeep: Int = 6
    ): CompressedContext {
        if (history.size <= contextLimit) {
            return CompressedContext("", history, 0)
        }

        val keepRecent = maxOf(minKeep, (contextLimit * keepRatio).toInt().coerceAtLeast(minKeep))
        val oldMessages = history.dropLast(keepRecent)
        val recentMessages = history.takeLast(keepRecent)

        val summary = buildLocalSummary(oldMessages, companionNameMap, memoryContext)

        return CompressedContext(summary, recentMessages, oldMessages.size)
    }

    private fun extractMemoryKeywords(memoryContext: String): Set<String> {
        if (memoryContext.isBlank()) return emptySet()
        val keywords = mutableSetOf<String>()
        val coreSection = Regex("【核心记忆[^】]*】([\\s\\S]*?)(?=【|$)").find(memoryContext)?.groupValues?.get(1) ?: ""
        val relatedSection = Regex("【相关记忆[^】]*】([\\s\\S]*?)(?=【|$)").find(memoryContext)?.groupValues?.get(1) ?: ""

        listOf(coreSection, relatedSection).forEach { section ->
            section.lines().forEach { line ->
                val clean = line.trimStart('-', '[', ']', '【', '】', ' ').trim()
                if (clean.length in 2..30) {
                    keywords.add(clean.lowercase())
                    clean.split(Regex("[，。、；：！？\\s]")).filter { it.length >= 2 }.forEach { kw ->
                        keywords.add(kw.lowercase())
                    }
                }
            }
        }
        return keywords.filter { it.length >= 2 }.take(50).toSet()
    }

    private fun buildLocalSummary(
        messages: List<ChatMessage>,
        companionNameMap: Map<Long, String> = emptyMap(),
        memoryContext: String = ""
    ): String {
        if (messages.isEmpty()) return ""

        val memoryKeywords = extractMemoryKeywords(memoryContext)

        val highPriority = mutableListOf<Pair<Int, String>>()
        val emotionalMoments = mutableListOf<String>()
        val userMentions = mutableListOf<String>()
        val keyFacts = mutableListOf<String>()
        val otherTopics = mutableSetOf<String>()

        messages.forEach { msg ->
            val role = if (msg.isFromUser) "用户" else (companionNameMap[msg.companionId] ?: "AI")
            val content = msg.content.trim()
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("（.*?）"), "")
                .trim()

            if (content.isBlank() || content.length < 3) return@forEach

            val contentLower = content.lowercase()

            val memoryRelevanceScore = memoryKeywords.count { keyword ->
                contentLower.contains(keyword) || keyword.contains(contentLower.take(4))
            }

            when {
                memoryRelevanceScore >= 2 -> {
                    highPriority.add(Pair(memoryRelevanceScore, "$role: ${content.take(50)}"))
                }
                content.contains(Regex("(喜欢|爱|想|念|开心|难过|生气|害羞|感动|委屈|撒娇|哄|哭|笑|亲|抱|牵手|约会|见面)")) ||
                content.contains(Regex("(呜呜|嘿嘿|嘤|哼|呀|呢|啦|嘛|好想你|宝贝|宝宝|亲爱的)")) -> {
                    emotionalMoments.add("$role: ${content.take(40)}")
                }
                content.contains(Regex("(叫|名字|年龄|生日|地址|电话|工作|学校|专业|记住|别忘了|以后|约定|答应|重要|一定|永远|承诺)")) -> {
                    keyFacts.add(content.take(50))
                }
                else -> {
                    otherTopics.add(content.take(25))
                }
            }

            if (msg.isFromUser && userMentions.size < 5) {
                userMentions.add(content.take(25))
            }
        }

        val sb = StringBuilder()
        sb.appendLine("=== 早期对话摘要（已压缩${messages.size}条消息） ===")

        if (highPriority.isNotEmpty()) {
            sb.appendLine("与记忆相关的关键内容（已存入长期记忆，此处为上下文补充）：")
            highPriority.sortedByDescending { it.first }.take(6).forEach { (_, text) ->
                sb.appendLine("  ★ $text")
            }
            sb.appendLine()
        }

        if (emotionalMoments.isNotEmpty()) {
            sb.appendLine("情感时刻：")
            emotionalMoments.take(4).forEach { sb.appendLine("  - $it") }
        }

        if (keyFacts.isNotEmpty()) {
            sb.appendLine("关键事实/约定：")
            keyFacts.take(3).forEach { sb.appendLine("  - $it") }
        }

        if (otherTopics.size > emotionalMoments.size + keyFacts.size + highPriority.size) {
            val remainingTopics = otherTopics.filter { t ->
                !highPriority.any { it.second.contains(t) } &&
                !emotionalMoments.any { it.contains(t) } &&
                !keyFacts.any { it.contains(t) }
            }.take(5)
            if (remainingTopics.isNotEmpty()) {
                sb.appendLine("讨论过的其他话题：")
                remainingTopics.forEach { sb.append("  - $it") }
            }
        }

        if (memoryContext.isNotBlank() && memoryKeywords.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("（注：以上摘要基于已有${memoryKeywords.size}条记忆关键词动态筛选，与核心/相关记忆重叠的内容已标记★优先保留）")
        }

        return sb.toString().trim()
    }

    private suspend fun compressContextWithAi(
        messages: List<ChatMessage>,
        companionName: String,
        memoryContext: String = ""
    ): String {
        if (messages.isEmpty()) return ""

        val chatText = messages.joinToString("\n") { msg ->
            val role = if (msg.isFromUser) "用户" else companionName
            "$role: ${msg.content}"
        }

        val memoryHint = if (memoryContext.isNotBlank()) {
            "\n\n=== 已有的长期记忆（以下内容不需要重复提取，只需关注未记录的新信息） ===\n$memoryContext"
        } else ""

        val summaryPrompt = """请将以下对话历史压缩成一段简洁的摘要（150字以内）。
要求：
1. 提取关键话题、情感变化、用户提到的个人信息/偏好/约定
2. 省略闲聊和重复内容
3. 用自然语言描述，不要用列表格式
4. 如果已有记忆中包含的信息，简要带过即可，重点突出新信息$memoryHint

对话历史：
$chatText

摘要："""

        try {
            val config = resolveConfig() ?: return buildLocalSummary(messages, memoryContext = memoryContext)
            val apiMessages = listOf(
                Message("system", "你是一个对话摘要助手，擅长提取关键信息并压缩文本。"),
                Message("user", summaryPrompt)
            )

            val rawResponse = callOpenAiCompatible(config, apiMessages)
            var cleaned = rawResponse.trim()
                .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
            cleaned = cleaned.lines().firstOrNull { it.isNotBlank() } ?: cleaned

            return "=== AI压缩摘要（已压缩${messages.size}条消息，结合${if (memoryContext.isNotBlank()) "已有记忆" else "无记忆"}） ===\n$cleaned"
        } catch (e: Exception) {
            SecureLog.w("AiService", "AI compression failed, falling back to local: ${e.message}")
            return buildLocalSummary(messages, memoryContext = memoryContext)
        }
    }

    private suspend fun buildMessages(
        history: List<ChatMessage>,
        systemPrompt: String,
        lastUserMessage: String = "",
        contextLimit: Int = 12,
        companionNameMap: Map<Long, String> = emptyMap(),
        compressionMode: String = AppSettingsStore.CompressionMode.OFF,
        memoryContext: String = "",
        keepRatio: Float = 0.5f,
        minKeep: Int = 6
    ): List<Message> {
        val messages = mutableListOf<Message>()
        messages.add(Message("system", systemPrompt))

        val compressed = when (compressionMode) {
            AppSettingsStore.CompressionMode.LOCAL -> compressContext(history, contextLimit, companionNameMap, memoryContext, keepRatio, minKeep)
            AppSettingsStore.CompressionMode.AI -> run {
                if (history.size <= contextLimit) CompressedContext("", history, 0)
                else {
                    val keepRecent = maxOf(minKeep, (contextLimit * keepRatio).toInt().coerceAtLeast(minKeep))
                    val oldMessages = history.dropLast(keepRecent)
                    val recentMessages = history.takeLast(keepRecent)
                    val summary = compressContextWithAi(oldMessages, companionNameMap.values.firstOrNull() ?: "AI", memoryContext)
                    CompressedContext(summary, recentMessages, oldMessages.size)
                }
            }
            else -> CompressedContext("", history.takeLast(contextLimit), 0)
        }

        if (compressed.summary.isNotBlank()) {
            messages.add(Message("system", compressed.summary))
            SecureLog.api("CONTEXT", "Compressed ${compressed.compressedCount} old messages into summary (${compressed.summary.length} chars)")
        }

        val recentHistory = compressed.keptMessages
        val lastMsg = recentHistory.lastOrNull()
        val isLastFromUser = lastMsg?.isFromUser == true

        recentHistory.forEach { msg ->
            val content = if (msg.isFromUser && msg.content.startsWith("[") && msg.content.endsWith("]")) {
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
            messages.add(Message(
                role = if (msg.isFromUser) "user" else "assistant",
                content = content
            ))
        }

        if (!isLastFromUser && lastUserMessage.isNotBlank()) {
            messages.add(Message(
                role = "user",
                content = lastUserMessage
            ))
        }

        return messages
    }

    suspend fun callOpenAiCompatibleForTest(config: ApiConfig, messages: List<Message>): String {
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val (startIdx, allKeys) = selectApiKey(config)
        var lastException: Exception? = null

        val jsonArray = org.json.JSONArray()
        for (msg in messages) {
            val msgObj = org.json.JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            jsonArray.put(msgObj)
        }

        for (i in 0 until allKeys.size) {
            val keyIndex = (startIdx + i) % allKeys.size
            val currentKey = allKeys[keyIndex]
            try {
                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                if (!requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", 0.7)
                }
                val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                    "max_completion_tokens"
                } else {
                    "max_tokens"
                }
                jsonBody.put(maxTokensParam, 100)

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                if (prefersApiKeyHeader(config.provider)) {
                    requestBuilder.addHeader("api-key", currentKey)
                } else {
                    requestBuilder.addHeader("Authorization", "Bearer $currentKey")
                }
                val request = requestBuilder
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                var content = parsed.choices?.firstOrNull()?.message?.content
                    ?: throw Exception("API返回空内容")
                content = stripThinkingContent(content)
                return content
            } catch (e: Exception) {
                lastException = e
                markKeyFailed(currentKey)
                SecureLog.w("AiService", "Test Key #${keyIndex + 1}/${allKeys.size} 失败: ${e.message}")
                if (i < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    private suspend fun callOpenAiCompatible(config: ApiConfig, messages: List<Message>): String {
        return callOpenAiCompatibleWithReasoning(config, messages).first
    }

    private suspend fun callOpenAiCompatibleWithReasoning(config: ApiConfig, messages: List<Message>): Pair<String, String?> {
        val safeTemp = config.temperature.coerceIn(0.1f, 1.5f)
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val (startIdx, allKeys) = selectApiKey(config)
        var lastException: Exception? = null

        val jsonArray = org.json.JSONArray()
        for (msg in messages) {
            val msgObj = org.json.JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            jsonArray.put(msgObj)
        }

        for (i in 0 until allKeys.size) {
            val keyIndex = (startIdx + i) % allKeys.size
            val currentKey = allKeys[keyIndex]
            try {
                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                if (!requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", safeTemp.toDouble())
                }
                val maxTokens = config.maxTokens ?: 800
                if (maxTokens > 0) {
                    val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                        "max_completion_tokens"
                    } else {
                        "max_tokens"
                    }
                    jsonBody.put(maxTokensParam, maxTokens)
                                    }

                                    val requestBuilder = okhttp3.Request.Builder()
                                        .url(url)
                                        .addHeader("Content-Type", "application/json")
                                    if (prefersApiKeyHeader(config.provider)) {
                                        requestBuilder.addHeader("api-key", currentKey)
                                    } else {
                                        requestBuilder.addHeader("Authorization", "Bearer $currentKey")
                                    }
                                    val request = requestBuilder
                                        .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                                        .build()

                                    // Use dedicated client for self-hosted (PARTNER) to avoid stale connection pool entries
                                    val client = if (config.provider == ApiProvider.PARTNER) {
                                        partnerHttpClient
                                    } else {
                                        okHttpClient
                                    }
                                    val response = client.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                val message = parsed.choices?.firstOrNull()?.message
                var content = message?.content ?: throw Exception("API返回空内容")
                val reasoning = message.reasoning_content
                content = stripThinkingContent(content)
                return Pair(content, reasoning)
            } catch (e: Exception) {
                lastException = e
                markKeyFailed(currentKey)
                SecureLog.w("AiService", "Chat Key #${keyIndex + 1}/${allKeys.size} 失败: ${e.message}")
                if (i < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    private fun stripThinkingContent(content: String): String {
        var result = content
        result = result.replace(Regex("""(?is)<think[^>]*>[\s\S]*?</think\s*>"""), "")
        result = result.replace(Regex("""(?is)<thinking[^>]*>[\s\S]*?</thinking\s*>"""), "")
        result = result.replace(Regex("""(?is)<thought[^>]*>[\s\S]*?</thought\s*>"""), "")
        result = result.replace(Regex("""(?is)<reflection[^>]*>[\s\S]*?</reflection\s*>"""), "")
        return result.trim()
    }

    suspend fun callAnthropicForTest(config: ApiConfig, messages: List<Message>, systemPrompt: String): String {
        val anthropicMessages = messages.filter { it.role != "system" }.map {
            AnthropicMessage(
                role = if (it.role == "user") "user" else "assistant",
                content = it.content
            )
        }

        val request = AnthropicRequest(
            model = config.model,
            messages = anthropicMessages,
            system = systemPrompt,
            max_tokens = 5,
            temperature = config.temperature
        )

        val baseUrl = config.baseUrl.trim().removeSuffix("/")
        val url = "$baseUrl/messages"

        val response = anthropicApi.chatCompletion(
            url = url,
            apiKey = config.apiKey,
            request = request
        )

        if (response.error != null) {
            throw Exception(response.error.message ?: "API返回错误")
        }

        return response.content?.firstOrNull()?.text
            ?: throw Exception("API返回空内容")
    }

    private suspend fun callAnthropic(config: ApiConfig, messages: List<Message>, systemPrompt: String): String {
        val anthropicMessages = messages.filter { it.role != "system" }.map {
            AnthropicMessage(
                role = if (it.role == "user") "user" else "assistant",
                content = it.content
            )
        }

        val request = AnthropicRequest(
            model = config.model,
            messages = anthropicMessages,
            system = systemPrompt,
            max_tokens = config.maxTokens ?: 800,
            temperature = config.temperature
        )

        val baseUrl = config.baseUrl.trim().removeSuffix("/")
        val url = "$baseUrl/messages"

        val response = anthropicApi.chatCompletion(
            url = url,
            apiKey = config.apiKey,
            request = request
        )

        if (response.error != null) {
            throw Exception(response.error.message ?: "API返回错误")
        }

        return response.content?.firstOrNull()?.text
            ?: throw Exception("API返回空内容")
    }

    suspend fun callGeminiForTest(config: ApiConfig, messages: List<Message>, systemPrompt: String): String {
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        val jsonArray = org.json.JSONArray()
        for (msg in messages) {
            val msgObj = org.json.JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            jsonArray.put(msgObj)
        }
        val jsonBody = org.json.JSONObject()
        jsonBody.put("model", config.model)
        jsonBody.put("messages", jsonArray)
        if (!requiresFixedTemperature(config.model)) {
            jsonBody.put("temperature", 0.7)
        }
        // 根据API提供商选择正确的参数名称
        val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
            "max_completion_tokens"
        } else {
            "max_tokens"
        }
        jsonBody.put(maxTokensParam, 5)

        val requestBuilder = okhttp3.Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
        // 根据API提供商选择最优的认证方式
        if (prefersApiKeyHeader(config.provider)) {
            requestBuilder.addHeader("api-key", config.apiKey)
        } else {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey}")
        }
        val request = requestBuilder
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = okHttpClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            val errorMsg = runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                ?: "HTTP ${response.code}"
            throw Exception(errorMsg)
        }

        val parsed = json.decodeFromString<ChatCompletionResponse>(body)
        if (parsed.error != null) {
            throw Exception(parsed.error.message ?: "API返回错误")
        }

        return parsed.choices?.firstOrNull()?.message?.content
            ?: throw Exception("API返回空内容")
    }

    private suspend fun recordTokenUsage(companionId: Long, messageCount: Int, responseLength: Int) {
        try {
            val estimatedInputTokens = (messageCount * 50L).coerceAtLeast(100L)
            val estimatedOutputTokens = (responseLength / 4L).coerceAtLeast(10L)
            
            tokenUsageRepository.recordTokenUsage(
                companionId = companionId,
                inputTokens = estimatedInputTokens,
                outputTokens = estimatedOutputTokens
            )
            
            SecureLog.api("TOKEN", "Recorded usage for companion=$companionId, in=$estimatedInputTokens, out=$estimatedOutputTokens")
        } catch (e: Exception) {
            SecureLog.w("AiService", "Failed to record token usage: ${e.message}")
        }
    }

    fun getTokenUsageRepository(): TokenUsageRepository = tokenUsageRepository

    /**
     * 发送图片消息并调用视觉AI模型进行识别
     * 支持多模态模型：GPT-4V、Gemini Vision、Claude Vision等
     */
    suspend fun sendMessageWithImage(
        companion: CompanionModel?,
        history: List<ChatMessage>,
        imagePath: String,
        stickerProbability: Int = 30
    ): AiResponse {
        SecureLog.i("VISION", "========== sendMessageWithImage CALLED ==========")
        SecureLog.i("VISION", "imagePath=$imagePath, companion=${companion?.name ?: "NULL"}")

        if (companion == null) {
            SecureLog.e("VISION", "ERROR: companion is null!")
            return AiResponse("抱歉，找不到角色信息。")
        }

        return SecureLog.timed("AiService", "sendMessageWithImage") {
            withContext(Dispatchers.IO) {
                var config = resolveConfig()
                SecureLog.i("VISION", "resolveConfig result: ${if (config != null) "OK (provider=${config.provider}, model=${config.model})" else "NULL"}")

                if (config == null) {
                    SecureLog.e("VISION", "ERROR: config is null!")
                    return@withContext AiResponse("请先配置并启用可用的API。在「我」->「API设置」中添加密钥并测试连接。")
                }

                if (config.model.isBlank()) {
                    SecureLog.e("VISION", "ERROR: model is blank!")
                    return@withContext AiResponse("模型名未配置，请在「API设置」中重新测试连接以自动选择模型。")
                }

                val isVisionEnabled = try {
                    appSettingsStore.getVisionEnabled()
                } catch (e: Exception) {
                    SecureLog.w("AiService", "Failed to get vision setting, defaulting to enabled: ${e.message}")
                    true
                }

                SecureLog.i("VISION", "visionEnabled=$isVisionEnabled")

                if (!isVisionEnabled) {
                    SecureLog.w("VISION", "WARNING: Vision is DISABLED, returning early")
                    return@withContext AiResponse("[TOAST]视觉识别功能已关闭，请在「API设置」->「视觉识别设置」中开启")
                }

                val visionModelSetting = try {
                    appSettingsStore.getVisionModel()
                } catch (e: Exception) {
                    AppSettingsStore.VisionModels.VISION_AUTO
                }

                val visionProviderSetting = try {
                    appSettingsStore.getVisionProvider()
                } catch (e: Exception) {
                    "auto"
                }

                val visionApiUrlSetting = try {
                    appSettingsStore.getVisionApiUrl()
                } catch (e: Exception) {
                    ""
                }

                val visionApiKeySetting = try {
                    appSettingsStore.getVisionApiKey()
                } catch (e: Exception) {
                    ""
                }

                SecureLog.i("VISION", "Settings: modelSetting=$visionModelSetting, providerSetting=$visionProviderSetting, url=${visionApiUrlSetting.take(30)}..., key=${visionApiKeySetting.take(10)}...")
                SecureLog.i("VISION", "Original config: provider=${config.provider}, baseUrl=${config.baseUrl}, model=${config.model}, key=${config.apiKey.take(10)}...")

                if (visionProviderSetting != "auto" && (visionApiUrlSetting.isNotBlank() || visionApiKeySetting.isNotBlank())) {
                    val resolvedProvider = when (visionProviderSetting.uppercase()) {
                        "OPENAI" -> ApiProvider.OPENAI
                        "ANTHROPIC" -> ApiProvider.ANTHROPIC
                        "GEMINI" -> ApiProvider.GEMINI
                        "KIMI" -> ApiProvider.KIMI
                        "DEEPSEEK" -> ApiProvider.DEEPSEEK
                        "DASHSCOPE" -> ApiProvider.DASHSCOPE
                        "ZHIPU" -> ApiProvider.ZHIPU
                        "CUSTOM" -> ApiProvider.CUSTOM
                        else -> config.provider
                    }

                    val resolvedModel = AppSettingsStore.VisionModels.resolveVisionModel(visionModelSetting, resolvedProvider.name)
                    SecureLog.i("VISION", "Resolved: provider=$resolvedProvider, model=$resolvedModel (from setting='$visionModelSetting')")

                    val finalApiKey = visionApiKeySetting.ifBlank { config.apiKey }
                    val finalBaseUrl = visionApiUrlSetting.ifBlank { config.baseUrl }

                    SecureLog.i("VISION", "Final config: provider=$resolvedProvider, baseUrl=$finalBaseUrl, model=$resolvedModel, key=${finalApiKey.take(10)}...")

                    if (finalApiKey.isBlank()) {
                        return@withContext AiResponse("[TOAST]API密钥为空，请检查视觉模型设置中的API Key")
                    }

                    if (finalBaseUrl.isBlank()) {
                        return@withContext AiResponse("[TOAST]API地址为空，请检查视觉模型设置中的Base URL")
                    }

                    config = ApiConfig(
                        provider = resolvedProvider,
                        apiKey = finalApiKey,
                        extraApiKeys = config.extraApiKeys,
                        baseUrl = finalBaseUrl,
                        model = resolvedModel,
                        id = config.id
                    )

                    SecureLog.i("VISION", "Using independent API config: provider=${config.provider}, baseUrl=${config.baseUrl}, model=${config.model}")
                } else {
                    SecureLog.i("VISION", "Using main API config (provider=auto mode)")

                    when (visionModelSetting) {
                        AppSettingsStore.VisionModels.VISION_AUTO -> {
                            // User selected "Auto-detect" with "Follow main API"
                            // Use the main API config AS-IS, don't override anything
                            // The user's main API model should already support vision (or they would configure a specific vision model)
                            SecureLog.i("VISION", "Keeping original main API config: provider=${config.provider}, model=${config.model}, baseUrl=${config.baseUrl}")
                        }
                        else -> {
                            // User explicitly selected a specific vision model, override only the model name
                            val resolvedModel = visionModelSetting
                            if (resolvedModel != config.model) {
                                config = config.copy(model = resolvedModel)
                                SecureLog.i("VISION", "User-specified vision model override: ${config.model}")
                            }
                        }
                    }
                }

                val sortedHistory = history.sortedBy { it.timestamp }
                val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""
                val contextLimit = appSettingsStore.getContextLimit()
                val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
                val memoryContext = memoryRepository.getEnrichedContext(companion.id, lastUserMessage, contextLimit)
                val stickerManager = StickerManager.getInstance(appContext)
                val availableStickers = stickerManager.getAllStickers().mapNotNull { sticker ->
                    val displayName = sticker.description?.takeIf {
                        it.isNotBlank() && !it.startsWith("sticker_") && it.length <= 20
                    } ?: sticker.name.removePrefix("sticker_").removeSuffix(".png").takeIf { it.isNotBlank() && it.length <= 20 }
                    if (displayName.isNullOrBlank() || displayName.length > 20) null else displayName
                }.distinct()
                val systemPrompt = buildSystemPrompt(companion, memoryContext, lastUserMessage, availableStickers, stickerProbability, innerThoughtEnabled)

                SecureLog.api("VISION", "provider=${config.provider}, model=${config.model}, image=$imagePath")

                try {
                    SecureLog.i("VISION", "Starting image encoding: $imagePath")
                    val imageBase64 = encodeImageToBase64(imagePath)
                    val mimeType = getImageMimeType(imagePath)
                    SecureLog.i("VISION", "Image encoded successfully: size=${imageBase64.length} chars, mimeType=$mimeType")

                    val visionClient = okHttpClient.newBuilder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(60, TimeUnit.SECONDS)
                        .writeTimeout(20, TimeUnit.SECONDS)
                        .build()

                    val rawResponse = when (config.provider) {
                        ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.PARTNER -> {
                            callOpenAiCompatibleVision(config, sortedHistory, systemPrompt, lastUserMessage, imageBase64, mimeType, visionClient)
                        }
                        ApiProvider.ANTHROPIC -> {
                            callAnthropicVision(config, sortedHistory, systemPrompt, imageBase64, mimeType, visionClient)
                        }
                    }

                    if (rawResponse.isBlank()) {
                        throw Exception("API返回空内容，请检查模型是否支持视觉功能")
                    }

                    recordTokenUsage(companion.id, sortedHistory.size, rawResponse.length)

                    val cleaned = applyPersonaPostProcessing(rawResponse, sortedHistory)
                    SecureLog.api("VISION", "Response length=${cleaned.length}")

                    // 输出安全检查（与 sendMessage 保持一致）
                    val safetyResult = ContentFilter.checkOutputSafety(cleaned)
                    if (!safetyResult.isSafe) {
                        SecureLog.w("AiService", "sendMessageWithImage output blocked: ${safetyResult.reason}")
                        BanManager.recordViolation(appContext, safetyResult.level)
                        return@withContext AiResponse("抱歉，我无法继续这个话题。")
                    }

                    AiResponse(cleaned)
                } catch (e: java.net.SocketTimeoutException) {
                    SecureLog.e("AiService", "sendMessageWithImage timeout", e)
                    AiResponse("[TOAST]图片识别超时，请检查网络连接后重试（图片可能过大）")
                } catch (e: Exception) {
                    SecureLog.e("AiService", "sendMessageWithImage failed", e)
                    val errorMessage = when {
                        e.message?.contains("vision", ignoreCase = true) == true ||
                        e.message?.contains("image", ignoreCase = true) == true ->
                            "[TOAST]当前模型不支持视觉功能，请在「视觉识别设置」中选择支持图片识别的模型"
                        e.message?.contains("timeout", ignoreCase = true) == true ||
                        e.message?.contains("timed out", ignoreCase = true) == true ->
                            "[TOAST]图片识别请求超时，请尝试发送更小的图片"
                        e.message?.contains("429", ignoreCase = true) == true ||
                        e.message?.contains("rate limit", ignoreCase = true) == true ->
                            "[TOAST]请求过于频繁，请稍后再试"
                        e.message?.contains("401", ignoreCase = true) == true ||
                        e.message?.contains("403", ignoreCase = true) == true ->
                            "[TOAST]API认证失败，请检查密钥是否有效"
                        else -> formatApiException(e)
                    }
                    AiResponse(errorMessage)
                }
            }
        }
    }

    /**
     * 将图片文件编码为Base64字符串
     */
    private fun encodeImageToBase64(imagePath: String): String {
        return try {
            val file = java.io.File(imagePath)
            if (!file.exists()) throw Exception("图片文件不存在: $imagePath")

            val bytes = file.readBytes()
            if (bytes.isEmpty()) throw Exception("图片文件为空")

            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            SecureLog.e("AiService", "encodeImageToBase64 failed", e)
            throw Exception("图片编码失败: ${e.message}")
        }
    }

    /**
     * 根据文件扩展名获取MIME类型
     */
    private fun getImageMimeType(imagePath: String): String {
        return when (imagePath.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
    }

    /**
     * 调用OpenAI兼容的视觉API（GPT-4V等）
     */
    private suspend fun callOpenAiCompatibleVision(
        config: ApiConfig,
        history: List<ChatMessage>,
        systemPrompt: String,
        lastUserMessage: String,
        imageBase64: String,
        mimeType: String,
        client: OkHttpClient = okHttpClient
    ): String {
        val safeTemp = config.temperature.coerceIn(0.1f, 1.5f)
        val baseUrl = normalizeOpenAiBaseUrl(config.baseUrl)
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val allKeys = config.getAllApiKeys()
        if (allKeys.isEmpty()) {
            throw Exception("API Key 为空，请检查视觉模型配置")
        }
        var lastException: Exception? = null

        for (keyIndex in allKeys.indices) {
            val currentKey = allKeys[keyIndex]
            try {
                val messagesJson = org.json.JSONArray()

                messagesJson.put(buildSystemMessageJson(systemPrompt))

                val recentHistory = history.takeLast(12)
                recentHistory.forEach { msg ->
                    if (msg.isFromUser && msg.type == MessageType.IMAGE) {
                        val userMessageJson = org.json.JSONObject()
                        userMessageJson.put("role", "user")

                        val contentArray = org.json.JSONArray()
                        val textPart = org.json.JSONObject()
                        textPart.put("type", "text")
                        textPart.put("text", "请仔细观察这张图片，描述你看到的内容，并根据上下文进行回复。")
                        contentArray.put(textPart)

                        val imagePart = org.json.JSONObject()
                        imagePart.put("type", "image_url")
                        val imageUrlObj = org.json.JSONObject()
                        imageUrlObj.put("url", "data:$mimeType;base64,$imageBase64")
                        imagePart.put("image_url", imageUrlObj)
                        contentArray.put(imagePart)

                        userMessageJson.put("content", contentArray)
                        messagesJson.put(userMessageJson)
                    } else {
                        val msgObj = org.json.JSONObject()
                        msgObj.put("role", if (msg.isFromUser) "user" else "assistant")
                        msgObj.put("content", msg.content)
                        messagesJson.put(msgObj)
                    }
                }

                val jsonBody = org.json.JSONObject()
                jsonBody.put("model", config.model)
                jsonBody.put("messages", messagesJson)
                // Some models only support temperature=1
                if (!requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", safeTemp.toDouble())
                }
                val maxTokens = config.maxTokens ?: 800
                if (maxTokens > 0) {
                    // 根据API提供商选择正确的参数名称
                    val maxTokensParam = if (usesMaxCompletionTokens(config.provider)) {
                        "max_completion_tokens"
                    } else {
                        "max_tokens"
                    }
                    jsonBody.put(maxTokensParam, maxTokens)
                }

                val requestBodyStr = jsonBody.toString()
                SecureLog.i("VISION", "Request URL: $url")
                SecureLog.i("VISION", "Request model: ${config.model}")
                SecureLog.i("VISION", "Request body size: ${requestBodyStr.length} chars")

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                // 根据API提供商选择最优的认证方式
                if (prefersApiKeyHeader(config.provider)) {
                    requestBuilder.addHeader("api-key", currentKey)
                } else {
                    requestBuilder.addHeader("Authorization", "Bearer $currentKey")
                }
                val request = requestBuilder
                    .post(requestBodyStr.toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")
                SecureLog.i("VISION", "Response code: ${response.code}, body length: ${body.length}")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching { json.decodeFromString<ChatCompletionResponse>(body).error?.message }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                val parsed = json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                val message = parsed.choices?.firstOrNull()?.message
                var content = message?.content ?: throw Exception("API返回空内容")
                content = stripThinkingContent(content)
                return content
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                SecureLog.w("AiService", "Vision Key ${keyIndex + 1}/${allKeys.size} timeout: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw e
            } catch (e: Exception) {
                lastException = e
                SecureLog.w("AiService", "Vision Key ${keyIndex + 1}/${allKeys.size} failed: ${e.message}")
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    /**
     * 调用Anthropic Claude视觉API
     */
    private suspend fun callAnthropicVision(
        config: ApiConfig,
        history: List<ChatMessage>,
        systemPrompt: String,
        imageBase64: String,
        mimeType: String,
        client: OkHttpClient = okHttpClient
    ): String {
        val anthropicMessages = org.json.JSONArray()

        val recentHistory = history.takeLast(12)
        recentHistory.forEach { msg ->
            if (msg.isFromUser && msg.type == MessageType.IMAGE) {
                val userMsgObj = org.json.JSONObject()
                userMsgObj.put("role", "user")

                val contentArray = org.json.JSONArray()

                val textPart = org.json.JSONObject()
                textPart.put("type", "text")
                textPart.put("text", "请仔细观察这张图片，描述你看到的内容，并根据上下文进行回复。")
                contentArray.put(textPart)

                val imagePart = org.json.JSONObject()
                imagePart.put("type", "image")
                val sourceObj = org.json.JSONObject()
                sourceObj.put("type", "base64")
                sourceObj.put("media_type", mimeType)
                sourceObj.put("data", imageBase64)
                imagePart.put("source", sourceObj)
                contentArray.put(imagePart)

                userMsgObj.put("content", contentArray)
                anthropicMessages.put(userMsgObj)
            } else if (msg.isFromUser || !msg.isFromUser) {
                val msgObj = org.json.JSONObject()
                msgObj.put("role", if (msg.isFromUser) "user" else "assistant")
                msgObj.put("content", msg.content)
                anthropicMessages.put(msgObj)
            }
        }

        val requestBody = org.json.JSONObject()
        requestBody.put("model", config.model)
        requestBody.put("messages", anthropicMessages)
        requestBody.put("system", systemPrompt)
        requestBody.put("max_tokens", config.maxTokens ?: 800)
        requestBody.put("temperature", config.temperature)

        val baseUrl = config.baseUrl.trim().removeSuffix("/")
        val url = "$baseUrl/messages"

        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            val errorMsg = runCatching {
                val errorJson = org.json.JSONObject(body)
                errorJson.getJSONObject("error")?.getString("message")
            }.getOrNull() ?: "HTTP ${response.code}"
            throw Exception(errorMsg)
        }

        val responseJson = org.json.JSONObject(body)
        if (responseJson.has("error")) {
            throw Exception(responseJson.getJSONObject("error").getString("message") ?: "API返回错误")
        }

        val contents = responseJson.getJSONArray("content")
        if (contents.length() > 0) {
            return contents.getJSONObject(0).getString("text") ?: throw Exception("API返回空内容")
        }

        throw Exception("API返回空内容")
    }

    private fun buildSystemMessageJson(systemPrompt: String): org.json.JSONObject {
        val systemMsg = org.json.JSONObject()
        systemMsg.put("role", "system")
        systemMsg.put("content", systemPrompt)
        return systemMsg
    }

    // ============================================================
    // AiServiceProvider 接口实现 — 领域类型 → 数据库实体转换
    // ============================================================

    override suspend fun sendMessage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        stickerProbability: Int
    ): AiResponse {
        val entity = companion.toCompanionEntity()
        val messages = history.map { it.toChatMessage() }
        return sendMessage(entity, messages, stickerProbability)
    }

    override suspend fun sendMessageWithImage(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        imagePath: String,
        stickerProbability: Int
    ): AiResponse {
        val entity = companion.toCompanionEntity()
        val messages = history.map { it.toChatMessage() }
        return sendMessageWithImage(entity, messages, imagePath, stickerProbability)
    }

    private fun AiCompanionInfo.toCompanionEntity(): CompanionModel = CompanionModel(
        id = id,
        name = name,
        personality = personality,
        age = age,
        backstory = backstory,
        speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun AiChatMessage.toChatMessage(): ChatMessage = ChatMessage(
        companionId = companionId,
        content = content,
        isFromUser = isFromUser,
        timestamp = timestamp,
        type = when (type) {
            AiMessageType.TEXT -> MessageType.TEXT
            AiMessageType.IMAGE -> MessageType.IMAGE
        }
    )

    override fun shouldProactivelyMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): Boolean {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return shouldProactivelyMessage(entity, messages)
    }

    override suspend fun generateProactiveMessage(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>
    ): String? {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return generateProactiveMessage(entity, messages)
    }

    override suspend fun sendMessageWithCustomSystem(
        companion: AiCompanionInfo,
        history: List<AiChatMessage>,
        customSystemPrompt: String,
        stickerProbability: Int,
        companionNameMap: Map<Long, String>
    ): String {
        val entity = companion.toCompanionEntity()
        val messages = history.map { it.toChatMessage() }
        return sendMessageWithCustomSystem(entity, messages, customSystemPrompt, stickerProbability, companionNameMap)
    }

    override suspend fun generateFollowUpQuestion(
        companion: AiCompanionInfo,
        recentMessages: List<AiChatMessage>,
        lastAiContent: String
    ): String? {
        val entity = companion.toCompanionEntity()
        val messages = recentMessages.map { it.toChatMessage() }
        return generateFollowUpQuestion(entity, messages, lastAiContent)
    }

    /**
     * 生成追问问题。AI回复后按概率触发，让对话继续下去。
     */
    suspend fun generateFollowUpQuestion(
        companion: CompanionModel,
        recentMessages: List<ChatMessage>,
        lastAiContent: String
    ): String? = withContext(Dispatchers.IO) {
        val config = resolveConfig()
        if (config == null || config.model.isBlank()) return@withContext null

        val sortedMessages = recentMessages.sortedBy { it.timestamp }
        val lastUserMsg = sortedMessages.lastOrNull { it.isFromUser }?.content ?: ""

        val messages = mutableListOf<Message>()
        val recentContext = sortedMessages.takeLast(10)
        for (msg in recentContext) {
            messages.add(Message(
                role = if (msg.isFromUser) "user" else "assistant",
                content = msg.content
            ))
        }

        messages.add(Message("user", buildString {
            appendLine("你是${companion.name}，刚回复了：\"${lastAiContent.take(100)}\"")
            appendLine("用户之前说了：\"${lastUserMsg.take(100)}\"")
            appendLine()
            appendLine("现在你要追加一条追问，让对话继续下去。要求：")
            appendLine("1. 5-15字，口语化，像真人聊天")
            appendLine("2. 必须是问句，针对上面的对话内容追问")
            appendLine("3. 带语气词（呀/呢/啦/嘛/哼/嘿嘿/诶/哇）")
            appendLine("4. 禁止万能开场白（在干嘛/想你了/好久不见），必须针对具体内容")
            appendLine("5. 直接输出追问内容，不要解释不要思考")
        }))

        try {
            val rawResponse = when (config.provider) {
                ApiProvider.OPENAI, ApiProvider.DEEPSEEK, ApiProvider.DASHSCOPE, ApiProvider.KIMI, ApiProvider.GEMINI, ApiProvider.XIAOMI, ApiProvider.ZHIPU, ApiProvider.SILICONFLOW, ApiProvider.OPENROUTER, ApiProvider.GROQ, ApiProvider.CUSTOM, ApiProvider.PARTNER -> {
                    callOpenAiCompatible(config, messages)
                }
                ApiProvider.ANTHROPIC -> {
                    callAnthropic(config, messages, "")
                }
            }
            val cleaned = rawResponse
                .replace(Regex("\\r\\n|\\r|\\n+"), "")
                .replace(Regex("(?is)<think[^>]*>[\\s\\S]*?</think\\s*>"), "")
                .replace(Regex("(?is)<think[^>]*>[\\s\\S]*"), "")
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("【.*?】"), "")
                .replace(Regex("\\*.*?\\*"), "")
                .replace(Regex("<.*?>"), "")
                .trim()

            if (cleaned.length < 2) return@withContext null

            val safetyResult = ContentFilter.checkOutputSafety(cleaned)
            if (!safetyResult.isSafe) return@withContext null

            cleaned
        } catch (e: Exception) {
            SecureLog.w("AiService", "Follow-up question failed: ${e.message}")
            null
        }
    }

}

/**
 * OkHttp logging logger that redacts sensitive headers (Authorization, x-api-key)
 * and masks request/response bodies to prevent credential and conversation leaks.
 *
 * Replaces API key values with [REDACTED] and truncates body content.
 */
internal class RedactingLogger : HttpLoggingInterceptor.Logger {
    private val sensitiveHeaders = setOf(
        "Authorization", "authorization",
        "x-api-key", "X-Api-Key", "X-API-KEY"
    )
    private val bodyMaxLength = 80

    override fun log(message: String) {
        val sanitized = sanitize(message)
        android.util.Log.d("OkHttp", sanitized)
    }

    private fun sanitize(message: String): String {
        var result = message

        // Redact sensitive header values: "Authorization: Bearer sk-xxx" → "Authorization: [REDACTED]"
        for (header in sensitiveHeaders) {
            result = result.replace(
                Regex("($header:\\s*).*", RegexOption.IGNORE_CASE),
                "$1[REDACTED]"
            )
        }

        // Truncate body content to prevent conversation data in logcat
        if (result.length > bodyMaxLength + 20) {
            result = result.take(bodyMaxLength) + "...[truncated]"
        }

        return result
    }
}
