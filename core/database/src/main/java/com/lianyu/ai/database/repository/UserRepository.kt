package com.lianyu.ai.database.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UserRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private val _userName = MutableStateFlow(prefs.getString("user_name", "我") ?: "我")
    val userName: StateFlow<String> = _userName

    private val _userAvatar = MutableStateFlow(prefs.getString("user_avatar", null))
    val userAvatar: StateFlow<String?> = _userAvatar

    fun updateUserName(name: String) {
        prefs.edit { putString("user_name", name) }
        _userName.value = name
    }

    fun updateUserAvatar(avatarUri: String?) {
        if (avatarUri != null) {
            prefs.edit { putString("user_avatar", avatarUri) }
        } else {
            prefs.edit { remove("user_avatar") }
        }
        _userAvatar.value = avatarUri
    }
}
