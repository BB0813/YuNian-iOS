package com.lianyu.ai.network.tts

import android.content.Context

interface TtsProviderInterface {
    suspend fun synthesize(context: Context, text: String, voiceId: String?): String?
    fun getVoices(): List<TtsVoice>
    /**
     * 真探活：实际调用厂商接口验证 Key/配置可用，避免"配置非空即通过"的假测试。
     * 本地/离线 provider 无网络依赖时返回其就绪状态即可。
     */
    suspend fun testConnection(context: Context): Boolean
    /** 最近一次合成失败的原因（供 UI 展示），默认无。 */
    fun lastError(): String? = null
}