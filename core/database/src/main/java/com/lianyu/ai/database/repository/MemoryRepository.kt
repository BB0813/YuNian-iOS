package com.lianyu.ai.database.repository

import com.lianyu.ai.database.dao.MemoryDao
import com.lianyu.ai.database.model.MemoryCategory
import com.lianyu.ai.database.model.MemoryEntry
import com.lianyu.ai.database.model.TempMemory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class MemoryRepository(private val memoryDao: MemoryDao, private val deviceId: String) {

    private fun decrypt(memory: MemoryEntry): MemoryEntry {
        val decryptedContext = try {
            MemoryCrypto.decrypt(memory.context)
        } catch (_: Exception) {
            memory.context
        }
        return memory.copy(context = decryptedContext)
    }

    fun getMemoriesForCompanion(companionId: Long): Flow<List<MemoryEntry>> {
        return memoryDao.getMemoriesForCompanion(companionId, deviceId)
            .map { list -> list.map { decrypt(it) } }
    }

    fun getMemoriesByCategory(companionId: Long, category: MemoryCategory): Flow<List<MemoryEntry>> {
        return memoryDao.getMemoriesByCategory(companionId, deviceId, category)
            .map { list -> list.map { decrypt(it) } }
    }

    suspend fun searchMemories(companionId: Long, query: String, limit: Int = 5): List<MemoryEntry> {
        return memoryDao.searchMemories(companionId, deviceId, query, limit)
            .map { decrypt(it) }
    }

    suspend fun getEnrichedContext(companionId: Long, lastUserMessage: String, contextLimit: Int): String {
        val memories = searchMemories(companionId, lastUserMessage, limit = 10)
        if (memories.isEmpty()) return ""
        return memories.joinToString("\n") { memory ->
            "[${memory.category.name}] ${memory.content}"
        }
    }

    suspend fun addMemory(
        companionId: Long,
        content: String,
        category: MemoryCategory = MemoryCategory.FACT,
        importance: Float = 0.5f,
        context: String = ""
    ) {
        val existing = memoryDao.searchMemories(companionId, deviceId, content, 1)
        val similar = existing.firstOrNull()?.let { entry ->
            val words1 = content.split(" ").toSet()
            val words2 = entry.content.split(" ").toSet()
            val overlap = words1.intersect(words2).size.toFloat() / maxOf(words1.size, words2.size)
            if (overlap > 0.7f) entry else null
        }

        if (similar != null) {
            val updated = similar.copy(
                context = encryptContext(similar.context),
                importance = minOf(1.0f, similar.importance + 0.1f),
                accessCount = similar.accessCount + 1,
                lastAccessed = System.currentTimeMillis()
            )
            memoryDao.insertMemory(updated)
        } else {
            val memory = MemoryEntry(
                companionId = companionId,
                content = content,
                category = category,
                importance = importance,
                context = encryptContext(context),
                deviceId = deviceId
            )
            memoryDao.insertMemory(memory)
        }
    }

    suspend fun deleteMemory(memory: MemoryEntry) {
        memoryDao.deleteMemory(memory)
    }

    suspend fun updateMemory(memory: MemoryEntry) {
        val updated = memory.copy(
            context = encryptContext(memory.context),
            lastAccessed = System.currentTimeMillis()
        )
        memoryDao.insertMemory(updated)
    }

    suspend fun deleteMemoriesForCompanion(companionId: Long) {
        memoryDao.deleteMemoriesForCompanion(companionId, deviceId)
    }

    fun getRecentTempMemories(companionId: Long, limit: Int = 20): Flow<List<TempMemory>> {
        return memoryDao.getRecentTempMemories(companionId, deviceId, limit)
    }

    suspend fun addTempMemory(companionId: Long, userInput: String, botResponse: String) {
        val tempMemory = TempMemory(
            companionId = companionId,
            userInput = userInput,
            botResponse = botResponse,
            deviceId = deviceId
        )
        memoryDao.insertTempMemory(tempMemory)
        memoryDao.cleanupOldTempMemories(companionId, deviceId, 20)
    }

    suspend fun deleteTempMemoriesForCompanion(companionId: Long) {
        memoryDao.deleteTempMemoriesForCompanion(companionId, deviceId)
    }

    suspend fun extractAndSaveMemories(companionId: Long, userInput: String, aiResponse: String? = null) {
        val trimmedInput = userInput.trim()
        if (trimmedInput.length < 2) return

        if (aiResponse != null) {
            addTempMemory(companionId, trimmedInput, aiResponse)
        }

        if (aiResponse == null) return

        if (containsAny(trimmedInput, listOf(
            "我叫", "我是", "我来自", "我工作", "职业是", "我的", "我姓",
            "我住在", "我住", "我学", "专业是", "我是做", "我在",
            "年龄", "岁", "生日", "星座", "血型", "身高", "体重",
            "电话", "微信", "qq", "邮箱", "地址", "公司", "学校"
        ))) {
            addMemory(companionId, trimmedInput, MemoryCategory.FACT, 0.8f)
        }

        if (containsAny(trimmedInput, listOf(
            "我喜欢", "我讨厌", "我爱吃", "我不爱吃", "我最爱", "不喜欢", "最爱",
            "爱", "恨", "怕", "想", "要", "觉得", "认为", "感觉",
            "好吃", "难吃", "好看", "难看", "好听", "好玩", "无聊",
            "感兴趣", "没兴趣", "热衷", "痴迷", "反感", "厌恶"
        ))) {
            addMemory(companionId, trimmedInput, MemoryCategory.PREFERENCE, 0.7f)
        }

        if (containsAny(trimmedInput, listOf(
            "开心", "难过", "生气", "感动", "惊喜", "伤心", "快乐", "郁闷", "焦虑", "兴奋",
            "累", "烦", "爽", "委屈", "害怕", "担心", "期待", "失望",
            "哭", "笑", "泪", "心", "情绪", "压力", "舒服", "难受"
        ))) {
            addMemory(companionId, trimmedInput, MemoryCategory.EMOTION, 0.7f)
        }

        if (containsAny(trimmedInput, listOf(
            "今天", "昨天", "明天", "上周", "下周", "周末", "放假", "考试",
            "出差", "旅行", "聚会", "约会", "面试", "入职", "离职", "搬家"
        ))) {
            addMemory(companionId, trimmedInput, MemoryCategory.EVENT, 0.6f)
        }
    }

    private fun encryptContext(context: String): String {
        if (context.isBlank()) return ""
        return try {
            MemoryCrypto.encrypt(context)
        } catch (_: Exception) {
            context
        }
    }

    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }
}
