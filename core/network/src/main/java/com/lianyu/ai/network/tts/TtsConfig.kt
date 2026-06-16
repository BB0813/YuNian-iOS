package com.lianyu.ai.network.tts

import android.content.Context
import com.lianyu.ai.common.SecureLog

data class TtsConfig(
    val aliyunKeyId: String = "",
    val aliyunKeySecret: String = "",
    val aliyunAppKey: String = "",
    val baiduApiKey: String = "",
    val baiduSecretKey: String = "",
    val xunfeiAppId: String = "",
    val xunfeiApiKey: String = "",
    val xunfeiApiSecret: String = "",
    val azureSubscriptionKey: String = "",
    val azureRegion: String = "eastasia",
    val volcengineAppId: String = "",
    val volcengineToken: String = "",
    val volcengineCluster: String = ""
) {
    fun isProviderConfigured(provider: TtsProvider): Boolean {
        return when (provider) {
            TtsProvider.ANDROID -> true
            TtsProvider.ALIYUN -> aliyunKeyId.isNotBlank() && aliyunKeySecret.isNotBlank() && aliyunAppKey.isNotBlank()
            TtsProvider.BAIDU -> baiduApiKey.isNotBlank() && baiduSecretKey.isNotBlank()
            TtsProvider.XUNFEI -> xunfeiAppId.isNotBlank() && xunfeiApiKey.isNotBlank() && xunfeiApiSecret.isNotBlank()
            TtsProvider.MICROSOFT -> azureSubscriptionKey.isNotBlank()
            TtsProvider.VOLCENGINE -> volcengineAppId.isNotBlank() && volcengineToken.isNotBlank()
        }
    }

    companion object {
        fun fromSharedPreferences(context: Context): TtsConfig {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            return TtsConfig(
                aliyunKeyId = prefs.getString("aliyun_key", "") ?: "",
                aliyunKeySecret = prefs.getString("aliyun_secret", "") ?: "",
                aliyunAppKey = prefs.getString("aliyun_app_key", "") ?: "",
                baiduApiKey = prefs.getString("baidu_key", "") ?: "",
                baiduSecretKey = prefs.getString("baidu_secret", "") ?: "",
                xunfeiAppId = prefs.getString("xunfei_app_id", "") ?: "",
                xunfeiApiKey = prefs.getString("xunfei_key", "") ?: "",
                xunfeiApiSecret = prefs.getString("xunfei_secret", "") ?: "",
                azureSubscriptionKey = prefs.getString("azure_key", "") ?: "",
                azureRegion = prefs.getString("azure_region", "eastasia") ?: "eastasia",
                volcengineAppId = prefs.getString("volcengine_app_id", "") ?: "",
                volcengineToken = prefs.getString("volcengine_token", "") ?: "",
                volcengineCluster = prefs.getString("volcengine_cluster", "") ?: ""
            )
        }

        fun saveToSharedPreferences(context: Context, config: TtsConfig) {
            val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putString("aliyun_key", config.aliyunKeyId)
                putString("aliyun_secret", config.aliyunKeySecret)
                putString("aliyun_app_key", config.aliyunAppKey)
                putString("baidu_key", config.baiduApiKey)
                putString("baidu_secret", config.baiduSecretKey)
                putString("xunfei_app_id", config.xunfeiAppId)
                putString("xunfei_key", config.xunfeiApiKey)
                putString("xunfei_secret", config.xunfeiApiSecret)
                putString("azure_key", config.azureSubscriptionKey)
                putString("azure_region", config.azureRegion)
                putString("volcengine_app_id", config.volcengineAppId)
                putString("volcengine_token", config.volcengineToken)
                putString("volcengine_cluster", config.volcengineCluster)
                apply()
            }
            SecureLog.i("TtsConfig", "Configuration saved to SharedPreferences")
        }
    }
}
