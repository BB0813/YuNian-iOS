package com.lianyu.ai.domain

/**
 * AI 流式响应分块（domain 层纯类型，零依赖）。
 *
 * 用于在 core:domain 的 [AiServiceProvider.sendMessageStream] 接口中
 * 暴露流式能力，避免把 core:network 的 ChunkedResponseHandler.ChunkResult
 * 泄漏到 domain 层（破坏零依赖原则）。
 *
 * core:network 的 AiService 实现负责把内部 ChunkResult 映射为此类型：
 *   Text     ← ChunkResult.Text
 *   Error    ← ChunkResult.Error
 *   Done     ← ChunkResult.Done
 *   (Reasoning 丢弃，domain 层不暴露思考过程)
 */
sealed class AiStreamChunk {
    /** 文本内容分块 */
    data class Text(val content: String) : AiStreamChunk()

    /** 错误终止 */
    data class Error(val message: String) : AiStreamChunk()

    /** 流式结束 */
    data object Done : AiStreamChunk()
}
