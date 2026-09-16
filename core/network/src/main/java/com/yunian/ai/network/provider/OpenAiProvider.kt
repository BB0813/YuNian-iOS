package com.yunian.ai.network.provider

import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.network.ChatCompletionResponse
import com.yunian.ai.network.Message
import com.yunian.ai.network.toApiTemperature
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

open class OpenAiCompatibleProvider : AiProvider {

    private fun normalizeBaseUrl(baseUrl: String): String {

        return baseUrl.trim().trimEnd('/')
    }

    private fun chatUrl(config: ApiConfig): String {
        return "${normalizeBaseUrl(config.baseUrl).trimEnd('/')}/chat/completions"
    }

    private fun usesMaxCompletionTokens(provider: ApiProvider): Boolean {
        return provider == ApiProvider.XIAOMI
    }

    private fun prefersApiKeyHeader(provider: ApiProvider): Boolean {
        return provider == ApiProvider.XIAOMI
    }

    private fun stripThinkingContent(content: String): String =
        com.yunian.ai.network.ResponsePostProcessor.stripThinkingContent(content)

    private fun extractThinking(content: String): Pair<String, String?> =
        com.yunian.ai.network.ResponsePostProcessor.extractThinkingContent(content)

    private fun buildAuthHeader(provider: ApiProvider, key: String): Pair<String, String> {
        return if (prefersApiKeyHeader(provider)) {
            "api-key" to key
        } else {
            "Authorization" to "Bearer $key"
        }
    }

    private fun buildMaxTokensParam(provider: ApiProvider): String {
        return if (usesMaxCompletionTokens(provider)) "max_completion_tokens" else "max_tokens"
    }

    override suspend fun chat(
        messages: List<Message>,
        config: ApiConfig
    ): String {
        return chatWithReasoning(messages, config).first
    }

