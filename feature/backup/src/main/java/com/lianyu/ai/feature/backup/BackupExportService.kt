package com.lianyu.ai.feature.backup

import android.content.Context
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.GroupMessage
import com.lianyu.ai.database.model.Message
import com.lianyu.ai.database.repository.ChatMessageCrypto
import com.lianyu.ai.database.repository.MemoryCrypto
import com.lianyu.ai.feature.backup.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 数据导出服务 — 读取用户数据，解密后组装为 [BackupData]。
 *
 * 支持按选中的联系人（companionId 集合）导出，未指定时导出全部。
 */
class BackupExportService(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val deviceId = DeviceIdProvider.getDeviceId(context)

    suspend fun export(companionIds: Set<Long>? = null): BackupData = withContext(Dispatchers.IO) {
        val allCompanions = db.companionDao().getAllCompanionsSync()
        val selectedCompanions = if (companionIds.isNullOrEmpty()) allCompanions
        else allCompanions.filter { it.id in companionIds }

        val companions = selectedCompanions.map { it.toSnapshot() }
        val chatMessages = mutableListOf<ChatMessageSnapshot>()
        val chatGroups = db.chatGroupDao().getAllGroupsSync().map { it.toSnapshot() }
        val groupMessages = mutableListOf<GroupMessageSnapshot>()
        val memoryEntries = db.memoryDao().getAllMemoriesSync(deviceId).map { it.toDecryptedSnapshot() }
        val tempMemories = db.memoryDao().getAllTempMemoriesSync(deviceId).map { it.toSnapshot() }
        val tokenUsages = db.tokenUsageDao().getAllUsageSync(deviceId).map { it.toSnapshot() }
        val unifiedMemories = db.unifiedMemoryDao().getAllActiveSync(deviceId)
            .filter { it.isDeleted == 0 }
            .map { it.toSnapshot() }
        val diaries = db.diaryDao().getAllDiariesSync(deviceId).map { it.toSnapshot() }

        // 读取每条选中 companion 的聊天消息（已解密）
        for (c in companions) {
            val raw = db.messageDao().getAllMessagesSync(c.id, "chat")
            chatMessages.addAll(
                ChatMessageCrypto.decryptFromStorage(raw.map { it.toChatMessage() })
                    .map { it.toSnapshot() }
            )
        }

        // 读取每个 group 的群聊消息（已解密）
        for (g in chatGroups) {
            val raw = db.messageDao().getAllMessagesSync(g.id, "group")
            groupMessages.addAll(
                ChatMessageCrypto.decryptFromStorageGroup(raw.map { it.toGroupMessage() })
                    .map { it.toSnapshot() }
            )
        }

        BackupData(
            exportedAt = System.currentTimeMillis(),
            appVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).versionName ?: "unknown",
            companions = companions,
            chatMessages = chatMessages,
            chatGroups = chatGroups,
            groupMessages = groupMessages,
            memoryEntries = memoryEntries,
            tempMemories = tempMemories,
            tokenUsages = tokenUsages,
            unifiedMemories = unifiedMemories,
            diaries = diaries
        )
    }

    /**
     * 统计每个联系人的导出数据概况（供导出选择页网格展示）。
     *
     * @return 按最后数据时间倒序排列的统计列表
     */
    suspend fun getCompanionStats(): List<CompanionExportStat> = withContext(Dispatchers.IO) {
        db.companionDao().getAllCompanionsSync().map { companion ->
            val raw = db.messageDao().getAllMessagesSync(companion.id, "chat")
            val messages = ChatMessageCrypto.decryptFromStorage(raw.map { it.toChatMessage() })
            val totalSizeBytes = messages.sumOf { m ->
                estimateMessageSizeBytes(m)
            }
            CompanionExportStat(
                companionId = companion.id,
                name = companion.name,
                avatarUrl = companion.avatarUrl,
                messageCount = messages.size,
                totalSizeBytes = totalSizeBytes,
                lastTimestamp = messages.maxOfOrNull { it.timestamp } ?: 0L
            )
        }.sortedByDescending { it.lastTimestamp }
    }

    /** 估算单条消息占用大小：正文字节数 + linkString 引用的媒体文件大小（若存在） */
    private fun estimateMessageSizeBytes(message: ChatMessage): Long {
        var size = message.content.toByteArray(Charsets.UTF_8).size.toLong()
        val mediaPath = message.linkString.ifBlank { message.content }
        if (mediaPath.isNotBlank()) {
            runCatching {
                val f = java.io.File(mediaPath)
                if (f.exists() && f.isFile) size += f.length()
            }
        }
        return size
    }
}

