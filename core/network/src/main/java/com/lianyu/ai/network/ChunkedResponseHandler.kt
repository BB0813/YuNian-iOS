package com.lianyu.ai.network

import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.network.provider.AiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ChunkedResponseHandler — 流式/分段响应处理器
 *
 * 支持 SSE (Server-Sent Events) 流式接收 AI 响应，实现打字机效果。
 * 同时提供非流式的分段组装功能。
 */
object ChunkedResponseHandler {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 流式请求的数据类
     */
    @Serializable
    data class StreamRequest(
        val model: String,
        val messages: List<StreamMessage>,
        val temperature: Float = 0.7f,
        val max_tokens: Int? = null,
        val top_p: Float = 0.9f,
        val frequency_penalty: Float = 0.0f,
        val presence_penalty: Float = 0.0f,
        val stream: Boolean = true
    )

    @Serializable
    data class StreamMessage(
        val role: String,
        val content: String,
        val reasoning_content: String? = null
    )

    @Serializable
    data class StreamChunk(
        val id: String? = null,
        val choices: List<StreamChoice>? = null
    )

    @Serializable
    data class StreamChoice(
        val delta: StreamDelta? = null,
        val finish_reason: String? = null
    )

    @Serializable
    data class StreamDelta(
        val content: String? = null,
        val role: String? = null,
        val reasoning_content: String? = null
    )

    /**
     * 分段结果密封类
     */
    sealed class ChunkResult {
        data class Text(val content: String) : ChunkResult()
        data class Reasoning(val content: String) : ChunkResult()
        data class Error(val message: String) : ChunkResult()
        data object Done : ChunkResult()
    }

    /**
     * 使用 SSE 流式接收响应
     *
     * @param url API 端点
     * @param apiKey 认证密钥
     * @param request 流式请求体
     * @return Flow<ChunkResult> 分段结果流
     */
    fun streamChatCompletion(
        url: String,
        apiKey: String,
        request: StreamRequest,
        client: OkHttpClient? = null,
        authHeaders: Map<String, String> = emptyMap()
    ): Flow<ChunkResult> = flow {
        SecureLog.chunk("STREAM", "Starting stream request to ${url.take(50)}...")

        val httpClient = client ?: run {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
            RequestSecurityInterceptor.enforceTls(builder)
            builder.certificatePinner(CertificatePins.certificatePinner)
            builder.build()
        }

        val jsonArray = org.json.JSONArray()
        for (msg in request.messages) {
            val msgObj = org.json.JSONObject()
            msgObj.put("role", msg.role)
            msgObj.put("content", msg.content)
            if (!msg.reasoning_content.isNullOrBlank()) {
                msgObj.put("reasoning_content", msg.reasoning_content)
            }
            jsonArray.put(msgObj)
        }
        val jsonBody = org.json.JSONObject()
        if (request.model.isNotBlank()) jsonBody.put("model", request.model)
        jsonBody.put("messages", jsonArray)
        if (!AiProvider.requiresFixedTemperature(request.model)) {
            jsonBody.put("temperature", request.temperature.toDouble())
        }
        request.max_tokens?.let { jsonBody.put("max_tokens", it) }
        jsonBody.put("stream", true)

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
        if (authHeaders.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        } else {
            authHeaders.forEach { (name, value) -> requestBuilder.addHeader(name, value) }
        }
        val httpRequest = requestBuilder.post(requestBody).build()

        val response = httpClient.newCall(httpRequest).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            SecureLog.chunk("STREAM", "HTTP ${response.code}: $errorBody")
            emit(ChunkResult.Error("HTTP ${response.code}: ${errorBody.take(100)}"))
            return@flow
        }

        val body = response.body
            ?: run {
                emit(ChunkResult.Error("Empty response body"))
                return@flow
            }

        val source = body.source()
        val accumulatedText = StringBuilder()
        var chunkCount = 0
        var bufferText = ""
        var lastEmitTime = System.currentTimeMillis()
        val bufferIntervalMs = 50L

