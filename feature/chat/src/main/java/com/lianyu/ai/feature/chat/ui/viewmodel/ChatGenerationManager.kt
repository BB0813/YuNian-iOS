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
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
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

        fun get(application: Application, companionId: Long): ChatGenerationManager =
            instances.getOrPut(companionId) { ChatGenerationManager(application, companionId) }
    }

    private val scope = ApplicationScopeProvider.scope
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        ChatDebugLog.log("[ChatGeneration] uncaught: ${throwable.javaClass.simpleName}: ${throwable.message}")
        SecureLog.e("ChatGenerationManager", "Uncaught generation exception", throwable)
    }
    private val database = AppDatabase.getDatabase(application)
    private val apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    private val chatRepository = ChatRepository(database.chatMessageDao())
    private val companionRepository = CompanionRepository(database.companionDao())
    private val contextResolver = ChatContextResolver(chatRepository)
    private val chatDetailSettingsStore = ChatDetailSettingsStore(application)
    private val appSettingsStore = AppSettingsStore(application)
    private val stickerManager = StickerManager.getInstance(application)
    private val memoryProvider: MemoryProvider by lazy {
        ServiceRegistry.getOrThrow(MemoryProvider::class.java).also { it.initialize() }
    }
    private val userRepository = ServiceRegistry.get(UserRepository::class.java)
    private val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    private val _chatTtsConfig = MutableStateFlow(ChatTtsConfig.fromSharedPreferences(application))
    val chatTtsConfig: StateFlow<ChatTtsConfig> = _chatTtsConfig.asStateFlow()
    @Volatile private var callActive = false
    private val ttsController = ChatTtsController(
        context = application.applicationContext,
        ttsService = TtsService.getInstance(application),
        scope = scope,
        configProvider = { _chatTtsConfig.value },
        callActiveProvider = { callActive }
    )
    val ttsState: StateFlow<ChatTtsState> = ttsController.state
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
    private val responseFinalizer = AiResponseFinalizer(
        companionId = companionId,
        chatRepository = chatRepository,
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
    }

    init {
        startMessageConsumer()
    }

    fun sendText(content: String) {
        startMessageConsumer()
        val userMessage = ChatMessage(
            companionId = companionId,
            content = content,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )
        scope.launch(Dispatchers.IO + exceptionHandler) {
            val userMessageId = chatRepository.sendMessage(userMessage)
            broadcastWeChatMessage(userMessageId)

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
            val userMessageId = chatRepository.sendMessage(
                ChatMessage(
                    companionId = companionId,
                    content = imagePath,
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
            chatRepository.sendMessage(ChatMessage(companionId = companionId, content = "请先配置API：我 → API设置 → 添加密钥", isFromUser = false, timestamp = System.currentTimeMillis()))
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
        enterLoading()
        try {
            val companion = companionRepository.getCompanionById(companionId)
            if (companion == null) {
                chatRepository.sendMessage(ChatMessage(companionId = companionId, content = "系统正在加载伴侣信息，请稍后再试", isFromUser = false, timestamp = System.currentTimeMillis()))
                return@launch
            }
            latestCompanionInfo = companion.toAiCompanionInfo()

            val aiResponse = if (imagePath != null) {
                withTimeoutOrNull(TimeoutBudgets.CHAT_VM_VISION_TIMEOUT_MS) {
                    aiService.sendMessageWithImage(companion.toAiCompanionInfo(), history.toAiChatMessages(), imagePath, stickerProbability, ntpTimeEnabled)
                } ?: throw Exception(application.getString(R.string.api_error_generic))
            } else if (isLocalModelEnabled()) {
                AiResponse(content = runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS) {
                    generateWithLocalModel(companion, history, stickerProbability, ntpTimeEnabled)
                } ?: throw java.util.concurrent.TimeoutException("Local model timeout"))
            } else {
                val tools = if (shouldEnableToolsFor(content = userContentForMemory, history = history)) {
                    ToolRegistry.all()
                } else {
                    emptyList()
                }
                runInterruptibleSafe(timeoutMs = TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS * 3) {
                    toolLoopRunner.executeWithToolLoop(companion.toAiCompanionInfo(), history.toAiChatMessages(), stickerProbability, ntpTimeEnabled, tools)
                } ?: throw java.util.concurrent.TimeoutException("AI response timeout")
            }

            val aiContent = aiResponse.content
            if (aiContent.startsWith("[TOAST]")) {
                _events.tryEmit(ChatUiEvent.Error(aiContent.removePrefix("[TOAST]")))
                return@launch
            }

            responseFinalizer.finalizeResponse(
                aiContent = aiContent,
                reasoning = aiResponse.reasoningContent,
                userContentForMemory = userContentForMemory,
                logMessage = if (batchMessageCount > 1) "AI batch response received (${batchMessageCount} msgs)" else "AI response received"
            )
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
            exitLoading()
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
        history: List<ChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean = false
    ): String {
        val sortedHistory = history.sortedBy { it.timestamp }
        val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""
        val memoryContext = memoryProvider.getMemoryContext(companion.id, null, lastUserMessage, 50).take(500)
        val role = userRepository?.selectedRole?.value ?: CompanionRole.GIRLFRIEND
        val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()
        val systemPrompt = buildString {
            appendLine(RolePromptProvider.getIdentityLine(companion.name, role))
            companion.personality?.take(300)?.takeIf { it.isNotBlank() }?.let { appendLine("性格：$it") }
            companion.speakingStyle?.take(100)?.takeIf { it.isNotBlank() }?.let { appendLine("说话风格：$it") }
            companion.backstory?.take(200)?.takeIf { it.isNotBlank() }?.let { appendLine("背景：$it") }
            if (memoryContext.isNotBlank()) appendLine("\n关于用户的记忆：$memoryContext")
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
        AiChatMessage(
            isFromUser = msg.isFromUser,
            content = msg.content,
            timestamp = msg.timestamp,
            type = if (msg.type == MessageType.IMAGE) AiMessageType.IMAGE else AiMessageType.TEXT,
            companionId = msg.companionId
        )
    }

}