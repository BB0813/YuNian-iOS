package com.yunian.ai.feature.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yunian.ai.common.AppForegroundTracker
import com.yunian.ai.database.AppDatabase
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.filterDecrypted
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.imagegen.ImageGenProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiReplyWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val aiServiceProvider: AiServiceProvider by lazy {
        ServiceRegistry.get(AiServiceProvider::class.java)
            ?: throw IllegalStateException("AiServiceProvider not registered")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val companionId = inputData.getLong(KEY_COMPANION_ID, -1L)
            val userMessageContent = inputData.getString(KEY_USER_MESSAGE) ?: ""

            if (companionId == -1L || userMessageContent.isBlank()) {
                return@withContext Result.failure()
            }

            if (com.yunian.ai.common.BanManager.isBanned(applicationContext)) {
                return@withContext Result.failure()
            }

            val inputCheck = com.yunian.ai.common.ContentFilter.checkInput(userMessageContent)
            if (inputCheck.isViolating) {
                com.yunian.ai.common.BanManager.recordViolation(applicationContext, inputCheck.level)
                return@withContext Result.failure()
            }

            val database = AppDatabase.getDatabase(applicationContext)
            val companionRepository = CompanionRepository(database.companionDao())
            val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
            val memoryProvider = ServiceRegistry.getOrThrow(MemoryProvider::class.java)

            try {
                val companionModel = companionRepository.getCompanionById(companionId)
                if (companionModel == null) {
                    return@withContext Result.failure()
                }

                val history = chatRepository.getRecentMessagesSync(companionId, limit = 50)
                    .filterDecrypted()

                val response = aiServiceProvider.sendMessage(
                    companionModel.toAiCompanionInfo(),
                    history.toAiChatMessages()
                )
                val trimmedResponse = response.content.trim()

                if (trimmedResponse.isNotEmpty()) {

                    val outputSafety = com.yunian.ai.common.ContentFilter.checkOutputSafety(trimmedResponse)
                    val safeResponse = if (!outputSafety.isSafe) {
                        android.util.Log.w("AiReplyWorker", "AI output blocked by safety filter: ${outputSafety.reason}")
                        com.yunian.ai.common.BanManager.recordViolation(applicationContext, outputSafety.level)
                        "抱歉，我无法回应这个话题。"
                    } else {
                        trimmedResponse
                    }

                    // 主动消息同样可能夹带生图标签/画面描述：落库前统一清洗
                    val cleanResponse = ImageGenProtocol.sanitizeForDisplay(safeResponse)
                        .ifBlank { safeResponse }
                    val aiMessage = ChatMessage(
                        companionId = companionId,
                        content = cleanResponse,
                        isFromUser = false
                    )
                    ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java).enqueueChat(aiMessage)
                    companionRepository.updateTimestamp(companionId)
                    companionRepository.increaseIntimacy(companionId, 2)

                    memoryProvider.extractAndSaveFromConversation(
                        userInput = userMessageContent,
                        aiResponse = safeResponse,
                        companionId = companionId,
                    )

                    if (!AppForegroundTracker.isInForeground) {
                        val notificationPreview = if (cleanResponse.length > 50) {
                            cleanResponse.take(50) + "..."
                        } else cleanResponse
                        NotificationHelper.showCompanionMessageNotification(
                            applicationContext,
                            companionModel.name,
                            notificationPreview,
                            companionId
                        )
                    }
                }
            } finally {
            }

            Result.success()
        } catch (e: IllegalStateException) {
            android.util.Log.e("AiReplyWorker", "Permanent failure, will not retry", e)
            Result.failure()
        } catch (e: SecurityException) {
            android.util.Log.e("AiReplyWorker", "Permission denied, will not retry", e)
            Result.failure()
        } catch (e: Exception) {
            android.util.Log.e("AiReplyWorker", "Transient failure, will retry", e)
            Result.retry()
        }
    }

    private fun com.yunian.ai.database.model.CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id, name = name, personality = personality,
        age = age, backstory = backstory, speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser, content = content, timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId
    )

    private fun List<ChatMessage>.toAiChatMessages() = map { it.toAiChatMessage() }

    companion object {
        private const val WORK_NAME_PREFIX = "ai_reply_"
        const val KEY_COMPANION_ID = "companion_id"
        const val KEY_USER_MESSAGE = "user_message"

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun enqueue(context: Context, companionId: Long, userMessage: String) {
            val inputData = Data.Builder()
                .putLong(KEY_COMPANION_ID, companionId)
                .putString(KEY_USER_MESSAGE, userMessage)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<AiReplyWorker>()
                .setInputData(inputData)
                .setConstraints(networkConstraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME_PREFIX$companionId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest
            )
        }
    }
}
