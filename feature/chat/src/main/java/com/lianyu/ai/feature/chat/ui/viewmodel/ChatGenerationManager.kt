package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.ApplicationScopeProvider
import com.lianyu.ai.common.BanManager
import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.CompanionRole
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.EnvAnchorCooldown
import com.lianyu.ai.common.EnvAnchorStore
import com.lianyu.ai.common.RolePromptProvider
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.domain.wechat.WeChatProactiveSync
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.database.repository.UserRepository
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiMessageType
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.ToolRegistry
import com.lianyu.ai.feature.chat.R
import com.lianyu.ai.feature.chat.data.ChatContextResolver
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.feature.chat.timeline.EventCommitRules
import com.lianyu.ai.feature.chat.timeline.PendingTurnStreamApplier
import com.lianyu.ai.feature.chat.timeline.StreamingReasoningMessagePipeline
import com.lianyu.ai.feature.chat.voice.ChatTtsController
import com.lianyu.ai.feature.chat.voice.ChatTtsState
import com.lianyu.ai.network.ChatTypingState
import com.lianyu.ai.network.bubble.BubbleJsonProtocol
import com.lianyu.ai.network.bubble.BubbleLoopRunner
import com.lianyu.ai.network.stream.NonStreamingAssistantStreamAdapter
import com.lianyu.ai.network.tts.ChatTtsConfig
import com.lianyu.ai.network.tts.ChatTtsMode
import com.lianyu.ai.network.tts.TtsService
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
import java.util.concurrent.atomic.AtomicInteger

