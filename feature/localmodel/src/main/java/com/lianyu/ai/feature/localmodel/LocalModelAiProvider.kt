package com.lianyu.ai.feature.localmodel

import android.content.Context
import com.lianyu.ai.common.localmodel.LocalAiModelProvider
import kotlinx.coroutines.flow.first

class LocalModelAiProvider(context: Context) : LocalAiModelProvider {
    private val appContext = context.applicationContext
    private val localAiService = LocalAiService.getInstance(appContext)
    private val localModelPreferences = LocalModelPreferences(appContext)
    private var closed = false

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

    override fun close() {
        if (!closed) {
            closed = true
            localAiService.close()
        }
    }

    private suspend fun selectedModel(): LocalModel {
        val modelId = localModelPreferences.selectedModelId.first()
        return LocalModelCatalog.findById(modelId)
    }
}
