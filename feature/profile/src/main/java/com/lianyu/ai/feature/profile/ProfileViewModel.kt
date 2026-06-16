package com.lianyu.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.database.repository.UserRepository
import com.lianyu.ai.common.ImageUtils
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UserRepository(application)

    val userName: StateFlow<String> = repository.userName
    val userAvatar: StateFlow<String?> = repository.userAvatar

    fun updateUserName(name: String) {
        viewModelScope.launch {
            repository.updateUserName(name)
        }
    }

    fun updateUserAvatar(avatarUri: String?) {
        viewModelScope.launch {
            val savedUri = if (avatarUri != null) {
                ImageUtils.saveUriToInternalStorage(getApplication(), avatarUri)
            } else null
            repository.updateUserAvatar(savedUri)
        }
    }
}
