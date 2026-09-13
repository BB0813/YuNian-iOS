package com.yunian.ai.network.provider

import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.network.Message
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

interface AiProvider {

    suspend fun chat(
        messages: List<Message>,
        config: ApiConfig
    ): String

    suspend fun chatWithReasoning(
        messages: List<Message>,
        config: ApiConfig
    ): Pair<String, String?>

    suspend fun chatLight(
        messages: List<Message>,
        config: ApiConfig,
        temperature: Double,
        maxTokens: Int
    ): String

    suspend fun chatForTest(
        messages: List<Message>,
        config: ApiConfig
    ): String

    suspend fun chatVision(
        history: List<ChatMessage>,
        systemPrompt: String,
        lastUserMessage: String,
        imageBase64: String,
        mimeType: String,
        config: ApiConfig,
        client: OkHttpClient
    ): String

    companion object {

        val json: Json = Json { ignoreUnknownKeys = true }

        lateinit var okHttpClient: OkHttpClient

        var keySelector: (ApiConfig) -> Pair<Int, List<String>> = { config ->
            0 to config.getAllApiKeys()
        }

        var keyFailureHandler: (String) -> Unit = {}

        fun requiresFixedTemperature(model: String): Boolean {
            return model.contains("kimi-k2.6", ignoreCase = true) ||
                   model.contains("k2.6", ignoreCase = true)
        }
    }
}
