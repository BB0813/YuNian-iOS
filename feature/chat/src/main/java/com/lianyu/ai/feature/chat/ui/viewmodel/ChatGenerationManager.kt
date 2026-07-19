package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.ApplicationScopeProvider
import com.lianyu.ai.common.BanManager
import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.CompanionRole
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.RolePromptProvider
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.common.wechat.WeChatBroadcastHelper
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
import com.lianyu.ai.domain.AiResponse
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.MemoryProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.ToolRegistry
import com.lianyu.ai.feature.chat.R
import com.lianyu.ai.feature.chat.data.ChatContextResolver
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.feature.chat.voice.ChatTtsController
import com.lianyu.ai.feature.chat.voice.ChatTtsState
import com.lianyu.ai.network.ChatTypingState
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _reasoningText = MutableStateFlow("")
    val reasoningText: StateFlow<String> = _reasoningText.asStateFlow()

    private val _isReasoning = MutableStateFlow(false)
    val isReasoning: StateFlow<Boolean> = _isReasoning.asStateFlow()

    private val _isRegenerating = MutableStateFlow(false)
    val isRegenerating: StateFlow<Boolean> = _isRegenerating.asStateFlow()

    private val _queueDepth = MutableStateFlow(0)
    val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

    val isTyping: StateFlow<Boolean> = typingState.isTyping
    val typingText: StateFlow<String> = typingState.typingText

    val pipeline = MessagePipelineRunner { level -> BanManager.recordViolation(application, level) }

    private val toolLoopRunner = AiToolLoopRunner(aiService)
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
        reasoningText = _reasoningText,
        isReasoning = _isReasoning,
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
                _queueDepth.value += 1
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
        updateTtsConfig(_chatTtsConfig.value.copy(mode = mode))
    }

    fun updateTtsConfig(config: ChatTtsConfig) {
        _chatTtsConfig.value = config
        ChatTtsConfig.saveToSharedPreferences(application, config)
        if (config.mode == ChatTtsMode.SILENT) ttsController.stop()
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

    suspend fun synthesizeForVoiceBar(text: String): String? = ttsController.synthesizeOnly(text)

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
            val batch = mutableListOf<String>()
            while (true) {
                val first = messageQueue.receiveCatching()
                if (first.isClosed) break
                if (first.exceptionOrNull() != null) continue

                batch.add(first.getOrThrow())
                _queueDepth.value = maxOf(0, _queueDepth.value - 1)

                while (true) {
                    val extra = messageQueue.tryReceive()
                    if (extra.isClosed || extra.isFailure) break
                    batch.add(extra.getOrThrow())
                    _queueDepth.value = maxOf(0, _queueDepth.value - 1)
                }

                val batches = if (batch.size <= ChatConstants.MESSAGE_BATCH_MAX_SIZE) listOf(batch.toList()) else batch.chunked(ChatConstants.MESSAGE_BATCH_MAX_SIZE)
                for ((batchIndex, subBatch) in batches.withIndex()) {
                    if (batchIndex > 0) delay(300L)
                    turnState.sendMessageJob?.takeIf { it.isActive }?.cancel(CancellationException("New message batch started, cancelling stale batch"))
                    try {
                        doSendMessage(subBatch)
                    } catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        ChatDebugLog.log("[ChatGeneration] Child generation cancelled; queue consumer remains active: ${cancelled.message}")
                    } catch (e: Exception) {
                        SecureLog.e("ChatGenerationManager", "doSendMessage failed", e)
                        _events.tryEmit(ChatUiEvent.Error("消息发送失败: ${e.message?.take(50) ?: "未知错误"}"))
                    }
                }
                batch.clear()
            }
        }
    }

    private suspend fun doSendMessage(batch: List<String>) {
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
            return
        }

        if (apiConfigRepository.getActiveEnabledConfig() == null) {
            for (msg in contentBatch) {
                val inputCheck = runCatching { ContentFilter.checkInput(msg) }.getOrNull()
                if (inputCheck == null) {
                    _events.tryEmit(ChatUiEvent.Error("安全检查异常"))
                    return
                }
                if (inputCheck.isViolating) {
                    BanManager.recordViolation(application, inputCheck.level)
                    _events.tryEmit(ChatUiEvent.ContentBlocked("内容违规: ${inputCheck.reason}"))
                    return
                }
            }
            // 架构：配置类错误只 Toast，禁止写入消息库污染对话与 AI 上下文
            _events.tryEmit(ChatUiEvent.Error("请先配置API：我 → API设置 → 添加密钥"))
            return
        }

        val pipelineOk = try {
            withTimeoutOrNull(TimeoutBudgets.PIPELINE_EXECUTE_MS) {
                pipeline.execute(MessagePipeline.PipelineInput(rawText = content, companionId = companionId))
            }
        } catch (_: Exception) { null }

        if (pipelineOk != true) {
            _events.tryEmit(ChatUiEvent.ContentBlocked(pipeline.pipelineState.value.error ?: "内容可能违规"))
            return
        }

        turnState.reset()
        val fetchedHistory = contextResolver.getHistoryForAi(companionId)
            .filterNot { !it.isFromUser && it.content.replace("\u200B", "").isBlank() }
        val settings = chatDetailSettingsStore.getSettings(companionId)
        turnState.sendMessageJob = startAiResponse(
            history = fetchedHistory,
            stickerProbability = settings.stickerProbability,
            userContentForMemory = content,
            batchMessageCount = contentBatch.size,
            ntpTimeEnabled = settings.ntpTimeEnabled
        )

        try {
            turnState.sendMessageJob?.join()
        } catch (e: CancellationException) {
            ChatDebugLog.log("[ChatGeneration] AI job cancelled: ${e.message}")
            return
        }
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
        // 新一轮请求开始时清理上一轮思考气泡，避免串轮
        _reasoningText.value = ""
        _isReasoning.value = false
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

            val aiResponse = if (imagePath != null) {
                withTimeoutOrNull(TimeoutBudgets.CHAT_VM_VISION_TIMEOUT_MS) {
                    aiService.sendMessageWithImage(companion.toAiCompanionInfo(), modelHistory, imagePath, stickerProbability, ntpTimeEnabled)
                } ?: throw Exception(application.getString(R.string.api_error_generic))
            } else if (isLocalModelEnabled()) {
                AiResponse(content = runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS) {
                    // 本地模型同样只消费清洗后的历史，避免运营错误/工具污染导致自言自语
                    generateWithLocalModel(companion, modelHistory, stickerProbability, ntpTimeEnabled)
                } ?: throw java.util.concurrent.TimeoutException("Local model timeout"))
            } else {
                val tools = if (shouldEnableToolsFor(content = userContentForMemory, history = history)) {
                    ToolRegistry.all()
                } else {
                    emptyList()
                }
                runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS * 3) {
                    toolLoopRunner.executeWithToolLoop(companion.toAiCompanionInfo(), modelHistory, stickerProbability, ntpTimeEnabled, tools)
                } ?: throw java.util.concurrent.TimeoutException("AI response timeout")
            }

            val aiContent = aiResponse.content
            // 运营错误 / [TOAST] 协议：只 Toast，绝不入库
            val toastMsg = com.lianyu.ai.domain.AiOperationalMessages.asToastMessage(aiContent)
            if (toastMsg != null) {
                _events.tryEmit(ChatUiEvent.Error(toastMsg))
                return@launch
            }
            if (aiContent.isBlank() && aiResponse.toolCalls.isNullOrEmpty()) {
                _events.tryEmit(ChatUiEvent.Error("API返回空内容，请检查模型名是否正确"))
                return@launch
            }

            // 1) 分段投递期间保持 loading/typing（模拟真人连发）
            // 2) 最后一条消息可见后立刻 exitLoading，避免记忆/追问把「对方正在输入」拖住
            val delivered = responseFinalizer.deliverResponse(
                aiContent = aiContent,
                reasoning = aiResponse.reasoningContent,
                userContentForMemory = userContentForMemory,
                logMessage = if (batchMessageCount > 1) "AI batch response received (${batchMessageCount} msgs)" else "AI response received"
            )
            exitLoading()
            loadingReleased = true
            responseFinalizer.afterDeliver(delivered)
            SecureLog.d("ChatGenerationManager", "AI request completed in ${System.currentTimeMillis() - requestStartedAt}ms, chars=${aiContent.length}")
        } catch (e: CancellationException) {
            val cancelReason = e.message ?: ""
            if (!cancelReason.contains("batch started") && !cancelReason.contains("stale")) {
                _events.tryEmit(ChatUiEvent.Error("回复被打断，请重试"))
            }
            throw e
        } catch (e: Exception) {
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
        return ChatToolIntent.shouldEnableTools(content, history.lastOrNull { it.isFromUser }?.content)
    }

    private fun broadcastWeChatMessage(messageId: Long, finalContent: String? = null) {
        WeChatBroadcastHelper.broadcast(application, companionId, messageId, finalContent)
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
        val dialogueContext = sortedHistory
            .takeLast(12)
            .joinToString("\n") { msg ->
                val speaker = if (msg.isFromUser) "用户" else companion.name
                "$speaker：${msg.content.take(200)}"
            }
            .take(1200)
        val systemPrompt = buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, role))
            companion.personality?.take(300)?.takeIf { it.isNotBlank() }?.let { appendLine("性格：$it") }
            companion.speakingStyle?.take(100)?.takeIf { it.isNotBlank() }?.let { appendLine("说话风格：$it") }
            companion.backstory?.take(200)?.takeIf { it.isNotBlank() }?.let { appendLine("背景：$it") }
            if (memoryContext.isNotBlank()) appendLine("\n关于用户的记忆：$memoryContext")
            if (dialogueContext.isNotBlank()) {
                appendLine("\n最近对话（仅供参考，不要复读系统错误或自言自语）：")
                appendLine(dialogueContext)
            }
            appendLine()
            appendLine("回复规则：")
            appendLine("1. 每次回复1-5句短话，控制在15-50字。")
            appendLine("2. 活人语气，自然口语化，不要AI腔。")
            appendLine("3. 每句话用标点结尾（。！？～…）。")
            appendLine("4. 不要重复同样的话。")
            appendLine("5. 先回应用户的消息，不要自说自话。")
            if (innerThoughtEnabled) appendLine("6. 每轮回复包含括号内的心理活动，如（脸红）（开心），放在回复开头或中间。") else appendLine("6. 禁止使用任何括号。禁止说教。")
            RolePromptProvider.getLocalModelRoleLines(role).forEachIndexed { index, line -> appendLine("${7 + index}. $line") }
            if (stickerProbability > 0) appendLine("9. 表情包：可按语境偶尔使用[名称]格式。")
            appendLine("\n记住：你不是AI，你是活人。直接回复内容，不要输出思考过程。")
            appendLine()
            appendLine(com.lianyu.ai.network.AiContextTools.buildCurrentTimeContext(ntpTimeEnabled))
        }
        val localProvider = ServiceRegistry.get(LocalModelProvider::class.java)
            ?: throw Exception(application.getString(R.string.api_error_generic))
        return localProvider.generateResponse(prompt = lastUserMessage.take(2000), context = systemPrompt)
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