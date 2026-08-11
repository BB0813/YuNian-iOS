package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.network.NetworkConstants
import com.lianyu.ai.network.RequestSecurityInterceptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 硅基流动 TTS Provider - 基于 CosyVoice2 大模型
 *
 * API: POST https://api.siliconflow.cn/v1/audio/speech
 * 模型: FunAudioLLM/CosyVoice2-0.5B (默认，可配置)
 *
 * 支持功能：
 * - 预设音色列表 (CosyVoice2 常用音色)
 * - 自定义音色 voice_id (通过 https://voice.gbkgov.cn/ 生成)
 * - 独立 API Key 或复用全局 Key
 * - 速度/增益/采样率调节
 *
 * 自定义 OpenAI 兼容端点请使用 [TtsProvider.OPENAI_COMPAT]。
 */
class SiliconFlowTtsProvider : TtsProviderInterface, ConfigurableTtsProvider {

    private val client = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
        RequestSecurityInterceptor.enforceTls(builder)
        builder.build()
    }

    // [TTS FIX] 长文本语音超时：按"一个字符 1 秒"动态配备超时
    private fun clientFor(textLength: Int): OkHttpClient {
        val timeoutMs = TimeoutBudgets.ttsSynthTimeoutMs(textLength)
        return client.newBuilder()
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    private var config: TtsConfig = TtsConfig()

    /** 最近一次合成失败原因（UI 直接展示，release 可见）。 */
    @Volatile
    private var lastErrorMsg: String? = null

    override fun updateConfig(config: TtsConfig) {
        this.config = config
    }

    override fun lastError(): String? = lastErrorMsg

    override suspend fun synthesize(context: Context, text: String, voiceId: String?): String? = withContext(Dispatchers.IO) {
        try {
            val url = NetworkConstants.SILICONFLOW_TTS_URL
            val apiKey = if (config.siliconflowUseGlobalKey) {
                getGlobalApiKey(context) ?: config.siliconflowApiKey
            } else {
                config.siliconflowApiKey
            }
            val model = config.siliconflowTtsModel.ifBlank { "FunAudioLLM/CosyVoice2-0.5B" }
            // [FIX] 自定义音色优先（对齐"自定义 OpenAI"行为：customTtsVoiceId 说了算）：
            // 只要配置了自定义音色就直接用它，不被下拉框的预设音色覆盖。
            // 用户实测：下拉停在预设（如 Alex）时 App 发的是预设而非自定义音色，
            // 而该账户预设会 500 → 误以为自定义音色失败。
            val rawVoice = when {
                config.siliconflowCustomVoiceId.isNotBlank() -> config.siliconflowCustomVoiceId
                voiceId.isNullOrBlank() || voiceId == "__custom__" ->
                    "FunAudioLLM/CosyVoice2-0.5B:anna"
                else -> voiceId
            }

            // [DIAG] 合成入口关键参数（SecureLog 调试门控，release 不输出）
            SecureLog.d(
                "SiliconFlowTts",
                "synthesize enter: len=${text.length}, useGlobal=${config.siliconflowUseGlobalKey}, " +
                    "customVoice='${config.siliconflowCustomVoiceId}', voiceId='$voiceId', rawVoice='$rawVoice'"
            )

            if (apiKey.isBlank()) {
                SecureLog.w("SiliconFlowTts", "API Key not configured")
                lastErrorMsg = "API Key 未配置"
                return@withContext null
            }
            // 记录 Key 来源与前缀，便于定位"Key 对不上"（如全局 Key 是旧值/别的厂商）
            SecureLog.d(
                "SiliconFlowTts",
                "key source: useGlobal=${config.siliconflowUseGlobalKey}, prefix=${apiKey.take(6)}…"
            )

            // [ADAPT] 克隆音色适配：voice 必须传完整 speech: URI。
            // 用户只填了音色名称（customName，如 dp_42824）时先解析出 URI；
            // 已带 speech: 前缀或预设音色（model:name）则原样透传。
            val finalVoice = resolveVoiceUri(apiKey, rawVoice)
            SecureLog.d("SiliconFlowTts", "request voice='$finalVoice' (raw='$rawVoice'), model=$model")

            val sampleRate = config.siliconflowSampleRate
            val speed = config.siliconflowSpeed.toDoubleOrNull() ?: 1.0
            val gain = config.siliconflowGain.toDoubleOrNull() ?: 0.0

            val jsonBody = JSONObject().apply {
                if (model.isNotBlank()) put("model", model)
                put("input", text)
                put("voice", finalVoice)
                put("response_format", "mp3")
                put("sample_rate", sampleRate)
                put("stream", false)
                put("speed", speed)
                put("gain", gain)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val response = clientFor(text.length).newCall(request).execute()
            if (!response.isSuccessful) {
                // [FIX] 记录错误响应体，便于定位真实原因（voice 非法 / Key 错误 / 限流等）
                val errBody = runCatching { response.body?.string() }.getOrNull().orEmpty()
                lastErrorMsg = "HTTP ${response.code}: $errBody"
                SecureLog.e("SiliconFlowTts", "HTTP ${response.code}: ${response.message} body=$errBody")
                return@withContext null
            }
            val body = response.body?.bytes()
            if (body == null || body.isEmpty()) {
                lastErrorMsg = "服务端返回空音频（voice='$finalVoice'）"
                SecureLog.e("SiliconFlowTts", "Empty response body (voice='$finalVoice')")
                return@withContext null
            }
            SecureLog.i("SiliconFlowTts", "synthesize OK: code=${response.code}, bytes=${body.size}, voice='$finalVoice'")

            val outputDir = File(context.cacheDir, "tts_audio")
            outputDir.mkdirs()
            val outputFile = File(outputDir, "siliconflow_${System.currentTimeMillis()}.mp3")
            outputFile.writeBytes(body)

            SecureLog.i("SiliconFlowTts", "TTS success: ${outputFile.absolutePath}")
            outputFile.absolutePath
        } catch (e: Exception) {
            lastErrorMsg = e.message ?: e.javaClass.simpleName
            SecureLog.e("SiliconFlowTts", "Synthesis failed", e)
            null
        }
    }

    /**
     * 解析最终 voice 参数（克隆音色适配）：
     * - `speech:` 开头 → 完整 URI，原样返回
     * - 含 `:`（预设音色 `模型:音色`）→ 原样返回
     * - 其余视为克隆音色名称（customName，如 dp_42824）→ 调音色列表接口解析出 URI；
     *   列表查询失败/未找到 → 原样透传，交由服务端报错（错误体现在日志中）
     */
    private suspend fun resolveVoiceUri(apiKey: String, voice: String): String {
        if (voice.startsWith("speech:") || voice.contains(":")) return voice
        return runCatching {
            val request = Request.Builder()
                .url(NetworkConstants.SILICONFLOW_VOICE_LIST_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    SecureLog.w("SiliconFlowTts", "voice list HTTP ${resp.code}, keep voice=$voice")
                    return@use voice
                }
                val json = resp.body?.string() ?: return@use voice
                resolveVoiceUriFromJson(json, voice) ?: voice
            }
        }.getOrDefault(voice)
    }

    override fun getVoices(): List<TtsVoice> {
        // 官方文档（https://api-docs.siliconflow.cn/docs/userguide/capabilities/text-to-speech）：
        // CosyVoice2-0.5B 系统预设音色共 8 个，请求时需带模型前缀 "FunAudioLLM/CosyVoice2-0.5B:音色名"。
        // [FIX] 此前列表混入 8 个不存在的音色（chloe/emma/grace/henry/jack/luna/sarah/sophia/william），
        // 选中即报错；且漏了 claire。按文档重写。
        return listOf(
            // ── 男声 ──
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:alex", "Alex", "男", "zh-CN", "沉稳男声 (steady male)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:benjamin", "Benjamin", "男", "en-US", "深沉男声 (deep male)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:charles", "Charles", "男", "en-GB", "磁性男声 (magnetic male)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:david", "David", "男", "zh-CN", "阳光开朗男声 (cheerful male)"),
            // ── 女声 ──
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:anna", "Anna", "女", "zh-CN", "沉稳女声 (steady female)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:bella", "Bella", "女", "zh-CN", "热情女声 (passionate female)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:claire", "Claire", "女", "zh-CN", "温柔女声 (gentle female)"),
            TtsVoice("FunAudioLLM/CosyVoice2-0.5B:diana", "Diana", "女", "en-US", "开朗女声 (cheerful female)"),
            // ── 自定义克隆音色 ──
            TtsVoice("__custom__", "自定义音色", "自定义", "zh-CN", "使用克隆音色（设置页填入名称或 speech: URI）")
        )
    }

    override suspend fun testConnection(context: Context): Boolean {
        return try {
            val apiKey = if (config.siliconflowUseGlobalKey) {
                getGlobalApiKey(context) ?: config.siliconflowApiKey
            } else {
                config.siliconflowApiKey
            }
            if (apiKey.isBlank()) return false
            // 真探活：调音色列表接口，Key 无效/欠费/无权限直接 4xx（不再"Key 非空即通过"）
            val request = Request.Builder()
                .url(NetworkConstants.SILICONFLOW_VOICE_LIST_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            SecureLog.e("SiliconFlowTts", "testConnection failed", e)
            false
        }
    }

    /**
     * 获取硅基流动全局 API Key。
     *
     * [FIX] 只读 `api_key_SILICONFLOW`（对齐 SiliconFlowSttProvider）：
     * 绝不能回退到"当前聊天模型商"的 Key（如 DeepSeek）——那会把别的厂商 Key 发给
     * 硅基接口导致 401。用户实测：同一 Key 在"自定义 OpenAI"成功、硅基 provider 失败，
     * 根因就是这里拿错了 Key。全局 Key 未配置时由调用方回退到 TTS 设置页填的
     * config.siliconflowApiKey。
     */
    private fun getGlobalApiKey(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences("api_settings", Context.MODE_PRIVATE)
            prefs.getString("api_key_SILICONFLOW", null)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        /**
         * 从音色列表响应 JSON 中解析 voice 对应的 URI（纯函数，可单测）。
         *
         * 实测硅基流动 `/v1/audio/voice/list` 响应键是 **`result`（单数）**：
         * `{"result":[{"customName":"dp_42824","uri":"speech:...","text":"...","model":"..."}]}`
         * 兼容早期文档的 `results` 双写。
         *
         * @return 匹配到的 URI；未找到/解析失败返回 null（调用方回退原 voice）。
         */
        internal fun resolveVoiceUriFromJson(json: String, voice: String): String? {
            return runCatching {
                val resultArray = JSONObject(json).let { obj ->
                    obj.optJSONArray("result") ?: obj.optJSONArray("results")
                } ?: return null
                for (i in 0 until resultArray.length()) {
                    val item = resultArray.optJSONObject(i) ?: continue
                    val name = item.optString("customName")
                    val uri = item.optString("uri")
                    if (name == voice || uri == voice) {
                        return uri.takeIf { it.isNotBlank() }
                    }
                }
                null
            }.getOrNull()
        }
    }
}
