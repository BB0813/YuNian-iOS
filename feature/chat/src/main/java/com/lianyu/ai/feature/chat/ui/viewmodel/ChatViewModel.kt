package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import android.content.Intent
import com.lianyu.ai.common.ApplicationScopeProvider
import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.CompanionRole
import com.lianyu.ai.common.ContentFilter
import com.lianyu.ai.common.BanManager
import com.lianyu.ai.common.DeviceIdProvider
import com.lianyu.ai.common.RolePromptProvider
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
import com.lianyu.ai.database.repository.UserRepository
import com.lianyu.ai.feature.chat.data.ChatContextResolver
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
import com.lianyu.ai.common.TimeoutBudgets
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
// [P0 FIX] 超时从25s降至15s：原25s导致消息队列严重堵塞，用户连续发消息时延迟指数增长
// 总链路 = pipeline(8s) + AI调用(15s) + 安全检查(6s) ≈ 29s（比原来39s改善26%）
// [M11 FIX] 超时常量集中到 TimeoutBudgets：原散落在文件顶层，修改需跨文件搜索。
// 保留本地常量作为别名引用 TimeoutBudgets，保持调用点可读性。
private const val API_TIMEOUT_MS = 30000L       // 与 OkHttp callTimeout 对齐（网络慢时 15s 不够）
private const val VISION_API_TIMEOUT_MS = 60000L   // 视觉识别需要编码+传输，保留60s
private const val SAFETY_CLASSIFY_TIMEOUT_MS = 30000L
private const val MEMORY_EXTRACT_TIMEOUT_MS = 5000L
private const val TTS_SYNTH_TIMEOUT_MS = 10000L

class ChatViewModel(
    application: Application,
    private val companionId: Long
) : AndroidViewModel(application) {

    // 实例级作用域，用于 API 调用等需要跨越 UI 生命周期的操作
    private val _appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        ChatDebugLog.log("[ChatVM] applicationApiScope UNCAUGHT: ${throwable.javaClass.simpleName}: ${throwable.message}")
        SecureLog.e("ChatViewModel", "applicationApiScope uncaught exception", throwable)
    }
    private val applicationApiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + _appExceptionHandler)

    /**
     * [P0 FIX] 应用级后台作用域，生命周期与 Application 一致。
     * AI 请求运行在此作用域中，确保用户退出聊天页面后请求仍能继续完成并写入数据库，
     * 重新进入聊天时即可看到回复。ViewModel 销毁时不会取消此作用域中的任务。
     */
    private val chatBackgroundScope = ApplicationScopeProvider.scope

    private val database = AppDatabase.getDatabase(application)
    private val deviceId = DeviceIdProvider.getDeviceId(application)
    private val chatRepository = ChatRepository(database.chatMessageDao())
    private val companionRepository = CompanionRepository(database.companionDao())
    private val apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    private val memoryRepository = MemoryRepository(database.memoryDao(), deviceId)
    private val userRepository = ServiceRegistry.get(UserRepository::class.java)
    private val stickerManager = StickerManager.getInstance(application)
    private val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    private val ttsService = TtsService.getInstance(application)
    private val sttService = SttService.getInstance(application)
    private val chatDetailSettingsStore = com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore(application)
    private val appSettingsStore = AppSettingsStore(application)
    // [P1 FIX] 统一解析上下文设置，消除 ViewModel 中的硬编码上下文条数
    private val contextResolver = ChatContextResolver(appSettingsStore, chatRepository)

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

    // ── 消息：Room Flow 做最新一页数据源，_olderMessages 做加载的历史 ──
    // 进入时先从 ChatRepository 内存缓存读取初始数据（HomeViewModel 预热），避免 loading
    private val _recentMessages = MutableStateFlow<List<ChatMessage>>(
        chatRepository.getCachedRecent(companionId) ?: emptyList()
    )
    private val _olderMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val messages: StateFlow<List<ChatMessage>> = combine(_recentMessages, _olderMessages) { recent, older ->
        // [P1 FIX] UI 消息列表做上限保护，避免长对话时内存无限增长
        contextResolver.capUiMessages(older + recent).first
    // [L3 FIX] Eagerly → WhileSubscribed(5000)：Eagerly 即使无订阅者也启动 16 路 collect，
    // 持续运行耗电。WhileSubscribed 在无订阅者 5s 后停止，省电。
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), chatRepository.getCachedRecent(companionId) ?: emptyList())

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()

    // true when a loadMore returned fewer than LOAD_MORE_SIZE, meaning no older messages
    private var _reachedEnd = false

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
            messages = values[1] as List<ChatMessage>,
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
        messages, _isLoading, chatTypingState.isTyping, _queueDepth
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
                ChatDebugLog.log("[ChatVM] ContentFilter warmUp: $warmOk")
            } catch (e: Exception) {
                ChatDebugLog.log("[ChatVM] ContentFilter init/warmUp failed: ${e.javaClass.simpleName}: ${e.message}")
            }
            KeywordBridge.initialize(application)
        }
        startMessageConsumer()
    }

