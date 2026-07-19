package com.lianyu.ai.feature.profile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.cache.HomeListCache
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.ConversationSummary
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.domain.ServiceRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val companionRepository = ServiceRegistry.getOrThrow(CompanionRepository::class.java)
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val summaryDao = AppDatabase.getDatabase(application).conversationSummaryDao()

    sealed class UiState {
        object Loading : UiState()
        data class Ready(val items: List<ChatListItem>) : UiState()
        data class Error(val message: String) : UiState()
    }

    val chatListState: StateFlow<UiState>

    init {
        val initialState = if (HomeListCache.isWarmed()) {
            buildReady(
                HomeListCache.snapshotCompanions(),
                HomeListCache.snapshotChatSummaries()
            )
        } else {
            UiState.Loading
        }

        chatListState = combine(
            companionRepository.getAllCompanions(),
            summaryDao.getSummariesByType("chat")
        ) { companions, summaries ->
            HomeListCache.putCompanions(companions)
            HomeListCache.putChatSummaries(summaries)
            buildReady(companions, summaries) as UiState
        }
            .catch { e ->
                // 不再把异常吞成空列表，避免“假空首页”
                emit(UiState.Error(e.message?.take(80) ?: "加载会话失败"))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = initialState
            )
    }

    fun markCompanionAsRead(companionId: Long) {
        viewModelScope.launch {
            chatRepository.markReadThroughLatest(companionId)
        }
    }

    private fun buildReady(
        companions: List<CompanionEntity>,
        summaries: List<ConversationSummary>
    ): UiState.Ready {
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
        return UiState.Ready(items)
    }
}

data class ChatListItem(
    val companion: CompanionEntity,
    val lastMessage: ChatMessage?,
    val hasUnread: Boolean = false
)
