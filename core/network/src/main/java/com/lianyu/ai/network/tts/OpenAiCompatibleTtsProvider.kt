package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.network.RequestSecurityInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 自定义 OpenAI 规范 TTS。
 *
 * 协议对齐 OpenAI Audio Speech API：
 * `POST {base}/v1/audio/speech`
 * body: `{ model, input, voice, response_format }`
 *
 * 兼容各类 OpenAI-compatible 网关（NewAPI、OneAPI、自建反向代理等）。
 * 不使用证书钉扎，走系统 CA（与当前 security tip 策略一致）。
 */
class OpenAiCompatibleTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

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
                val apiKey = config.customTtsApiKey.trim()
                val speechUrl = normalizeSpeechUrl(config.customTtsUrl)
                val model = config.customTtsModel.trim().ifBlank { DEFAULT_MODEL }
                val voice = when {
                    !voiceId.isNullOrBlank() && voiceId != "__custom__" -> voiceId
                    else -> config.customTtsVoiceId.trim().ifBlank { DEFAULT_VOICE }
                }
                val format = normalizeFormat(config.customTtsResponseFormat)

                if (speechUrl.isNullOrBlank()) {
                    SecureLog.w(TAG, "Custom OpenAI TTS URL not configured")
                    return@withContext null
                }
                if (apiKey.isBlank()) {
                    SecureLog.w(TAG, "Custom OpenAI TTS API key not configured")
                    return@withContext null
                }
                if (text.isBlank()) {
                    SecureLog.w(TAG, "Custom OpenAI TTS text is blank")
                    return@withContext null
                }

                val jsonBody = JSONObject().apply {
                    put("model", model)
                    put("input", text)
                    put("voice", voice)
                    put("response_format", format)
                }.toString()

                val request = Request.Builder()
                    .url(speechUrl)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()

                client.newCall(request).execute().use { response ->
                    val bodyBytes = response.body?.bytes()
                    if (!response.isSuccessful || bodyBytes == null || bodyBytes.isEmpty()) {
                        SecureLog.e(
                            TAG,
                            "HTTP ${response.code}, bodyBytes=${bodyBytes?.size ?: 0}"
                        )
                        return@withContext null
                    }

                    val outputDir = File(context.cacheDir, "tts/openai_compat").apply { mkdirs() }
                    val outputFile = File(outputDir, "openai_${System.currentTimeMillis()}.$format")
                    outputFile.writeBytes(bodyBytes)
                    if (!outputFile.exists() || outputFile.length() <= 0L) {
                        SecureLog.e(TAG, "Output file empty")
                        return@withContext null
                    }
                    SecureLog.i(TAG, "Synthesis success, bytes=${outputFile.length()}, format=$format")
                    outputFile.absolutePath
                }
            } catch (e: Exception) {
                SecureLog.e(TAG, "Synthesis failed", e)
                null
            }
        }

    override fun getVoices(): List<TtsVoice> = listOf(
        TtsVoice("alloy", "alloy", "中性", "en", "OpenAI 默认"),
        TtsVoice("echo", "echo", "男", "en", ""),
        TtsVoice("fable", "fable", "中性", "en", ""),
        TtsVoice("onyx", "onyx", "男", "en", ""),
        TtsVoice("nova", "nova", "女", "en", ""),
        TtsVoice("shimmer", "shimmer", "女", "en", ""),
        TtsVoice("__custom__", "自定义 voice", "自定义", "zh-CN", "在设置页填写 voice 字段")
    )

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            val apiKey = config.customTtsApiKey.trim()
            val speechUrl = normalizeSpeechUrl(config.customTtsUrl) ?: return@withContext false
            if (apiKey.isBlank()) return@withContext false

            val format = normalizeFormat(config.customTtsResponseFormat)
            val jsonBody = JSONObject().apply {
                put("model", config.customTtsModel.trim().ifBlank { DEFAULT_MODEL })
                put("input", "测试")
                put("voice", config.customTtsVoiceId.trim().ifBlank { DEFAULT_VOICE })
                put("response_format", format)
            }.toString()

            val request = Request.Builder()
                .url(speechUrl)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            client.newCall(request).execute().use { response ->
                val ok = response.isSuccessful && (response.body?.contentLength() ?: 1L) != 0L
                if (!ok) {
                    SecureLog.e(TAG, "testConnection HTTP ${response.code}")
                }
                ok
            }
        } catch (e: Exception) {
            SecureLog.e(TAG, "testConnection failed", e)
            false
        }
    }

    companion object {
        private const val TAG = "OpenAiCompatTts"
        private const val DEFAULT_MODEL = "tts-1"
        private const val DEFAULT_VOICE = "alloy"
        private val ALLOWED_FORMATS = setOf("mp3", "opus", "aac", "flac", "wav", "pcm")

        /**
         * 接受：
         * - `https://host/v1`
         * - `https://host/v1/`
         * - `https://host/v1/audio/speech`
         * - 任意以 `/audio/speech` 结尾的 https URL
         */
        fun normalizeSpeechUrl(raw: String): String? {
            val value = raw.trim().trimEnd('/')
            if (value.isBlank()) return null
            return runCatching {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase(Locale.US)
                val host = uri.host
                if (scheme != "https" || host.isNullOrBlank()) return null
                if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null

                val path = uri.path.orEmpty().trimEnd('/')
                val speechPath = when {
                    path.endsWith("/audio/speech") -> path
                    path.endsWith("/v1/audio") -> "$path/speech"
                    path.endsWith("/v1") -> "$path/audio/speech"
                    path.isBlank() -> "/v1/audio/speech"
                    else -> return null
                }
                val portPart = if (uri.port != -1) ":${uri.port}" else ""
                "https://$host$portPart$speechPath"
            }.getOrNull()
        }

        fun normalizeFormat(raw: String): String {
            val f = raw.trim().lowercase(Locale.US)
            return if (f in ALLOWED_FORMATS) f else "mp3"
        }
    }
}