    override suspend fun chatWithReasoning(
        messages: List<Message>,
        config: ApiConfig
    ): Pair<String, String?> {
        val safeTemp = config.temperature.coerceIn(0.1f, 1.5f)
        val url = chatUrl(config)

        val (startIdx, allKeys) = AiProvider.keySelector(config)
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
                if (config.model.isNotBlank()) jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                if (!AiProvider.requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", safeTemp.toApiTemperature())
                }
                val maxTokens = config.maxTokens ?: 800
                if (maxTokens > 0) {
                    jsonBody.put(buildMaxTokensParam(config.provider), maxTokens)
                }

                val (headerName, headerValue) = buildAuthHeader(config.provider, currentKey)
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader(headerName, headerValue)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = AiProvider.okHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching {
                            AiProvider.json.decodeFromString<ChatCompletionResponse>(body).error?.message
                        }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                val trimmedBody = body.trimStart()
                if (trimmedBody.startsWith("<!") || trimmedBody.startsWith("<html", ignoreCase = true)) {
                    throw Exception("服务器返回了网页而非API响应 (HTTP ${response.code})，请检查API密钥/地址是否正确")
                }
                val parsed = AiProvider.json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                val message = parsed.choices?.firstOrNull()?.message
                val rawContent = message?.content ?: throw Exception("API返回空内容")
                val fieldReasoning = message.reasoning_content?.trim()?.takeIf { it.isNotBlank() }
                val (content, tagReasoning) = extractThinking(rawContent)
                val reasoning = listOfNotNull(fieldReasoning, tagReasoning)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .joinToString("\n\n")
                    .ifBlank { null }
                if (content.isBlank()) {
                    throw Exception("模型仅返回了思考过程，未生成实际回复，请重试")
                }
                return Pair(content, reasoning)
            } catch (e: Exception) {
                lastException = e
                AiProvider.keyFailureHandler(currentKey)
                if (i < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    override suspend fun chatLight(
        messages: List<Message>,
        config: ApiConfig,
        temperature: Double,
        maxTokens: Int
    ): String {
        val url = chatUrl(config)
        val allKeys = config.getAllApiKeys()
        var lastException: Exception? = null

        val lightClient = AiProvider.okHttpClient.newBuilder()
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
                if (config.model.isNotBlank()) jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                if (!AiProvider.requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", temperature.toApiTemperature())
                }
                jsonBody.put(buildMaxTokensParam(config.provider), maxTokens)

                val (headerName, headerValue) = buildAuthHeader(config.provider, currentKey)
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader(headerName, headerValue)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = lightClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")
                if (!response.isSuccessful) {
                    throw Exception("HTTP ${response.code}")
                }
                val parsed = AiProvider.json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) throw Exception(parsed.error.message ?: "API error")

                return parsed.choices?.firstOrNull()?.message?.content ?: ""
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            } catch (e: Exception) {
                lastException = e
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    override suspend fun chatForTest(
        messages: List<Message>,
        config: ApiConfig
    ): String {
        val url = chatUrl(config)

        val (startIdx, allKeys) = AiProvider.keySelector(config)
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
                if (config.model.isNotBlank()) jsonBody.put("model", config.model)
                jsonBody.put("messages", jsonArray)
                if (!AiProvider.requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", 0.7.toApiTemperature())
                }
                jsonBody.put(buildMaxTokensParam(config.provider), 100)

                val (headerName, headerValue) = buildAuthHeader(config.provider, currentKey)
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader(headerName, headerValue)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = AiProvider.okHttpClient.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching {
                            AiProvider.json.decodeFromString<ChatCompletionResponse>(body).error?.message
                        }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                val trimmedBody = body.trimStart()
                if (trimmedBody.startsWith("<!") || trimmedBody.startsWith("<html", ignoreCase = true)) {
                    throw Exception("服务器返回了网页而非API响应 (HTTP ${response.code})，请检查API密钥/地址是否正确")
                }
                val parsed = AiProvider.json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                var content = parsed.choices?.firstOrNull()?.message?.content
                    ?: throw Exception("API返回空内容")
                content = stripThinkingContent(content)
                return content
            } catch (e: Exception) {
                lastException = e
                AiProvider.keyFailureHandler(currentKey)
                if (i < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }

    override suspend fun chatVision(
        history: List<ChatMessage>,
        systemPrompt: String,
        lastUserMessage: String,
        imageBase64: String,
        mimeType: String,
        config: ApiConfig,
        client: OkHttpClient
    ): String {
        val safeTemp = config.temperature.coerceIn(0.1f, 1.5f)
        val url = chatUrl(config)
        val allKeys = config.getAllApiKeys()
        if (allKeys.isEmpty()) {
            throw Exception("API Key 为空，请检查视觉模型配置")
        }
        var lastException: Exception? = null

        for (keyIndex in allKeys.indices) {
            val currentKey = allKeys[keyIndex]
            try {
                val messagesJson = org.json.JSONArray()

                val systemMsg = org.json.JSONObject()
                systemMsg.put("role", "system")
                systemMsg.put("content", systemPrompt)
                messagesJson.put(systemMsg)

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
                if (config.model.isNotBlank()) jsonBody.put("model", config.model)
                jsonBody.put("messages", messagesJson)
                if (!AiProvider.requiresFixedTemperature(config.model)) {
                    jsonBody.put("temperature", safeTemp.toApiTemperature())
                }
                val maxTokens = config.maxTokens ?: 800
                if (maxTokens > 0) {
                    jsonBody.put(buildMaxTokensParam(config.provider), maxTokens)
                }

                val (headerName, headerValue) = buildAuthHeader(config.provider, currentKey)
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .addHeader(headerName, headerValue)
                    .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    val errorMsg = if (body.trimStart().startsWith("{")) {
                        runCatching {
                            AiProvider.json.decodeFromString<ChatCompletionResponse>(body).error?.message
                        }.getOrNull()
                    } else null
                    throw Exception(errorMsg ?: "HTTP ${response.code}: 服务器返回错误页面")
                }

                val trimmedBody = body.trimStart()
                if (trimmedBody.startsWith("<!") || trimmedBody.startsWith("<html", ignoreCase = true)) {
                    throw Exception("服务器返回了网页而非API响应 (HTTP ${response.code})，请检查API密钥/地址是否正确")
                }
                val parsed = AiProvider.json.decodeFromString<ChatCompletionResponse>(body)
                if (parsed.error != null) {
                    throw Exception(parsed.error.message ?: "API返回错误")
                }

                val message = parsed.choices?.firstOrNull()?.message
                var content = message?.content ?: throw Exception("API返回空内容")
                content = stripThinkingContent(content)
                return content
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                if (keyIndex < allKeys.size - 1) continue else throw e
            } catch (e: Exception) {
                lastException = e
                if (keyIndex < allKeys.size - 1) continue else throw lastException
            }
        }

        throw lastException ?: Exception("所有 API Key 均请求失败")
    }
}
