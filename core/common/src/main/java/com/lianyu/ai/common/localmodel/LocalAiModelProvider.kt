package com.lianyu.ai.common.localmodel

interface LocalAiModelProvider {
    suspend fun isEnabled(): Boolean
    suspend fun generate(systemPrompt: String, historyPrompt: String, userPrompt: String): String?
    fun close()
}
