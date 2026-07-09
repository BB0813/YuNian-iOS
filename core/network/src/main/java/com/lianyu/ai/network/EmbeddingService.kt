package com.lianyu.ai.network

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.EmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * 语义嵌入服务 —— Phase 3: 本地语义检索。
 *
 * 职责：
 * 1. 通过用户配置的 API 生成文本嵌入向量
 * 2. 将 FloatArray ↔ ByteArray 互转（BLOB 存储）
 * 3. 不支持 embedding 的 provider 优雅降级（返回 null，调用方回退到关键词匹配）
 *
 * 设计原则：
 * - 隐私优先：embedding 生成完全依赖用户自己的 API key，不引入第三方服务
 * - 懒加载：仅在 extractAndSaveMemories 时异步生成，不阻塞对话
 * - 容错：API 失败时静默降级，记忆系统仍可用关键词检索
 *
 * 性能预算：
 * - text-embedding-3-small: 384 维 × 4 字节 = 1.5KB/条
 * - 1000 条记忆 = 1.5MB，暴力 cosine 相似度 <1ms
 */
class EmbeddingService(private val context: Context) : EmbeddingProvider {

    companion object {
        private const val TAG = "EmbeddingService"

        /** 默认 embedding 模型（OpenAI 格式） */
        const val DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small"

        /** 支持生成 embedding 的 provider 白名单 */
        private val EMBEDDING_CAPABLE_PROVIDERS = setOf(
            ApiProvider.OPENAI,
            ApiProvider.OPENROUTER,
            ApiProvider.SILICONFLOW,
            ApiProvider.DASHSCOPE,
            ApiProvider.ZHIPU,
            ApiProvider.GEMINI,
            ApiProvider.CUSTOM
        )

        /** 各 provider 推荐的 embedding 模型 */
        private val PROVIDER_EMBEDDING_MODELS = mapOf(
            ApiProvider.OPENAI to "text-embedding-3-small",
            ApiProvider.OPENROUTER to "openai/text-embedding-3-small",
            ApiProvider.SILICONFLOW to "BAAI/bge-m3",
            ApiProvider.DASHSCOPE to "text-embedding-v3",
            ApiProvider.ZHIPU to "embedding-3",
            ApiProvider.GEMINI to "text-embedding-004"
        )

        /**
         * FloatArray → ByteArray（BLOB 存储）。
         * 使用 little-endian Float 格式，每 4 字节一个 float。
         */
        fun floatsToBytes(floats: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(floats.size * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(floats)
            return buffer.array()
        }

        /**
         * ByteArray → FloatArray（从 BLOB 还原）。
         */
        fun bytesToFloats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(bytes.size / 4)
            buffer.asFloatBuffer().get(floats)
            return floats
        }

        /**
         * 计算两个向量的余弦相似度。
         * 范围 [-1, 1]，值越大表示越相似。
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dotProduct = 0.0
            var normA = 0.0
            var normB = 0.0
            for (i in a.indices) {
                dotProduct += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
            return if (denominator == 0.0) 0f else (dotProduct / denominator).toFloat()
        }
    }

    private val apiConfigRepository: ApiConfigRepository
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ── EmbeddingProvider 接口实现（委托给 companion object 静态方法） ──

    override fun floatsToBytes(floats: FloatArray): ByteArray = Companion.floatsToBytes(floats)

    override fun bytesToFloats(bytes: ByteArray): FloatArray = Companion.bytesToFloats(bytes)

    override fun cosineSimilarity(a: FloatArray, b: FloatArray): Float = Companion.cosineSimilarity(a, b)

    // 专用于 embedding 的轻量 HTTP client（短超时，无重试拦截器）
    private val embeddingClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    init {
        val database = AppDatabase.getDatabase(context.applicationContext)
        apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    }

    /**
     * 判断当前配置的 API 是否支持生成 embedding。
     */
    override suspend fun isEmbeddingSupported(): Boolean {
        val config = getConfig() ?: return false
        return EMBEDDING_CAPABLE_PROVIDERS.contains(config.provider)
    }

    /**
     * 获取当前使用的 embedding 模型名称。
     */
    override suspend fun getEmbeddingModelName(): String {
        val config = getConfig() ?: return DEFAULT_EMBEDDING_MODEL
        val recommended = PROVIDER_EMBEDDING_MODELS[config.provider]
        return recommended ?: DEFAULT_EMBEDDING_MODEL
    }

    /**
     * 为文本生成嵌入向量。
     *
     * @param text 待嵌入的文本
     * @return FloatArray 嵌入向量，或 null（API 不支持或请求失败时）
     */
    override suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null

        val config = getConfig() ?: return@withContext null
        if (!EMBEDDING_CAPABLE_PROVIDERS.contains(config.provider)) return@withContext null

        val model = PROVIDER_EMBEDDING_MODELS[config.provider] ?: DEFAULT_EMBEDDING_MODEL
        val keys = config.getAllApiKeys()
        if (keys.isEmpty()) return@withContext null

        // 构造 embedding API URL
        val baseUrl = config.baseUrl.trimEnd('/')
        val embeddingUrl = when (config.provider) {
            ApiProvider.GEMINI -> "$baseUrl/openai/embeddings"
            else -> "$baseUrl/embeddings"
        }

        val requestBody = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("input", JsonPrimitive(text))
        }.toString()

        val request = Request.Builder()
            .url(embeddingUrl)
            .header("Authorization", "Bearer ${keys.first()}")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            embeddingClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    SecureLog.w(TAG, "Embedding API failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val jsonBody = json.parseToJsonElement(body).jsonObject
                val data = jsonBody["data"]?.jsonArray?.firstOrNull()
                    ?: return@withContext null
                val embedding = data.jsonObject["embedding"]?.jsonArray
                    ?: return@withContext null

                val floats = FloatArray(embedding.size)
                for (i in embedding.indices) {
                    floats[i] = embedding[i].jsonPrimitive.content.toFloat()
                }
                return@withContext floats
            }
        } catch (e: Exception) {
            SecureLog.w(TAG, "Embedding generation failed: ${e.message}")
            return@withContext null
        }
    }

    /**
     * 批量为多条文本生成嵌入向量。
     * 逐条调用以兼容所有 provider（部分 provider 不支持 batch input）。
     *
     * @param texts 待嵌入的文本列表
     * @return 与输入等长的 List<FloatArray?>，失败的条目为 null
     */
    suspend fun embedBatch(texts: List<String>): List<FloatArray?> = withContext(Dispatchers.IO) {
        texts.map { embed(it) }
    }

    // ── 内部工具 ──

    private suspend fun getConfig(): ApiConfig? {
        return apiConfigRepository.getActiveEnabledConfig()
    }
}
