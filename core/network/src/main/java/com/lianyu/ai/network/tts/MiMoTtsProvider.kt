package com.lianyu.ai.network.tts

import android.content.Context
import android.util.Base64
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.network.RequestSecurityInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 小米 MiMo TTS。
 *
 * MiMo 不提供 `/v1/audio/speech`，而是走 `/v1/chat/completions` + `audio` 字段。
 * 合成文本放在 assistant message；可选 user message 作为风格提示。
 */
class MiMoTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

    private val client = run {
        val builder = OkHttpClient.Builder()
            .callTimeout(TimeoutBudgets.TTS_SYNTH_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(TimeoutBudgets.TTS_SYNTH_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(TimeoutBudgets.TTS_SYNTH_MS, TimeUnit.MILLISECONDS)
        RequestSecurityInterceptor.enforceTls(builder)
        builder.build()
    }

    private var config: TtsConfig = TtsConfig()

    override fun updateConfig(config: TtsConfig) {
        this.config = config
    }

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = config.mimoApiKey.trim()
                val baseUrl = normalizeBaseUrl(config.mimoBaseUrl)
                val model = config.mimoModel.ifBlank { DEFAULT_MODEL }
                val outputFormat = normalizeOutputFormat(config.mimoOutputFormat)
                val resolvedVoiceId = when {
                    !voiceId.isNullOrBlank() && voiceId != "__custom__" -> voiceId
                    else -> config.mimoVoiceId.takeIf { it.isNotBlank() } ?: DEFAULT_VOICE
                }

                if (apiKey.isBlank() || baseUrl.isNullOrBlank()) {
                    SecureLog.w(TAG, "MiMo TTS not configured")
                    return@withContext null
                }
                if (text.isBlank()) {
                    SecureLog.w(TAG, "MiMo TTS text is blank")
                    return@withContext null
                }

                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("audio", JSONObject().apply {
                        put("format", outputFormat)
                        put("voice", resolvedVoiceId)
                    })
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "请将下一条 assistant 消息合成为自然中文语音。")
                        })
                        put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", text)
                        })
                    })
                }.toString()

                val request = Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("api-key", apiKey)
                    .build()

                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        SecureLog.e(TAG, "HTTP ${response.code}, bodyBytes=${body.length}")
                        return@withContext null
                    }
                    val audioData = extractAudioData(body)
                    if (audioData.isBlank()) {
                        SecureLog.e(TAG, "response missing audio data")
                        return@withContext null
                    }
                    val audioBytes = Base64.decode(audioData, Base64.DEFAULT)
                    if (audioBytes.isEmpty()) {
                        SecureLog.e(TAG, "decoded empty audio")
                        return@withContext null
                    }
                    val outputDir = File(context.cacheDir, "tts/mimo").apply { mkdirs() }
                    val outputFile = File(outputDir, "mimo_${System.currentTimeMillis()}.$outputFormat")
                    outputFile.writeBytes(audioBytes)
                    if (!outputFile.exists() || outputFile.length() <= 0L) {
                        SecureLog.e(TAG, "output file empty")
                        return@withContext null
                    }
                    SecureLog.i(TAG, "success bytes=${outputFile.length()} format=$outputFormat")
                    outputFile.absolutePath
                }
            } catch (e: Exception) {
                SecureLog.e(TAG, "synthesis failed", e)
                null
            }
        }

    override fun getVoices(): List<TtsVoice> = listOf(
        TtsVoice("mimo_default", "mimo_default", "默认", "zh-CN", "MiMo 默认音色"),
        TtsVoice("Chloe", "Chloe", "女", "en", "文档示例音色"),
        TtsVoice("__custom__", "自定义 voice", "自定义", "zh-CN", "在设置页填写 voice")
    )

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val apiKey = config.mimoApiKey.trim()
        val baseUrl = normalizeBaseUrl(config.mimoBaseUrl) ?: return@withContext false
        if (apiKey.isBlank()) return@withContext false
        probeSpeech(apiKey, baseUrl)
    }

    private fun probeSpeech(apiKey: String, baseUrl: String): Boolean {
        return try {
            val requestBody = JSONObject().apply {
                put("model", config.mimoModel.ifBlank { DEFAULT_MODEL })
                put("audio", JSONObject().apply {
                    put("format", "wav")
                    put("voice", config.mimoVoiceId.ifBlank { DEFAULT_VOICE })
                })
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "assistant")
                        put("content", "测试")
                    })
                })
            }.toString()
            val request = Request.Builder()
                .url("$baseUrl/chat/completions")
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("api-key", apiKey)
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                response.isSuccessful && extractAudioData(body).isNotBlank()
            }
        } catch (e: Exception) {
            SecureLog.e(TAG, "testConnection failed", e)
            false
        }
    }

    private fun extractAudioData(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return message.optJSONObject("audio")?.optString("data").orEmpty()
    }

    companion object {
        private const val TAG = "MiMoTts"
        private const val DEFAULT_MODEL = "mimo-v2.5-tts"
        private const val DEFAULT_VOICE = "mimo_default"
        private val ALLOWED_HOSTS = setOf(
            "api.xiaomimimo.com",
            "token-plan-cn.xiaomimimo.com",
            "token-plan-sgp.xiaomimimo.com",
            "token-plan-ams.xiaomimimo.com"
        )

        fun normalizeBaseUrl(value: String): String? {
            val raw = value.trim().trimEnd('/')
            if (raw.isBlank()) return null
            return runCatching {
                val uri = URI(raw)
                val scheme = uri.scheme?.lowercase(Locale.US)
                val host = uri.host?.lowercase(Locale.US)
                val path = uri.path.orEmpty().trimEnd('/')
                if (scheme != "https" || host !in ALLOWED_HOSTS) return null
                if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
                if (uri.port != -1) return null
                if (path.isNotBlank() && path != "/v1") return null
                "https://$host/v1"
            }.getOrNull()
        }

        fun isAllowedBaseUrl(value: String): Boolean = normalizeBaseUrl(value) != null

        fun normalizeOutputFormat(value: String): String {
            val f = value.trim().lowercase(Locale.US)
            return if (f == "pcm" || f == "pcm16") "pcm" else "wav"
        }

        fun defaultBaseUrl(): String = "https://api.xiaomimimo.com/v1"
    }
}
