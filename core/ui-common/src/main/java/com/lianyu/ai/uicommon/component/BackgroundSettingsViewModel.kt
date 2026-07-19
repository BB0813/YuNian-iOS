package com.lianyu.ai.uicommon.component

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 背景设置 ViewModel。
 *
 * 将 chat_prefs / 自定义背景文件的读写从 Composable 迁出，
 * 避免 UI 层直接写 SharedPreferences 与文件。
 */
class BackgroundSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    private val _mainBgKey = MutableStateFlow(getMainBackgroundKey(appContext))
    val mainBgKey: StateFlow<String> = _mainBgKey.asStateFlow()

    private val _chatBgKey = MutableStateFlow(getChatBackgroundKey(appContext))
    val chatBgKey: StateFlow<String> = _chatBgKey.asStateFlow()

    private val _customImageKeys = MutableStateFlow(listCustomBackgroundKeys(appContext))
    val customImageKeys: StateFlow<List<String>> = _customImageKeys.asStateFlow()

    private val _customSolidColors = MutableStateFlow(listCustomSolidColors(appContext))
    val customSolidColors: StateFlow<List<CustomSolidColor>> = _customSolidColors.asStateFlow()

    fun setMainBackground(key: String) {
        setMainBackgroundKey(appContext, key)
        _mainBgKey.value = key
    }

    fun setChatBackground(key: String) {
        setChatBackgroundKey(appContext, key)
        _chatBgKey.value = key
    }

    fun applyBackground(targetMain: Boolean, key: String) {
        if (targetMain) setMainBackground(key) else setChatBackground(key)
    }

    fun refreshCustomImages() {
        _customImageKeys.value = listCustomBackgroundKeys(appContext)
    }

    fun refreshCustomSolids() {
        _customSolidColors.value = listCustomSolidColors(appContext)
    }

    fun saveSolidColor(color: Color, name: String): String {
        val key = saveCustomSolidColor(appContext, color, name)
        refreshCustomSolids()
        return key
    }

    fun updateSolidColor(oldKey: String, color: Color, name: String): String {
        val newKey = updateCustomSolidColor(
            context = appContext,
            oldKey = oldKey,
            color = color,
            name = name
        )
        // 若主/聊背景仍指向旧 key，同步切到新 key
        if (_mainBgKey.value == oldKey) {
            setMainBackground(newKey)
        }
        if (_chatBgKey.value == oldKey) {
            setChatBackground(newKey)
        }
        refreshCustomSolids()
        return newKey
    }

    fun deleteSolidColor(key: String, currentKey: String, targetMain: Boolean) {
        deleteCustomSolidColor(appContext, key)
        if (currentKey == key) {
            applyBackground(targetMain, "default")
        }
        refreshCustomSolids()
    }

    fun saveImageBackground(uri: Uri): String? {
        val key = saveCustomBackground(appContext, uri)
        if (key != null) refreshCustomImages()
        return key
    }

    fun saveImageBackground(bitmap: Bitmap): String? {
        val key = saveCustomBackground(appContext, bitmap)
        if (key != null) refreshCustomImages()
        return key
    }

    fun deleteImageBackground(key: String, currentKey: String, targetMain: Boolean) {
        deleteCustomBackground(appContext, key)
        if (currentKey == key) {
            applyBackground(targetMain, "default")
        }
        refreshCustomImages()
    }
}
