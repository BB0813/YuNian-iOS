package com.yunian.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.ApplicationScopeProvider
import com.yunian.ai.common.BanManager
import com.yunian.ai.common.ChatConstants
import com.yunian.ai.common.CompanionRole
import com.yunian.ai.common.ContentFilter
import com.yunian.ai.common.EnvAnchorCooldown
import com.yunian.ai.common.EnvAnchorStore
import com.yunian.ai.common.RolePromptProvider
import com.yunian.ai.common.SecureLog
import com.yunian.ai.common.StickerManager
import com.yunian.ai.common.TimeoutBudgets
import com.yunian.ai.domain.wechat.WeChatProactiveSync
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.database.model.ChatMessage
import com.yunian.ai.database.model.CompanionEntity
import com.yunian.ai.database.model.MessageType
import com.yunian.ai.database.repository.ApiConfigRepository
import com.yunian.ai.database.repository.ChatRepository
import com.yunian.ai.database.repository.CompanionRepository
import com.yunian.ai.database.repository.MessageWriteCoordinator
import com.yunian.ai.database.repository.UserRepository
import com.yunian.ai.domain.AiChatMessage
import com.yunian.ai.domain.AiCompanionInfo
import com.yunian.ai.domain.AiMessageType
import com.yunian.ai.domain.AiServiceProvider
import com.yunian.ai.domain.LocalModelProvider
import com.yunian.ai.domain.imagegen.ImageGenService
import com.yunian.ai.domain.MemoryProvider
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.ToolRegistry
import com.yunian.ai.feature.chat.R
import com.yunian.ai.feature.chat.data.ChatContextResolver
import com.yunian.ai.feature.chat.data.ChatDetailSettingsStore
import com.yunian.ai.feature.chat.timeline.EventCommitRules
import com.yunian.ai.feature.chat.timeline.PendingTurnStreamApplier
import com.yunian.ai.feature.chat.timeline.StreamingReasoningMessagePipeline
import com.yunian.ai.feature.chat.voice.ChatTtsController
import com.yunian.ai.feature.chat.voice.ChatTtsState
import com.yunian.ai.network.ChatTypingState
import com.yunian.ai.network.bubble.BubbleJsonProtocol
import com.yunian.ai.network.bubble.BubbleLoopRunner
import com.yunian.ai.network.stream.NonStreamingAssistantStreamAdapter
import com.yunian.ai.network.tts.ChatTtsConfig
import com.yunian.ai.network.tts.ChatTtsMode
import com.yunian.ai.network.tts.TtsService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ChatGenerationManager private constructor(
    private val application: Application,
    private val companionId: Long
) {
    companion object {
        private val instances = ConcurrentHashMap<Long, ChatGenerationManager>()
        private val questionRegex = Regex("[?？]|吗|呢|什么|怎么|为什么|多少|哪|谁|几|是不是|有没有|能不能|会不会|要不要|好不好")

        private const val IDLE_DISPOSE_MS = 60_000L

        /** 模型整条回复只有生图标签时的占位文案（不含方括号，避免被当成表情包标签） */
        private const val IMAGE_GEN_ONLY_REPLY_TEXT = "（正在为你配图…）"

        fun get(application: Application, companionId: Long): ChatGenerationManager =
            instances.getOrPut(companionId) { ChatGenerationManager(application, companionId) }

        fun acquire(application: Application, companionId: Long): ChatGenerationManager {
            while (true) {
                val manager = get(application, companionId)
                if (manager.tryAcquire()) return manager
                instances.remove(companionId, manager)
            }
        }

        fun release(companionId: Long) {
            instances[companionId]?.onReleased()
        }
    }

    private val refCount = AtomicInteger(0)
    @Volatile private var disposed = false
    private var disposeJob: Job? = null

    private val scope = ApplicationScopeProvider.scope
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        ChatDebugLog.log("[ChatGeneration] uncaught: ${throwable.javaClass.simpleName}: ${throwable.message}")
        SecureLog.e("ChatGenerationManager", "Uncaught generation exception", throwable)
    }
    private val apiConfigRepository = ServiceRegistry.getOrThrow(ApiConfigRepository::class.java)
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val messageWriter = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
    private val companionRepository = ServiceRegistry.getOrThrow(CompanionRepository::class.java)
    private val contextResolver = ChatContextResolver(chatRepository)
    private val chatDetailSettingsStore = ChatDetailSettingsStore(application)
    private val appSettingsStore = AppSettingsStore(application)
    private val envAnchorStore by lazy { EnvAnchorStore(application) }
    private val stickerManager by lazy { StickerManager.getInstance(application) }
    private val memoryProvider: MemoryProvider by lazy {
        ServiceRegistry.getOrThrow(MemoryProvider::class.java).also { it.initialize() }
    }
    private val userRepository = ServiceRegistry.get(UserRepository::class.java)
    private val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    private val _chatTtsConfig = MutableStateFlow(ChatTtsConfig.fromSharedPreferences(application))
    val chatTtsConfig: StateFlow<ChatTtsConfig> = _chatTtsConfig.asStateFlow()
    @Volatile private var callActive = false
    private val _ttsState = MutableStateFlow(ChatTtsState.IDLE)
    private val ttsControllerDelegate = lazy {
        ChatTtsController(
            context = application.applicationContext,
            ttsService = TtsService.getInstance(application),
            scope = scope,
            configProvider = { _chatTtsConfig.value },
            callActiveProvider = { callActive }
        ).also { controller ->
            scope.launch { controller.state.collect { _ttsState.value = it } }
        }
    }
    private val ttsController by ttsControllerDelegate
    val ttsState: StateFlow<ChatTtsState> = _ttsState.asStateFlow()
    private val typingState = ChatTypingState()
    private val activeRequests = AtomicInteger(0)
    private val messageQueue = Channel<String>(capacity = 100)
    private val turnState = ChatTurnState()
    @Volatile private var latestCompanionInfo: AiCompanionInfo? = null
    private var messageConsumerJob: Job? = null
    private var replacementJob: Job? = null

    /**
     * 单轮生成的"是否已产出回复（已落库）"信号。
     *
     * 每轮生成都新建一个（不复用上一轮的值），默认 `false`。
     * 由 [AiResponseFinalizer.deliverResponse] 在某段回复确实写入数据库后置位（见 `onCommitted`）。
     * 消费循环在"新消息打断在飞生成"时据此决定旧 batch 的去留：
     * - 已落库 → 旧批消息已被回答过，丢弃以免重复回答；
     * - 未落库 → 旧 job 被干净取消、没有产生任何回复，保留原合并重发行为以免丢消息。
     */
    private class TurnCommitSignal {
        private val committed = AtomicBoolean(false)
        val isCommitted: Boolean get() = committed.get()
        fun markCommitted() { committed.set(true) }
    }

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    private val _confirmationRequest = MutableStateFlow<ToolConfirmationRequest?>(null)

    val confirmationRequest: StateFlow<ToolConfirmationRequest?> = _confirmationRequest.asStateFlow()
    private val confirmationChannel = Channel<Boolean>(capacity = 1)

    private suspend fun requestToolConfirmation(toolName: String, argumentsJson: String): Boolean {
        val tool = ToolRegistry.get(toolName)
        val summary = tool?.summarizeArguments(argumentsJson) ?: argumentsJson.take(120)
        val request = ToolConfirmationRequest(
            id = System.currentTimeMillis(),
            toolName = toolName,
            summary = summary,
            argumentsJson = argumentsJson
        )
        typingState.stopTyping()
        _confirmationRequest.value = request

        while (confirmationChannel.tryReceive().isSuccess) { }
        return try {
            confirmationChannel.receive()
        } finally {
            if (_confirmationRequest.value?.id == request.id) {
                _confirmationRequest.value = null
            }
            if (activeRequests.get() > 0) typingState.startTyping()
        }
    }

    fun respondToConfirmation(id: Long, confirmed: Boolean) {
        val current = _confirmationRequest.value
        if (current?.id != id) return
        _confirmationRequest.value = null
        confirmationChannel.trySend(confirmed)
    }

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRegenerating = MutableStateFlow(false)
    val isRegenerating: StateFlow<Boolean> = _isRegenerating.asStateFlow()

    private val _queueDepth = MutableStateFlow(0)
    val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

    /** 当前轮次的工具调用活动（OpenMinis 风格过程气泡数据源），turn 结束清空 */
    private val _toolActivity = MutableStateFlow<List<ToolActivity>>(emptyList())
    val toolActivity: StateFlow<List<ToolActivity>> = _toolActivity.asStateFlow()

    val isTyping: StateFlow<Boolean> = typingState.isTyping
    val typingText: StateFlow<String> = typingState.typingText

    val pipeline = MessagePipelineRunner { level -> BanManager.recordViolation(application, level) }

    private val toolLoopRunner = AiToolLoopRunner(aiService, confirmationGate = ::requestToolConfirmation)
    private val streamApplier = PendingTurnStreamApplier()

    private val bubbleLoopRunner = BubbleLoopRunner()
    private val responseFinalizer by lazy { AiResponseFinalizer(
        companionId = companionId,
        chatRepository = chatRepository,
        messageWriter = messageWriter,
        memoryProvider = memoryProvider,
        stickerManager = stickerManager,
        chatDetailSettingsStore = chatDetailSettingsStore,
        appSettingsStore = appSettingsStore,
        contextResolver = contextResolver,
        aiService = aiService,
        applicationApiScope = scope,
        turnState = turnState,
        chatTtsController = ttsController,
        application = application,
        questionRegex = questionRegex,
    ).apply {
        companionInfoProvider = { latestCompanionInfo }
    } }

    fun sendText(content: String) {
        startMessageConsumer()
        // 新一轮对话开始：清掉上一轮的工具过程气泡（常驻展示一轮）
        _toolActivity.value = emptyList()
        val userMessage = ChatMessage(
            companionId = companionId,
            content = content,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )
        scope.launch(Dispatchers.IO + exceptionHandler) {
            val userMessageId = messageWriter.enqueueChat(userMessage)
            broadcastWeChatMessage(userMessageId)

        if (apiConfigRepository.getActiveEnabledConfig() == null && !hasCompanionBoundConfig()) {
            _events.tryEmit(ChatUiEvent.Error("请先配置API：我 → API设置 → 添加密钥"))
        }
            val result = messageQueue.trySend(content)
            if (result.isSuccess) {
                _queueDepth.update { it + 1 }

                if (!typingState.isTyping.value) {
                    typingState.startTyping()
                }
            } else {
                _events.tryEmit(ChatUiEvent.Error("消息队列已满，请稍后再试"))
                SecureLog.w("ChatGenerationManager", "Message queue full, dropped: ${content.take(20)}...")
            }
        }
    }

    fun sendImage(imagePath: String) {
        replaceActiveGeneration("Image message superseded active generation") {
            val userMessageId = messageWriter.enqueueChat(
                ChatMessage(
                    companionId = companionId,

                    content = "[图片]",
                    isFromUser = true,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.IMAGE,
                    linkString = imagePath
                )
            )
            broadcastWeChatMessage(userMessageId)
            val history = contextResolver.getHistoryForAi(companionId)
            val settings = chatDetailSettingsStore.getSettings(companionId)
            turnState.sendMessageJob = startAiResponse(
                history = history,
                stickerProbability = settings.stickerProbability,
                userContentForMemory = "[图片]",
                imagePath = imagePath,
                ntpTimeEnabled = settings.ntpTimeEnabled
            )
            turnState.sendMessageJob?.join()
        }
    }

    fun regenerate(targetMessage: ChatMessage) {
        replaceActiveGeneration("Regenerate superseded active generation") {
            _isRegenerating.value = true
            try {
                chatRepository.deleteMessage(targetMessage)
                // 一并清理被替换那一轮的工具调用卡片（其 turnId 与该条助手消息一致）
                targetMessage.turnId?.let { chatRepository.deleteToolActivitiesForTurn(companionId, it) }
                val history = contextResolver.getHistoryForAi(companionId)
                val settings = chatDetailSettingsStore.getSettings(companionId)
                turnState.sendMessageJob = startAiResponse(
                    history = history,
                    stickerProbability = settings.stickerProbability,
                    userContentForMemory = "",
                    ntpTimeEnabled = settings.ntpTimeEnabled
                )
                turnState.sendMessageJob?.join()
            } finally {
                _isRegenerating.value = false
            }
        }
    }

    fun setCallActive(active: Boolean) {
        callActive = active
        if (active) ttsController.stop()
    }

    fun setTtsMode(mode: ChatTtsMode) {

        val normalized = when (mode) {
            ChatTtsMode.VOICE_BAR -> ChatTtsMode.VOICE_BAR
            else -> ChatTtsMode.SILENT
        }
        updateTtsConfig(_chatTtsConfig.value.copy(mode = normalized))
    }

    fun updateTtsConfig(config: ChatTtsConfig) {
        val normalized = config.copy(
            mode = when (config.mode) {
                ChatTtsMode.VOICE_BAR -> ChatTtsMode.VOICE_BAR
                else -> ChatTtsMode.SILENT
            }
        )
        _chatTtsConfig.value = normalized
        ChatTtsConfig.saveToSharedPreferences(application, normalized)
        if (normalized.mode == ChatTtsMode.SILENT) ttsController.stop()
    }

    fun stopTts() = ttsController.stop()

    private fun tryAcquire(): Boolean {
        synchronized(this) {
            if (disposed) return false
            refCount.incrementAndGet()
            cancelPendingDisposeLocked()
            return true
        }
    }

    private fun onReleased() {
        val shouldSchedule: Boolean
        synchronized(this) {
            if (disposed) return
            val remaining = refCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
            shouldSchedule = remaining == 0
        }
        if (shouldSchedule) {
            scheduleIdleDispose()
        }
    }

    private fun cancelPendingDisposeLocked() {
        disposeJob?.cancel()
        disposeJob = null
    }

    private fun scheduleIdleDispose() {
        synchronized(this) {
            if (disposed || refCount.get() > 0) return
            cancelPendingDisposeLocked()
            disposeJob = scope.launch(Dispatchers.IO + exceptionHandler) {
                delay(IDLE_DISPOSE_MS)
                disposeIfIdle()
            }
        }
    }

    private fun isBusy(): Boolean {
        return activeRequests.get() > 0 ||
            _isLoading.value ||
            _isRegenerating.value ||
            _queueDepth.value > 0 ||
            turnState.sendMessageJob?.isActive == true ||
            replacementJob?.isActive == true
    }

    private fun disposeIfIdle() {
        val shouldReschedule: Boolean
        synchronized(this) {
            if (disposed) return
            if (refCount.get() > 0 || isBusy()) {

                shouldReschedule = refCount.get() == 0
            } else {
                disposed = true
                cancelPendingDisposeLocked()
                runCatching { stopTts() }
                runCatching { messageQueue.close() }
                messageConsumerJob?.cancel()
                messageConsumerJob = null
                replacementJob?.cancel()
                replacementJob = null
                turnState.cancelSendJob()
                turnState.sendMessageJob = null
                contextResolver.clearCache(companionId)
                instances.remove(companionId, this)
                SecureLog.i("ChatGenerationManager", "Disposed idle manager for companion=$companionId")
                shouldReschedule = false
            }
        }
        if (shouldReschedule) {
            scheduleIdleDispose()
        }
    }

    suspend fun synthesizeForVoiceBar(text: String): String? =
        ttsController.synthesizeOnly(text)?.path

    private fun replaceActiveGeneration(reason: String, block: suspend () -> Unit) {
        replacementJob?.cancel(CancellationException(reason))
        turnState.sendMessageJob?.takeIf { it.isActive }?.cancel(CancellationException(reason))
        turnState.reset()
        replacementJob = scope.launch(Dispatchers.IO + exceptionHandler) {
            try {
                block()
            } catch (_: CancellationException) {
                Unit
            } catch (exception: Exception) {
                SecureLog.e("ChatGenerationManager", reason, exception)
                _events.tryEmit(ChatUiEvent.Error(exception.message?.removePrefix("[TOAST]") ?: "发送失败"))
            }
        }
    }

    private fun startMessageConsumer() {
        if (messageConsumerJob?.isActive == true) return
        messageConsumerJob = scope.launch(Dispatchers.IO + exceptionHandler) {

            val pending = mutableListOf<String>()

            var waitForMergeWindow = true
            while (true) {
                if (pending.isEmpty()) {
                    val first = messageQueue.receiveCatching()
                    if (first.isClosed) break
                    if (first.exceptionOrNull() != null) continue
                    pending.add(first.getOrThrow())
                    _queueDepth.update { maxOf(0, it - 1) }
                    waitForMergeWindow = true
                }

                if (waitForMergeWindow) {

                    drainQueueWithTimeout(pending, ChatConstants.MESSAGE_BATCH_WINDOW_MS)
                } else {
                    delay(ChatConstants.MESSAGE_BATCH_SPLIT_DELAY_MS)
                    drainTryReceive(pending)
                }

                val batchSize = pending.size.coerceAtMost(ChatConstants.MESSAGE_BATCH_MAX_SIZE)
                if (batchSize <= 0) continue
                val batch = pending.take(batchSize)

                cancelActiveGenerationForMerge()

                try {
                    // 每轮生成独立的"已落库"信号，用于打断时判定旧批去留（默认未落库）
                    val commitSignal = TurnCommitSignal()
                    val job = startSendMessage(batch, commitSignal)
                    if (job == null) {

                        typingState.stopTyping()
                        repeat(batchSize) { if (pending.isNotEmpty()) pending.removeAt(0) }
                        waitForMergeWindow = pending.isEmpty()
                        continue
                    }

                    val interrupted = awaitGenerationOrNewMessage(job, pending)
                    if (interrupted) {
                        job.cancel(
                            CancellationException("New message batch started, cancelling stale batch")
                        )
                        runCatching { job.join() }
                        if (commitSignal.isCommitted) {
                            // 旧 job 已把回复落库：这批旧消息已经被回答过，
                            // 必须从 pending 移除，否则下一轮会把它们连同新消息再次作答
                            // → 用户看到"发完一句、很快又发下一句，前一句被回两遍"。
                            repeat(batchSize) { if (pending.isNotEmpty()) pending.removeAt(0) }
                            ChatDebugLog.log(
                                "[ChatGeneration] Interrupted in-flight AI already committed; " +
                                    "dropped $batchSize answered message(s), keeping ${pending.size} new message(s)"
                            )
                        } else {
                            // 旧 job 被干净取消、未产出任何回复：保持原合并重发行为，不丢消息。
                            ChatDebugLog.log(
                                "[ChatGeneration] Interrupted in-flight AI before commit; " +
                                    "merging ${pending.size} pending user message(s)"
                            )
                        }

                        waitForMergeWindow = true
                        continue
                    }

                    repeat(batchSize) { if (pending.isNotEmpty()) pending.removeAt(0) }
                    waitForMergeWindow = false
                } catch (cancelled: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    ChatDebugLog.log(
                        "[ChatGeneration] Child generation cancelled; queue consumer remains active: ${cancelled.message}"
                    )
                    waitForMergeWindow = true
                } catch (e: Exception) {
                    SecureLog.e("ChatGenerationManager", "doSendMessage failed", e)
                    typingState.stopTyping()
                    _events.tryEmit(
                        ChatUiEvent.Error("消息发送失败: ${e.message?.take(50) ?: "未知错误"}")
                    )

                    repeat(batchSize) { if (pending.isNotEmpty()) pending.removeAt(0) }
                    waitForMergeWindow = pending.isEmpty()
                }
            }
        }
    }

    private fun drainTryReceive(pending: MutableList<String>) {
        while (pending.size < ChatConstants.MESSAGE_BATCH_MAX_SIZE) {
            val extra = messageQueue.tryReceive()
            if (extra.isClosed || extra.isFailure) break
            pending.add(extra.getOrThrow())
            _queueDepth.update { maxOf(0, it - 1) }
        }
    }

    private suspend fun drainQueueWithTimeout(pending: MutableList<String>, windowMs: Long) {
        val deadline = System.currentTimeMillis() + windowMs
        drainTryReceive(pending)
        while (pending.size < ChatConstants.MESSAGE_BATCH_MAX_SIZE) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            val waitMs = minOf(remaining, ChatConstants.MESSAGE_BATCH_POLL_INTERVAL_MS)
            val result = withTimeoutOrNull(waitMs) { messageQueue.receiveCatching() } ?: continue
            if (result.isClosed) return
            if (result.isFailure) continue
            pending.add(result.getOrThrow())
            _queueDepth.update { maxOf(0, it - 1) }
            drainTryReceive(pending)
        }
    }

    private suspend fun awaitGenerationOrNewMessage(
        job: Job,
        pending: MutableList<String>,
    ): Boolean {
        while (job.isActive) {
            val result = withTimeoutOrNull(ChatConstants.MESSAGE_BATCH_POLL_INTERVAL_MS) {
                messageQueue.receiveCatching()
            }
            if (result == null) continue
            if (result.isClosed) {

                job.join()
                return false
            }
            if (result.isFailure) continue
            pending.add(result.getOrThrow())
            _queueDepth.update { maxOf(0, it - 1) }
            drainTryReceive(pending)
            return true
        }

        runCatching { job.join() }
        return false
    }

    private fun cancelActiveGenerationForMerge() {
        val active = turnState.sendMessageJob?.takeIf { it.isActive } ?: return
        active.cancel(CancellationException("New message batch started, cancelling stale batch"))

    }

    /**
     * 该角色是否绑定了可用的专属 API 配置（apiConfigId 有效且配置存在/有 Key）。
     * 与 AiService.resolveConfig(companionId) 的回退判定保持一致：
     * 无全局配置但角色绑定了专属配置时，不应误报「请先配置API」。
     */
    private suspend fun hasCompanionBoundConfig(): Boolean {
        return try {
            val boundId = companionRepository.getCompanionById(companionId)?.apiConfigId
                ?: return false
            if (boundId <= 0L) return false
            val bound = apiConfigRepository.getConfigById(boundId) ?: return false
            bound.apiKey.isNotBlank() || bound.provider == ApiProvider.PARTNER
        } catch (e: Exception) {
            SecureLog.w("ChatGenerationManager", "hasCompanionBoundConfig failed: ${e.message}")
            false
        }
    }

    private suspend fun startSendMessage(batch: List<String>, commitSignal: TurnCommitSignal): Job? {
        val contentBatch = batch
        val content = if (contentBatch.size == 1) contentBatch[0] else contentBatch.joinToString("\n")

        if (BanManager.isBanned(application)) {
            val banInfo = BanManager.getBanInfo(application)
            val banMsg = if (banInfo.remainingDays > 0) {
                "账号已被封禁（剩余${banInfo.remainingDays}天${banInfo.remainingHours}小时），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
            } else if (banInfo.remainingHours > 0) {
                "账号已被封禁（剩余${banInfo.remainingHours}小时${banInfo.remainingMinutes}分钟），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
            } else {
                "账号已被封禁，原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。请完成安全答题以解除封禁。"
            }
            _events.tryEmit(ChatUiEvent.Error(banMsg))
            return null
        }

        if (apiConfigRepository.getActiveEnabledConfig() == null && !hasCompanionBoundConfig()) {
            for (msg in contentBatch) {
                val inputCheck = runCatching { ContentFilter.checkInput(msg) }.getOrNull()
                if (inputCheck == null) {
                    _events.tryEmit(ChatUiEvent.Error("安全检查异常"))
                    return null
                }
                if (inputCheck.isViolating) {
                    BanManager.recordViolation(application, inputCheck.level)
                    _events.tryEmit(ChatUiEvent.ContentBlocked("内容违规: ${inputCheck.reason}"))
                    return null
                }
            }

            _events.tryEmit(ChatUiEvent.Error("请先配置API：我 → API设置 → 添加密钥"))
            return null
        }

        val pipelineOk = try {
            withTimeoutOrNull(TimeoutBudgets.PIPELINE_EXECUTE_MS) {
                pipeline.execute(MessagePipeline.PipelineInput(rawText = content, companionId = companionId))
            }
        } catch (_: Exception) { null }

        if (pipelineOk != true) {
            _events.tryEmit(ChatUiEvent.ContentBlocked(pipeline.pipelineState.value.error ?: "内容可能违规"))
            return null
        }

        turnState.reset()
        val fetchedHistory = contextResolver.getHistoryForAi(companionId)
            .filterNot { !it.isFromUser && it.content.replace("\u200B", "").isBlank() }
        val settings = chatDetailSettingsStore.getSettings(companionId)
        val job = startAiResponse(
            history = fetchedHistory,
            stickerProbability = settings.stickerProbability,
            userContentForMemory = content,
            batchMessageCount = contentBatch.size,
            ntpTimeEnabled = settings.ntpTimeEnabled,
            onCommitted = commitSignal::markCommitted,
        )
        turnState.sendMessageJob = job
        return job
    }

    private fun startAiResponse(
        history: List<ChatMessage>,
        stickerProbability: Int,
        userContentForMemory: String,
        imagePath: String? = null,
        batchMessageCount: Int = 1,
        ntpTimeEnabled: Boolean = false,
        onCommitted: (() -> Unit)? = null
    ) = scope.launch(Dispatchers.IO + exceptionHandler) {
        val requestStartedAt = System.currentTimeMillis()

        com.yunian.ai.feature.chat.timeline.StreamingReasoningMessagePipeline
            .clearAllStreaming(companionId)
        val pendingTurn = com.yunian.ai.feature.chat.timeline.PendingTurn.start(
            conversation = com.yunian.ai.domain.timeline.ConversationRef(
                conversationId = companionId,
                conversationType = "chat",
            ),
            startedAtMs = requestStartedAt,
        )
        enterLoading()

        var loadingReleased = false
        // 本轮工具调用活动（按执行先后保序，同 id 覆盖 RUNNING→终态）；
        // 轮次结束时持久化为一条 TOOL_ACTIVITY 消息，让过程卡片进入消息流。
        val turnActivityMap = LinkedHashMap<Long, ToolActivity>()
        try {
            val companion = companionRepository.getCompanionById(companionId)
            if (companion == null) {
                _events.tryEmit(ChatUiEvent.Error("系统正在加载伴侣信息，请稍后再试"))
                return@launch
            }
            val imageGenEnabled = runCatching { appSettingsStore.getImageGenEnabled() }.getOrDefault(false)
            val imageGenRules = runCatching {
                ImageGenTriggerLogic.systemRules(
                    enabled = imageGenEnabled,
                    hasKeywordTrigger = appSettingsStore.getImageGenKeywords().isNotEmpty(),
                )
            }.getOrDefault("")
            // 生图协议文本通过「自定义角色指令」并入系统提示词，
            // 从而无需改动 core:domain 接口与 core:network 的提示词装配。
            val aiCompanion = companion.toAiCompanionInfo().let { base ->
                if (imageGenRules.isBlank()) {
                    base
                } else {
                    base.copy(
                        systemPrompt = listOfNotNull(
                            base.systemPrompt?.trim()?.takeIf { it.isNotEmpty() },
                            imageGenRules,
                        ).joinToString("\n\n")
                    )
                }
            }
            latestCompanionInfo = aiCompanion

            val modelHistory = com.yunian.ai.domain.AiDialogueHistoryPolicy
                .sanitizeForModel(history.toAiChatMessages())

            val showReasoning = appSettingsStore.getShowReasoning()
            // OpenMinis 模式：只要不是图片/本地模型路径就带工具，由模型自主决定是否调用
            val useTools = imagePath == null && !isLocalModelEnabled() && ToolRegistry.isNotEmpty()
            val streamEvents = when {
                imagePath != null -> {
                    val aiResponse = withTimeoutOrNull(TimeoutBudgets.CHAT_VM_VISION_TIMEOUT_MS) {
                        aiService.sendMessageWithImage(
                            aiCompanion,
                            modelHistory,
                            imagePath,
                            stickerProbability,
                            ntpTimeEnabled,
                        )
                    } ?: throw Exception(application.getString(R.string.api_error_generic))
                    NonStreamingAssistantStreamAdapter.fromCompleted(
                        turnId = pendingTurn.turnId,
                        reasoning = aiResponse.reasoningContent,
                        content = aiResponse.content,
                        startedAtMs = requestStartedAt,
                        completedAtMs = System.currentTimeMillis(),
                    )
                }
                isLocalModelEnabled() -> {
                    val content = runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS) {
                        generateWithLocalModel(companion, modelHistory, stickerProbability, ntpTimeEnabled)
                    } ?: throw java.util.concurrent.TimeoutException("Local model timeout")
                    NonStreamingAssistantStreamAdapter.fromCompleted(
                        turnId = pendingTurn.turnId,
                        reasoning = null,
                        content = content,
                        startedAtMs = requestStartedAt,
                        completedAtMs = System.currentTimeMillis(),
                    )
                }
                useTools -> {
                    val tools = ToolRegistry.all()
                    // 轮次预算放大到 6，外层超时预算随之放大，避免多轮工具调用被总超时误杀。
                    val toolLoopRounds = ChatConstants.CHAT_TOOL_LOOP_MAX_ROUNDS
                    val aiResponse = runInterruptibleSafe(
                        timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS * toolLoopRounds,
                    ) {
                        toolLoopRunner.executeWithToolLoop(
                            aiCompanion,
                            modelHistory,
                            stickerProbability,
                            ntpTimeEnabled,
                            tools,
                            maxRounds = toolLoopRounds,
                            onToolActivity = { event ->
                                // 同 id 覆盖（RUNNING → 终态），保持先后顺序
                                turnActivityMap[event.id] = event
                                _toolActivity.value =
                                    (_toolActivity.value.filterNot { it.id == event.id } + event)
                                        .sortedBy { it.id }
                            },
                        )
                    } ?: run {
                        throw java.util.concurrent.TimeoutException("AI response timeout")
                    }
                    NonStreamingAssistantStreamAdapter.fromCompleted(
                        turnId = pendingTurn.turnId,
                        reasoning = aiResponse.reasoningContent,
                        content = aiResponse.content,
                        startedAtMs = requestStartedAt,
                        completedAtMs = System.currentTimeMillis(),
                    )
                }
                else -> {

                    aiService.streamMessage(
                        companion = aiCompanion,
                        history = modelHistory,
                        stickerProbability = stickerProbability,
                        ntpTimeEnabled = ntpTimeEnabled,
                        turnId = pendingTurn.turnId,
                        startedAtMs = requestStartedAt,
                    )
                }
            }

            val streamResult = streamApplier.apply(
                events = streamEvents,
                turn = pendingTurn,
                projectLive = showReasoning,
                onReasoningSnapshot = { snapshot ->

                    if (EventCommitRules.shouldProjectReasoningLive(showReasoning, snapshot)) {
                        StreamingReasoningMessagePipeline.upsertStreaming(
                            companionId = companionId,
                            turnId = pendingTurn.turnId,
                            text = snapshot,
                            timestamp = pendingTurn.startedAtMs,
                            eventIndex = pendingTurn.streamingReasoningEvent()?.eventIndex,
                            anchorMessageId = pendingTurn.anchorMessageId,
                        )
                    }
                },
            )

            if (streamResult.failedMessage != null) {
                StreamingReasoningMessagePipeline.removeStreaming(companionId, pendingTurn.turnId)
                val toastMsg = com.yunian.ai.domain.AiOperationalMessages.asToastMessage(
                    "[TOAST]${streamResult.failedMessage}",
                ) ?: streamResult.failedMessage
                _events.tryEmit(ChatUiEvent.Error(toastMsg))
                return@launch
            }

            val aiContentRaw = streamResult.assistantText
            // 生图标签只用于提取画面描述，绝不允许进入气泡或会话列表摘要。
            // 若剥离后为空（模型整条回复只有标签/画面描述），也不能回落成原文——那正是标签泄漏的来源。
            val aiContent = if (imageGenEnabled) {
                val stripped = ImageGenTriggerLogic.stripTags(aiContentRaw)
                when {
                    stripped.isNotBlank() -> stripped
                    ImageGenTriggerLogic.isPromptOnly(aiContentRaw) -> IMAGE_GEN_ONLY_REPLY_TEXT
                    else -> aiContentRaw
                }
            } else {
                aiContentRaw
            }
            val toastMsg = com.yunian.ai.domain.AiOperationalMessages.asToastMessage(aiContent)
            if (toastMsg != null) {
                StreamingReasoningMessagePipeline.removeStreaming(companionId, pendingTurn.turnId)
                _events.tryEmit(ChatUiEvent.Error(toastMsg))
                return@launch
            }
            if (aiContent.isBlank()) {
                StreamingReasoningMessagePipeline.removeStreaming(companionId, pendingTurn.turnId)
                _events.tryEmit(ChatUiEvent.Error("API返回空内容，请检查模型名是否正确"))
                return@launch
            }

            val reasoningForCommit = streamResult.reasoningText

            val firstDelivered = responseFinalizer.deliverResponse(
                aiContent = aiContent,
                reasoning = reasoningForCommit,
                userContentForMemory = null,
                logMessage = if (batchMessageCount > 1) "AI batch response received (${batchMessageCount} msgs)" else "AI response received",
                pendingTurn = pendingTurn,
                reasoningStartedAtMs = requestStartedAt,
                onCommitted = onCommitted,
            )

            exitLoading()
            loadingReleased = true

            // AI 生图：关键词/概率触发，独立协程执行，失败绝不影响聊天主流程
            maybeTriggerImageGeneration(
                userText = userContentForMemory,
                aiText = aiContentRaw,
                enabled = imageGenEnabled,
            )

            val enableBubbleChain = imagePath == null && !isLocalModelEnabled()
            val followUpBubbles = if (enableBubbleChain) {
                bubbleLoopRunner.runFollowingBubbles { alreadyGenerated ->
                    val appendedHistory = modelHistory + alreadyGenerated.map { text ->
                        AiChatMessage(
                            isFromUser = false,
                            content = text,
                            timestamp = System.currentTimeMillis(),
                            role = com.yunian.ai.domain.AiMessageRole.ASSISTANT,
                        )
                    }
                    val resp = runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS) {
                        aiService.sendMessage(
                            companion = aiCompanion,
                            history = appendedHistory,
                            stickerProbability = stickerProbability,
                            ntpTimeEnabled = ntpTimeEnabled,
                            extraSystemRules = BubbleJsonProtocol.systemRules(),
                        )
                    }
                    resp?.content.orEmpty()
                }
            } else {
                emptyList()
            }

            // 追尾气泡同样可能夹带生图标签：剥离后再落库，
            // 否则标签会写进气泡、并污染会话列表摘要（用户看到的就是提示词原文）。
            val followUpBubblesDelivered = followUpBubbles
                .map { if (imageGenEnabled) ImageGenTriggerLogic.stripTags(it) else it }
                .filter { it.isNotBlank() }

            followUpBubblesDelivered.forEach { bubble ->
                delay(800L + kotlin.random.Random.nextLong(1200L))
                runCatching {
                    responseFinalizer.deliverResponse(
                        aiContent = bubble,
                        reasoning = null,
                        userContentForMemory = null,
                        pendingTurn = pendingTurn,
                        onCommitted = onCommitted,
                    )
                }.onFailure { e ->
                    SecureLog.e("ChatGenerationManager", "Follow-up bubble delivery failed", e)
                }
            }

            val allBubbleContent = (listOf(aiContent) + followUpBubblesDelivered).joinToString("\n")
            responseFinalizer.afterDeliver(
                firstDelivered.copy(
                    aiContent = allBubbleContent,
                    segments = listOf(aiContent) + followUpBubblesDelivered,
                    userContentForMemory = userContentForMemory,
                )
            )
            // 所有气泡已落库后再写工具卡片：保证其时间戳最大，在消息流中排在本轮最后一条助手消息之后。
            persistToolActivities(pendingTurn.turnId.value, turnActivityMap.values.toList())
            SecureLog.d("ChatGenerationManager", "AI request completed in ${System.currentTimeMillis() - requestStartedAt}ms, chars=${aiContent.length}, bubbles=${1 + followUpBubblesDelivered.size}")
        } catch (e: CancellationException) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, pendingTurn.turnId)
            // 被打断（如新一轮消息到达/再生成）：丢弃实时过程态，避免卡片残留在输入框上方。
            // 此处不落库（协程已取消，且避免与新一轮消息顺序错乱）。
            _toolActivity.value = emptyList()
            val cancelReason = e.message ?: ""
            if (!cancelReason.contains("batch started") && !cancelReason.contains("stale")) {
                _events.tryEmit(ChatUiEvent.Error("回复被打断，请重试"))
            }
            throw e
        } catch (e: Exception) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, pendingTurn.turnId)
            // 出错也要把已发生的工具调用记录进消息流，用户能看到实际执行了什么。
            persistToolActivities(pendingTurn.turnId.value, turnActivityMap.values.toList())
            val rawMessage = e.message ?: "发送失败"
            _events.tryEmit(ChatUiEvent.Error(rawMessage.removePrefix("[TOAST]")))
            SecureLog.e("ChatGenerationManager", "AI response failed", e)
        } finally {
            if (!loadingReleased) {
                exitLoading()
            }
        }
    }

    private fun enterLoading() {
        if (activeRequests.incrementAndGet() == 1) {
            _isLoading.value = true
            typingState.startTyping()
        }
    }

    private fun exitLoading() {
        val remaining = activeRequests.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        if (remaining == 0) {
            _isLoading.value = false
            typingState.stopTyping()
        }
    }

    private fun shouldEnableToolsFor(content: String, history: List<ChatMessage>): Boolean {

        return ChatToolIntent.shouldEnableTools(
            content = content,
            latestUserText = history.lastOrNull { it.isFromUser }?.content
        )
    }

    /**
     * 聊天配图触发。
     *
     * 独立协程执行，绝不阻塞或打断聊天主流程；触发条件与执行细节全部收敛在
     * ImageGenService（→ ImageGenCoordinator / ImageGenTriggerLogic，可单测）。
     * 总开关关闭时不读取任何其他配置即返回。
     */
    private fun maybeTriggerImageGeneration(userText: String, aiText: String, enabled: Boolean) {
        if (!enabled) return
        val service = ServiceRegistry.get(ImageGenService::class.java)
        if (service == null) {
            SecureLog.w("ChatGenerationManager", "ImageGenService not registered, skip image gen")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                val toastEnabled = runCatching { appSettingsStore.getImageGenToastEnabled() }
                    .getOrDefault(true)
                // 判定/生图/落库/微信镜像全部由 ImageGenService 统一完成；
                // 微信与 QQ 桥接链路复用同一份实现，避免第二套判定逻辑。
                service.generateForReply(
                    companionId = companionId,
                    userText = userText,
                    aiText = aiText,
                    mirrorToWeChat = true,
                    onMessage = { text, isError ->
                        if (toastEnabled) {
                            if (isError) {
                                _events.tryEmit(ChatUiEvent.Error(text))
                            } else {
                                _events.tryEmit(ChatUiEvent.Info(text))
                            }
                        }
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SecureLog.e("ChatGenerationManager", "Image gen trigger failed", e)
            } finally {
                // 兜底：任何异常路径都不能让等待动画一直转下去
                ImageGenGenerationStatus.markFinished(companionId)
            }
        }
    }

    /**
     * 把本轮的工具调用活动持久化为**一条** [MessageType.TOOL_ACTIVITY] 消息。
     *
     * - 幂等：同 turnId 先删旧记录（防止重复写入）；
     * - 写入走 [MessageWriteCoordinator]（→ ChatRepository.batchInsertMessages →
     *   MessageCache.appendChatMessage），已打开的聊天页可立即刷新出该卡片；
     * - 该类型不参与 AI 上下文/会话摘要（见 ChatContextResolver / ChatRepository 的过滤）；
     * - 结束后清空实时态 [_toolActivity]，由持久化卡片接管消息流显示。
     */
    private suspend fun persistToolActivities(turnId: String, activities: List<ToolActivity>) {
        try {
            if (activities.isNotEmpty() && turnId.isNotBlank()) {
                runCatching { chatRepository.deleteToolActivitiesForTurn(companionId, turnId) }
                    .onFailure { SecureLog.w("ChatGenerationManager", "Delete stale tool card failed: ${it.message}") }
                messageWriter.enqueueChat(
                    ChatMessage(
                        companionId = companionId,
                        content = ToolActivityCodec.encode(activities),
                        isFromUser = false,
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.TOOL_ACTIVITY,
                        turnId = turnId,
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecureLog.e("ChatGenerationManager", "Persist tool activities failed", e)
        } finally {
            _toolActivity.value = emptyList()
        }
    }

    private fun broadcastWeChatMessage(messageId: Long, finalContent: String? = null) {
        WeChatProactiveSync.enqueue(companionId, messageId, finalContent)
    }

    private suspend fun isLocalModelEnabled(): Boolean {
        val provider = ServiceRegistry.get(LocalModelProvider::class.java) ?: return false
        return provider.isAvailable()
    }

    private suspend fun generateWithLocalModel(
        companion: CompanionEntity,
        history: List<AiChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean = false
    ): String {

        val sortedHistory = history.sortedBy { it.timestamp }
        val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""
        val memoryContext = memoryProvider.getMemoryContext(companion.id, null, lastUserMessage, 50).take(500)
        val role = userRepository?.selectedRole?.value ?: CompanionRole.GIRLFRIEND
        val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
        val recentAiTexts = sortedHistory
            .asReversed()
            .asSequence()
            .filter { !it.isFromUser }
            .take(ChatConstants.ENV_ANCHOR_RECENT_LOOKBACK)
            .map { it.content }
            .toList()
        val allowEnvAnchor = envAnchorStore.allowEnvAnchor(companion.id, recentAiTexts)
        val dialogueContext = sortedHistory
            .takeLast(12)
            .joinToString("\n") { msg ->
                val speaker = if (msg.isFromUser) "用户" else companion.name
                "$speaker：${msg.content.take(200)}"
            }
            .take(1200)
        val personality = companion.personality?.take(300).orEmpty()
        val rawPrompt = companion.rawPrompt?.take(300).orEmpty()
        val customSystem = companion.systemPrompt?.take(4000).orEmpty()
        val systemPrompt = buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, role))
            appendLine()
            appendLine(com.yunian.ai.network.AiContextTools.buildDeliveryBudgetPriority(lastUserMessage))
            personality.takeIf { it.isNotBlank() }?.let { appendLine("性格：$it") }
            companion.speakingStyle?.take(100)?.takeIf { it.isNotBlank() }?.let { appendLine("说话风格：$it") }
            companion.backstory?.take(200)?.takeIf { it.isNotBlank() }?.let { appendLine("背景：$it") }
            if (rawPrompt.isNotBlank() &&
                rawPrompt != personality &&
                !personality.contains(rawPrompt) &&
                !rawPrompt.contains(personality)
            ) {
                appendLine("补充设定：$rawPrompt")
            }
            if (customSystem.isNotBlank()) {
                appendLine("自定义角色指令：$customSystem")
            }
            if (memoryContext.isNotBlank()) appendLine("\n关于用户的记忆：$memoryContext")
            if (dialogueContext.isNotBlank()) {
                appendLine("\n最近对话（仅供参考，不要复读系统错误或自言自语）：")
                appendLine(dialogueContext)
            }
            appendLine()
            appendLine("回复规则：")
            appendLine("1. 单次单动作：每轮只做一个核心社交意图（纯共情/纯反问/纯表态/纯答问；求方案才给一步），完整说完。严禁问好+共情+反问+方案打包，也严禁半截残句。")
            appendLine("2. 长度服从动作数：闲聊通常一句完整口语即可；解释可稍长。不要为凑字再塞第二个动作；写完自查意图数，不要按 30 字砍成残句。")
            appendLine("3. 活人语气，自然口语化，不要AI腔。")
            appendLine("4. 每句话用标点结尾（。！？～…），表意收住。")
            appendLine("5. 不要重复同样的话。")
            appendLine("6. 镜像前置：开口先接表层情绪或表层问句；未求方案时优先反问/接住，别主动结案。")
            appendLine("7. 每条回复默认 = 一条气泡：把同一动作用一句完整口语说完并收尾；如果用户明确要求发多条、或你的回复由多条独立短消息组成，把每条消息写成一句完整的话并用标点（。！？～）收尾，系统会按句末标点自动拆成多条气泡连发。")
            if (innerThoughtEnabled) appendLine("8. 每轮回复包含括号内的心理活动，如（脸红）（开心），放在回复开头或中间。") else appendLine("8. 禁止使用任何括号。禁止说教。")
            RolePromptProvider.getLocalModelRoleLines(role).forEachIndexed { index, line -> appendLine("${9 + index}. $line") }
            if (stickerProbability > 0) {
                appendLine("12. 表情包：可按语境偶尔使用[名称]格式。")
                // P5 修复：本地模型路径注入自定义表情语义清单（修「本地模型不知道有哪些表情」）；
                // gated：概率=0 或未导入任何自定义表情时整段不拼，保持提示词零变化
                val customStickers = stickerManager.getPromptStickers().filter { it.isCustom }
                if (customStickers.isNotEmpty()) {
                    appendLine("13. 用户还教了你专属表情包，含义如下，请在语境匹配时优先使用：")
                    com.yunian.ai.common.CustomStickerPrompt.buildLines(customStickers).forEach { appendLine("    $it") }
                }
            }
            appendLine("\n记住：你不是AI，你是活人。直接回复内容，不要输出思考过程。")
            appendLine()
            appendLine(com.yunian.ai.network.AiContextTools.buildConversationTimingRules())
            appendLine(com.yunian.ai.network.AiContextTools.buildDeliveryBudgetRules())

            val phaseHistory = sortedHistory.map { msg ->
                com.yunian.ai.database.model.ChatMessage(
                    companionId = companion.id,
                    content = msg.content,
                    isFromUser = msg.isFromUser,
                    timestamp = msg.timestamp,
                )
            }
            val phase = com.yunian.ai.network.ConversationPhaseDetector.detect(phaseHistory)
            val effectivePhase =
                if (!allowEnvAnchor && phase == com.yunian.ai.network.ConversationPhase.OPENING) {
                    com.yunian.ai.network.ConversationPhase.TOPIC
                } else {
                    phase
                }
            appendLine(com.yunian.ai.network.AiContextTools.buildConversationPhaseSection(effectivePhase))
            appendLine(com.yunian.ai.network.AiContextTools.buildCurrentTimeContext(ntpTimeEnabled, effectivePhase))
            val cooldown = EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor)
            if (cooldown.isNotBlank()) {
                appendLine()
                appendLine(cooldown)
            }
            appendLine()
            appendLine(com.yunian.ai.network.AiContextTools.buildDeliveryBudgetEndCap(lastUserMessage))
        }
        val localProvider = ServiceRegistry.get(LocalModelProvider::class.java)
            ?: throw Exception(application.getString(R.string.api_error_generic))
        val response = localProvider.generateResponse(prompt = lastUserMessage.take(2000), context = systemPrompt)

        val cleaned = com.yunian.ai.network.ResponsePostProcessor
            .trimIdleEmotionOverDelivery(
                com.yunian.ai.network.ResponsePostProcessor.stripThinkingContent(response),
                lastUserMessage,
            )
            .ifBlank {
                SecureLog.w(
                    "ChatGenerationManager",
                    "Local model returned only thinking/empty after strip, rawLen=${response.length}",
                )
                ""
            }
        if (EnvAnchorCooldown.looksLikeEnvCare(cleaned)) {
            envAnchorStore.markEnvAnchor(companion.id)
            SecureLog.d("ChatGenerationManager", "Marked env anchor companion=${companion.id} (local)")
        }
        return cleaned
    }

    private fun CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
        id = id,
        name = name,
        personality = personality,
        age = age,
        backstory = backstory,
        speakingStyle = speakingStyle,
        systemPrompt = systemPrompt
    )

    private fun List<ChatMessage>.toAiChatMessages(): List<AiChatMessage> = map { msg ->
        val base = AiChatMessage(
            isFromUser = msg.isFromUser,
            // 图片消息把画面描述一并带给模型，否则追问「再生成一张」会货不对板
            content = msg.contentForModel(),
            timestamp = msg.timestamp,
            type = if (msg.type == MessageType.IMAGE) AiMessageType.IMAGE else AiMessageType.TEXT,
            companionId = msg.companionId
        )
        com.yunian.ai.domain.AiDialogueHistoryPolicy.normalizeRole(base)
    }

}
