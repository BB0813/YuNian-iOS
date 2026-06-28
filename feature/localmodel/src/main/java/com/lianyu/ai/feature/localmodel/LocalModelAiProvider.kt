package com.lianyu.ai.feature.localmodel

import android.content.Context
import com.lianyu.ai.common.localmodel.LocalAiModelProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class LocalModelAiProvider(context: Context) : LocalAiModelProvider {
    private val appContext = context.applicationContext
    private val localAiService = LocalAiService.getInstance(appContext)
    private val localModelPreferences = LocalModelPreferences(appContext)
    @Volatile private var closed = false

    init {
        localAiService.acquire()
    }

    override suspend fun isEnabled(): Boolean {
        if (!LocalAiService.isNativeLibrarySupported) return false
        val prefs = localModelPreferences.state.first()
        val modelFile = selectedModel().modelFile(appContext)
        return prefs.isGemmaEnabled && modelFile.exists()
    }

    override suspend fun generate(
        systemPrompt: String,
        historyPrompt: String,
        userPrompt: String
    ): String? = runCatching {
        localAiService.generate(systemPrompt, historyPrompt, userPrompt, selectedModel())
    }.getOrNull()

    // [M12 FIX] close/shutdownEngine 改为 suspend（内部走 mutex.withLock），
    // 这里用 runBlocking 桥接同步 close() 接口。LocalModelAiProvider 的 close 由上层同步调用。
    override fun close() {
        if (!closed) {
            closed = true
            runBlocking { localAiService.close() }
        }
    }

    private suspend fun selectedModel(): LocalModel {
        val modelId = localModelPreferences.selectedModelId.first()
        return LocalModelCatalog.findById(modelId)
    }
}

