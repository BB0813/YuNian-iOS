package com.lianyu.ai.feature.settings.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lianyu.ai.common.LocaleHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LanguageViewModel(application: Application) : AndroidViewModel(application) {
    private val _language = MutableStateFlow(LocaleHelper.DEFAULT_LANGUAGE)
    val language: StateFlow<String> = _language

    init {
        _language.value = LocaleHelper.getSavedLanguage(application)
    }

    fun setLanguage(code: String) {
        _language.value = code
        // 必须同步 commit：recreate 会立刻读 prefs，apply() 异步写入会导致语言不生效。
        LocaleHelper.saveLanguage(getApplication(), code)
    }

    fun applyLanguage(activity: android.app.Activity) {
        val code = _language.value
        LocaleHelper.saveLanguage(activity, code)
        LocaleHelper.applyToResources(activity.applicationContext, code)
        LocaleHelper.applyToResources(activity, code)
        activity.recreate()
    }
}
