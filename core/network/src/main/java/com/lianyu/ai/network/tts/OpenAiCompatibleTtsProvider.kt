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

    // [TTS FIX] 固定超时的 client 改为按文本长度动态构建（见 clientFor）。
    // 保留基础 builder，合成时按文本长度配备超时，长文本不再被 30s 固定窗口掐断。
    private val baseClient = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
        RequestSecurityInterceptor.enforceTls(builder)
        builder.build()
    }

    /** [TTS FIX] 按文本长度构建带动态超时的 client：一个字符 1 秒。 */
    private fun clientFor(textLength: Int): OkHttpClient {
        val timeoutMs = TimeoutBudgets.ttsSynthTimeoutMs(textLength)
        return baseClient.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
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

                // [TTS FIX] 按文本长度动态配备超时（一个字符 1 秒）
                clientFor(text.length).newCall(request).execute().use { response ->
                    val bodyBytes = response.body?.bytes()
                    if (!response.isSuccessful || bodyBytes == null || bodyBytes.isEmpty()) {
                        SecureLog.e(
                            TAG,
                            "HTTP ${response.code}, bodyBytes=${bodyBytes?.size ?: 0}"
                        )
                        return@withContext null
                    }

                    // [TTS FIX] 校验响应体是否为有效音频，避免网关返回 2xx + 错误 JSON
                    // （如 {"error":{"message":"app_key错误"...}}，实测 110 字节）被当成音频文件
                    // 写盘入库 → 消息有语音条但播放失败。
                    val contentType = response.header("Content-Type").orEmpty()
                    if (!isLikelyAudioBody(bodyBytes, contentType, format)) {
                        SecureLog.e(
                            TAG,
                            "Response body is not valid audio: code=${response.code}, " +
                                "contentType='$contentType', bytes=${bodyBytes.size}, " +
                                "head=${bodyBytes.toHexPreview()}"
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

            // [TTS FIX] testConnection 用 2 字符超时（走动态函数，下限 30s）
            clientFor(2).newCall(request).execute().use { response ->
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
         * - 任意以 `/audio/speech` 结尾的 URL
         *
         * 协议策略：
         * - 公网主机强制 `https`（与 security 基线一致）。
         * - 私有/局域网主机（回环、RFC1918、链路本地）允许 `http` 明文，
         *   配合 `network_security_config.xml` 的 `cleartextTrafficPermitted="true"`，
         *   支持本地自建 TTS 服务（如 `http://192.168.x.x:port/v1/audio/speech`）。
         */
        fun normalizeSpeechUrl(raw: String): String? {
            val value = raw.trim().trimEnd('/')
            if (value.isBlank()) return null
            return runCatching {
                val uri = URI(value)
                val scheme = uri.scheme?.lowercase(Locale.US)
                val host = uri.host?.lowercase(Locale.US)
                if ((scheme != "https" && scheme != "http") || host.isNullOrBlank()) return null
                if (scheme == "http" && !isPrivateHost(host)) return null
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
                "$scheme://$host$portPart$speechPath"
            }.getOrNull()
        }

        /**
         * 判断是否为可放行明文 HTTP 的私有/局域网主机：
         * - `localhost`
         * - IPv4 回环 `127.0.0.0/8`
         * - RFC1918 私有网段 `10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`
         * - IPv6 回环 `::1`
         * - 链路本地 `169.254.0.0/16`
         * 其余主机（含域名）一律要求 HTTPS。
         */
        fun isPrivateHost(host: String): Boolean {
            val h = host.trim().lowercase(Locale.US).trimEnd('.')
            if (h == "localhost" || h == "::1") return true
            return parseIpv4(h)?.let { ip ->
                ip[0] == 127.toByte() ||                        // 127.0.0.0/8
                    ip[0] == 10.toByte() ||                     // 10.0.0.0/8
                    (ip[0] == 172.toByte() && ip[1] in 16..31) || // 172.16.0.0/12
                    (ip[0] == 192.toByte() && ip[1] == 168.toByte()) || // 192.168.0.0/16
                    (ip[0] == 169.toByte() && ip[1] == 254.toByte())    // 169.254.0.0/16
            } ?: false
        }

        private fun parseIpv4(host: String): ByteArray? {
            val parts = host.split('.')
            if (parts.size != 4) return null
            return runCatching {
                ByteArray(4) { i -> parts[i].toInt().also { if (it !in 0..255) throw IllegalArgumentException() }.toByte() }
            }.getOrNull()
        }

        fun normalizeFormat(raw: String): String {
            val f = raw.trim().lowercase(Locale.US)
            return if (f in ALLOWED_FORMATS) f else "mp3"
        }

        /**
         * [TTS FIX] 判断响应体是否为有效音频数据。
         *
         * 背景：部分 OpenAI-compatible 网关（NewAPI/OneAPI/自建代理）在鉴权失败或模型
         * 报错时仍返回 HTTP 2xx + JSON 错误体。若不校验，错误 JSON 会被当作音频文件
         * 写盘入库，UI 显示语音条但播放失败（实测 110 字节 `{"error":{...}}` 被当成 wav）。
         *
         * 策略（按优先级）：
         * 1. Content-Type 明确为 audio 类型 → 放行；
         * 2. 明确为 JSON 或纯文本 → 拒绝（网关错误响应）；
         * 3. 按文件头 magic bytes 识别 WAV / MP3 / FLAC / OGG(Opus) / AAC；
         * 4. 以上都无结论时：体积过小（< 128B）视为错误体拒绝，否则放行（pcm 等裸流）。
         */
        fun isLikelyAudioBody(
            body: ByteArray,
            contentType: String,
            format: String
        ): Boolean {
            val ct = contentType.lowercase(Locale.US)
            if (ct.isNotBlank()) {
                if (ct.startsWith("audio/")) return true
                if (ct.contains("json") || ct.startsWith("text/") ||
                    ct.contains("html") || ct.contains("xml")
                ) {
                    return false
                }
            }
            // magic bytes 识别
            if (body.size >= 12 && body[0] == 'R'.code.toByte() && body[1] == 'I'.code.toByte() &&
                body[2] == 'F'.code.toByte() && body[3] == 'F'.code.toByte() &&
                body[8] == 'W'.code.toByte() && body[9] == 'A'.code.toByte() &&
                body[10] == 'V'.code.toByte() && body[11] == 'E'.code.toByte()
            ) {
                return true // WAV RIFF/WAVE
            }
            if (body.size >= 3 && body[0] == 'I'.code.toByte() && body[1] == 'D'.code.toByte() &&
                body[2] == '3'.code.toByte()
            ) {
                return true // MP3 ID3 tag
            }
            if (body.size >= 4 && body[0] == 0xFF.toByte() && (body[1] == 0xFB.toByte() ||
                    body[1] == 0xF3.toByte() || body[1] == 0xF2.toByte())
            ) {
                return true // MP3 帧头 0xFFFB/0xFFF3/0xFFF2
            }
            if (body.size >= 4 && body[0] == 'f'.code.toByte() && body[1] == 'L'.code.toByte() &&
                body[2] == 'a'.code.toByte() && body[3] == 'C'.code.toByte()
            ) {
                return true // FLAC
            }
            if (body.size >= 4 && body[0] == 'O'.code.toByte() && body[1] == 'g'.code.toByte() &&
                body[2] == 'g'.code.toByte() && body[3] == 'S'.code.toByte()
            ) {
                return true // OGG (Opus/Vorbis)
            }
            if (body.size >= 4 && body[0] == 0xFF.toByte() && (body[1] == 0xF1.toByte() ||
                    body[1] == 0xF9.toByte())
            ) {
                return true // AAC ADTS 帧头
            }
            // 未识别类型：极小体积大概率是错误 JSON / 空壳，拒绝；
            // 其余（pcm 等无头裸流）放行。
            return body.size >= 128
        }

        /** 输出前若干字节的可见 ASCII 预览，便于定位错误体内容。 */
        private fun ByteArray.toHexPreview(limit: Int = 32): String =
            take(limit).joinToString("") { b ->
                val v = b.toInt() and 0xFF
                if (v in 0x20..0x7E) v.toChar().toString() else "."
            }
    }
}
