package com.lianyu.ai.feature.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.DiaryEntry
import com.lianyu.ai.database.model.MemoryCategory
import com.lianyu.ai.database.model.MemoryRecord
import com.lianyu.ai.database.model.MemoryScope
import com.lianyu.ai.database.model.MemorySource
import com.lianyu.ai.database.model.MemoryType
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.UnifiedMemoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryRepository: UnifiedMemoryRepository
    private val companionRepository: CompanionRepository
    private val diaryDao = AppDatabase.getDatabase(application).diaryDao()
    private val deviceId = DeviceIdProvider.getDeviceId(application)

    val companions: Flow<List<CompanionEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        memoryRepository = UnifiedMemoryRepository(database.unifiedMemoryDao(), deviceId)
        companionRepository = CompanionRepository(database.companionDao())
        companions = companionRepository.getAllCompanions()
    }

    fun getMemoriesForCompanion(companionId: Long): Flow<List<MemoryRecord>> {
        return memoryRepository.getStableMemories(MemoryScope.COMPANION, companionId)
    }

    fun getTempMemoriesForCompanion(companionId: Long): Flow<List<MemoryRecord>> {
        return memoryRepository.getWorkingMemories(MemoryScope.COMPANION, companionId, limit = 20)
    }

    fun deleteMemory(memory: MemoryRecord) {
        viewModelScope.launch {
            memoryRepository.softDelete(memory.id)
        }
    }

    fun updateMemory(memory: MemoryRecord) {
        viewModelScope.launch {
            memoryRepository.updateMemory(memory)
        }
    }

    fun addManualMemory(
        companionId: Long,
        content: String,
        category: MemoryCategory = MemoryCategory.FACT,
        importance: Float = 0.7f,
        context: String = ""
    ) {
        viewModelScope.launch {
            memoryRepository.addMemory(
                content = content,
                type = category.toMemoryType(),
                scope = MemoryScope.COMPANION,
                sourceId = companionId,
                source = MemorySource.MANUAL,
                importance = importance,
                confidence = 1.0f,
                summary = context,
                tags = category.name.lowercase()
            )
        }
    }

    fun deleteMemoriesForCompanion(companionId: Long) {
        viewModelScope.launch {
            memoryRepository.softDeleteByScopeAndSource(
                scope = MemoryScope.COMPANION,
                sourceId = companionId,
                source = MemorySource.MANUAL
            )
        }
    }

    private fun MemoryCategory.toMemoryType(): MemoryType = when (this) {
        MemoryCategory.FACT -> MemoryType.SEMANTIC
        MemoryCategory.EMOTION -> MemoryType.EPISODIC
        MemoryCategory.PREFERENCE -> MemoryType.PREFERENCE
        MemoryCategory.EVENT -> MemoryType.EPISODIC
        MemoryCategory.HABIT -> MemoryType.PROCEDURAL
        MemoryCategory.RELATIONSHIP -> MemoryType.RELATIONSHIP
    }

    // === 日记功能 ===

    fun getDiariesForCompanion(companionId: Long): Flow<List<DiaryEntry>> {
        return diaryDao.getDiariesForCompanion(companionId, deviceId)
    }

    fun addDiary(
        companionId: Long,
        title: String,
        content: String,
        mood: Int = 2,
        weather: String = "",
        tags: String = "",
        date: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            val diary = DiaryEntry(
                companionId = companionId,
                title = title.trim(),
                content = content.trim(),
                mood = mood,
                weather = weather.trim(),
                tags = tags.trim(),
                date = date,
                deviceId = deviceId
            )
            diaryDao.insertDiary(diary)
        }
    }

    fun updateDiary(diary: DiaryEntry) {
        viewModelScope.launch {
            diaryDao.insertDiary(diary)
        }
    }

    fun deleteDiary(diary: DiaryEntry) {
        viewModelScope.launch {
            diaryDao.deleteDiary(diary)
        }
    }

    fun deleteDiariesForCompanion(companionId: Long) {
        viewModelScope.launch {
            diaryDao.deleteDiariesForCompanion(companionId, deviceId)
        }
    }
}
