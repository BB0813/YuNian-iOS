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
    ): Job {
        // 服务侧配置必须同步更新（不能在协程里）：
        // 设置页"测试连接/试听"在 saveSettings() 返回后立即调用
        // testProvider()/testWithSampleText()，二者读取 TtsService.currentConfig。
        // 若此处异步，测试会用旧配置（customTtsUrl 等仍为默认值 → isProviderConfigured=false
        // → 直接返回失败，请求甚至不会发出）。
        ttsService.updateConfig(config)
        ttsService.setProvider(provider)
        // 持久化放后台协程，避免阻塞主线程
        return viewModelScope.launch {
            withContext(Dispatchers.IO) {
                TtsConfig.saveToSharedPreferences(appContext, config)
                appContext.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("tts_enabled", ttsEnabled)
                    .putString("tts_provider", provider.name)
                    .putString("tts_voice_${provider.name}", voiceId)
                    .apply()
            }
        }
    }

    fun saveChatTtsSettings(config: ChatTtsConfig): Job = viewModelScope.launch(Dispatchers.IO) {
        ChatTtsConfig.saveToSharedPreferences(appContext, config)
    }

    fun getTtsService(): TtsService = ttsService
}
