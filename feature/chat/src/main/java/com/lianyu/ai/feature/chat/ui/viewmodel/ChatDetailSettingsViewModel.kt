package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.feature.chat.data.CompanionChatDetailSettings
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 单聊详情设置 ViewModel。
 *
 * DataStore 写入绑定 [viewModelScope]，生命周期长于页面 composition，
 * 避免 [androidx.compose.runtime.rememberCoroutineScope] 在离开页面/关闭对话框时取消写入。
 *
 * 确认类操作可对返回的 [Job] 调用 [Job.join]，落盘后再 dismiss。
 */
class ChatDetailSettingsViewModel(
    application: Application,
    private val companionId: Long
) : AndroidViewModel(application) {

    private val store = ChatDetailSettingsStore(application)
    private val appSettingsStore = AppSettingsStore(application)

    val settings: StateFlow<CompanionChatDetailSettings> = store.settingsFlow(companionId)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CompanionChatDetailSettings()
        )

    val innerThoughtEnabled: StateFlow<Boolean> = appSettingsStore.innerThoughtEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun updateSettings(
        transform: (CompanionChatDetailSettings) -> CompanionChatDetailSettings
    ): Job = viewModelScope.launch {
        store.updateSettings(companionId, transform)
    }

    /**
     * 切换「使用全局背景」。
     * 关闭全局时冻结当前全局背景为专属 key，避免 false+空 key 语义不清。
     */
    fun setUseGlobalBackground(checked: Boolean): Job = viewModelScope.launch {
        store.updateSettings(companionId) { current ->
            if (checked) {
                current.copy(useGlobalBackground = true, backgroundKey = null)
            } else {
                val frozenKey = current.backgroundKey
                    ?.takeIf { key -> key.isNotBlank() }
                    ?: getChatBackgroundKey(getApplication())
                current.copy(
                    useGlobalBackground = false,
                    backgroundKey = frozenKey
                )
            }
        }
    }

    /**
     * 选择聊天背景。选「默认」视为跟随全局。
     */
    fun selectBackground(key: String): Job = viewModelScope.launch {
        store.updateSettings(companionId) { current ->
            if (key.isBlank() || key == "default") {
                current.copy(useGlobalBackground = true, backgroundKey = null)
            } else {
                current.copy(backgroundKey = key, useGlobalBackground = false)
            }
        }
    }

    fun setInnerThoughtEnabled(enabled: Boolean): Job = viewModelScope.launch {
        appSettingsStore.setInnerThoughtEnabled(enabled)
    }

    fun resetSettings(): Job = viewModelScope.launch {
        store.resetSettings(companionId)
    }
}

class ChatDetailSettingsViewModelFactory(
    private val application: Application,
    private val companionId: Long
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras
    ): T {
        @Suppress("UNCHECKED_CAST")
        return ChatDetailSettingsViewModel(application, companionId) as T
    }
}
