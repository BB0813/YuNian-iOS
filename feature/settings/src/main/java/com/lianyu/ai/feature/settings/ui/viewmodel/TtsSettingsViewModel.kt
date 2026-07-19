package com.lianyu.ai.feature.settings.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.network.tts.ChatTtsConfig
import com.lianyu.ai.network.tts.TtsConfig
import com.lianyu.ai.network.tts.TtsProvider
import com.lianyu.ai.network.tts.TtsService
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TTS 设置 ViewModel。
 *
 * SharedPreferences 写入与 [TtsService] 配置更新绑定 [viewModelScope]，
 * 避免页面离开时 composition 作用域中断保存路径。
 */
class TtsSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val ttsService = TtsService.getInstance(appContext)

    fun saveSettings(
        config: TtsConfig,
        ttsEnabled: Boolean,
        provider: TtsProvider,
        voiceId: String
    ): Job = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            TtsConfig.saveToSharedPreferences(appContext, config)
            appContext.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("tts_enabled", ttsEnabled)
                .putString("tts_provider", provider.name)
                .putString("tts_voice_${provider.name}", voiceId)
                .apply()
        }
        // 服务侧更新放主线程，与原 UI 行为一致
        ttsService.updateConfig(config)
        ttsService.setProvider(provider)
    }

    fun saveChatTtsSettings(config: ChatTtsConfig): Job = viewModelScope.launch(Dispatchers.IO) {
        ChatTtsConfig.saveToSharedPreferences(appContext, config)
    }

    fun getTtsService(): TtsService = ttsService
}