/** 导出选择页展示用的联系人统计 */
data class CompanionExportStat(
    val companionId: Long,
    val name: String,
    val avatarUrl: String?,
    val messageCount: Int,
    val totalSizeBytes: Long,
    val lastTimestamp: Long
)

// --- Entity → Snapshot 映射扩展 ---

private fun com.lianyu.ai.database.model.CompanionEntity.toSnapshot() = CompanionSnapshot(
    id = id, name = name, avatarUrl = avatarUrl, age = age,
    personality = personality, backstory = backstory, speakingStyle = speakingStyle,
    tags = tags, rawPrompt = rawPrompt, systemPrompt = systemPrompt,
    intimacy = intimacy, createdAt = createdAt, updatedAt = updatedAt
)

private fun ChatMessage.toSnapshot() = ChatMessageSnapshot(
    id = id, companionId = companionId, content = content, isFromUser = isFromUser,
    timestamp = timestamp, type = type.name, searchContent = searchContent,
    fileFormat = fileFormat.name, linkString = linkString,
    turnId = turnId, eventIndex = eventIndex, durationMs = durationMs,
    anchorMessageId = anchorMessageId
)

private fun com.lianyu.ai.database.model.ChatGroup.toSnapshot() = ChatGroupSnapshot(
    id = id, name = name, avatarUrl = avatarUrl, companionIds = companionIds,
    createdAt = createdAt, updatedAt = updatedAt
)

private fun GroupMessage.toSnapshot() = GroupMessageSnapshot(
    id = id, groupId = groupId, companionId = companionId, content = content,
    timestamp = timestamp, searchContent = searchContent,
    fileFormat = fileFormat.name, linkString = linkString
)

private fun com.lianyu.ai.database.model.MemoryEntry.toDecryptedSnapshot(): MemoryEntrySnapshot {
    val decryptedContext = try {
        MemoryCrypto.decrypt(context)
    } catch (_: Exception) {
        context
    }
    return MemoryEntrySnapshot(
        id = id, companionId = companionId, content = content, category = category.name,
        importance = importance, context = decryptedContext, accessCount = accessCount,
        timestamp = timestamp, lastAccessed = lastAccessed, deviceId = deviceId
    )
}

private fun com.lianyu.ai.database.model.TempMemory.toSnapshot() = TempMemorySnapshot(
    id = id, companionId = companionId, userInput = userInput, botResponse = botResponse,
    timestamp = timestamp, deviceId = deviceId
)

private fun com.lianyu.ai.database.model.TokenUsage.toSnapshot() = TokenUsageSnapshot(
    id = id, companionId = companionId, date = date, inputTokens = inputTokens,
    outputTokens = outputTokens, totalTokens = totalTokens, requestCount = requestCount,
    timestamp = timestamp, deviceId = deviceId
)

private fun com.lianyu.ai.database.model.MemoryRecord.toSnapshot() = UnifiedMemorySnapshot(
    id = id, memoryType = memoryType.name, scope = scope.name, source = source.name,
    content = content, summary = summary, confidence = confidence, importance = importance,
    sourceId = sourceId, createdAt = createdAt, updatedAt = updatedAt, observedAt = observedAt,
    expiresAt = expiresAt, accessCount = accessCount, tags = tags, deviceId = deviceId
)

private fun com.lianyu.ai.database.model.DiaryEntry.toSnapshot() = DiarySnapshot(
    id = id, companionId = companionId, title = title, content = content, mood = mood,
    date = date, weather = weather, tags = tags, deviceId = deviceId
)