// [P0 FIX] 批量合并窗口：用户快速连发的多条消息在窗口期内合并为一批
// [L4 FIX] 引用 TimeoutBudgets 集中常量：原为文件顶层硬编码 2500L
// 窗口时长2.5秒：平衡"响应速度"和"合并更多消息"
private val BATCH_WINDOW_MS = TimeoutBudgets.CHAT_VM_BATCH_WINDOW_MS
// 窗口内轮询间隔：100ms，避免CPU忙等待
private val BATCH_POLL_INTERVAL_MS = 100L
// 单批次最大消息数：防止恶意/异常连续发送导致token溢出
private val MAX_BATCH_SIZE = 10

    private fun startMessageConsumer() {
        ChatDebugLog.log("[ChatVM] startMessageConsumer called, companionId=$companionId")
        applicationApiScope.launch {
            ChatDebugLog.log("[ChatVM] consumer coroutine STARTED (batch-merge mode)")
            try {
                val batch = mutableListOf<String>()

                while (true) {
                    // 阻塞等待第一条消息（无消息时挂起，不占CPU）
                    val first = messageQueue.receiveCatching()
                    if (first.isClosed) {
                        ChatDebugLog.log("[ChatVM] consumer: channel closed, exiting")
                        break
                    }
                    if (first.exceptionOrNull() != null) continue

                    batch.add(first.getOrThrow())
                    _queueDepth.value = maxOf(0, _queueDepth.value - 1)
                    ChatDebugLog.log("[ChatVM] consumer received first msg of batch: '${batch.last().take(30)}'")

                    // ── 批量合并窗口 ──
                    // 在窗口期内持续收集新消息（不阻塞AI处理，只收集入队消息）
                    val windowStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - windowStart < BATCH_WINDOW_MS) {
                        val next = messageQueue.tryReceive()
                        if (next.isClosed) {
                            ChatDebugLog.log("[ChatVM] consumer: channel closed during batch window")
                            break
                        }
                        if (next.isFailure) {
                            delay(BATCH_POLL_INTERVAL_MS)
                            continue
                        }
                        batch.add(next.getOrThrow())
                        _queueDepth.value = maxOf(0, _queueDepth.value - 1)
                        ChatDebugLog.log("[ChatVM] consumer collected into batch (${batch.size} total): '${batch.last().take(30)}'")
                    }

                    // 窗口结束后再排空一次残余（边界情况：delay期间刚好到达的消息）
                    while (true) {
                        val extra = messageQueue.tryReceive()
                        if (extra.isClosed || extra.isFailure) break
                        batch.add(extra.getOrThrow())
                        _queueDepth.value = maxOf(0, _queueDepth.value - 1)
                    }

                    if (batch.isEmpty()) continue

                    // [P0 FIX] 批次大小安全保护：超限时自动拆分（不丢弃任何消息）
                    // 单批次最多10条消息，超出部分作为下一批次处理
                    val batches = if (batch.size <= MAX_BATCH_SIZE) {
                        listOf(batch.toList())
                    } else {
                        ChatDebugLog.log("[ChatVM] Batch overflow: ${batch.size} > $MAX_BATCH_SIZE, splitting into chunks")
                        batch.chunked(MAX_BATCH_SIZE)
                    }

                    for ((batchIndex, subBatch) in batches.withIndex()) {
                        // 第一个子批次开始时，取消上一批次的未完成AI请求
                        if (batchIndex == 0) {
                            val previousJob = turnState.sendMessageJob
                            if (previousJob != null && previousJob.isActive) {
                                ChatDebugLog.log("[ChatVM] Cancelling previous batch AI job before new batch")
                                previousJob.cancel("New message batch started, cancelling stale batch")
                            }
                        } else {
                            // 拆分后的子批次之间短暂间隔，避免API限流
                            delay(300L)
                            // 取消上一子批次的未完成请求
                            val prevSubJob = turnState.sendMessageJob
                            if (prevSubJob?.isActive == true) {
                                prevSubJob.cancel("New message batch started, cancelling stale batch")
                            }
                        }
                        ChatDebugLog.log("[ChatVM] processing sub-batch ${batchIndex + 1}/${batches.size}: ${subBatch.size} messages")

                        try {
                            doSendMessage(batch = subBatch)
                        } catch (e: Exception) {
                            SecureLog.e("ChatViewModel", "doSendMessage failed for sub-batch ${batchIndex + 1}", e)
                            _events.tryEmit(ChatUiEvent.Error("消息发送失败: ${e.message?.take(50) ?: "未知错误"}"))
                        }
                    }

                    batch.clear()
                }
            } catch (e: CancellationException) {
                ChatDebugLog.log("[ChatVM] consumer coroutine CANCELLED")
                throw e
            } catch (e: Exception) {
                ChatDebugLog.log("[ChatVM] consumer coroutine CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                SecureLog.e("ChatViewModel", "Consumer crashed, restarting...", e)
                // [M2 FIX] 加退避延迟：若为确定性崩溃（如 DB 损坏），立即重启会形成快速崩溃循环，
                // 耗 CPU + 刷日志。对比 observeMessages 有 delay(500)，消费者同样需要退避。
                delay(1000)
                startMessageConsumer()
            }
            ChatDebugLog.log("[ChatVM] consumer coroutine ENDED (channel closed)")
        }
    }

    private fun observeMessages() {
        ChatDebugLog.log("[ChatVM] observeMessages START, companionId=$companionId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                chatRepository.getMessagesForCompanion(companionId, PAGE_SIZE).collect { recent ->
                    _recentMessages.value = recent
                    _hasMoreMessages.value = recent.size >= PAGE_SIZE && !_reachedEnd
                    ChatDebugLog.log("[ChatVM] Room Flow emit: ${recent.size} recent messages")
                }
            } catch (e: CancellationException) {
                ChatDebugLog.log("[ChatVM] observeMessages CANCELLED (viewModelScope cancelled)")
                throw e
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "observeMessages Flow crashed, restarting...", e)
                ChatDebugLog.log("[ChatVM] observeMessages CRASHED: ${e.javaClass.simpleName}: ${e.message}")
                // 延迟重启，避免快速重试风暴
                delay(500)
                observeMessages()
            }
            ChatDebugLog.log("[ChatVM] observeMessages ENDED normally")
        }
    }

    fun loadMoreHistory() {
        if (_isLoadingMore.value || !_hasMoreMessages.value) return
        _isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            delay(200) // 短暂延迟让加载指示器可见
            try {
                val beforeTimestamp = _olderMessages.value.firstOrNull()?.timestamp
                    ?: _recentMessages.value.firstOrNull()?.timestamp
                    ?: Long.MAX_VALUE
                val older = chatRepository.getMessagesBeforeSync(companionId, beforeTimestamp, LOAD_MORE_SIZE)
                if (older.isNotEmpty()) {
                    _olderMessages.value = older.reversed() + _olderMessages.value
                }
                if (older.size < LOAD_MORE_SIZE) {
                    _reachedEnd = true
                }
                _hasMoreMessages.value = !_reachedEnd
            } catch (e: Exception) {
                SecureLog.e("ChatViewModel", "loadMoreHistory failed", e)
            } finally {
                _isLoadingMore.value = false
            }
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
        stickerProbability: Int,
        ntpTimeEnabled: Boolean = false
    ): String {
        val sortedHistory = history.sortedBy { it.timestamp }
        val lastUserMessage = sortedHistory.lastOrNull { it.isFromUser }?.content ?: ""
        val innerThoughtEnabled = appSettingsStore.getInnerThoughtEnabled()

        val name = companion.name
        val personality = companion.personality?.take(300) ?: ""
        val speakingStyle = companion.speakingStyle?.take(100) ?: ""
        val backstory = companion.backstory?.take(200) ?: ""

        val memoryContext = memoryRepository.getEnrichedContext(companion.id, lastUserMessage, 3).take(500)

        val role = userRepository?.selectedRole?.value ?: CompanionRole.GIRLFRIEND
        val systemPrompt = buildString {
            appendLine(RolePromptProvider.getIdentityLine(name, role))
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
            RolePromptProvider.getLocalModelRoleLines(role).forEachIndexed { index, line ->
                appendLine("${7 + index}. $line")
            }
            appendLine("${7 + RolePromptProvider.getLocalModelRoleLines(role).size}. 情绪顺着上下文走，不要突然换情绪。")
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
            appendLine()
            appendLine(com.lianyu.ai.network.AiContextTools.buildCurrentTimeContext(ntpTimeEnabled))
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
        ChatDebugLog.log("[ChatVM] sendMessage called, content='${content.take(30)}', apis=${_availableApis.value.size}")

        // 乐观存储：用户消息立即存库显示，不等AI回复。
        // [H1 FIX] 改为 suspend 等待入库完成后再入队：原异步入库与消费者 doSendMessage
        // 存在竞态——消费者可能在用户消息尚未写库时即读取历史，导致 AI 看不到刚发的消息。
        // 现在用 applicationApiScope 串行执行"存库→入队"，保证顺序，且不阻塞 UI 线程。
        val userMessage = ChatMessage(
            companionId = companionId,
            content = content,
            isFromUser = true,
            timestamp = System.currentTimeMillis()
        )
        applicationApiScope.launch(Dispatchers.IO) {
            val userMessageId = chatRepository.sendMessage(userMessage)
            broadcastWeChatMessage(userMessageId)

            if (_availableApis.value.isEmpty()) {
                _events.tryEmit(ChatUiEvent.Error("请先配置API：我 → API设置 → 添加密钥"))
            }
            val result = messageQueue.trySend(content)
            ChatDebugLog.log("[ChatVM] trySend result=$result, queueDepth=${_queueDepth.value}")
            if (result.isSuccess) {
                _queueDepth.value += 1
            } else {
                android.widget.Toast.makeText(getApplication(), "→ 入队失败: ${result.exceptionOrNull()?.message ?: "closed"}", android.widget.Toast.LENGTH_SHORT).show()
                _events.tryEmit(ChatUiEvent.Error("消息队列已满，请稍后再试"))
                SecureLog.w("ChatViewModel", "Message queue full, dropped: ${content.take(20)}...")
            }
        }
    }

    /**
     * 处理一批消息（批量合并模式）。
     * 窗口期内（2.5秒）的多条消息合并为一次AI调用，不丢弃任何消息。
     * AI会看到所有消息内容并像真人一样综合/逐一回复。
     *
     * @param batch 当前批次的所有消息（按时间顺序，最早在前）
     */
    private suspend fun doSendMessage(batch: List<String>) {
        val content = if (batch.size == 1) batch[0] else batch.joinToString("\n")
        ChatDebugLog.log("[ChatVM] doSendMessage ENTER, batchSize=${batch.size}, mergedContent='${content.take(50)}', apis=${_availableApis.value.size}")

        // 封禁检查：已封禁用户禁止发送
        if (com.lianyu.ai.common.BanManager.isBanned(getApplication())) {
            val banInfo = com.lianyu.ai.common.BanManager.getBanInfo(getApplication())
            val banMsg = if (banInfo.remainingDays > 0) {
                "账号已被封禁（剩余${banInfo.remainingDays}天${banInfo.remainingHours}小时），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
            } else if (banInfo.remainingHours > 0) {
                "账号已被封禁（剩余${banInfo.remainingHours}小时${banInfo.remainingMinutes}分钟），原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。"
            } else {
                "账号已被封禁，原因：${banInfo.levelName}。第${banInfo.violationCount}次违规。请完成安全答题以解除封禁。"
            }
            ChatDebugLog.log("[ChatVM] doSendMessage BLOCKED: account is banned - $banMsg")
            _events.tryEmit(ChatUiEvent.Error(banMsg))
            return
        }

        // 等待 API 配置加载完成（最多 1.5 秒），避免冷启动时序竞态
        if (_availableApis.value.isEmpty() && !_apisLoaded) {
            try {
                withTimeoutOrNull(1500L) { _availableApis.first { it.isNotEmpty() } }
            } catch (_: Exception) {}
        }

        // 无 API 时：仅做安全检查 + 提示，用户消息已在 sendMessage 中乐观存库显示
        if (_availableApis.value.isEmpty()) {
            // 对批次中每条消息都做安全检查
            for (msg in batch) {
                try {
                    val inputCheck = ContentFilter.checkInput(msg)
                    if (inputCheck.isViolating) {
                        BanManager.recordViolation(getApplication(), inputCheck.level)
                        _events.tryEmit(ChatUiEvent.ContentBlocked("内容违规: ${inputCheck.reason}"))
                        return@doSendMessage
                    }
                } catch (_: Exception) {
                    _events.tryEmit(ChatUiEvent.Error("安全检查异常"))
                    return@doSendMessage
                }
            }
            val tipMessage = ChatMessage(
                companionId = companionId,
                content = "请先配置API：我 → API设置 → 添加密钥",
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            chatRepository.sendMessage(tipMessage)
            return@doSendMessage
        }

        // 安全检查（对合并后的内容做pipeline检查）
        val pipelineOk = try {
            withTimeoutOrNull(8000L) {
                pipeline.execute(MessagePipeline.PipelineInput(rawText = content, companionId = companionId))
            }
        } catch (_: Exception) { null }

        if (pipelineOk == false) {
            // 明确违规 → 阻止发送
            val err = pipeline.pipelineState.value.error ?: "内容可能违规"
            ChatDebugLog.log("[ChatVM] doSendMessage BLOCKED by pipeline: $err")
            _events.tryEmit(ChatUiEvent.ContentBlocked(err))
            return@doSendMessage
        }
        // pipelineOk == null（超时）→ fail-open，允许继续发送
        // pipelineOk == true → 正常通过
        if (pipelineOk == null) {
            ChatDebugLog.log("[ChatVM] doSendMessage: pipeline TIMEOUT, proceeding anyway (fail-open)")
        }

        // cancel已由消费者端统一处理（新批次开始时取消旧批次的AI Job），此处不再重复cancel

        turnState.reset()
        // [P1 FIX] 使用用户设置的上下文条数，不再写死 50；同时走 contextResolver 的缓存减少重复解密
        val fetchedHistory = contextResolver.getHistoryForAi(companionId)
            .filterNot { !it.isFromUser && it.content.replace("\u200B", "").isBlank() }

        // 上下文一致性校验：确保DB中最后的用户消息与当前批次匹配
        val lastUserMsgInDb = fetchedHistory.lastOrNull { it.isFromUser }?.content
        val batchLastMsg = batch.last()
        if (lastUserMsgInDb != batchLastMsg && batch.size == 1) {
            ChatDebugLog.log("[ChatVM] CONTEXT WARNING: processing '$batchLastMsg' but DB lastUserMsg='$lastUserMsgInDb'")
        }
        if (batch.size > 1) {
            ChatDebugLog.log("[ChatVM] Processing batch of ${batch.size} messages, last='${batchLastMsg.take(30)}', DB lastUser='$lastUserMsgInDb'")
        }

        val chatSettings = chatDetailSettingsStore.getSettings(companionId)
        ChatDebugLog.log("[ChatVM] doSendMessage: starting AI response, historySize=${fetchedHistory.size}, stickerProb=${chatSettings.stickerProbability}")
        // [P0 FIX] AI 请求运行在应用级作用域，避免退出聊天页面后因 ViewModel 销毁而取消。
        turnState.sendMessageJob = chatBackgroundScope.startAiResponse(
            history = fetchedHistory,
            stickerProbability = chatSettings.stickerProbability,
            userContentForMemory = content,  // 批量合并后的完整内容用于记忆提取
            batchMessageCount = batch.size,   // 告诉AI这是批量消息（用于prompt优化）
            ntpTimeEnabled = chatSettings.ntpTimeEnabled
        )
        ChatDebugLog.log("[ChatVM] doSendMessage: AI job started, isActive=${turnState.sendMessageJob?.isActive}, joining...")
        // 串行等待当前批次AI回复完成（超时15s）
        try {
            turnState.sendMessageJob?.join()
            ChatDebugLog.log("[ChatVM] doSendMessage: AI job completed normally")
        } catch (e: CancellationException) {
            // [P0 FIX] 批量替换是预期行为：新批次开始时会取消旧批次。
            // 如果在这里重新抛出 CancellationException，会导致整个消息消费者协程退出，
            // 后续所有消息只被乐观存库但永远不会触发 AI 回复。
            ChatDebugLog.log("[ChatVM] AI job cancelled (superseded by newer batch), returning to consumer")
            return@doSendMessage
        }
    }
    /**
     * 公共 AI 响应流程：调用 AI → finalizeResponse → 错误处理
     * @param history 聊天历史
     * @param stickerProbability 表情包概率
     * @param userContentForMemory 用于记忆提取的用户内容
     * @param imagePath 图片路径（视觉模型），null 则用普通文本模型
     * @param batchMessageCount 批量消息数量（>1表示多条消息合并处理）
     */
    private fun CoroutineScope.startAiResponse(
        history: List<ChatMessage>,
        stickerProbability: Int,
        userContentForMemory: String,
        imagePath: String? = null,
        batchMessageCount: Int = 1,
        ntpTimeEnabled: Boolean = false
    ) = launch {
        ChatDebugLog.log("[ChatVM] startAiResponse LAUNCHED, companionId=$companionId, imagePath=$imagePath, batchMsgCount=$batchMessageCount")
        enterLoading()
        try {
            val companion = _companionData.value
            if (companion == null) {
                SecureLog.e("ChatViewModel", "Companion data not loaded yet for id=$companionId")
                ChatDebugLog.log("[ChatVM] startAiResponse: companion is NULL, storing placeholder")
                val errorMessage = ChatMessage(
                    companionId = companionId,
                    content = "系统正在加载伴侣信息，请稍后再试",
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                chatRepository.sendMessage(errorMessage)
                return@launch
            }

            ChatDebugLog.log("[ChatVM] startAiResponse: companion loaded, calling AI service...")
            val aiResponse = if (imagePath != null) {
                withTimeoutOrNull(VISION_API_TIMEOUT_MS) {
                    aiService.sendMessageWithImage(companion.toAiCompanionInfo(), history.toAiChatMessages(), imagePath, stickerProbability, ntpTimeEnabled)
                } ?: throw Exception(getApplication<Application>().getString(R.string.api_error_generic))
            } else if (isLocalModelEnabled()) {
                AiResponse(content = runInterruptibleSafe(timeoutMs = API_TIMEOUT_MS) {
                    generateWithLocalModel(companion, history, stickerProbability, ntpTimeEnabled)
                } ?: throw java.util.concurrent.TimeoutException("Local model timeout"))
            } else {
                // 工具调用循环：最多 3 轮，防死循环
                val tools = com.lianyu.ai.domain.ToolRegistry.all()
                val historyForAi = history.toAiChatMessages()
                val companionInfo = companion.toAiCompanionInfo()
                runInterruptibleSafe(timeoutMs = API_TIMEOUT_MS * 3) {
                    executeWithToolLoop(companionInfo, historyForAi, stickerProbability, ntpTimeEnabled, tools)
                } ?: throw java.util.concurrent.TimeoutException("AI response timeout")
            }

            val aiContent = aiResponse.content
            ChatDebugLog.log("[ChatVM] startAiResponse: AI response received, length=${aiContent.length}, startsWithToast=${aiContent.startsWith("[TOAST]")}")
            if (aiContent.startsWith("[TOAST]")) {
                _events.tryEmit(ChatUiEvent.Error(aiContent.removePrefix("[TOAST]")))
                return@launch
            }

            finalizeResponse(
                aiContent = aiContent,
                reasoning = aiResponse.reasoningContent,
                userContentForMemory = userContentForMemory,
                logMessage = if (batchMessageCount > 1) "AI batch response received (${batchMessageCount} msgs)"
                           else if (imagePath != null) "AI image response received"
                           else "AI response received"
            )
        } catch (e: CancellationException) {
            SecureLog.e("ChatViewModel", "API call cancelled", e)
            ChatDebugLog.log("[ChatVM] startAiResponse CANCELLED: reason='${e.message}'")
            // [FIX] 所有取消都给用户反馈，不再静默
            // 即使是"正常批量替换"，新批次的回复可能也会失败，用户需要知道当前请求被取消了
            val cancelReason = e.message ?: ""
            val isExpectedSupersede = cancelReason.contains("batch started") ||
                                      cancelReason.contains("Image message") ||
                                      cancelReason.contains("stale")
            if (!isExpectedSupersede) {
                // 非预期的取消——可能是网络超时或系统级中断
                ChatDebugLog.log("[ChatVM] UNEXPECTED cancellation: $cancelReason")
                _events.tryEmit(ChatUiEvent.Error("回复被打断，请重试"))
            } else {
                // 预期的取消（新批次替换）——新回复马上到来，不存占位消息
                ChatDebugLog.log("[ChatVM] Expected cancellation (batch superseded): $cancelReason")
            }
        } catch (e: Exception) {
            val rawMessage = e.message ?: "发送失败"
            ChatDebugLog.log("[ChatVM] startAiResponse EXCEPTION: ${e.javaClass.simpleName}: $rawMessage")
            if (rawMessage.startsWith("[TOAST]")) {
                _events.tryEmit(ChatUiEvent.Error(rawMessage.removePrefix("[TOAST]")))
            } else {
                _events.tryEmit(ChatUiEvent.Error(rawMessage))
            }
            SecureLog.e("ChatViewModel", "AI response failed", e)
        } finally {
            ChatDebugLog.log("[ChatVM] startAiResponse FINALLY: exitLoading, activeRequests=${_activeRequests.get()}")
            exitLoading()
        }
    }

    /**
     * 工具调用执行循环。
     *
     * AI 返回 tool_calls → 执行本地工具 → 把结果作为 role=tool 消息追加到 history → 重新调用 AI。
     * 最多 [maxRounds] 轮，防死循环。最后一轮若仍为 tool_calls，转为提示文本。
     *
     * 安全约束：createOrder 等涉及支付的工具需用户确认——当前实现直接执行
     * （createOrder 工具的 description 已告知 AI 先确认，且 AI 系统提示词注入了说明）。
     * 后续可在此拦截特定工具名做用户确认交互。
     */
    private suspend fun executeWithToolLoop(
        companionInfo: com.lianyu.ai.domain.AiCompanionInfo,
        history: List<com.lianyu.ai.domain.AiChatMessage>,
        stickerProbability: Int,
        ntpTimeEnabled: Boolean,
        tools: List<com.lianyu.ai.domain.AiTool>,
        maxRounds: Int = 3
    ): com.lianyu.ai.domain.AiResponse {
        // 用可变列表承载 history，工具调用中间轮次追加消息但不入库
        val mutableHistory = history.toMutableList()
        var currentResponse = aiService.sendMessage(companionInfo, mutableHistory.toList(), stickerProbability, ntpTimeEnabled, tools)

        var round = 0
        while (!currentResponse.toolCalls.isNullOrEmpty() && round < maxRounds) {
            round++
            val activeToolCalls = currentResponse.toolCalls!!
            ChatDebugLog.log("[ChatVM] Tool loop round $round: ${activeToolCalls.size} calls")

            // 把 AI 的 tool_call 消息追加到 history（role=assistant, 带 tool_calls）
            // AiChatMessage 不支持 tool_calls 字段，这里用特殊 content 标记，
            // 实际 tool_calls + tool 结果由 AiService 的内部消息构建处理
            // —— 但 AiChatMessage→ChatMessage→Message 转换不保留 tool_calls，
            // 所以这里需要走 AiService 的 Message 列表重载路径。
            // 简化：直接在 ChatViewModel 构造 tool 结果消息，追加为普通 user/system 消息
            // 让 AI 在下一轮看到工具结果。
            for (toolCall in activeToolCalls) {
                val tool = com.lianyu.ai.domain.ToolRegistry.get(toolCall.name)
                val result = if (tool != null) {
                    runCatching {
                        withTimeoutOrNull(TimeoutBudgets.MCP_READ_MS) {
                            tool.execute(toolCall.arguments)
                        } ?: "工具执行超时"
                    }.getOrElse {
                        "工具执行失败: ${it.message}"
                    }
                } else {
                    "工具 ${toolCall.name} 不存在"
                }
                ChatDebugLog.log("[ChatVM] Tool ${toolCall.name} executed, resultLen=${result.length}")

                // 把工具结果作为 assistant 消息追加（AiChatMessage 不支持 role=tool，
                // 用 user 角色携带 "工具结果" 前缀让 AI 理解）
                mutableHistory.add(
                    com.lianyu.ai.domain.AiChatMessage(
                        isFromUser = false,
                        content = "[工具调用结果] ${toolCall.name}:\n$result",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            // 重新调用 AI，让它基于工具结果生成回复
            currentResponse = aiService.sendMessage(
                companionInfo,
                mutableHistory.toList(),
                stickerProbability,
                ntpTimeEnabled,
                tools
            )
        }

        // 超过最大轮次仍是 tool_calls → 转为提示
        if (!currentResponse.toolCalls.isNullOrEmpty()) {
            ChatDebugLog.log("[ChatVM] Tool loop reached max rounds $maxRounds")
            return com.lianyu.ai.domain.AiResponse(
                content = "我已经帮你处理了相关操作，但还有部分工具调用未能完成。你可以告诉我具体想做什么，我来帮你。",
                reasoningContent = null,
                toolCalls = null,
                finishReason = "stop"
            )
        }

        return currentResponse
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
        // 关键词级拦截：HIGH 及以上违规直接拦截
        // [FIX] AI 生成的内容不应记录用户封禁——模型输出不是用户的责任
        if (modelKw.isViolating && modelKw.level >= ContentFilter.ViolationLevel.HIGH) {
            SecureLog.w("ChatViewModel", "Output keyword violation: ${modelKw.level} - ${modelKw.reason}")
            ChatDebugLog.log("[ChatVM] AI output blocked by keyword check: ${modelKw.level} - ${modelKw.reason}")
            // 不再调用 BanManager.recordViolation()——AI 回复不应累加用户违规
            val safeFallback = "抱歉，我无法继续这个话题。"
            val fallbackMsg = ChatMessage(
                companionId = companionId,
                content = safeFallback,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            val fallbackId = chatRepository.sendMessageAndGetId(fallbackMsg)
            _reasoningText.value = ""
            _isReasoning.value = false
            return fallbackId
        }

        // [P0 FIX] 贝叶斯模型输出校验必须带超时，防止 native JNI 死锁导致 AI 回复永久卡死。
        val modelBayesian = try {
            withTimeoutOrNull(5000L) {
                ContentSafetyVerifier.verifyModelOutputAsync(
                    aiContent, modelKw,
                    modelVec ?: ContentFilter.CheckResult(false, ContentFilter.ViolationLevel.NONE, "timeout", emptyList()),
                    userContentForMemory ?: ""
                )
            } ?: com.lianyu.ai.common.safety.SafetyScore(
                score = 0.0,
                source = com.lianyu.ai.common.safety.ScoreSource.MODEL_OUTPUT,
                explanation = "模型输出校验超时"
            )
        } catch (e: Exception) {
            SecureLog.e("ChatViewModel", "Model output verification failed", e)
            com.lianyu.ai.common.safety.SafetyScore(
                score = 0.0,
                source = com.lianyu.ai.common.safety.ScoreSource.MODEL_OUTPUT,
                explanation = "模型输出校验异常"
            )
        }
        if (modelBayesian.isDangerous) {
            SecureLog.w("ChatViewModel", "Bayesian model output blocked (" + "%.3f".format(modelBayesian.score) + "): " + modelBayesian.explanation)
            ChatDebugLog.log("[ChatVM] AI output blocked by Bayesian: score=${"%.3f".format(modelBayesian.score)}, reason=${modelBayesian.explanation}")
            // [FIX] AI 生成的内容不应记录用户封禁
            val safeFallback = "抱歉，我无法继续这个话题。"
            val fallbackMsg = ChatMessage(
                companionId = companionId,
                content = safeFallback,
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            )
            val fallbackId = chatRepository.sendMessageAndGetId(fallbackMsg)
            _reasoningText.value = ""
            _isReasoning.value = false
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

        // [P0 FIX] 空内容保护和日志记录
        if (processedText.isBlank() && aiContent.isNotBlank()) {
            SecureLog.w("ChatViewModel", "WARNING: processedText is blank but aiContent has ${aiContent.length} chars. Original: '${aiContent.take(80)}'")
        }

        val aiMessageId = if (segments.size <= 1) {
            // 单条回复，走原有逻辑
            // [P0 FIX] 改进空内容处理：使用零宽空格但记录详细日志
            val safeProcessed = processedText.ifBlank {
                if (aiContent.isNotBlank()) {
                    SecureLog.w("ChatViewModel", "Falling back to zero-width space. aiContent length=${aiContent.length}")
                    "\u200B"
                } else {
                    SecureLog.w("ChatViewModel", "Both processedText and aiContent are blank, storing empty message")
                    ""
                }
            }
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
            _reasoningText.value = ""
            _isReasoning.value = false
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
        _reasoningText.value = ""
        _isReasoning.value = false
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
                delay(ChatConstants.FOLLOW_UP_BASE_DELAY_MS + kotlin.random.Random.nextLong(ChatConstants.FOLLOW_UP_RANDOM_DELAY_MS))

                // [P1 FIX] 追问场景仍用少量历史，但统一走 contextResolver，避免硬编码直接查库
                val history = contextResolver.getShortHistoryForAi(companionId, shortLimit = ChatConstants.SHORT_HISTORY_LIMIT)
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
                _olderMessages.value = emptyList()
                _reachedEnd = false
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

    /**
     * 重新生成指定AI消息的回复
     *
     * [P0 FIX] regenerate是用户主动触发的操作，cancel+replace语义正确：
     * 用户点击"重新生成"时，期望取消当前处理并立即重新生成。
     * 这会中断队列消费者的.join()等待（抛CancellationException），消费者会继续处理下一条。
     * 与自动发送新消息的场景不同，regenerate不需要走messageQueue（用户明确意图）。
     */
    fun regenerateMessage(targetMessage: ChatMessage) {
        turnState.sendMessageJob?.cancel()
        // [P0 FIX] 重新生成也运行在应用级作用域，退出聊天后仍能完成。
        turnState.sendMessageJob = chatBackgroundScope.launch {
            turnState.reset()
            _isRegenerating.value = true
            try {
                chatRepository.deleteMessage(targetMessage)
                // [P1 FIX] 重生成使用用户设置的上下文条数，不再写死 100
                val allMessages = contextResolver.getHistoryForAi(companionId)
                val companion = _companionData.value
                    ?: throw IllegalStateException("Companion data is null")
                val settings = chatDetailSettingsStore.getSettings(companionId)
                val aiResponse = withTimeoutOrNull(API_TIMEOUT_MS) {
                    aiService.sendMessage(companion.toAiCompanionInfo(), allMessages.toAiChatMessages(), settings.stickerProbability, settings.ntpTimeEnabled)
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
     *
     * [P0 FIX] 统一走 messageQueue：无论AI是否在处理中，表情包都通过队列串行处理。
     * 移除了 _isLoading 判断的else分支（原分支直接启动AI Job绕过队列，造成并发竞态）。
     * 队列排空机制确保快速连发时只处理最新一条，不会产生冗余AI调用。
     */
    fun sendSticker(sticker: StickerInfo) {
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf { it.isNotBlank() }
            ?: sticker.name
        // [P0 FIX] 始终通过 sendMessage → messageQueue → doSendMessage 的完整流水线
        // 这保证了：安全检查、串行化、队列排空、旧请求取消 等机制全部生效
        sendMessage("[$stickerId]")
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
     *
     * [P0 FIX] 图片消息现在会取消正在进行的文本AI请求（如果有的话），
     * 避免视觉Job与队列中的文本Job并发执行导致上下文混乱和回复错乱。
     * 视觉处理独立于messageQueue（因为需要特殊参数），但通过cancel保证互斥。
     */
    fun sendImageMessage(imagePath: String) {
        SecureLog.i("VISION", "========== sendImageMessage CALLED ==========")
        SecureLog.i("VISION", "imagePath=$imagePath")
        // [P0 FIX] 取消正在进行的AI请求（可能是队列消费者正在处理的文本消息）
        // 这确保视觉请求不会与过时的文本回复并发，避免"答非所问"
        val previousJob = turnState.sendMessageJob
        if (previousJob != null && previousJob.isActive) {
            SecureLog.i("VISION", "Cancelling previous AI job before vision processing")
            previousJob.cancel("Image message sent, cancelling previous AI request")
        }
        turnState.reset()
        // [P0 FIX] 视觉请求运行在应用级作用域，退出聊天后仍能完成。
        turnState.sendMessageJob = chatBackgroundScope.launch {
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

                // [P1 FIX] 图片理解使用用户设置的上下文条数，不再写死 50
                val history = contextResolver.getHistoryForAi(companionId)
                val companion = _companionData.value

                if (companion != null) {
                    val settings = chatDetailSettingsStore.getSettings(companionId)
                    val aiResponse = withTimeoutOrNull(VISION_API_TIMEOUT_MS) {
                        aiService.sendMessageWithImage(companion.toAiCompanionInfo(), history.toAiChatMessages(), imagePath, settings.stickerProbability, settings.ntpTimeEnabled)
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
                    // 错误消息仅通过UI事件展示，不存库——避免污染AI历史上下文
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
        // 关闭消息队列，让消费者协程退出等待
        messageQueue.close()
        // 取消 applicationApiScope：停止当前 ViewModel 的消费者协程、内容预热等 UI 相关任务。
        // [P0 FIX] 不要取消 turnState.sendMessageJob：AI 请求已迁移到 chatBackgroundScope（应用级作用域），
        // 退出聊天页面后应继续运行并写入数据库，重新进入聊天时即可看到回复。
        applicationApiScope.cancel()
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = null
        chatTypingState.stopTyping()
        _activeRequests.set(0)
        _isLoading.value = false
        _isRegenerating.value = false
        _reasoningText.value = ""
        _isReasoning.value = false
        _recentMessages.value = emptyList()
        _olderMessages.value = emptyList()
        _reachedEnd = false
        _hasMoreMessages.value = false
        // [P1 FIX] 退出聊天页时清理上下文缓存，避免内存泄漏
        contextResolver.clearCache(companionId)
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