        try {
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: continue

                if (line.startsWith("data: ")) {
                    val data = line.substring(6)

                    if (data == "[DONE]") {
                        if (bufferText.isNotEmpty()) {
                            emit(ChunkResult.Text(bufferText))
                            bufferText = ""
                        }
                        SecureLog.chunk("STREAM", "Stream completed, total chunks: $chunkCount, chars: ${accumulatedText.length}")
                        if (chunkCount == 0) {
                            emit(ChunkResult.Error("API返回空内容，未收到任何文本"))
                        } else {
                            emit(ChunkResult.Done)
                        }
                        break
                    }

                    try {
                        val chunk = json.decodeFromString(StreamChunk.serializer(), data)
                        val choice = chunk.choices?.firstOrNull()
                        val content = choice?.delta?.content
                        val reasoning = choice?.delta?.reasoning_content
                        val finishReason = choice?.finish_reason

                        if (!reasoning.isNullOrEmpty()) {
                            emit(ChunkResult.Reasoning(reasoning))
                        }

                        if (!content.isNullOrEmpty()) {
                            accumulatedText.append(content)
                            chunkCount++
                            bufferText += content

                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= bufferIntervalMs || bufferText.length >= 20) {
                                emit(ChunkResult.Text(bufferText))
                                bufferText = ""
                                lastEmitTime = now
                            }

                            if (chunkCount % 20 == 0) {
                                SecureLog.chunk("STREAM", "Received $chunkCount chunks, ${accumulatedText.length} chars")
                            }
                        } else if (choice?.delta?.role != null) {
                            SecureLog.chunk("STREAM", "Received role chunk: ${choice.delta.role}")
                        }

                        if (finishReason != null) {
                            if (bufferText.isNotEmpty()) {
                                emit(ChunkResult.Text(bufferText))
                            }
                            SecureLog.chunk("STREAM", "Finish reason: $finishReason")
                            if (chunkCount == 0) {
                                emit(ChunkResult.Error("API返回空内容，finish_reason=$finishReason"))
                            } else {
                                emit(ChunkResult.Done)
                            }
                            break
                        }
                    } catch (e: Exception) {
                        SecureLog.chunk("STREAM", "Parse error: ${e.message}, data: ${data.take(200)}")
                    }
                } else if (line.trimStart().startsWith("{")) {
                    try {
                        val chunk = json.decodeFromString(StreamChunk.serializer(), line)
                        val content = chunk.choices?.firstOrNull()?.delta?.content
                        if (!content.isNullOrEmpty()) {
                            accumulatedText.append(content)
                            chunkCount++
                            bufferText += content
                            val now = System.currentTimeMillis()
                            if (now - lastEmitTime >= bufferIntervalMs || bufferText.length >= 20) {
                                emit(ChunkResult.Text(bufferText))
                                bufferText = ""
                                lastEmitTime = now
                            }
                        }
                        if (chunk.choices?.firstOrNull()?.finish_reason != null) {
                            if (bufferText.isNotEmpty()) {
                                emit(ChunkResult.Text(bufferText))
                            }
                            emit(ChunkResult.Done)
                            break
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            if (bufferText.isNotEmpty()) {
                emit(ChunkResult.Text(bufferText))
            }

            if (chunkCount == 0) {
                SecureLog.chunk("STREAM", "Stream ended with no content received")
                emit(ChunkResult.Error("API返回空内容，请检查模型名或API配置"))
            }
        } catch (e: Exception) {
            SecureLog.chunk("STREAM", "Stream error: ${e.message}")
            emit(ChunkResult.Error("Stream error: ${e.message}"))
        } finally {
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 非流式分段模拟（用于不支持 SSE 的 API）
     * 将完整响应按句子/标点切分，模拟打字效果
     */
    fun simulateChunks(fullText: String): Flow<ChunkResult> = flow {
        SecureLog.chunk("SIM", "Simulating chunks for ${fullText.length} chars")

        val sentences = splitIntoChunks(fullText)
        var accumulated = ""

        for (chunk in sentences) {
            accumulated += chunk
            emit(ChunkResult.Text(chunk))
            kotlinx.coroutines.delay(calculateDelay(chunk))
        }

        SecureLog.chunk("SIM", "Simulation complete, ${sentences.size} chunks")
        emit(ChunkResult.Done)
    }.flowOn(Dispatchers.IO)

    /**
     * 将文本切分成合适的片段
     */
    private fun splitIntoChunks(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val delimiters = listOf("\n\n", "。", "？", "！", "；", "，", " ", "")

        var remaining = text
        while (remaining.isNotEmpty()) {
            var found = false
            for (delimiter in delimiters) {
                if (delimiter.isEmpty()) {
                    // 最后手段：按字符切
                    val size = minOf(remaining.length, 3)
                    chunks.add(remaining.substring(0, size))
                    remaining = remaining.substring(size)
                    found = true
                    break
                }
                val idx = remaining.indexOf(delimiter)
                if (idx >= 0) {
                    val endIdx = idx + delimiter.length
                    chunks.add(remaining.substring(0, endIdx))
                    remaining = remaining.substring(endIdx)
                    found = true
                    break
                }
            }
            if (!found) {
                chunks.add(remaining)
                break
            }
        }

        return chunks
    }

    /**
     * 根据片段长度计算延迟，模拟真实打字速度
     */
    private fun calculateDelay(chunk: String): Long {
        val baseDelay = 30L
        val perCharDelay = 15L
        return baseDelay + chunk.length * perCharDelay
    }
}
