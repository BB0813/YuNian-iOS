package com.lianyu.ai.feature.memory

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.DiaryEntry
import com.lianyu.ai.database.model.MemoryCategory
import com.lianyu.ai.database.model.MemoryRecord
import com.lianyu.ai.database.model.MemoryScope
import com.lianyu.ai.database.model.MemorySource
import com.lianyu.ai.database.model.MemoryType
import com.lianyu.ai.database.repository.ChatMessageCrypto
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.DiaryProvider
import com.lianyu.ai.database.repository.UnifiedMemoryRepository
import com.lianyu.ai.domain.ServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryRepository: UnifiedMemoryRepository
    private val companionRepository: CompanionRepository
    private val diaryDao = AppDatabase.getDatabase(application).diaryDao()
    private val messageDao = AppDatabase.getDatabase(application).messageDao()
    private val deviceId = DeviceIdProvider.getDeviceId(application)
    private val stableMemoryFlows = mutableMapOf<Long, Flow<List<MemoryRecord>>>()
    private val workingMemoryFlows = mutableMapOf<Long, Flow<List<MemoryRecord>>>()
    private val diaryFlows = mutableMapOf<Long, Flow<List<DiaryEntry>>>()

    private val _isGeneratingDiary = MutableStateFlow(false)
    val isGeneratingDiary: StateFlow<Boolean> = _isGeneratingDiary.asStateFlow()

    val companions: Flow<List<CompanionEntity>>

    init {
        val database = AppDatabase.getDatabase(application)
        memoryRepository = UnifiedMemoryRepository(database.unifiedMemoryDao(), deviceId)
        companionRepository = CompanionRepository(database.companionDao())
        companions = companionRepository.getAllCompanions()
    }

    fun getMemoriesForCompanion(companionId: Long): Flow<List<MemoryRecord>> {
        return stableMemoryFlows.getOrPut(companionId) {
            memoryRepository.getStableMemories(MemoryScope.COMPANION, companionId)
        }
    }

    fun getTempMemoriesForCompanion(companionId: Long): Flow<List<MemoryRecord>> {
        return workingMemoryFlows.getOrPut(companionId) {
            memoryRepository.getWorkingMemories(MemoryScope.COMPANION, companionId, limit = 20)
        }
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
        return diaryFlows.getOrPut(companionId) {
            diaryDao.getDiariesForCompanion(companionId, deviceId)
        }
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

    /**
     * AI 生成日记：根据最近聊天记录，调用 AI 以真人风格生成日记文本。
     * @param companionId 角色 ID
     * @param onResult 回调，返回生成的日记文本（失败时为 null）
     */
    fun generateDiary(companionId: Long, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            _isGeneratingDiary.value = true
            try {
                val diaryProvider = ServiceRegistry.getOrThrow(DiaryProvider::class.java)
                val companion = companionRepository.getCompanionById(companionId)
                if (companion == null) {
                    Log.w(TAG, "generateDiary: companion not found, id=$companionId")
                    onResult(null)
                    return@launch
                }

                // 获取最近 50 条聊天记录（倒序），然后反转为正序
                val recentMessages = withContext(Dispatchers.IO) {
                    messageDao.getRecentMessagesSync(companionId, "chat", 50)
                        .map { ChatMessageCrypto.decryptFromStorage(it.toChatMessage()) }
                        .reversed()
                }
                if (recentMessages.isEmpty()) {
                    Log.w(TAG, "generateDiary: no chat messages for companion=$companionId")
                    onResult(null)
                    return@launch
                }

                // 格式化对话文本
                val conversationText = formatConversation(recentMessages, companion)

                // 获取记忆上下文（可选，用于让日记更贴合角色记忆）
                val memoryContext = buildMemoryContext(companion)

                // 调用 AI 生成日记
                val diaryText = withContext(Dispatchers.IO) {
                    diaryProvider.generateDiary(companion, conversationText, memoryContext)
                }
                onResult(diaryText)
            } catch (e: Exception) {
                Log.e(TAG, "generateDiary failed", e)
                onResult(null)
            } finally {
                _isGeneratingDiary.value = false
            }
        }
    }

    /**
     * 格式化聊天记录为对话文本。
     */
    private fun formatConversation(messages: List<ChatMessage>, companion: CompanionEntity): String {
        val sb = StringBuilder()
        for (msg in messages) {
            val speaker = if (msg.isFromUser) "用户" else companion.name
            sb.append(speaker).append(": ").append(msg.content).append("\n")
        }
        return sb.toString().trim()
    }

    /**
     * 构建记忆上下文，让 AI 生成的日记更贴合角色设定。
     */
    private suspend fun buildMemoryContext(companion: CompanionEntity): String {
        val sb = StringBuilder()
        sb.append("角色名: ").append(companion.name).append("\n")
        if (companion.personality.isNotBlank()) {
            sb.append("性格: ").append(companion.personality).append("\n")
        }
        if (!companion.backstory.isNullOrBlank()) {
            sb.append("背景: ").append(companion.backstory).append("\n")
        }
        if (!companion.speakingStyle.isNullOrBlank()) {
            sb.append("说话风格: ").append(companion.speakingStyle).append("\n")
        }
        return sb.toString().trim()
    }

    companion object {
        private const val TAG = "MemoryViewModel"
    }
}
