package com.lianyu.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 登录页 ViewModel。
 *
 * 登录成功后的 SharedPreferences 写入绑定 [viewModelScope]，
 * 避免 composition 作用域在导航离开时取消持久化。
 */
class AuthViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val isLoading: Boolean = false,
        val errorMessage: String? = null,
        val loginSucceeded: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun login(email: String, password: String): Job = viewModelScope.launch {
        _uiState.value = UiState(isLoading = true)
        try {
            val result = loginUser(email, password)
            if (result.success) {
                getApplication<Application>()
                    .getSharedPreferences("auth_prefs", Application.MODE_PRIVATE)
                    .edit()
                    .putString("auth_token", result.token)
                    .putString("refresh_token", result.refreshToken)
                    .putString("user_email", email)
                    .putString("user_nickname", result.user?.nickname ?: email)
                    .putString("user_avatar", result.user?.avatar)
                    .apply()
                _uiState.value = UiState(isLoading = false, loginSucceeded = true)
            } else {
                _uiState.value = UiState(
                    isLoading = false,
                    errorMessage = result.message
                )
            }
        } catch (e: Exception) {
            _uiState.value = UiState(
                isLoading = false,
                errorMessage = e.message ?: "请求失败"
            )
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun consumeLoginSuccess() {
        _uiState.value = _uiState.value.copy(loginSucceeded = false)
    }
}
