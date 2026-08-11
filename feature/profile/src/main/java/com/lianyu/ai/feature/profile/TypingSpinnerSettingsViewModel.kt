package com.lianyu.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.common.AppSettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 聊天页顶栏「对方正在输入」转圈动画开关 ViewModel。
 *
 * 写入绑定 [viewModelScope]，生命周期长于设置页 composition，
 * 避免 [androidx.compose.runtime.rememberCoroutineScope] 在离开页面时取消 DataStore 写入。
 */
class TypingSpinnerSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AppSettingsStore(application)

    val showTypingSpinner: StateFlow<Boolean> = store.showTypingSpinnerFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setShowTypingSpinner(enabled: Boolean) {
        viewModelScope.launch { store.setShowTypingSpinner(enabled) }
    }
}
