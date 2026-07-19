package com.lianyu.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.common.AppSettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 思考设置 ViewModel。
 *
 * 写入绑定 [viewModelScope]，生命周期长于对话框 composition，
 * 避免 [androidx.compose.runtime.rememberCoroutineScope] 在对话框关闭时取消 DataStore 写入。
 *
 * UI 应调用 [saveThinkingSettings] 后 [Job.join]，确认落盘再 dismiss。
 */
class ThinkingSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AppSettingsStore(application)

    val showReasoning: StateFlow<Boolean> = store.showReasoningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoCollapseReasoning: StateFlow<Boolean> = store.autoCollapseReasoningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val sendReasoning: StateFlow<Boolean> = store.sendReasoningFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val responseField: StateFlow<String> = store.reasoningResponseFieldFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "reasoning_content")

    val requestField: StateFlow<String> = store.reasoningRequestFieldFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "reasoning_content")

    /**
     * 在 [viewModelScope] 中原子保存思考设置。
     * @return 可 [Job.join] 的任务，供 UI 等待完成后再关闭对话框。
     */
    fun saveThinkingSettings(
        showReasoning: Boolean,
        autoCollapseReasoning: Boolean,
        responseField: String,
        requestField: String,
        sendReasoning: Boolean
    ): Job = viewModelScope.launch {
        store.saveThinkingSettings(
            showReasoning = showReasoning,
            autoCollapseReasoning = autoCollapseReasoning,
            responseField = responseField,
            requestField = requestField,
            sendReasoning = sendReasoning
        )
    }
}
