package com.lianyu.ai.uicommon.picker.ui

import android.content.ContentResolver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * PickerViewModel 工厂 — 注入 ContentResolver。
 */
internal class PickerViewModelFactory(
    private val contentResolver: ContentResolver
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PickerViewModel::class.java)) {
            return PickerViewModel(contentResolver) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
