package com.lianyu.ai.feature.notification

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lianyu.ai.common.wechat.WeChatBroadcast
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.repository.ChatMessageCrypto
import com.lianyu.ai.database.repository.filterDecrypted
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiMessageType
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.common.AppForegroundTracker
import com.lianyu.ai.common.BanManager
import com.lianyu.ai.common.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class CompanionMessageWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val aiServiceProvider: AiServiceProvider by lazy {
        ServiceRegistry.get(AiServiceProvider::class.java)
            ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (BanManager.isBanned(context)) {
                SecureLog.d("CompanionMessageWorker", "User is banned, skip proactive message")
                return@withContext Result.success()
            }

            val database = AppDatabase.getDatabase(context)
            val companionDao = database.companionDao()
            val chatMessageDao = database.chatMessageDao()

            val companions = companionDao.getAllCompanionsSync()
            if (companions.isEmpty()) return@withContext Result.success()

            val randomCompanion = companions.random()
            val intimacy = randomCompanion.intimacy.coerceIn(0, 100)

            val recentMessages = chatMessageDao.getRecentMessagesSync(randomCompanion.id, 10)
                .map { ChatMessageCrypto.decryptFromStorage(it) }
                .filterDecrypted()

            if (!aiServiceProvider.shouldProactivelyMessage(randomCompanion.toAiCompanionInfo(), recentMessages.toAiChatMessages())) {
                scheduleNext(context)
                return@withContext Result.success()
            }

            val messageContent = aiServiceProvider.generateProactiveMessage(randomCompanion.toAiCompanionInfo(), recentMessages.toAiChatMessages())

            if (messageContent == null) {
                SecureLog.w("CompanionMessageWorker", "Proactive message is null, skipping")
                scheduleNext(context)
                return@withContext Result.success()
            }

            // 安全检查：拦截 AI 主动消息中的违规内容
            val outputSafety = com.lianyu.ai.common.ContentFilter.checkOutputSafety(messageContent)
            if (!outputSafety.isSafe) {
                SecureLog.w("CompanionMessageWorker", "Proactive message blocked by safety filter: ${outputSafety.reason}")
                com.lianyu.ai.common.BanManager.recordViolation(context, outputSafety.level)
                scheduleNext(context)
                return@withContext Result.success()
            }

            val message = ChatMessage(
                companionId = randomCompanion.id,
                content = messageContent,
                isFromUser = false
            )
            val messageId = chatMessageDao.insertMessage(ChatMessageCrypto.encryptForStorage(message))
            broadcastProactiveWeChatMessage(randomCompanion.id, messageId)

            if (!AppForegroundTracker.isInForeground) {
                NotificationHelper.showCompanionMessageNotification(
                    context,
                    randomCompanion.name,
                    messageContent,
                    randomCompanion.id
                )
            }

            scheduleNext(context)

            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun broadcastProactiveWeChatMessage(companionId: Long, messageId: Long) {
        val intent = Intent(WeChatBroadcast.ACTION_SEND_PROACTIVE).apply {
            setPackage(context.packageName)
            putExtra(WeChatBroadcast.EXTRA_COMPANION_ID, companionId)
            putExtra(WeChatBroadcast.EXTRA_MESSAGE_ID, messageId)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                context.applicationContext.sendBroadcast(intent, null)
            } else {
                context.applicationContext.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            SecureLog.w("CompanionMessageWorker", "Failed to send broadcast: ${e.message}")
        }
    }

    // ── 领域类型转换辅助 ──

    private fun com.lianyu.ai.database.model.CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id, name = name, personality = personality,
        age = age, backstory = backstory, speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun com.lianyu.ai.database.model.ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser, content = content, timestamp = timestamp,
        type = when (type) {
            MessageType.IMAGE -> AiMessageType.IMAGE
            else -> AiMessageType.TEXT
        },
        companionId = companionId
    )

    private fun List<com.lianyu.ai.database.model.ChatMessage>.toAiChatMessages() = map { it.toAiChatMessage() }

    companion object {
        private const val WORK_NAME = "companion_message_work"
        private const val MIN_INTERVAL_MINUTES = 15
        private const val MAX_INTERVAL_MINUTES = 30

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val delayMinutes = Random.nextInt(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES + 1)

            val workRequest = OneTimeWorkRequestBuilder<CompanionMessageWorker>()
                .setConstraints(networkConstraints)
                .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        private fun scheduleNext(context: Context) {
            val delayMinutes = Random.nextInt(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES + 1)

            val workRequest = OneTimeWorkRequestBuilder<CompanionMessageWorker>()
                .setConstraints(networkConstraints)
                .setInitialDelay(delayMinutes.toLong(), TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