class ChatGenerationManager private constructor(
    private val application: Application,
    private val companionId: Long
) {
    companion object {
        private val instances = ConcurrentHashMap<Long, ChatGenerationManager>()
        private val questionRegex = Regex("[?？]|吗|呢|什么|怎么|为什么|多少|哪|谁|几|是不是|有没有|能不能|会不会|要不要|好不好")

        /** 离开聊天页后，空闲多久才回收实例（不打断进行中的 AI） */
        private const val IDLE_DISPOSE_MS = 60_000L

        /**
         * 获取或创建实例（不改变引用计数）。
         * UI 层应优先使用 [acquire] / [release]。
         */
        fun get(application: Application, companionId: Long): ChatGenerationManager =
            instances.getOrPut(companionId) { ChatGenerationManager(application, companionId) }

        /**
         * 进入聊天页时获取实例并增加引用计数。
         * 会取消挂起的空闲回收，保证离页后仍在生成的任务可继续。
         * 若命中已 dispose 的竞态实例，会剔除并重建。
         */
        fun acquire(application: Application, companionId: Long): ChatGenerationManager {
            while (true) {
                val manager = get(application, companionId)
                if (manager.tryAcquire()) return manager
                instances.remove(companionId, manager)
            }
        }

        /**
         * 离开聊天页时减少引用计数。
         * 引用归零且空闲后延迟回收；生成中不会取消 AI。
         */
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

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    private val _confirmationRequest = MutableStateFlow<ToolConfirmationRequest?>(null)
    /** AI 工具确认卡片请求；非空时 ChatScreen 弹确认 Dialog */
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
        // 丢弃上一轮取消/超时残留的响应，防止自动确认本轮请求
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

    val isTyping: StateFlow<Boolean> = typingState.isTyping
    val typingText: StateFlow<String> = typingState.typingText

    val pipeline = MessagePipelineRunner { level -> BanManager.recordViolation(application, level) }

    private val toolLoopRunner = AiToolLoopRunner(aiService, confirmationGate = ::requestToolConfirmation)
    private val streamApplier = PendingTurnStreamApplier()
    /**
     * 气泡连发循环器（用户定稿架构）：
     * 首条气泡由完整生成流程产出，之后由 LLM 自决是否继续连发（每次调用一条气泡）。
     */
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
        val userMessage = ChatMessage(
            companionId = companionId,
            content = content,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )
        scope.launch(Dispatchers.IO + exceptionHandler) {
            val userMessageId = messageWriter.enqueueChat(userMessage)
            broadcastWeChatMessage(userMessageId)

            // 无 API 时仍入队用户消息，但错误只走 UI 事件（Toast），不在此处写假 AI 消息
            if (apiConfigRepository.getActiveEnabledConfig() == null) {
                _events.tryEmit(ChatUiEvent.Error("请先配置API：我 → API设置 → 添加密钥"))
            }
            val result = messageQueue.trySend(content)
            if (result.isSuccess) {
                _queueDepth.update { it + 1 }
                // 乐观 typing：入队即显示，不等 2.5s 合并窗口 + pipeline 校验
                // 上一轮 AI 仍在生成时 activeRequests>0，enterLoading 不会重复置 typing
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
                    // 展示文案用标签；真实路径只放 linkString，避免引用预览泄漏本地路径
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
        // 废弃 READ_ALOUD：统一落到 SILENT / VOICE_BAR
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

    /** @return false 表示实例已 dispose，调用方应重建 */
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

    /**
     * 引用归零后延迟回收。生成中 / 队列非空时只重试，不取消进行中的 AI。
     */
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

    /**
     * 仅在无引用且空闲时释放 TTS / 队列消费者，并从全局 map 移除。
     * 绝不在生成中 dispose，保证离页后 AI 可跑完。
     */
    private fun disposeIfIdle() {
        val shouldReschedule: Boolean
        synchronized(this) {
            if (disposed) return
            if (refCount.get() > 0 || isBusy()) {
                // 仍在生成：延后再试，不打断
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
            // 尚未被 AI 成功消费的用户文本；打断重发时保留，成功后才移除
            val pending = mutableListOf<String>()
            // true：连发聚合窗口；false：仅因子批次上限拆分后的短间隔
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
                    // 快速连发：窗口内合并为一次 AI 请求
                    drainQueueWithTimeout(pending, ChatConstants.MESSAGE_BATCH_WINDOW_MS)
                } else {
                    delay(ChatConstants.MESSAGE_BATCH_SPLIT_DELAY_MS)
                    drainTryReceive(pending)
                }

                val batchSize = pending.size.coerceAtMost(ChatConstants.MESSAGE_BATCH_MAX_SIZE)
                if (batchSize <= 0) continue
                val batch = pending.take(batchSize)

                // 若上一轮 AI 仍在生成/分段投递，先打断再带着合并后的用户消息重发
                cancelActiveGenerationForMerge()

                try {
                    val job = startSendMessage(batch)
                    if (job == null) {
                        // 校验失败（封禁/无 API/违规等）：丢弃本批，避免死循环
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
                        ChatDebugLog.log(
                            "[ChatGeneration] Interrupted in-flight AI to merge ${pending.size} pending user message(s)"
                        )
                        // pending 仍含本批 + 新消息，下一轮窗口合并后重发
                        waitForMergeWindow = true
                        continue
                    }

                    // 生成正常结束：从 pending 移除已消费批次
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
                    // 失败也移除本批，避免同一内容无限重试
                    repeat(batchSize) { if (pending.isNotEmpty()) pending.removeAt(0) }
                    waitForMergeWindow = pending.isEmpty()
                }
            }
        }
    }

    /** 非阻塞排空队列中已到达的用户消息 */
    private fun drainTryReceive(pending: MutableList<String>) {
        while (pending.size < ChatConstants.MESSAGE_BATCH_MAX_SIZE) {
            val extra = messageQueue.tryReceive()
            if (extra.isClosed || extra.isFailure) break
            pending.add(extra.getOrThrow())
            _queueDepth.update { maxOf(0, it - 1) }
        }
    }

    /** 在聚合窗口内轮询接收连发消息 */
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

    /**
     * 等待 AI 完成；若生成过程中又有用户消息入队，则立即返回 true 以便打断合并。
     */
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
                // 队列关闭后只等当前 AI 收尾
                job.join()
                return false
            }
            if (result.isFailure) continue
            pending.add(result.getOrThrow())
            _queueDepth.update { maxOf(0, it - 1) }
            drainTryReceive(pending)
            return true
        }
        // 让 job 内异常/取消路径走完
        runCatching { job.join() }
        return false
    }

    private fun cancelActiveGenerationForMerge() {
        val active = turnState.sendMessageJob?.takeIf { it.isActive } ?: return
        active.cancel(CancellationException("New message batch started, cancelling stale batch"))
        // 不在此处 join：由调用方在启动新任务前通过 await/join 收敛，避免阻塞过久
    }

    /**
     * 校验并启动 AI 回复。成功返回 job；校验失败返回 null（不启动生成）。
     * 调用方负责：生成中监听新消息并打断合并、成功后消费 pending。
     */
    private suspend fun startSendMessage(batch: List<String>): Job? {
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

        if (apiConfigRepository.getActiveEnabledConfig() == null) {
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
            // 架构：配置类错误只 Toast，禁止写入消息库污染对话与 AI 上下文
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
            ntpTimeEnabled = settings.ntpTimeEnabled
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
        ntpTimeEnabled: Boolean = false
    ) = scope.launch(Dispatchers.IO + exceptionHandler) {
        val requestStartedAt = System.currentTimeMillis()
        // 新一轮请求开始时清理上一轮流式思考临时消息，避免串轮
        com.lianyu.ai.feature.chat.timeline.StreamingReasoningMessagePipeline
            .clearAllStreaming(companionId)
        val pendingTurn = com.lianyu.ai.feature.chat.timeline.PendingTurn.start(
            conversation = com.lianyu.ai.domain.timeline.ConversationRef(
                conversationId = companionId,
                conversationType = "chat",
            ),
            startedAtMs = requestStartedAt,
        )
        enterLoading()
        // 成功路径在消息落地后提前 release；finally 仅兜底失败/提前 return，禁止双重 decrement。
        var loadingReleased = false
        try {
            val companion = companionRepository.getCompanionById(companionId)
            if (companion == null) {
                _events.tryEmit(ChatUiEvent.Error("系统正在加载伴侣信息，请稍后再试"))
                return@launch
            }
            latestCompanionInfo = companion.toAiCompanionInfo()

            // 发送前清洗历史：剔除运营错误、规范工具角色，防止 AI 自言自语
            val modelHistory = com.lianyu.ai.domain.AiDialogueHistoryPolicy
                .sanitizeForModel(history.toAiChatMessages())

            // Slice 6：无工具远程路径走真实 SSE；tools / vision / local 降级非流式终态事件
            val showReasoning = appSettingsStore.getShowReasoning()
            val useTools = imagePath == null &&
                !isLocalModelEnabled() &&
                shouldEnableToolsFor(content = userContentForMemory, history = history)
            val streamEvents = when {
                imagePath != null -> {
                    val aiResponse = withTimeoutOrNull(TimeoutBudgets.CHAT_VM_VISION_TIMEOUT_MS) {
                        aiService.sendMessageWithImage(
                            companion.toAiCompanionInfo(),
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
                    val aiResponse = runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS * 3) {
                        toolLoopRunner.executeWithToolLoop(
                            companion.toAiCompanionInfo(),
                            modelHistory,
                            stickerProbability,
                            ntpTimeEnabled,
                            tools,
                        )
                    } ?: throw java.util.concurrent.TimeoutException("AI response timeout")
                    NonStreamingAssistantStreamAdapter.fromCompleted(
                        turnId = pendingTurn.turnId,
                        reasoning = aiResponse.reasoningContent,
                        content = aiResponse.content,
                        startedAtMs = requestStartedAt,
                        completedAtMs = System.currentTimeMillis(),
                    )
                }
                else -> {
                    // 真实 SSE；超时由 OkHttp 读超时 + 上层 job cancel 约束
                    aiService.streamMessage(
                        companion = companion.toAiCompanionInfo(),
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
                    // 流式思考走消息链路（L1 MessageCache），不再写 ephemeral StateFlow
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
                val toastMsg = com.lianyu.ai.domain.AiOperationalMessages.asToastMessage(
                    "[TOAST]${streamResult.failedMessage}",
                ) ?: streamResult.failedMessage
                _events.tryEmit(ChatUiEvent.Error(toastMsg))
                return@launch
            }

            val aiContent = streamResult.assistantText
            val toastMsg = com.lianyu.ai.domain.AiOperationalMessages.asToastMessage(aiContent)
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

            // 首条气泡：保持完整能力（流式/工具/视觉/本地模型）生成；
            // 若模型用空行显式分隔多条独立消息（用户要求多发/逐条回应多点），deliverResponse 内会按空行拆成多条气泡连发
            val firstDelivered = responseFinalizer.deliverResponse(
                aiContent = aiContent,
                reasoning = reasoningForCommit,
                userContentForMemory = null,
                logMessage = if (batchMessageCount > 1) "AI batch response received (${batchMessageCount} msgs)" else "AI response received",
                pendingTurn = pendingTurn,
                reasoningStartedAtMs = requestStartedAt,
            )

            // 首条（含空行拆分的全部气泡）已可见：立即关闭 loading/typing，避免后续连发链把「对方正在输入」拖住
            exitLoading()
            loadingReleased = true

            // 后续气泡：循环调用 LLM，每次调用输出一条气泡（用户定稿架构）
            // - 本地模型/视觉路径不循环（本地无 JSON 能力；视觉后续无图上下文）
            // - 首条之后的每次调用：已生成气泡以 assistant 消息追加回历史，AI 依据角色性格自决 {继续}
            // - JSON 协议约束输出格式，解析失败最多重试 3 次，仍失败则停止连发（已生成气泡保留）
            // - 单次调用带硬超时：模型挂起/卡死不会无限拖住本轮（超时视为失败，重试后停止）
            val enableBubbleChain = imagePath == null && !isLocalModelEnabled()
            val followUpBubbles = if (enableBubbleChain) {
                bubbleLoopRunner.runFollowingBubbles { alreadyGenerated ->
                    val appendedHistory = modelHistory + alreadyGenerated.map { text ->
                        AiChatMessage(
                            isFromUser = false,
                            content = text,
                            timestamp = System.currentTimeMillis(),
                            role = com.lianyu.ai.domain.AiMessageRole.ASSISTANT,
                        )
                    }
                    val resp = runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS) {
                        aiService.sendMessage(
                            companion = companion.toAiCompanionInfo(),
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

            // 逐条落地后续气泡，气泡间 0.8~2 秒模拟真人连发；失败不阻断本轮
            followUpBubbles.forEach { bubble ->
                delay(800L + kotlin.random.Random.nextLong(1200L))
                runCatching {
                    responseFinalizer.deliverResponse(
                        aiContent = bubble,
                        reasoning = null,
                        userContentForMemory = null,
                        pendingTurn = pendingTurn,
                    )
                }.onFailure { e ->
                    SecureLog.e("ChatGenerationManager", "Follow-up bubble delivery failed", e)
                }
            }

            // 记忆统一用全部气泡合并文本（避免只存首条）
            val allBubbleContent = (listOf(aiContent) + followUpBubbles).joinToString("\n")
            responseFinalizer.afterDeliver(
                firstDelivered.copy(
                    aiContent = allBubbleContent,
                    segments = listOf(aiContent) + followUpBubbles,
                    userContentForMemory = userContentForMemory,
                )
            )
            SecureLog.d("ChatGenerationManager", "AI request completed in ${System.currentTimeMillis() - requestStartedAt}ms, chars=${aiContent.length}, bubbles=${1 + followUpBubbles.size}")
        } catch (e: CancellationException) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, pendingTurn.turnId)
            val cancelReason = e.message ?: ""
            if (!cancelReason.contains("batch started") && !cancelReason.contains("stale")) {
                _events.tryEmit(ChatUiEvent.Error("回复被打断，请重试"))
            }
            throw e
        } catch (e: Exception) {
            StreamingReasoningMessagePipeline.removeStreaming(companionId, pendingTurn.turnId)
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
        // 纯本地关键词判断，0 网络延迟；AI 预判已移除（阻塞回复起点且结果不可靠）
        return ChatToolIntent.shouldEnableTools(
            content = content,
            latestUserText = history.lastOrNull { it.isFromUser }?.content
        )
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
        // history 已由 AiDialogueHistoryPolicy.sanitizeForModel 清洗
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
        val customSystem = companion.systemPrompt?.take(800).orEmpty()
        val systemPrompt = buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, role))
            appendLine()
            appendLine(com.lianyu.ai.network.AiContextTools.buildDeliveryBudgetPriority(lastUserMessage))
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
            if (stickerProbability > 0) appendLine("12. 表情包：可按语境偶尔使用[名称]格式。")
            appendLine("\n记住：你不是AI，你是活人。直接回复内容，不要输出思考过程。")
            appendLine()
            appendLine(com.lianyu.ai.network.AiContextTools.buildConversationTimingRules())
            appendLine(com.lianyu.ai.network.AiContextTools.buildDeliveryBudgetRules())
            // 本地路径历史为领域类型，映射为轻量 ChatMessage 供阶段检测
            val phaseHistory = sortedHistory.map { msg ->
                com.lianyu.ai.database.model.ChatMessage(
                    companionId = companion.id,
                    content = msg.content,
                    isFromUser = msg.isFromUser,
                    timestamp = msg.timestamp,
                )
            }
            val phase = com.lianyu.ai.network.ConversationPhaseDetector.detect(phaseHistory)
            val effectivePhase =
                if (!allowEnvAnchor && phase == com.lianyu.ai.network.ConversationPhase.OPENING) {
                    com.lianyu.ai.network.ConversationPhase.TOPIC
                } else {
                    phase
                }
            appendLine(com.lianyu.ai.network.AiContextTools.buildConversationPhaseSection(effectivePhase))
            appendLine(com.lianyu.ai.network.AiContextTools.buildCurrentTimeContext(ntpTimeEnabled, effectivePhase))
            val cooldown = EnvAnchorCooldown.buildCooldownDirective(allowEnvAnchor)
            if (cooldown.isNotBlank()) {
                appendLine()
                appendLine(cooldown)
            }
            appendLine()
            appendLine(com.lianyu.ai.network.AiContextTools.buildDeliveryBudgetEndCap(lastUserMessage))
        }
        val localProvider = ServiceRegistry.get(LocalModelProvider::class.java)
            ?: throw Exception(application.getString(R.string.api_error_generic))
        val response = localProvider.generateResponse(prompt = lastUserMessage.take(2000), context = systemPrompt)
        // 本地模型也会输出 <think>/纯文本 CoT；与云端路径统一剥离，禁止思考进气泡
        // 闲聊情绪轮再裁护理包，与 applyPersonaPostProcessing 对齐
        val cleaned = com.lianyu.ai.network.ResponsePostProcessor
            .trimIdleEmotionOverDelivery(
                com.lianyu.ai.network.ResponsePostProcessor.stripThinkingContent(response),
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
            content = msg.content,
            timestamp = msg.timestamp,
            type = if (msg.type == MessageType.IMAGE) AiMessageType.IMAGE else AiMessageType.TEXT,
            companionId = msg.companionId
        )
        com.lianyu.ai.domain.AiDialogueHistoryPolicy.normalizeRole(base)
    }

}