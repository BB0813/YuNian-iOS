package com.lianyu.ai.feature.qqbot.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.feature.qqbot.data.QQBotMessageRepository
import com.lianyu.ai.feature.qqbot.data.QQBotTokenStore
import com.lianyu.ai.feature.qqbot.data.model.QQBotAccount
import com.lianyu.ai.feature.qqbot.service.QQBotForegroundService
import com.lianyu.ai.feature.qqbot.service.QQBotServiceLocator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class QQBotViewModel(
    application: Application,
    private val repository: QQBotMessageRepository,
    private val tokenStore: QQBotTokenStore
) : ViewModel() {

    private val appContext = application.applicationContext

    private val _uiState = MutableStateFlow(QQBotUiState())
    val uiState: StateFlow<QQBotUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<QQBotEvent>()
    val events: SharedFlow<QQBotEvent> = _events.asSharedFlow()

    private val companionDao = AppDatabase.getDatabase(appContext).companionDao()

    init {
        viewModelScope.launch {
            repository.accountFlow.collect { account ->
                _uiState.value = _uiState.value.copy(
                    isLoggedIn = account != null,
                    account = account
                )
                if (account != null) {
                    QQBotForegroundService.start(appContext)
                    loadUserMappings()
                } else {
                    QQBotForegroundService.stop(appContext)
                }
            }
        }

        viewModelScope.launch {
            tokenStore.autoReplyFlow.collect { _uiState.value = _uiState.value.copy(autoReply = it) }
        }
        viewModelScope.launch {
            tokenStore.notifyEnabledFlow.collect { _uiState.value = _uiState.value.copy(notifyEnabled = it) }
        }
        viewModelScope.launch {
            tokenStore.forwardEnabledFlow.collect { _uiState.value = _uiState.value.copy(forwardEnabled = it) }
        }
        viewModelScope.launch {
            tokenStore.defaultCompanionIdFlow.collect { _uiState.value = _uiState.value.copy(defaultCompanionId = it) }
        }
        viewModelScope.launch {
            tokenStore.customBotNameFlow.collect { _uiState.value = _uiState.value.copy(customBotName = it) }
        }
        viewModelScope.launch {
            companionDao.getAllCompanions().collect { _uiState.value = _uiState.value.copy(availableCompanions = it) }
        }
        viewModelScope.launch {
            repository.incomingEvents.collect { event ->
                val text = repository.extractText(event)
                _events.emit(QQBotEvent.MessageReceived(repository.getReplyKey(event), text))
                // [FIX] 自动回复已移到 QQBotForegroundService/QQBotChatBridge，避免与 UI 生命周期绑定
            }
        }
    }

    fun saveAccount(appId: String, clientSecret: String, customName: String?) {
        if (_uiState.value.isLoading) return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = repository.saveAccount(appId.trim(), clientSecret.trim(), customName?.trim())
            result.onSuccess {
                _uiState.value = _uiState.value.copy(isLoading = false, isLoggedIn = true, error = null)
                _events.emit(QQBotEvent.LoginSuccess)
                QQBotForegroundService.start(appContext)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
                _events.emit(QQBotEvent.LoginFailed(e.message ?: "绑定失败"))
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            repository.logout()
            QQBotForegroundService.stop(appContext)
            _uiState.value = _uiState.value.copy(isLoggedIn = false, account = null)
            _events.emit(QQBotEvent.LoggedOut)
        }
    }

    fun toggleAutoReply(enabled: Boolean) {
        viewModelScope.launch { tokenStore.setAutoReply(enabled) }
    }

    fun toggleNotifyEnabled(enabled: Boolean) {
        viewModelScope.launch { tokenStore.setNotifyEnabled(enabled) }
    }

    fun toggleForwardEnabled(enabled: Boolean) {
        viewModelScope.launch { tokenStore.setForwardEnabled(enabled) }
    }

    fun setDefaultCompanionId(companionId: Long?) {
        viewModelScope.launch { tokenStore.setDefaultCompanionId(companionId) }
    }

    fun setCustomBotName(name: String?) {
        viewModelScope.launch { tokenStore.setCustomBotName(name?.takeIf { it.isNotBlank() }) }
    }

    fun setUserCompanionMapping(qqUserId: String, companionId: Long) {
        viewModelScope.launch {
            tokenStore.setCompanionIdForQQUser(qqUserId, companionId)
            loadUserMappings()
        }
    }

    fun removeUserCompanionMapping(qqUserId: String) {
        viewModelScope.launch {
            tokenStore.removeQQUserMapping(qqUserId)
            loadUserMappings()
        }
    }

    private fun loadUserMappings() {
        viewModelScope.launch {
            val mappings = tokenStore.getAllQQUserMappings()
            _uiState.value = _uiState.value.copy(userCompanionMappings = mappings)
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}

data class QQBotUiState(
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val account: QQBotAccount? = null,
    val error: String? = null,
    val autoReply: Boolean = false,
    val notifyEnabled: Boolean = true,
    val forwardEnabled: Boolean = true,
    val defaultCompanionId: Long? = null,
    val availableCompanions: List<CompanionEntity> = emptyList(),
    val userCompanionMappings: Map<String, Long> = emptyMap(),
    val customBotName: String? = null
)

sealed class QQBotEvent {
    data class MessageReceived(val key: String, val text: String) : QQBotEvent()
    data object LoginSuccess : QQBotEvent()
    data object LoggedOut : QQBotEvent()
    data class LoginFailed(val error: String) : QQBotEvent()
}

class QQBotViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val repository = QQBotServiceLocator.messageRepository(application)
        val tokenStore = QQBotServiceLocator.tokenStore(application)
        return QQBotViewModel(application, repository, tokenStore) as T
    }
}
