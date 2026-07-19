package com.lianyu.ai.common

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * 全局语言应用入口。
 *
 * 统一 prefs 读写、Locale 解析与 Configuration 注入，
 * 避免 Activity / ViewModel 各自实现导致切换后不生效。
 */
object LocaleHelper {
    const val PREFS_NAME = "language_prefs"
    const val KEY_LANGUAGE = "language"
    const val DEFAULT_LANGUAGE = "zh-CN"

    fun getSavedLanguage(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    /**
     * 同步写入语言配置。必须用 commit，避免 recreate 时读到旧值。
     */
    fun saveLanguage(context: Context, code: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.edit().putString(KEY_LANGUAGE, code).commit()
    }

    fun toLocale(code: String): Locale = when (code) {
        "zh-CN" -> Locale.SIMPLIFIED_CHINESE
        "zh-TW" -> Locale.TRADITIONAL_CHINESE
        "en" -> Locale.ENGLISH
        "ja" -> Locale.JAPANESE
        "ko" -> Locale.KOREAN
        else -> Locale.SIMPLIFIED_CHINESE
    }

    fun applyToContext(base: Context, languageCode: String = getSavedLanguage(base)): Context {
        val locale = toLocale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        return base.createConfigurationContext(config)
    }

    /**
     * 将语言立即应用到当前 Context 的 Resources（recreate 前的兜底）。
     */
    fun applyToResources(context: Context, languageCode: String) {
        val locale = toLocale(languageCode)
        Locale.setDefault(locale)
        val resources = context.resources
        val config = Configuration(resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    }
}
