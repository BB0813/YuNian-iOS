package com.lianyu.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lianyu.ai.common.FrameRateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 帧率设置 ViewModel。
 *
 * SharedPreferences 写入放在 ViewModel 中，避免 UI 层直接操作持久化。
 * 与 [LanguageViewModel] / [com.lianyu.ai.uicommon.theme.ThemeViewModel] 保持一致。
 */
class FrameRateViewModel(application: Application) : AndroidViewModel(application) {
    private val _frameRate = MutableStateFlow(
        FrameRateManager.getSavedFrameRate(application)
    )
    val frameRate: StateFlow<FrameRateManager.FrameRate> = _frameRate.asStateFlow()

    fun setFrameRate(rate: FrameRateManager.FrameRate) {
        if (rate == _frameRate.value) return
        _frameRate.value = rate
        FrameRateManager.saveFrameRate(getApplication(), rate)
    }
}
