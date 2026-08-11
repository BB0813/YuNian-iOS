package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class TtsService(private val context: Context) {

    private val providers = mutableMapOf<TtsProvider, TtsProviderInterface>()
    private var currentProvider: TtsProvider = TtsProvider.entries.first()
    private val sherpaLocalTts = SherpaLocalTtsProvider()
    private var currentConfig: TtsConfig = TtsConfig.fromSharedPreferences(context)

    init {
        providers[TtsProvider.ALIYUN] = AliyunTtsProvider()
        providers[TtsProvider.BAIDU] = BaiduTtsProvider()
        providers[TtsProvider.XUNFEI] = XunfeiTtsProvider()
        providers[TtsProvider.MICROSOFT] = MicrosoftTtsProvider()
        providers[TtsProvider.VOLCENGINE] = VolcengineTtsProvider()
        providers[TtsProvider.SILICONFLOW] = SiliconFlowTtsProvider()
        providers[TtsProvider.MIMO] = MiMoTtsProvider()
        providers[TtsProvider.OPENAI_COMPAT] = OpenAiCompatibleTtsProvider()
        providers[TtsProvider.SHERPA_LOCAL] = sherpaLocalTts

        // 从 prefs 恢复当前 provider；系统TTS(ANDROID)已移除，
        // 旧配置残留 "ANDROID" 时 entries.find 失败，回退到第一个可用 provider。
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        val providerName = prefs.getString("tts_provider", null)
        currentProvider = TtsProvider.entries.find { it.name == providerName } ?: TtsProvider.entries.first()
        providers.values.filterIsInstance<ConfigurableTtsProvider>().forEach { it.updateConfig(currentConfig) }
        SecureLog.i("TtsService", "Initialized with provider=${currentProvider.displayName}")
    }

    /** 本地离线 TTS 模型管理器（下载/校验/启用），供设置页 UI 消费 */
    val localTtsManager: LocalTtsModelManager by lazy {
        LocalTtsModelManager.getInstance(context)
    }

    fun setProvider(provider: TtsProvider) {
        currentProvider = provider
        SecureLog.i("TtsService", "Switched TTS provider to ${provider.displayName}")
    }

    fun getCurrentProvider(): TtsProvider = currentProvider

    fun getAvailableProviders(): List<TtsProvider> = TtsProvider.entries.toList()

    fun updateConfig(config: TtsConfig) {
        currentConfig = config
        providers.forEach { (provider, providerInterface) ->
            if (providerInterface is ConfigurableTtsProvider) {
                providerInterface.updateConfig(config)
            }
        }
        SecureLog.i("TtsService", "TTS configuration updated")
    }

    fun getConfig(): TtsConfig = currentConfig

    /** 最近一次合成失败的展示原因（试听按钮直接展示；成功时清空）。 */
    @Volatile
    var lastSynthesisError: String? = null
        private set

    suspend fun synthesize(text: String, voiceId: String? = null): String? = withContext(Dispatchers.IO) {
        try {
            val provider = providers[currentProvider]
                ?: throw IllegalStateException("Provider ${currentProvider.name} not initialized")

            // [ADAPT] 调用方（语音条/语音通话/试听）不传 voiceId 时，
            // 统一用设置页保存的音色（tts_voice_<PROVIDER>），否则音色选择从未生效。
            val resolvedVoiceId = voiceId ?: savedVoiceFor(currentProvider)

            SecureLog.d("TtsService", "Synthesizing text with ${currentProvider.displayName}, length=${text.length}")
            lastSynthesisError = null
            val result = provider.synthesize(context, text, resolvedVoiceId)

            if (result != null) {
                SecureLog.i("TtsService", "TTS synthesis successful: $result")
            } else {
                lastSynthesisError = provider.lastError() ?: "合成失败（${currentProvider.displayName}）"
                SecureLog.e("TtsService", "TTS synthesis returned null: $lastSynthesisError")
            }
            result
        } catch (e: Exception) {
            lastSynthesisError = e.message ?: e.javaClass.simpleName
            SecureLog.e("TtsService", "TTS synthesis failed", e)
            null
        }
    }

    /** 设置页保存的当前 provider 音色（无/空则返回 null，交给 provider 用默认）。 */
    private fun savedVoiceFor(provider: TtsProvider): String? {
        return runCatching {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            prefs.getString("tts_voice_${provider.name}", null)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun getVoices(provider: TtsProvider = currentProvider): List<TtsVoice> {
        return providers[provider]?.getVoices() ?: emptyList()
    }

    suspend fun testProvider(provider: TtsProvider): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = providers[provider] ?: return@withContext false

            if (p is ConfigurableTtsProvider) {
                p.updateConfig(currentConfig)
            }

            val isConfigured = currentConfig.isProviderConfigured(provider)
            if (!isConfigured) {
                SecureLog.w("TtsService", "Provider ${provider.displayName} not configured")
                return@withContext false
            }

            p.testConnection(context)
        } catch (e: Exception) {
            SecureLog.e("TtsService", "Test provider ${provider.displayName} failed", e)
            false
        }
    }

    suspend fun testWithSampleText(
        provider: TtsProvider = currentProvider,
        text: String = "你好，这是一个语音合成测试。",
        voiceId: String? = null
    ): String? {
        return try {
            val p = providers[provider] ?: return null
            if (p is ConfigurableTtsProvider) {
                p.updateConfig(currentConfig)
            }

            // [FIX] voiceId 优先取调用方显式传入（设置页试听传当前选中音色，
            // 避免读异步写盘的 tts_voice_<厂商> 拿到旧值）；未传时回退 prefs。
            val resolvedVoiceId = voiceId ?: savedVoiceFor(provider)
            SecureLog.i("TtsService", "Testing TTS with sample text for ${provider.displayName}, voice=$resolvedVoiceId")
            lastSynthesisError = null
            val result = p.synthesize(context, text, resolvedVoiceId)
            if (result == null) {
                lastSynthesisError = p.lastError() ?: "合成失败（${provider.displayName}）"
                SecureLog.e("TtsService", "Test synthesis failed: $lastSynthesisError")
            }
            result
        } catch (e: Exception) {
            lastSynthesisError = e.message ?: e.javaClass.simpleName
            SecureLog.e("TtsService", "Test synthesis failed", e)
            null
        }
    }

    companion object {
        @Volatile
        private var instance: TtsService? = null

        fun getInstance(context: Context): TtsService {
            return instance ?: synchronized(this) {
                instance ?: TtsService(context.applicationContext).also { instance = it }
            }
        }
    }
}
