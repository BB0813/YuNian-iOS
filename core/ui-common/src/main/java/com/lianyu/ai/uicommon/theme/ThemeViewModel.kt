package com.lianyu.ai.uicommon.theme

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ThemeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("theme_prefs", Application.MODE_PRIVATE)

    val themeMode: StateFlow<ThemeMode> = _themeMode

    init {
        initCompanion(prefs)
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).commit()
    }

    fun isDarkTheme(): Boolean {
        return when (_themeMode.value) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> {
                val nightModeFlags = getApplication<Application>().resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    fun applyTheme(activity: android.app.Activity) {
        activity.recreate()
    }

    companion object {
        private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)

        @Volatile
        private var companionInitialized = false

        private fun initCompanion(prefs: SharedPreferences) {
            if (companionInitialized) return
            synchronized(this) {
                if (companionInitialized) return
                val saved = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
                _themeMode.value = try {
                    ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name)
                } catch (e: Exception) {
                    ThemeMode.SYSTEM
                }
                companionInitialized = true
            }
        }
    }
}
