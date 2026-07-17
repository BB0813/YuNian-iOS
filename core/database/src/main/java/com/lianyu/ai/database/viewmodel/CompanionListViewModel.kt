package com.lianyu.ai.database.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.DefaultCompanionSeeder
import com.lianyu.ai.database.cache.HomeListCache
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.repository.CompanionRepository
import androidx.core.content.edit
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CompanionListViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: CompanionRepository
    val companions: StateFlow<List<CompanionEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = CompanionRepository(database.companionDao())
        companions = repository.getAllCompanions()
            .onEach { HomeListCache.putCompanions(it) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = HomeListCache.snapshotCompanions()
            )
    }

    fun deleteCompanion(companion: CompanionEntity) {
        viewModelScope.launch {
            // 如果删除的是默认测试伴侣（含新旧版 tag），标记用户已主动删除
            val isDefaultCompanion = companion.tags.orEmpty()
                .split(',')
                .map { it.trim() }
                .any { it == DefaultCompanionSeeder.LEGACY_TAG || it == DefaultCompanionSeeder.defaultExperienceCompanionTag }
            if (isDefaultCompanion) {
                getApplication<Application>()
                    .getSharedPreferences("default_companion", android.content.Context.MODE_PRIVATE)
                    .edit { putBoolean("deleted_by_user", true) }
            }
            repository.deleteCompanion(companion)
        }
    }
}
