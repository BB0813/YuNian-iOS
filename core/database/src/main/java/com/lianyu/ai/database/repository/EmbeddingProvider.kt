package com.lianyu.ai.database.repository

/**
 * 嵌入向量提供者接口 —— Phase 3: 本地语义检索。
 *
 * 定义在 core:database 中，由 core:network 的 EmbeddingService 实现。
 * 这样 UnifiedMemoryRepository 可以生成 embedding 而不直接依赖 core:network。
 *
 * 隐私优先：embedding 生成完全依赖用户自己的 API key，不引入第三方服务。
 * 容错：返回 null 时，调用方回退到关键词匹配（Jaccard 相似度）。
 */
interface EmbeddingProvider {

    /**
     * 判断当前配置的 API 是否支持生成 embedding。
     */
    suspend fun isEmbeddingSupported(): Boolean

    /**
     * 获取当前使用的 embedding 模型名称。
     */
    suspend fun getEmbeddingModelName(): String

    /**
     * 为文本生成嵌入向量。
     *
     * @param text 待嵌入的文本
     * @return FloatArray 嵌入向量，或 null（API 不支持或请求失败时）
     */
    suspend fun embed(text: String): FloatArray?

    /**
     * FloatArray → ByteArray（BLOB 存储）。
     */
    fun floatsToBytes(floats: FloatArray): ByteArray

    /**
     * ByteArray → FloatArray（从 BLOB 还原）。
     */
    fun bytesToFloats(bytes: ByteArray): FloatArray

    /**
     * 计算两个向量的余弦相似度。
     * 范围 [-1, 1]，值越大表示越相似。
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float
}
