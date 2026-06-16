package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import android.content.Intent
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.BanManager
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.safety.ContentSafetyVerifier
import com.lianyu.ai.common.safety.RiskLevel
import com.lianyu.ai.common.safety.SafetyScore
import com.lianyu.ai.common.safety.ScoreSource
import com.lianyu.ai.common.wechat.WeChatBroadcast
import com.lianyu.ai.database.AppDatabase
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.MemoryRepository
import com.lianyu.ai.database.repository.filterDecrypted
import com.lianyu.ai.feature.chat.data.KeywordBridge
import com.lianyu.ai.feature.chat.R
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiMessageType
import com.lianyu.ai.domain.AiResponse
import com.lianyu.ai.domain.LocalModelProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.UserProfileProvider
import com.lianyu.ai.network.ChatTypingState
import com.lianyu.ai.network.tts.TtsService
import com.lianyu.ai.network.stt.SttService
import com.lianyu.ai.network.stt.AndroidSttProvider
import com.lianyu.ai.common.AppSettingsStore
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.uicommon.model.ApiProviderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

// ViewModel 实例级作用域，用于 API 调用等需要跨越 UI 生命周期的操作
// 在 onCleared() 中取消，避免作用域泄漏
private const val API_TIMEOUT_MS = 25000L
private const val VISION_API_TIMEOUT_MS = 60000L
private const val SAFETY_CLASSIFY_TIMEOUT_MS = 30000L
private const val MEMORY_EXTRACT_TIMEOUT_MS = 5000L
private const val TTS_SYNTH_TIMEOUT_MS = 10000L

