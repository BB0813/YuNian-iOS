package com.lianyu.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.cache.HomeListCache
import com.lianyu.ai.database.model.ChatGroup
import com.lianyu.ai.database.repository.ChatGroupRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ChatGroupViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: ChatGroupRepository
    val groups: StateFlow<List<ChatGroup>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ChatGroupRepository(database.chatGroupDao())
        groups = repository.getAllGroups()
            .onEach { HomeListCache.putGroups(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = HomeListCache.snapshotGroups()
            )
    }

    fun createGroup(name: String, companionIds: List<Long>) {
        viewModelScope.launch {
            val group = ChatGroup(
                name = name,
                companionIds = companionIds.joinToString(",")
            )
            repository.insertGroup(group)
        }
    }

    fun deleteGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.deleteGroup(group)
        }
    }

    fun updateGroup(group: ChatGroup) {
        viewModelScope.launch {
            repository.updateGroup(group)
        }
    }
}
