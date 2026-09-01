package com.lianyu.ai.feature.notification

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.repository.ChatMessageCrypto
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.database.repository.filterDecrypted
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiMessageType
import com.lianyu.ai.domain.ProactiveMessageSettings
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.wechat.WeChatProactiveSync
import com.lianyu.ai.common.AppForegroundTracker
import com.lianyu.ai.common.BanManager
import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.ChatDetailSettingsDataStoreProvider
import com.lianyu.ai.common.SecureLog
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 仅提取主动消息相关字段，避免跨 feature 依赖完整设置类。
 * 字段必须与 feature:chat 的 [CompanionChatDetailSettings] 中同名字段保持对齐，
 * 否则反序列化时 ignoreUnknownKeys=true 会静默丢弃。 */
@Serializable
data class ProactiveSettings(
    val proactiveEnabled: Boolean = true,
    /** 用户手动输入的间隔（分钟），优先使用；UI 可编辑范围 30~1440 */
    val proactiveIntervalMinutes: Int = 180,
    val proactiveMinIntervalMinutes: Int = 60,
    val proactiveMaxIntervalMinutes: Int = 720,
    val proactiveDailyLimit: Int = 6,
    /** 是否允许 AI 主动开启新话题 */
    val allowNewTopic: Boolean = true,
    /** 是否允许在主动消息后追加追问句 */
    val allowFollowUpMessage: Boolean = true,
    val doNotDisturbEnabled: Boolean = false,
    val dndStartMinutes: Int = 23 * 60,
    val dndEndMinutes: Int = 8 * 60,
    val allowLateNightMessage: Boolean = false,
    val allowPriorityMessageInDnd: Boolean = false,
    val blocked: Boolean = false,
    /** 未回复追问提醒开关 */
    val followUpReminderEnabled: Boolean = true,
    /** 未回复追问间隔（分钟） */
    val followUpReminderIntervalMinutes: Int = 5,
    /** 每条 AI 消息未回复时最多追问次数 */
    val followUpReminderMaxTimes: Int = 3
)

class CompanionMessageWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val aiServiceProvider: AiServiceProvider by lazy {
        ServiceRegistry.get(AiServiceProvider::class.java)
            ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    }

    // 直接读取 chat_detail_settings DataStore，避免跨 feature 依赖
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            if (BanManager.isBanned(context)) {
                SecureLog.d("CompanionMessageWorker", "User is banned, skip proactive message")
                return@withContext Result.success()
            }

            val database = AppDatabase.getDatabase(context)
            val companionDao = database.companionDao()
            val messageDao = database.messageDao()

            val companions = companionDao.getAllCompanionsSync()
            if (companions.isEmpty()) return@withContext Result.success()

            // 一次性解码全部伴侣设置（DataStore 内存缓存），后续过滤/选择共用，避免重复读取
            val settingsById = readAllCompanionSettings()

            // ── 筛选启用主动消息且未屏蔽的伴侣 ──
            val eligibleCompanions = companions.filter { companion ->
                settingsById[companion.id]?.let { settings ->
                    settings.proactiveEnabled && !settings.blocked
                } ?: false
            }

            if (eligibleCompanions.isEmpty()) {
                SecureLog.d("CompanionMessageWorker", "No eligible companions (all disabled/blocked), reschedule")
                scheduleNext(context, null)
                return@withContext Result.success()
            }

            val now = System.currentTimeMillis()
            val nowCal = java.util.Calendar.getInstance()
            val nowMinutes = nowCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + nowCal.get(java.util.Calendar.MINUTE)

            // ── 优先选择「追问到期」的伴侣 ──
            // 随机选择会让到期追问被其它伴侣的调度不断推后（不主动/不追问的感知来源）。
            // 先扫描一遍，优先处理 AI 最后发言、用户未回复且已过追问间隔的伴侣。
            val dueFollowUpCompanions = eligibleCompanions.filter { companion ->
                val s = settingsById[companion.id] ?: return@filter false
                if (!s.followUpReminderEnabled) return@filter false
                // 免打扰期间不追问
                if (isInDndRange(nowMinutes, s) && !s.allowLateNightMessage) return@filter false
                // 每日上限已满不追问
                if (s.proactiveDailyLimit > 0 && getTodayProactiveCount(context, companion.id) >= s.proactiveDailyLimit) {
                    return@filter false
                }
                val last = runCatching { messageDao.getLastMessageSync(companion.id, "chat")?.toChatMessage() }
                    .getOrNull() ?: return@filter false
                if (last.isFromUser) return@filter false
                val elapsedMs = now - last.timestamp
                // 超过最大时效（如 24 小时）不再追问，避免对久远的旧消息反复骚扰
                if (elapsedMs >= ChatConstants.FOLLOW_UP_REMINDER_MAX_AGE_HOURS * 60L * 60L * 1000L) return@filter false
                if (elapsedMs < s.followUpIntervalMs()) return@filter false
                val state = readFollowUpState(context, companion.id)
                val nudgeCount = if (state.lastNudgeMessageId == last.id) state.nudgeCount else 0
                nudgeCount < s.maxNudgeTimes()
            }

            // 有到期的追问优先处理；否则从符合条件的伴侣中随机选一个
            val companion = dueFollowUpCompanions.randomOrNull() ?: eligibleCompanions.random()
            val settings = settingsById[companion.id] ?: ProactiveSettings()
            val domainSettings = settings.toDomain()

            // ── 免打扰检查 ──
            // 追问到期或主动消息恰逢免打扰：调度到免打扰结束时刻复查，
            // 避免按普通间隔（可能数小时）错过 DND 结束后的追问窗口。
            if (isInDndRange(nowMinutes, settings) && !settings.allowLateNightMessage) {
                val minutesToDndEnd = minutesUntilDndEnd(nowMinutes, settings.dndStartMinutes, settings.dndEndMinutes)
                SecureLog.d("CompanionMessageWorker", "DND active for ${companion.name}, retry in ${minutesToDndEnd}min")
                scheduleWithDelay(context, minutesToDndEnd.coerceIn(1, 1440).toLong())
                return@withContext Result.success()
            }

            // ── 每日上限检查（精确计数，跨天自动重置） ──
            if (settings.proactiveDailyLimit > 0) {
                val todayCount = getTodayProactiveCount(context, companion.id)
                if (todayCount >= settings.proactiveDailyLimit) {
                    SecureLog.d("CompanionMessageWorker", "Daily limit reached ($todayCount/${settings.proactiveDailyLimit}) for ${companion.name}")
                    scheduleNext(context, settings)
                    return@withContext Result.success()
                }
            }

            val recentMessages = ChatMessageCrypto.decryptFromStorage(
                    messageDao.getRecentMessagesSync(companion.id, "chat", 10)
                        .map { it.toChatMessage() }
                ).filterDecrypted()

            // 按时间升序排序，DAO 返回的是 DESC（新→旧），必须先排序再取最后一条
            val sortedMessages = recentMessages.sortedBy { it.timestamp }
            val lastMessage = sortedMessages.lastOrNull()

            // ── 未回复追问分支：AI 已发言、用户长时间未回复，按设定间隔追问 ──
            if (lastMessage != null && !lastMessage.isFromUser && settings.followUpReminderEnabled) {
                val elapsedMs = now - lastMessage.timestamp
                // 超过最大时效（如 24 小时）不再追问，退回普通调度
                if (elapsedMs >= ChatConstants.FOLLOW_UP_REMINDER_MAX_AGE_HOURS * 60L * 60L * 1000L) {
                    scheduleNext(context, settings)
                    return@withContext Result.success()
                }
                val state = readFollowUpState(context, companion.id)
                // 当前最后一条就是上次追问发的消息 → 本轮仍未收到用户回复，计数延续；否则视为新一轮
                val nudgeCount = if (state.lastNudgeMessageId == lastMessage.id) state.nudgeCount else 0

                if (elapsedMs >= settings.followUpIntervalMs() && nudgeCount < settings.maxNudgeTimes()) {
                    val reminder = aiServiceProvider.generateFollowUpReminder(
                        companion.toAiCompanionInfo(),
                        recentMessages.toAiChatMessages(),
                        domainSettings
                    )
                    val nudgeMsgId = reminder?.let { sendMessage(companion, it) }
                    if (nudgeMsgId != null) {
                        // 记录本次追问的消息 id，下次运行时若用户仍未回复则继续计数
                        saveFollowUpState(context, companion.id, nudgeMsgId, nudgeCount + 1)
                        SecureLog.d(
                            "CompanionMessageWorker",
                            "Follow-up reminder sent for ${companion.name}, nudge=${nudgeCount + 1}/${settings.maxNudgeTimes()}"
                        )
                        scheduleFollowUpNext(context, settings)
                        return@withContext Result.success()
                    }
                    SecureLog.d("CompanionMessageWorker", "Follow-up reminder declined, reschedule")
                }

                // 未到追问时间点：按追问间隔尽快复查；追问次数已到上限：退回普通间隔
                if (elapsedMs < settings.followUpIntervalMs()) {
                    scheduleFollowUpNext(context, settings)
                } else {
                    scheduleNext(context, settings)
                }
                return@withContext Result.success()
            }

            if (!aiServiceProvider.shouldProactivelyMessage(companion.toAiCompanionInfo(), recentMessages.toAiChatMessages(), domainSettings)) {
                scheduleNext(context, settings)
                return@withContext Result.success()
            }

            val messageContent = aiServiceProvider.generateProactiveMessage(companion.toAiCompanionInfo(), recentMessages.toAiChatMessages(), domainSettings)
                ?: run {
                    SecureLog.w("CompanionMessageWorker", "Proactive message is null, skipping")
                    scheduleNext(context, settings)
                    return@withContext Result.success()
                }

            // 入库 + 广播 + 通知（统一发送路径，内部含安全检查兜底）
            sendMessage(companion, messageContent)

            scheduleNext(context, settings)

            Result.success()
        } catch (e: IllegalStateException) {
            SecureLog.e("CompanionMessageWorker", "Permanent failure, will not retry", e)
            Result.failure()
        } catch (e: SecurityException) {
            SecureLog.e("CompanionMessageWorker", "Permission denied, will not retry", e)
            Result.failure()
        } catch (e: Exception) {
            SecureLog.e("CompanionMessageWorker", "Transient failure, will retry", e)
            Result.retry()
        }
    }

    private fun broadcastProactiveWeChatMessage(companionId: Long, messageId: Long) {
        WeChatProactiveSync.enqueue(companionId, messageId)
        SecureLog.d("CompanionMessageWorker", "Enqueue WeChat proactive message, companionId=$companionId, messageId=$messageId")
    }

    /**
     * 统一发送一条 AI 主动消息：安全检查兜底 → 入库 → 微信同步 → 今日计数 → 通知。
     * @return 入库后的真实消息 id；内容为空/未通过安全检查返回 null。
     */
    private suspend fun sendMessage(companion: com.lianyu.ai.database.model.CompanionEntity, content: String): Long? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null
        // 安全检查：拦截 AI 主动消息中的违规内容（仅最终防线，不累计封禁）
        val safety = com.lianyu.ai.common.ContentFilter.checkOutputSafety(trimmed)
        if (!safety.isSafe) {
            SecureLog.w("CompanionMessageWorker", "Proactive message blocked by safety filter: ${safety.reason}")
            // AI 输出违规不应累加用户封禁（见 Bug #1 根因 A3）
            return null
        }
        val message = ChatMessage(
            companionId = companion.id,
            content = trimmed,
            isFromUser = false
        )
        val messageId = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
            .enqueueChat(message)
        broadcastProactiveWeChatMessage(companion.id, messageId)
        incrementTodayProactiveCount(context, companion.id, 1)
        if (!AppForegroundTracker.isInForeground) {
            NotificationHelper.showCompanionMessageNotification(
                context,
                companion.name,
                trimmed,
                companion.id
            )
        }
        return messageId
    }

    /** 是否处于免打扰时间段（不考虑 allowLateNightMessage/allowPriorityMessageInDnd 的放行语义） */
    private fun isInDndRange(nowMinutes: Int, settings: ProactiveSettings): Boolean {
        if (!settings.doNotDisturbEnabled || settings.allowPriorityMessageInDnd) return false
        return if (settings.dndStartMinutes > settings.dndEndMinutes) {
            // 跨午夜：如 23:00 ~ 08:00
            nowMinutes >= settings.dndStartMinutes || nowMinutes < settings.dndEndMinutes
        } else {
            nowMinutes in settings.dndStartMinutes until settings.dndEndMinutes
        }
    }

    /** 当前时刻到免打扰结束的分钟数（仅允许在 DND 范围内调用） */
    private fun minutesUntilDndEnd(nowMinutes: Int, dndStartMinutes: Int, dndEndMinutes: Int): Int {
        val dayMinutes = 24 * 60
        return if (dndStartMinutes > dndEndMinutes) {
            // 跨午夜：现在在 [start, 24h) → 结束于明天的 end；现在在 [0, end) → 结束于今天的 end
            if (nowMinutes >= dndStartMinutes) (dayMinutes - nowMinutes) + dndEndMinutes
            else dndEndMinutes - nowMinutes
        } else {
            dndEndMinutes - nowMinutes
        }
    }

    /**
     * 从 DataStore 一次性读取全部伴侣的主动消息相关设置。
     * 直接读取与 ChatDetailSettingsStore 共享的同一 DataStore，避免跨 feature 依赖。
     */
    private suspend fun readAllCompanionSettings(): Map<Long, ProactiveSettings> {
        return runCatching {
            val dataStore = ChatDetailSettingsDataStoreProvider.get(context)
            val prefs = dataStore.data.first()
            val raw = prefs[stringPreferencesKey("companion_chat_detail_settings_map")] ?: return@runCatching emptyMap()
            json.decodeFromString<Map<Long, ProactiveSettings>>(raw)
        }.getOrNull() ?: emptyMap()
    }

    // ── 领域类型转换辅助 ──

    /** 将 Worker 侧 [ProactiveSettings] 映射为 domain 层 [ProactiveMessageSettings] */
    private fun ProactiveSettings.toDomain() = ProactiveMessageSettings(
        proactiveEnabled = proactiveEnabled,
        proactiveIntervalMinutes = proactiveIntervalMinutes,
        proactiveMinIntervalMinutes = proactiveMinIntervalMinutes,
        proactiveMaxIntervalMinutes = proactiveMaxIntervalMinutes,
        proactiveDailyLimit = proactiveDailyLimit,
        allowNewTopic = allowNewTopic,
        allowFollowUpMessage = allowFollowUpMessage,
        doNotDisturbEnabled = doNotDisturbEnabled,
        dndStartMinutes = dndStartMinutes,
        dndEndMinutes = dndEndMinutes,
        allowLateNightMessage = allowLateNightMessage,
        allowPriorityMessageInDnd = allowPriorityMessageInDnd,
        blocked = blocked,
        followUpReminderEnabled = followUpReminderEnabled,
        followUpReminderIntervalMinutes = followUpReminderIntervalMinutes,
        followUpReminderMaxTimes = followUpReminderMaxTimes
    )

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

        private const val DAILY_COUNT_PREFS = "proactive_daily_count"

        /** 未回复追问状态存储：lastNudgeMessageId + nudgeCount（按伴侣分 key） */
        private const val FOLLOW_UP_STATE_PREFS = "proactive_followup_state"

        /** 获取指定伴侣今日已发主动消息数（精确计数，不取近似） */
        private fun getTodayProactiveCount(context: Context, companionId: Long): Int {
            val prefs = context.getSharedPreferences(DAILY_COUNT_PREFS, Context.MODE_PRIVATE)
            val today = todayKey()
            val storedDate = prefs.getString("date_$companionId", null)
            return if (storedDate == today) prefs.getInt("count_$companionId", 0) else 0
        }

        /** 增加指定伴侣今日主动消息计数 */
        private fun incrementTodayProactiveCount(context: Context, companionId: Long, delta: Int) {
            val prefs = context.getSharedPreferences(DAILY_COUNT_PREFS, Context.MODE_PRIVATE)
            val today = todayKey()
            val count = getTodayProactiveCount(context, companionId) + delta
            prefs.edit()
                .putString("date_$companionId", today)
                .putInt("count_$companionId", count)
                .apply()
        }

        private fun todayKey(): String {
            val cal = java.util.Calendar.getInstance()
            return "${cal.get(java.util.Calendar.YEAR)}-${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
        }

        private data class FollowUpState(
            val lastNudgeMessageId: Long = -1L,
            val nudgeCount: Int = 0
        )

        private fun readFollowUpState(context: Context, companionId: Long): FollowUpState {
            val prefs = context.getSharedPreferences(FOLLOW_UP_STATE_PREFS, Context.MODE_PRIVATE)
            return FollowUpState(
                lastNudgeMessageId = prefs.getLong("last_nudge_msg_$companionId", -1L),
                nudgeCount = prefs.getInt("nudge_count_$companionId", 0)
            )
        }

        private fun saveFollowUpState(context: Context, companionId: Long, lastNudgeMessageId: Long, nudgeCount: Int) {
            context.getSharedPreferences(FOLLOW_UP_STATE_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong("last_nudge_msg_$companionId", lastNudgeMessageId)
                .putInt("nudge_count_$companionId", nudgeCount)
                .apply()
        }

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /**
         * 外部入口：首次调度，使用默认间隔。
         * 使用 enqueueUniqueWork + REPLACE 确保只保留最新一次调度。
         *
         * 防重置竞态（Bug #1 根因）：KeepAliveAlarmReceiver（每 8 分钟）、
         * IqooKeepAliveJobService（每 15 分钟）、MainActivity（启动）、
         * KeepAliveService（心跳/超时）都会调用本方法；若无保护，
         * REPLACE 会反复取消待执行的主动消息并重置为随机 15~60 分钟延迟，
         * 导致主动消息几乎永不触发。因此仅当没有 ENQUEUED/RUNNING 工作时才调度。
         */
        fun schedule(context: Context) {
            // 查询带 500ms 超时：查询失败/超时时保守跳过（alarm 每 8 分钟会再试），
            // 避免在 UI 线程/广播窗口内长时间阻塞，也避免破坏一个正在工作的调度。
            val hasActiveWork = runCatching {
                WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME)
                    .get(500, TimeUnit.MILLISECONDS)
                    .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            }.getOrDefault(true)
            if (hasActiveWork) return

            val delayMinutes = Random.nextInt(
                ChatConstants.PROACTIVE_FALLBACK_MIN_MINUTES.toInt(),
                ChatConstants.PROACTIVE_FALLBACK_MAX_MINUTES.toInt()
            )
            scheduleWithDelay(context, delayMinutes.toLong())
        }

        /**
         * 后续调度：优先使用用户手动输入的 [ProactiveSettings.proactiveIntervalMinutes]，
         * 未设置时回退到 min/max 区间随机。
         */
        private fun scheduleNext(context: Context, settings: ProactiveSettings?) {
            val delayMinutes = if (settings != null && settings.proactiveIntervalMinutes > 0) {
                // 用户手动输入的间隔优先，允许自定义到分钟级（≥1 分钟），最大 1440 分钟（24h）
                settings.proactiveIntervalMinutes.coerceIn(
                    ChatConstants.PROACTIVE_USER_MIN_INTERVAL_MINUTES,
                    ChatConstants.PROACTIVE_USER_MAX_INTERVAL_MINUTES
                ).toLong()
            } else {
                val minInterval = settings?.proactiveMinIntervalMinutes
                    ?.coerceAtLeast(ChatConstants.PROACTIVE_USER_MIN_INTERVAL_MINUTES)
                    ?: ChatConstants.PROACTIVE_FALLBACK_MIN_MINUTES.toInt()
                val maxInterval = settings?.proactiveMaxIntervalMinutes
                    ?.coerceAtLeast(minInterval + 1)
                    ?: ChatConstants.PROACTIVE_FALLBACK_MAX_MINUTES.toInt()
                Random.nextInt(minInterval, maxInterval + 1).toLong()
            }
            scheduleWithDelay(context, delayMinutes)
        }

        /**
         * 未回复追问复查调度：按追问间隔尽快复查，保证追问到点即发。
         */
        private fun scheduleFollowUpNext(context: Context, settings: ProactiveSettings) {
            scheduleWithDelay(context, settings.followUpIntervalMs() / 60_000L)
        }

        /** 通用调度：以指定分钟延迟重排主动消息 Worker（REPLACE，单链） */
        private fun scheduleWithDelay(context: Context, delayMinutes: Long) {
            val workRequest = OneTimeWorkRequestBuilder<CompanionMessageWorker>()
                .setConstraints(networkConstraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

// ── 文件级扩展函数（companion object 与 doWork 共用） ──

/** 未回复追问间隔（毫秒），限制在用户可调范围内 */
private fun ProactiveSettings.followUpIntervalMs(): Long =
    followUpReminderIntervalMinutes.coerceIn(
        ChatConstants.FOLLOW_UP_REMINDER_MIN_INTERVAL_MINUTES,
        ChatConstants.FOLLOW_UP_REMINDER_MAX_INTERVAL_MINUTES
    ) * 60_000L

/** 未回复追问次数上限，限制在合理范围内 */
private fun ProactiveSettings.maxNudgeTimes(): Int =
    followUpReminderMaxTimes.coerceIn(1, ChatConstants.FOLLOW_UP_REMINDER_MAX_TIMES_LIMIT)