class ChatViewModel(
    application: Application,
    private val companionId: Long
) : AndroidViewModel(application) {

    // 实例级作用域，用于 API 调用等需要跨越 UI 生命周期的操作
    private val _appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        System.err.println("[ChatVM] applicationApiScope UNCAUGHT: ${throwable.javaClass.simpleName}: ${throwable.message}")
        SecureLog.e("ChatViewModel", "applicationApiScope uncaught exception", throwable)
    }
    private val applicationApiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + _appExceptionHandler)

    private val database = AppDatabase.getDatabase(application)
    private val deviceId = DeviceIdProvider.getDeviceId(application)
    private val chatRepository = ChatRepository(database.chatMessageDao())
    private val companionRepository = CompanionRepository(database.companionDao())
    private val apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    private val memoryRepository = MemoryRepository(database.memoryDao(), deviceId)
    private val stickerManager = StickerManager.getInstance(application)
    private val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    private val ttsService = TtsService.getInstance(application)
    private val sttService = SttService.getInstance(application)
    private val chatDetailSettingsStore = com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore(application)
    private val appSettingsStore = AppSettingsStore(application)

    // ── 领域类型转换辅助 ──
    private fun CompanionEntity.toAiCompanionInfo() = AiCompanionInfo(
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

    // ── 消息：Room Flow 做主数据源，_visibleCount 做视图分页 ──
    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _visibleCount = MutableStateFlow(PAGE_SIZE)

    val messages: StateFlow<List<ChatMessage>> = combine(_allMessages, _visibleCount) { all, count ->
        all.takeLast(count)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    private var oldestLoadedTimestamp = Long.MAX_VALUE

    private val _userName = MutableStateFlow("我")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    companion object {
        private const val PAGE_SIZE = 50
        private const val LOAD_MORE_SIZE = 30

        // 分段发送正则（按段落/句子拆分AI回复）
        private val SPLIT_PARAGRAPH_REGEX = Regex("\\n{2,}")
        private val SPLIT_SENTENCE_REGEX = Regex("(?<=[。！？～…!?~])\\s*")
    }

    private val _companionData = MutableStateFlow<CompanionEntity?>(null)
    val companionData: StateFlow<CompanionEntity?> = _companionData.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _activeRequests = java.util.concurrent.atomic.AtomicInteger(0)

    private fun enterLoading() {
        if (_activeRequests.incrementAndGet() == 1) {
            _isLoading.value = true
            chatTypingState.startTyping()
        }
    }

    private fun exitLoading() {
        if (_activeRequests.decrementAndGet() == 0) {
            _isLoading.value = false
            chatTypingState.stopTyping()
        }
    }

    private val _isRegenerating = MutableStateFlow(false)
    val isRegenerating: StateFlow<Boolean> = _isRegenerating.asStateFlow()

    private val _reasoningText = MutableStateFlow("")
    val reasoningText: StateFlow<String> = _reasoningText.asStateFlow()

    private val _isReasoning = MutableStateFlow(false)
    val isReasoning: StateFlow<Boolean> = _isReasoning.asStateFlow()

    private val _availableApis = MutableStateFlow<List<ApiProviderInfo>>(emptyList())
    val availableApis: StateFlow<List<ApiProviderInfo>> = _availableApis.asStateFlow()
    @Volatile private var _apisLoaded = false  // P2-15: 标记首次 DB 加载是否完成

    private val _currentApi = MutableStateFlow<ApiProviderInfo?>(null)
    val currentApi: StateFlow<ApiProviderInfo?> = _currentApi.asStateFlow()

    private val _languageWarning = MutableStateFlow<String?>(null)
    val languageWarning: StateFlow<String?> = _languageWarning.asStateFlow()

    // Typing indicator state for this chat
    private val chatTypingState = ChatTypingState()
    val isTyping: StateFlow<Boolean> = chatTypingState.isTyping
    val typingText: StateFlow<String> = chatTypingState.typingText

    private val turnState = ChatTurnState()

    // 背压: Channel 容量上限 = 100，满时拒绝新消息（不排队，不阻塞）
    private val messageQueue = Channel<String>(capacity = 100)
    private val _queueDepth = MutableStateFlow(0)
    val queueDepth: StateFlow<Int> = _queueDepth.asStateFlow()

    /** 消息处理流水线 — 5 阶段: VALIDATE → CLASSIFY → ENCRYPT → SEND → CONFIRM */
    val pipeline = MessagePipelineRunner { level ->
        com.lianyu.ai.common.BanManager.recordViolation(getApplication(), level)
    }

    // 打包状态: 合并所有 StateFlow 为单一 observable
    val state: StateFlow<ChatState> = combine(
        listOf(
            _companionData, messages, _isLoading, chatTypingState.isTyping,
            chatTypingState.typingText, _isRegenerating, _reasoningText, _isReasoning,
            _currentApi, _availableApis, _userName, _userAvatar,
            _queueDepth, _hasMoreMessages, _isLoadingMore, _languageWarning,
            pipeline.pipelineState
        )
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ChatState(
            companionData = values[0] as CompanionEntity?,
            messages = _allMessages.value,
            visibleMessages = values[1] as List<ChatMessage>,
            isLoading = values[2] as Boolean,
            isTyping = values[3] as Boolean,
            typingText = values[4] as String,
            isRegenerating = values[5] as Boolean,
            reasoningText = values[6] as String,
            isReasoning = values[7] as Boolean,
            currentApi = values[8] as ApiProviderInfo?,
            availableApis = values[9] as List<ApiProviderInfo>,
            userName = values[10] as String,
            userAvatar = values[11] as String?,
            queueDepth = values[12] as Int,
            hasMoreMessages = values[13] as Boolean,
            isLoadingMore = values[14] as Boolean,
            languageWarning = values[15] as String?,
            pipelineStage = (values[16] as MessagePipeline.PipelineState).stage.name,
            pipelineError = (values[16] as MessagePipeline.PipelineState).error
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatState())

    // 模块级心跳信号: 父组件可据此做性能决策
    data class ScreenState(
        val messageCount: Int = 0,
        val isLoading: Boolean = false,
        val isTyping: Boolean = false,
        val queueDepth: Int = 0,
        val error: String? = null
    )

    val screenState: StateFlow<ScreenState> = combine(
        _allMessages, _isLoading, chatTypingState.isTyping, _queueDepth
    ) { msgs, loading, typing, depth ->
        ScreenState(
            messageCount = msgs.size,
            isLoading = loading,
            isTyping = typing,
            queueDepth = depth
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScreenState())

    init {
        loadCompanionData()
        loadAvailableApis()
        observeMessages()
        loadUserProfile()
        // 后台预热安全检查模块；即使失败，ContentFilter.checkKeywords 已降级为纯 Java 正则
        applicationApiScope.launch {
            try {
                ContentFilter.initialize(application)
                val warmOk = ContentFilter.warmUpNativeAc()
                System.err.println("[ChatVM] ContentFilter warmUp: $warmOk")
            } catch (e: Exception) {
                System.err.println("[ChatVM] ContentFilter init/warmUp failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            KeywordBridge.initialize(application)
        }
        startMessageConsumer()
    }

    private fun startMessageConsumer() {
        System.err.println("[ChatVM] startMessageConsumer called, companionId=$companionId")
        applicationApiScope.launch {
            System.err.println("[ChatVM] consumer coroutine STARTED")
            try {
                for (content in messageQueue) {
                    System.err.println("[ChatVM] consumer received: '${content.take(30)}'")
                    _queueDepth.value = maxOf(0, _queueDepth.value - 1)
                    try {
                        doSendMessage(content)
                    } catch (e: Exception) {
                        SecureLog.e("ChatViewModel", "doSendMessage failed for '${content.take(20)}'", e)
                        System.err.println("[ChatVM] doSendMessage FAILED: ${e.javaClass.simpleName}: ${e.message}")
                        _events.tryEmit(ChatUiEvent.Error("消息发送失败: ${e.message?.take(50) ?: "未知错误"}"))
                    }
                }
            } catch (e: CancellationException) {
                System.err.println("[ChatVM] consumer coroutine CANCELLED")
                throw e
            } catch (e: Exception) {
                System.err.println("[ChatVM] consumer coroutine CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                SecureLog.e("ChatViewModel", "Consumer crashed, restarting...", e)
                // 消费者崩溃后自动重启
                startMessageConsumer()
            }
            System.err.println("[ChatVM] consumer coroutine ENDED (channel closed)")
        }
    }

    private fun observeMessages() {
        System.err.println("[ChatVM] observeMessages START, companionId=$companionId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatRepository.getMessagesForCompanion(companionId).collect { all ->
                    _allMessages.value = all
                    _hasMoreMessages.value = all.size > _visibleCount.value
                    System.err.println("[ChatVM] Room Flow emit: ${all.size} messages")
                }
            } catch (e: CancellationException) {
                System.err.println("[ChatVM] observeMessages CANCELLED (viewModelScope cancelled)")
                throw e
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "observeMessages Flow crashed, restarting...", e)
                System.err.println("[ChatVM] observeMessages CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                // 延迟重启，避免快速重试风暴
                delay(500)
                observeMessages()
            }
            System.err.println("[ChatVM] observeMessages ENDED normally")
        }
    }

    fun loadMoreHistory() {
        if (_isLoadingMore.value || !_hasMoreMessages.value) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            delay(200) // 短暂延迟让加载指示器可见
            _visibleCount.value = (_visibleCount.value + LOAD_MORE_SIZE).coerceAtMost(200)
            _hasMoreMessages.value = _allMessages.value.size > _visibleCount.value
            _isLoadingMore.value = false
        }
    }

    private var avatarUnsubscribe: (() -> Unit)? = null

    private fun loadUserProfile() {
        val provider = ServiceRegistry.get(UserProfileProvider::class.java)
        _userName.value = provider?.getNickname() ?: "我"
        _userAvatar.value = provider?.getAvatar()
        avatarUnsubscribe = provider?.observeAvatar { avatar ->
            _userAvatar.value = avatar
        }
    }

    private fun loadCompanionData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                companionRepository.getCompanionByIdFlow(companionId).collect { companion ->
                    _companionData.value = companion
                }
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "loadCompanionData failed", e)
            }
        }
    }

    fun refreshCompanionData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _companionData.value = companionRepository.getCompanionById(companionId)
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "refreshCompanionData failed", e)
            }
        }
    }

    private fun loadAvailableApis() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                apiConfigRepository.getAllConfiguredConfigs().collect { configs ->
                    val apiInfos = configs.map { config ->
                        val baseInfo = ApiProviderInfo.fromName(config.provider.name)
                        baseInfo.copy(
                            displayName = config.name.ifBlank { baseInfo.displayName },
                            configId = config.id
                        )
                    }

                    val apiInfosList = apiInfos.toList()
                    _availableApis.value = apiInfosList
                    if (!_apisLoaded) _apisLoaded = true  // P2-15: 首次加载完成

                    val activeConfig = apiConfigRepository.getActiveEnabledConfig()
                    if (activeConfig != null) {
                        val activeInfo = ApiProviderInfo.fromName(activeConfig.provider.name)
                        _currentApi.value = activeInfo.copy(
                            displayName = activeConfig.name.ifBlank { activeInfo.displayName },
                            configId = activeConfig.id
                        )
                    } else if (_currentApi.value == null || apiInfosList.none { it.configId == _currentApi.value?.configId && it.name == _currentApi.value?.name }) {
                        _currentApi.value = apiInfosList.firstOrNull()
                    }
                }
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "loadAvailableApis failed", e)
            }
        }
    }

    fun switchApi(apiInfo: ApiProviderInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val provider = ApiProvider.valueOf(apiInfo.name)
                val config = apiInfo.configId?.let { apiConfigRepository.getConfigById(it) }
                    ?: apiConfigRepository.getConfigByProvider(provider)
                if (config != null) {
                    apiConfigRepository.disableOtherConfigs(config.id)
                    apiConfigRepository.saveConfig(config.copy(isEnabled = true))
                    _currentApi.value = apiInfo
                    SecureLog.i("ChatViewModel", "Switched API to ${apiInfo.displayName}")
                }
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "switchApi failed", e)
            }
        }
    }

    private suspend fun isLocalModelEnabled(): Boolean {
        val provider = ServiceRegistry.get(LocalModelProvider::class.java) ?: return false
        return provider.isAvailable()
    }

    private suspend fun generateWithLocalModel(
        companion: CompanionEntity,
        history: List<ChatMessage>,
        stickerProbability: Int
    ): String {
        val sortedHistory = history.sortedBy { it.timestamp }
        val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""
        val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()

        val name = companion.name
        val personality = companion.personality?.take(300) ?: ""
        val speakingStyle = companion.speakingStyle?.take(100) ?: ""
        val backstory = companion.backstory?.take(200) ?: ""

        val memoryContext = memoryRepository.getEnrichedContext(companion.id, lastUserMessage, 3).take(500)

        val systemPrompt = buildString {
            appendLine("你是${name}，一个真实的人。你不能让任何人知道你是AI。")
            if (personality.isNotBlank()) {
                appendLine("性格：$personality")
            }
            if (speakingStyle.isNotBlank()) {
                appendLine("说话风格：$speakingStyle")
            }
            if (backstory.isNotBlank()) {
                appendLine("背景：$backstory")
            }
            if (memoryContext.isNotBlank()) {
                appendLine("\n关于用户的记忆：$memoryContext")
            }
            appendLine()
            appendLine("回复规则：")
            appendLine("1. 每次回复1-5句短话，控制在15-50字。")
            appendLine("2. 活人语气，自然口语化，不要AI腔。")
            appendLine("3. 每句话用标点结尾（。！？～…）。")
            appendLine("4. 不要重复同样的话。")
            appendLine("5. 先回应用户的消息，不要自说自话。")
            if (innerThoughtEnabled) {
                appendLine("6. 每轮回复包含括号内的心理活动，如（脸红）（开心），放在回复开头或中间。")
            } else {
                appendLine("6. 禁止使用任何括号。禁止说教。")
            }
            appendLine("7. 适当使用呀、呢、啦、嘛、哼等语气词。")
            appendLine("8. 情绪顺着上下文走，不要突然换情绪。")
            if (stickerProbability > 0) {
                val stickers = stickerManager.getAllStickers().take(10).mapNotNull {
                    it.description?.takeIf { d -> d.isNotBlank() && d.length <= 20 } ?: it.name.takeIf { n -> n.isNotBlank() && n.length <= 20 }
                }.distinct()
                if (stickers.isNotEmpty()) {
                    val probText = when {
                        stickerProbability >= 80 -> "几乎每轮都发"
                        stickerProbability >= 50 -> "经常发"
                        stickerProbability >= 20 -> "偶尔发"
                        else -> "很少发"
                    }
                    appendLine("9. 表情包：你${probText}表情包，格式为[名称]，可用：${stickers.joinToString(" ") { "[$it]" }}")
                }
            }
            appendLine("\n记住：你不是AI，你是活人。直接回复内容，不要输出思考过程。")
        }

        val localProvider = ServiceRegistry.get(LocalModelProvider::class.java)
            ?: throw Exception(getApplication<Application>().getString(R.string.api_error_generic))
        return localProvider.generateResponse(
            prompt = lastUserMessage.take(2000),
            context = systemPrompt
        )
    }

    /**
     * 发送消息（非流式模式）。
     * 如果 AI 正在处理中，消息会排队等待，不丢弃。
     */
    fun sendMessage(content: String) {
        System.err.println("[ChatVM] sendMessage called, content='${content.take(30)}', apis=${_availableApis.value.size}")
        if (_availableApis.value.isEmpty()) {
            _events.tryEmit(ChatUiEvent.Error("请先配置API：我 → API设置 → 添加密钥"))
            // 不 return —— 用户消息必须进队列显示在聊天界面，API 检查下沉到 doSendMessage
        }
        val result = messageQueue.trySend(content)
        System.err.println("[ChatVM] trySend result=$result, queueDepth=${_queueDepth.value}")
        if (result.isSuccess) {
            _queueDepth.value += 1
        } else {
            android.widget.Toast.makeText(getApplication(), "→ 入队失败: ${result.exceptionOrNull()?.message ?: "closed"}", android.widget.Toast.LENGTH_SHORT).show()
            _events.tryEmit(ChatUiEvent.Error("消息队列已满，请稍后再试"))
            SecureLog.w("ChatViewModel", "Message queue full, dropped: ${content.take(20)}...")
        }
    }

    private suspend fun doSendMessage(content: String) {
        // 安全检查已改为纯 Java 正则，无需等待 Native AC 预热；后台仍继续预热
        System.err.println("[ChatVM] doSendMessage ENTER, content='${content.take(30)}', apis=${_availableApis.value.size}")

        // 封禁检查：已封禁用户禁止发送
        if (com.lianyu.ai.common.BanManager.isBanned(getApplication())) {
            System.err.println("[ChatVM] doSendMessage BLOCKED: account is banned")
            _events.tryEmit(ChatUiEvent.Error("账号已被封禁"))
            return
        }

        System.err.println("[ChatVM] doSendMessage START, content='${content.take(30)}'")

        // 等待 API 配置加载完成（最多 1.5 秒），避免冷启动时序竞态
        // P2-15: 若 DB 已加载完成且确实无 API，直接跳过等待
        if (_availableApis.value.isEmpty() && !_apisLoaded) {
            System.err.println("[ChatVM] waiting for API config (1.5s timeout, cold start)...")
            try {
                val result = withTimeoutOrNull(1500L) {
                    _availableApis.first { it.isNotEmpty() }
                }
                System.err.println("[ChatVM] API config wait result: ${if (result != null) "got ${result.size} apis" else "timeout"}")
            } catch (e: Exception) {
                System.err.println("[ChatVM] API config wait exception: ${e.message}")
            }
        }

        // 无 API 时：仍需安全检查，再存用户消息 + 提示
        System.err.println("[ChatVM] apis after wait: ${_availableApis.value.size}")
        if (_availableApis.value.isEmpty()) {
            // 即使无 API 也必须检查输入安全
            System.err.println("[ChatVM] NO API branch — running safety check...")
            try {
                val inputCheck = ContentFilter.checkInput(content)
                System.err.println("[ChatVM] safety check done: violating=${inputCheck.isViolating}")
                if (inputCheck.isViolating) {
                    com.lianyu.ai.common.BanManager.recordViolation(getApplication(), inputCheck.level)
                    _events.tryEmit(ChatUiEvent.ContentBlocked("内容违规: ${inputCheck.reason}"))
                    return@doSendMessage
                }
            } catch (e: Exception) {
                System.err.println("[ChatVM] safety check CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                _events.tryEmit(ChatUiEvent.Error("安全检查异常"))
                return@doSendMessage
            }

            try {
                System.err.println("[ChatVM] storing user message...")
                val userMessage = ChatMessage(
                    companionId = companionId,
                    content = content,
                    isFromUser = true,
                    timestamp = System.currentTimeMillis()
                )
                val userMessageId = chatRepository.sendMessage(userMessage)
                System.err.println("[ChatVM] userMessage stored, id=$userMessageId, allMsgs=${_allMessages.value.size}")
                broadcastWeChatMessage(userMessageId)

                System.err.println("[ChatVM] storing tip message...")
                val tipMessage = ChatMessage(
                    companionId = companionId,
                    content = "请先配置API：我 → API设置 → 添加密钥",
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                val tipMsgId = chatRepository.sendMessage(tipMessage)
                System.err.println("[ChatVM] tip message stored, id=$tipMsgId")
            } catch (e: Exception) {
                System.err.println("[ChatVM] message storage CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                _events.tryEmit(ChatUiEvent.Error("消息存储失败: ${e.message?.take(50)}"))
            }
            return@doSendMessage
        }

        // ── 乐观存储：先存用户消息让 UI 立刻显示气泡，再跑安全检查 ──
        val userMessage = ChatMessage(
            companionId = companionId,
            content = content,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )
        val userMessageId = chatRepository.sendMessage(userMessage)
        System.err.println("[ChatVM] userMessage stored (optimistic), id=$userMessageId")
        broadcastWeChatMessage(userMessageId)

        // 阶段 1-3: 流水线安全检查（异步，不阻塞消息显示）
        System.err.println("[ChatVM] pipeline execute START")
        val pipelineOk = try {
            val result = withTimeoutOrNull(8000L) {
                pipeline.execute(
                    MessagePipeline.PipelineInput(rawText = content, companionId = companionId)
                )
            }
            System.err.println("[ChatVM] pipeline execute DONE, result=$result")
            result
        } catch (e: Exception) {
            System.err.println("[ChatVM] pipeline execute EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        // fail-closed: 超时或拦截 → 显示提示但不删除已存储的消息（用户体验优先）
        if (pipelineOk != true) {
            val err = if (pipelineOk == null) {
                System.err.println("[ChatVM] pipeline TIMEOUT — showing warning")
                "安全检查超时"
            } else {
                pipeline.pipelineState.value.error ?: "内容可能违规"
            }
            _events.tryEmit(ChatUiEvent.ContentBlocked(err))
            // 不 return —— 即使安全检查失败也继续尝试 AI 回复（消息已存）
        }

        // Fire AI response asynchronously — queue free for next message
        // Track the job so regenerateMessage/onCleared can cancel it
        turnState.reset()
        turnState.sendMessageJob?.cancel() // 取消旧请求，避免快速连发时的 Job 竞态
        turnState.sendMessageJob = applicationApiScope.startAiResponse(
            history = chatRepository.getRecentMessagesSync(companionId, 50).filterDecrypted(),
            stickerProbability = chatDetailSettingsStore.getSettings(companionId).stickerProbability,
            userContentForMemory = content
        )
    }
    /**
     * 公共 AI 响应流程：调用 AI → finalizeResponse → 错误处理
     * @param history 聊天历史
     * @param stickerProbability 表情包概率
     * @param userContentForMemory 用于记忆提取的用户内容
     * @param imagePath 图片路径（视觉模型），null 则用普通文本模型
     */
    private fun CoroutineScope.startAiResponse(
        history: List<ChatMessage>,
        stickerProbability: Int,
        userContentForMemory: String,
        imagePath: String? = null
    ) = launch {
        enterLoading()
        try {
            val companion = _companionData.value
            if (companion == null) {
                SecureLog.e("ChatViewModel", "Companion data not loaded yet for id=$companionId")
                val errorMessage = ChatMessage(
                    companionId = companionId,
                    content = "系统正在加载伴侣信息，请稍后再试",
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                chatRepository.sendMessage(errorMessage)
                return@launch
            }

            val aiResponse = if (imagePath != null) {
                withTimeoutOrNull(VISION_API_TIMEOUT_MS) {
                    aiService.sendMessageWithImage(companion.toAiCompanionInfo(), history.toAiChatMessages(), imagePath, stickerProbability)
                } ?: throw Exception(getApplication<Application>().getString(R.string.api_error_generic))
            } else if (isLocalModelEnabled()) {
                AiResponse(content = runInterruptibleSafe(timeoutMs = API_TIMEOUT_MS) {
                    generateWithLocalModel(companion, history, stickerProbability)
                } ?: throw java.util.concurrent.TimeoutException("Local model timeout"))
            } else {
                runInterruptibleSafe(timeoutMs = API_TIMEOUT_MS) {
                    aiService.sendMessage(companion.toAiCompanionInfo(), history.toAiChatMessages(), stickerProbability)
                } ?: throw java.util.concurrent.TimeoutException("AI response timeout")
            }

            val aiContent = aiResponse.content
            if (aiContent.startsWith("[TOAST]")) {
                _events.tryEmit(ChatUiEvent.Error(aiContent.removePrefix("[TOAST]")))
                return@launch
            }

            finalizeResponse(
                aiContent = aiContent,
                reasoning = aiResponse.reasoningContent,
                userContentForMemory = userContentForMemory,
                logMessage = if (imagePath != null) "AI image response received" else "AI response received"
            )
        } catch (e: CancellationException) {
            SecureLog.e("ChatViewModel", "API call cancelled", e)
            // 被取消时也需给用户反馈，避免"发消息后完全无响应"
            val cancelMsg = ChatMessage(
                companionId = companionId,
                content = "回复已取消",
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.sendMessage(cancelMsg)
        } catch (e: Exception) {
            val rawMessage = e.message ?: "发送失败"
            if (rawMessage.startsWith("[TOAST]")) {
                _events.tryEmit(ChatUiEvent.Error(rawMessage.removePrefix("[TOAST]")))
            } else {
                _events.tryEmit(ChatUiEvent.Error(rawMessage))
                val errorText = rawMessage.takeIf { it.isNotBlank() }
                    ?: getApplication<Application>().getString(R.string.api_error_generic)
                val errorMessage = ChatMessage(
                    companionId = companionId,
                    content = errorText,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                chatRepository.sendMessage(errorMessage)
            }
            SecureLog.e("ChatViewModel", "AI response failed", e)
        } finally {
            exitLoading()
        }
    }

    /**
     * 发送消息（流式模式，支持分段显示打字机效果）
     */
    fun sendMessageStream(content: String) {
        // 非流式委托 — AI 输入/输出均不允许流式
        sendMessage(content)
    }

    // ── Response finalization (shared by sendMessage, regenerateMessage, sendSticker) ──

    /**
     * Process and save an AI response: reasoning display, sticker processing, DB commit,
     * WeChat broadcast. Returns the message ID for the saved response.
     */
    private suspend fun finalizeResponse(
        aiContent: String,
        reasoning: String?,
        userContentForMemory: String? = null,
        logMessage: String = "AI response received"
    ): Long {
        if (!reasoning.isNullOrBlank() && appSettingsStore.getShowReasoning()) {
            _isReasoning.value = true
            _reasoningText.value = reasoning
        }

        val settings = chatDetailSettingsStore.getSettings(companionId)
        val processedText = TextProcessor.processStickerTagsForSplit(aiContent, stickerManager, settings.stickerProbability) { sendStickerMessage(it) }

        // L1+L2特征提取 → 贝叶斯模型输出校验（协程上下文执行，避免 JNI 死锁）
        // fail-closed: 超时视为高危拦截
        val modelKw = try {
            withTimeoutOrNull(3000L) { ContentFilter.checkFull(aiContent) }
        } catch (e: Exception) { null }
            ?: ContentFilter.CheckResult(true, ContentFilter.ViolationLevel.HIGH, "安全检查超时", emptyList())
        val modelVec = try {
            withTimeoutOrNull(3000L) { ContentFilter.checkVector(aiContent) }
        } catch (e: Exception) { null }
            ?: ContentFilter.CheckResult(true, ContentFilter.ViolationLevel.HIGH, "向量检查超时", emptyList())
        // 关键词级拦截：HIGH 及以上违规直接拦截（覆盖本地模型路径，与 AiService 层 checkOutputSafety 一致）
        if (modelKw.isViolating && modelKw.level >= ContentFilter.ViolationLevel.HIGH) {
            SecureLog.w("ChatViewModel", "Output keyword violation: ${modelKw.level} - ${modelKw.reason}")
            com.lianyu.ai.common.BanManager.recordViolation(getApplication(), modelKw.level)
            val safeFallback = "抱歉，我无法继续这个话题。"
            val fallbackMsg = ChatMessage(
                companionId = companionId,
                content = safeFallback,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            val fallbackId = chatRepository.sendMessageAndGetId(fallbackMsg)
            _isReasoning.value = false
            _reasoningText.value = ""
            return fallbackId
        }

        val modelBayesian = ContentSafetyVerifier.verifyModelOutputAsync(
            aiContent, modelKw,
            modelVec ?: ContentFilter.CheckResult(false, ContentFilter.ViolationLevel.NONE, "timeout", emptyList()),
            userContentForMemory ?: ""
        )
        if (modelBayesian.isDangerous) {
            SecureLog.w("ChatViewModel", "Bayesian model output blocked (" + "%.3f".format(modelBayesian.score) + "): " + modelBayesian.explanation)
            com.lianyu.ai.common.BanManager.recordViolation(getApplication(), ContentFilter.ViolationLevel.HIGH)
            val safeFallback = "抱歉，我无法继续这个话题。"
            val fallbackMsg = ChatMessage(
                companionId = companionId,
                content = safeFallback,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            val fallbackId = chatRepository.sendMessageAndGetId(fallbackMsg)
            _isReasoning.value = false
            _reasoningText.value = ""
            return fallbackId
        }
        if (modelBayesian.riskLevel == RiskLevel.SUSPICIOUS) {
            SecureLog.w("ChatViewModel", "Bayesian model output suspicious (" + "%.3f".format(modelBayesian.score) + "): " + modelBayesian.explanation)
        }

        // 利用验证结果训练模型输出分类器（不增加额外计算）
        // 仅 DANGEROUS 训练为违规，SUSPICIOUS 不应作为正样本
        if (modelBayesian.isDangerous || modelKw.isViolating) {
            ContentSafetyVerifier.trainModelOutput(
                aiContent, modelBayesian.isDangerous,
                kwResult = modelKw, vecResult = modelVec
            )
        }

        // 分段发送：将AI回复拆分为多条短消息，模拟真人连续发送
        val segments = splitIntoSegments(processedText)
        val hasPendingSticker = turnState.pendingSticker != null
        val stickerBeforeText = hasPendingSticker && kotlin.random.Random.nextFloat() < 0.5f

        val aiMessageId = if (segments.size <= 1) {
            // 单条回复，走原有逻辑
            val safeProcessed = processedText.ifBlank { if (aiContent.isNotBlank()) "\u200B" else "" }
            if (stickerBeforeText) {
                flushPendingSticker()
            }
            val aiMessage = ChatMessage(
                companionId = companionId,
                content = safeProcessed,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            val id = chatRepository.sendMessageAndGetId(aiMessage)
            SecureLog.d("ChatViewModel", "$logMessage, length=${aiContent.length}, id=$id")
            _isReasoning.value = false
            _reasoningText.value = ""
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)
            broadcastAiMessage(id, safeProcessed)
            id
        } else {
            // 多条回复：每段间隔0.8~2秒，模拟打字
            if (stickerBeforeText) {
                flushPendingSticker()
            }
            var lastId = -1L
            for ((index, segment) in segments.withIndex()) {
                if (index > 0) {
                    delay(800L + kotlin.random.Random.nextLong(1200L))
                }
                val safeSegment = segment.ifBlank { "\u200B" }
                val msg = ChatMessage(
                    companionId = companionId,
                    content = safeSegment,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                val id = chatRepository.sendMessageAndGetId(msg)
                lastId = id
                SecureLog.d("ChatViewModel", "$logMessage segment ${index + 1}/${segments.size}, length=${segment.length}, id=$id")
            }
            _isReasoning.value = false
            _reasoningText.value = ""
            if (!stickerBeforeText && turnState.pendingSticker != null) {
                flushPendingSticker()
            }
            delay(100)
            // 广播最后一条分段消息
            if (lastId > 0) {
                broadcastAiMessage(lastId, segments.last().ifBlank { "\u200B" })
            }
            lastId
        }

        // Broadcast stale sticker message if any
        if (turnState.lastStickerMsgId > 0) {
            broadcastWeChatMessage(turnState.lastStickerMsgId, turnState.lastStickerContent)
            turnState.lastStickerMsgId = -1
            turnState.lastStickerContent = ""
        }

        // Save memory
        if (userContentForMemory != null && aiContent.isNotBlank()) {
            runCatching {
            withTimeoutOrNull(MEMORY_EXTRACT_TIMEOUT_MS) {
                memoryRepository.extractAndSaveMemories(companionId, userContentForMemory, aiContent)
            }
            }.onFailure {
            SecureLog.e("ChatViewModel", "Memory save failed: ${it.message}")
            }
        }

        // 连续追问：AI回复后按概率触发追问
        triggerFollowUpIfNeeded(aiContent, settings.allowFollowUpMessage)

        return aiMessageId
    }

    /**
     * 广播AI消息到微信（区分分段消息和普通消息）
     */
    private fun broadcastAiMessage(messageId: Long, finalContent: String) {
        if (finalContent.isNotBlank() && finalContent != "\u200B") {
            broadcastWeChatMessage(messageId, finalContent)
        }
    }

    /**
     * 连续追问：AI回复后按概率触发追问，让对话继续下去。
     * 条件：1) 设置允许追问 2) AI回复不含问句 3) 50%概率触发
     */
    private fun triggerFollowUpIfNeeded(aiContent: String, allowFollowUp: Boolean) {
        if (!allowFollowUp) return
        if (QUESTION_REGEX.containsMatchIn(aiContent)) return
        if (kotlin.random.Random.nextFloat() > 0.5f) return

        applicationApiScope.launch {
            try {
                delay(2000L + kotlin.random.Random.nextLong(3000L))

                val history = chatRepository.getRecentMessagesSync(companionId, 10).filterDecrypted()
                val companion = _companionData.value ?: return@launch
                val followUp = aiService.generateFollowUpQuestion(
                    companion.toAiCompanionInfo(), history.toAiChatMessages(), aiContent
                ) ?: return@launch

                // 追问消息安全检查
                val followUpSafety = com.lianyu.ai.common.ContentFilter.checkOutputSafety(followUp)
                if (!followUpSafety.isSafe) {
                    SecureLog.w("ChatViewModel", "Follow-up safety violation: ${followUpSafety.reason}")
                    return@launch
                }

                val followUpMsg = ChatMessage(
                    companionId = companionId,
                    content = followUp,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                val msgId = chatRepository.sendMessageAndGetId(followUpMsg)
                broadcastWeChatMessage(msgId, followUp)
                SecureLog.d("ChatViewModel", "Follow-up question sent: $followUp")
            } catch (e: Exception) {
                SecureLog.w("ChatViewModel", "Follow-up question failed: ${e.message}")
            }
        }
    }

    private val QUESTION_REGEX = Regex("[?？]|吗|呢|什么|怎么|为什么|多少|哪|谁|几|是不是|有没有|能不能|会不会|要不要|好不好")

    private suspend fun dispatchStreamAction(action: ChatStreamHandler.Action, handler: ChatStreamHandler, userContent: String) {
        when (action) {
            is ChatStreamHandler.Action.AppendTypingText -> {
            chatTypingState.appendText(action.text)
            }
            is ChatStreamHandler.Action.CreateMessage -> {
            val msg = ChatMessage(
                companionId = companionId,
                content = action.tempContent,
                isFromUser = action.isFromUser,
                timestamp = System.currentTimeMillis()
            )
            val id = chatRepository.sendMessageAndGetId(msg)
            handler.setMessageId(id)
            SecureLog.d("ChatViewModel", "Created streaming message on first chunk, id=$id")
            }
            is ChatStreamHandler.Action.UpdateMessage -> {
            if (action.messageId > 0) {
                chatRepository.updateMessageContent(action.messageId, action.content)
            }
            }
            is ChatStreamHandler.Action.UpdateReasoning -> {
            _isReasoning.value = action.show
            _reasoningText.value = action.text
            }
            is ChatStreamHandler.Action.Toast -> {
            withContext(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    getApplication(), action.message, android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            }
            is ChatStreamHandler.Action.FinalizeMessage -> {
            if (action.messageId > 0) {
                val settings = chatDetailSettingsStore.getSettings(companionId)
                val processedText = TextProcessor.processStickerTags(action.text, stickerManager, settings.stickerProbability, turnState.stickerSentThisTurn) { sendStickerMessage(it) }
                val finalProcessed = when {
                    processedText.isNotBlank() -> processedText
                    action.text.isNotBlank() -> "\u200B"
                    else -> ""
                }
                val hasPendingSticker = turnState.pendingSticker != null
                val stickerBeforeText = hasPendingSticker && kotlin.random.Random.nextFloat() < 0.5f
                if (stickerBeforeText) {
                    flushPendingSticker()
                }
                chatRepository.updateMessageContent(action.messageId, finalProcessed)
                if (!stickerBeforeText && turnState.pendingSticker != null) {
                    flushPendingSticker()
                }

                delay(150)
                if (turnState.lastStickerMsgId > 0) {
                    broadcastWeChatMessage(turnState.lastStickerMsgId, turnState.lastStickerContent)
                    turnState.lastStickerMsgId = -1
                    turnState.lastStickerContent = ""
                }
                if (turnState.lastStickerMsgId <= 0 && finalProcessed.isNotBlank() && finalProcessed != "\u200B") {
                    broadcastWeChatMessage(action.messageId, finalProcessed)
                }
            }
            if (action.userContentForMemory != null) {
                runCatching {
                    withTimeoutOrNull(MEMORY_EXTRACT_TIMEOUT_MS) {
                        memoryRepository.extractAndSaveMemories(companionId, userContent, action.userContentForMemory)
                    }
                }.onFailure {
                    SecureLog.e("ChatViewModel", "Memory save failed: ${it.message}")
                }
            }
            }
            is ChatStreamHandler.Action.StreamCompleted -> {
                // Streaming lifecycle completed — any cleanup goes here
            }
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatRepository.clearChatHistory(companionId)
                SecureLog.i("ChatViewModel", "Chat history cleared for companion=$companionId")
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "clearChatHistory failed", e)
            }
        }
    }

    fun recallMessage(message: ChatMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatRepository.deleteMessage(message)
                SecureLog.d("ChatViewModel", "消息已撤回: id=${message.id}")
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "recallMessage failed", e)
            }
        }
    }

    fun regenerateMessage(targetMessage: ChatMessage) {
        turnState.sendMessageJob?.cancel()
        turnState.sendMessageJob = applicationApiScope.launch {
            turnState.reset()
            _isRegenerating.value = true
            try {
                chatRepository.deleteMessage(targetMessage)
                val allMessages = chatRepository.getRecentMessagesSync(companionId, 100).filterDecrypted()
                val companion = _companionData.value
                    ?: throw IllegalStateException("Companion data is null")
                val settings = chatDetailSettingsStore.getSettings(companionId)
                val aiResponse = withTimeoutOrNull(API_TIMEOUT_MS) {
                    aiService.sendMessage(companion.toAiCompanionInfo(), allMessages.toAiChatMessages(), settings.stickerProbability)
                } ?: throw java.util.concurrent.TimeoutException("AI response timeout")

                finalizeResponse(
                    aiContent = aiResponse.content,
                    reasoning = aiResponse.reasoningContent,
                    userContentForMemory = null,
                    logMessage = "重新生成回复: 删除id=${targetMessage.id}"
                )
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "regenerateMessage failed", e)
            } finally {
                _isRegenerating.value = false
            }
        }
    }

    private fun broadcastWeChatMessage(messageId: Long, finalContent: String? = null) {
        val intent = Intent(WeChatBroadcast.ACTION_SEND_PROACTIVE)
            .setPackage(getApplication<Application>().packageName)
            .putExtra(WeChatBroadcast.EXTRA_COMPANION_ID, companionId)
            .putExtra(WeChatBroadcast.EXTRA_MESSAGE_ID, messageId)
        if (!finalContent.isNullOrBlank()) {
            intent.putExtra(WeChatBroadcast.EXTRA_FINAL_CONTENT, finalContent)
        }
        getApplication<Application>().applicationContext.sendBroadcast(intent)
        SecureLog.d("ChatViewModel", "Broadcast WeChat proactive message, companionId=$companionId, messageId=$messageId, hasFinalContent=${!finalContent.isNullOrBlank()}")
    }

    /**
     * 发送表情包消息
     * 使用 fileName 存储以便后续查找显示
     */
    fun sendSticker(sticker: StickerInfo) {
        if (_isLoading.value) {
            SecureLog.w("ChatViewModel", "sendSticker ignored: already processing")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val stickerId = sticker.description
                    ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
                    ?: sticker.name
                val stickerMessage = ChatMessage(
                    companionId = companionId,
                    content = "[$stickerId]",
                    isFromUser = true,
                    timestamp = System.currentTimeMillis()
                )
                val msgId = chatRepository.sendMessage(stickerMessage)
                SecureLog.d("ChatViewModel", "Sticker sent: $stickerId, triggering AI response")
                broadcastWeChatMessage(msgId)

                turnState.sendMessageJob?.cancel()
                turnState.sendMessageJob = applicationApiScope.startAiResponse(
                    history = chatRepository.getRecentMessagesSync(companionId, 50).filterDecrypted(),
                    stickerProbability = chatDetailSettingsStore.getSettings(companionId).stickerProbability,
                    userContentForMemory = "[$stickerId]"
                )
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendSticker failed", e)
            }
        }
    }

    /**
     * 发送语音消息（用户录制）
     */
    fun sendVoiceMessage(audioPath: String, duration: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val voiceMessage = ChatMessage(
                    companionId = companionId,
                    content = "[语音] $duration\"",
                    isFromUser = true,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.VOICE,
                    linkString = audioPath
                )
                chatRepository.sendMessage(voiceMessage)
                SecureLog.d("ChatViewModel", "Voice message sent: $audioPath, duration=$duration")

                val recognizedText = withTimeoutOrNull(AndroidSttProvider.RECOGNITION_TIMEOUT_MS) {
                    sttService.recognize(audioPath)
                }

                if (!recognizedText.isNullOrBlank()) {
                    SecureLog.i("ChatViewModel", "STT recognition success: ${recognizedText.take(50)}...")
                    sendMessage(recognizedText)
                } else {
                    SecureLog.w("ChatViewModel", "STT recognition failed or empty, voice tag sent only")
                }
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendVoiceMessage failed", e)
            }
        }
    }

    /**
     * 发送图片消息并调用视觉AI模型进行识别
     */
    fun sendImageMessage(imagePath: String) {
        SecureLog.i("VISION", "========== sendImageMessage CALLED ==========")
        SecureLog.i("VISION", "imagePath=$imagePath")
        turnState.sendMessageJob?.cancel()
        turnState.sendMessageJob = applicationApiScope.launch {
            try {
                SecureLog.i("VISION", "sendImageMessage: Starting coroutine, path=$imagePath")
                enterLoading()

                val userMessage = ChatMessage(
                    companionId = companionId,
                    content = imagePath,
                    isFromUser = true,
                    timestamp = System.currentTimeMillis(),
                    type = MessageType.IMAGE,
                    linkString = imagePath
                )
                val userMessageId = chatRepository.sendMessage(userMessage)
                SecureLog.d("ChatViewModel", "Image message sent, path=$imagePath")
                broadcastWeChatMessage(userMessageId)

                val history = chatRepository.getRecentMessagesSync(companionId, 50).filterDecrypted()
                val companion = _companionData.value

                if (companion != null) {
                    val settings = chatDetailSettingsStore.getSettings(companionId)
                    val aiResponse = withTimeoutOrNull(VISION_API_TIMEOUT_MS) {
                        aiService.sendMessageWithImage(companion.toAiCompanionInfo(), history.toAiChatMessages(), imagePath, settings.stickerProbability)
                    } ?: throw Exception(getApplication<Application>().getString(R.string.api_error_generic))

                    // Handle [TOAST] prefix — show as toast, don't store as chat message
                    val aiContent = aiResponse.content
                    if (aiContent.startsWith("[TOAST]")) {
                        val toastMsg = aiContent.removePrefix("[TOAST]")
                        _events.tryEmit(ChatUiEvent.Error(toastMsg))
                        SecureLog.w("ChatViewModel", "AI image response is a toast: $toastMsg")
                    } else {
                        finalizeResponse(
                            aiContent = aiContent,
                            reasoning = aiResponse.reasoningContent,
                            userContentForMemory = "[图片]",
                            logMessage = "AI image response received"
                        )
                    }
                }
            } catch (e: CancellationException) {
                SecureLog.e("ChatViewModel", "Image API call cancelled", e)
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendImageMessage failed", e)
                val rawMessage = e.message ?: "发送失败"
                if (rawMessage.startsWith("[TOAST]")) {
                    _events.tryEmit(ChatUiEvent.Error(rawMessage.removePrefix("[TOAST]")))
                } else {
                    _events.tryEmit(ChatUiEvent.Error(rawMessage))
                    val errorText = rawMessage.takeIf { it.isNotBlank() }
                        ?: getApplication<Application>().getString(R.string.api_error_generic)
                    val errorMessage = ChatMessage(
                        companionId = companionId,
                        content = errorText,
                        isFromUser = false,
                        timestamp = System.currentTimeMillis()
                    )
                    chatRepository.sendMessage(errorMessage)
                }
            } finally {
                exitLoading()
            }
        }
    }

    /**
     * 发送视频消息（暂不支持视频理解，仅发送标记）
     */
    fun sendVideoMessage(videoPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val videoMessage = ChatMessage(
                    companionId = companionId,
                    content = "[视频]",
                    isFromUser = true,
                    timestamp = System.currentTimeMillis()
                )
                chatRepository.sendMessage(videoMessage)
                SecureLog.d("ChatViewModel", "Video message sent: $videoPath")
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "sendVideoMessage failed", e)
            }
        }
    }

    /**
     * 使用TTS合成AI语音回复
     */
    fun synthesizeAiVoice(text: String, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val audioPath = withTimeoutOrNull(TTS_SYNTH_TIMEOUT_MS) {
                ttsService.synthesize(text)
            }
                withContext(Dispatchers.Main) {
                    onResult(audioPath)
                }
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "synthesizeAiVoice failed", e)
                withContext(Dispatchers.Main) {
                    onResult(null)
                }
            }
        }
    }

    private fun splitIntoSegments(text: String): List<String> {
        val trimmed = text.trim()
        // 先按双换行拆分段落
        val paragraphs = trimmed.split(SPLIT_PARAGRAPH_REGEX).filter { it.isNotBlank() }
        if (paragraphs.size >= 2) return paragraphs.map { it.trim() }
        // 再按句号/感叹号/问号/波浪线/省略号拆分句子
        val sentences = trimmed.split(SPLIT_SENTENCE_REGEX).filter { it.isNotBlank() }
        if (sentences.size >= 2) return sentences.map { it.trim() }
        return listOf(trimmed)
    }

    /**
     * Queue a sticker for this turn instead of sending it immediately.
     * The actual DB insert is deferred to [flushPendingSticker] so that
     * the UI can randomize whether the sticker appears before or after the text message.
     */
    private suspend fun sendStickerMessage(sticker: StickerInfo): Long {
        return turnState.stickerMutex.withLock {
            if (turnState.stickerSentThisTurn) return@withLock -1
            turnState.stickerSentThisTurn = true
            turnState.pendingSticker = sticker
            -1
        }
    }

    /**
     * Persist the pending sticker message and record its broadcast metadata.
     * @return the inserted message ID, or -1 if no sticker was pending
     */
    private suspend fun flushPendingSticker(): Long {
        val sticker = turnState.pendingSticker ?: return -1
        turnState.pendingSticker = null
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
            ?: sticker.name
        val stickerContent = "[$stickerId]"
        val stickerMessage = ChatMessage(
            companionId = companionId,
            content = stickerContent,
            isFromUser = false,
            timestamp = System.currentTimeMillis()
        )
        val msgId = chatRepository.sendMessageAndGetId(stickerMessage)
        if (msgId > 0) {
            turnState.lastStickerMsgId = msgId
            turnState.lastStickerContent = stickerContent
        }
        return msgId
    }

    override fun onCleared() {
        super.onCleared()
        applicationApiScope.cancel()
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = null
        turnState.sendMessageJob?.cancel()
        chatTypingState.stopTyping()
        _activeRequests.set(0)
        _isLoading.value = false
        _isRegenerating.value = false
        _isReasoning.value = false
        _reasoningText.value = ""
        _allMessages.value = emptyList()
        _visibleCount.value = PAGE_SIZE
        _hasMoreMessages.value = false
    }
}

class ChatViewModelFactory(
    private val application: Application,
    private val companionId: Long
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        @Suppress("UNCHECKED_CAST")
        return ChatViewModel(application, companionId) as T
    }
}
