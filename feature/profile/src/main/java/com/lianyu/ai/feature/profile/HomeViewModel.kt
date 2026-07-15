package com.lianyu.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.domain.ServiceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val companionRepository: CompanionRepository
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val summaryDao = AppDatabase.getDatabase(application).conversationSummaryDao()

    sealed class UiState {
        object Loading : UiState()
        data class Ready(val items: List<ChatListItem>) : UiState()
    }

    val chatListState: Flow<UiState>

    init {
        val database = AppDatabase.getDatabase(application)
        companionRepository = CompanionRepository(database.companionDao())

        chatListState = combine(
            companionRepository.getAllCompanions(),
            summaryDao.getSummariesByType("chat")
        ) { companions, summaries ->
            val summariesById = summaries.associateBy { it.sessionId }
            val items = companions.map { companion ->
                val summary = summariesById[companion.id]
                val lastMessage = summary?.let {
                    ChatMessage(
                        companionId = companion.id,
                        content = it.lastMessagePreview,
                        isFromUser = it.lastMessageIsFromUser,
                        timestamp = it.lastMessageTimestamp
                    )
                }
                ChatListItem(
                    companion = companion,
                    lastMessage = lastMessage,
                    hasUnread = (summary?.unreadCount ?: 0) > 0
                )
            }
            UiState.Ready(items) as UiState
        }
            .onStart { emit(UiState.Loading) }
            .catch {
                emit(UiState.Ready(emptyList()))
            }
    }

    fun markCompanionAsRead(companionId: Long) {
        viewModelScope.launch {
            chatRepository.markReadThroughLatest(companionId)
        }
    }
}

data class ChatListItem(
    val companion: CompanionEntity,
    val lastMessage: ChatMessage?,
    val hasUnread: Boolean = false
)
