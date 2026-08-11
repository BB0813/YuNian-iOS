package com.lianyu.ai.feature.chat.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.lianyu.ai.common.ChatConstants
import com.lianyu.ai.common.MessageBodyState
import com.lianyu.ai.common.SecureLog
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.common.TimeoutBudgets
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.database.model.Message
import com.lianyu.ai.database.repository.ApiConfigRepository
import com.lianyu.ai.database.repository.ChatRepository
import com.lianyu.ai.database.repository.CompanionRepository
import com.lianyu.ai.database.repository.MessageWriteCoordinator
import com.lianyu.ai.domain.AiChatMessage
import com.lianyu.ai.domain.AiCompanionInfo
import com.lianyu.ai.domain.AiMessageType
import com.lianyu.ai.domain.AiServiceProvider
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.ToolRegistry
import com.lianyu.ai.domain.UserProfileProvider
import com.lianyu.ai.feature.chat.data.ChatContextResolver
import com.lianyu.ai.feature.chat.data.ChatDraftStore
import com.lianyu.ai.feature.chat.voice.ChatTtsState
import com.lianyu.ai.network.stt.AndroidSttProvider
import com.lianyu.ai.network.stt.SttService
import com.lianyu.ai.network.tts.ChatTtsConfig
import com.lianyu.ai.network.tts.ChatTtsMode
import com.lianyu.ai.uicommon.model.ApiProviderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ChatViewModel(
    application: Application,
    private val companionId: Long
) : AndroidViewModel(application) {

    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val messageWriter = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
    private val companionRepository = ServiceRegistry.getOrThrow(CompanionRepository::class.java)
    private val apiConfigRepository = ServiceRegistry.getOrThrow(ApiConfigRepository::class.java)
    private val contextResolver = ChatContextResolver(chatRepository)
    private val draftStore = ChatDraftStore(application)
    private val generation = ChatGenerationManager.acquire(application, companionId)
    private val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    private val sttService by lazy { SttService.getInstance(application) }

    private val cachedRecent = chatRepository.getCachedRecent(companionId).orEmpty()
    private val _recentMessages = MutableStateFlow(cachedRecent)
    private val _olderMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _messages = MutableStateFlow(cachedRecent)
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // 首帧用 L1 缓存推导 metadata，避免进页先空列表再闪 BodyLoading
    private val _messageMetadata = MutableStateFlow(
        cachedRecent.takeLast(ChatConstants.CHAT_PAGE_SIZE).map { it.toMetadataMessage() }
    )
    val messageMetadata: StateFlow<List<Message>> = _messageMetadata.asStateFlow()

    private val _messageBodies = MutableStateFlow<Map<Long, MessageBodyState<ChatMessage>>>(
        cachedRecent.associate { it.id to MessageBodyState.Ready(it) }
    )
    val messageBodies: StateFlow<Map<Long, MessageBodyState<ChatMessage>>> = _messageBodies.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMoreMessages = MutableStateFlow(false)
    val hasMoreMessages: StateFlow<Boolean> = _hasMoreMessages.asStateFlow()
    private var reachedHistoryStart = false

    private val _companionData = MutableStateFlow<CompanionEntity?>(null)
    val companionData: StateFlow<CompanionEntity?> = _companionData.asStateFlow()

    private val _userName = MutableStateFlow("我")
    val userName: StateFlow<String> = _userName.asStateFlow()

    private val _userAvatar = MutableStateFlow<String?>(null)
    val userAvatar: StateFlow<String?> = _userAvatar.asStateFlow()

    private val _availableApis = MutableStateFlow<List<ApiProviderInfo>>(emptyList())
    val availableApis: StateFlow<List<ApiProviderInfo>> = _availableApis.asStateFlow()

    private val _currentApi = MutableStateFlow<ApiProviderInfo?>(null)
    val currentApi: StateFlow<ApiProviderInfo?> = _currentApi.asStateFlow()

    private val _events = MutableSharedFlow<ChatUiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ChatUiEvent> = _events.asSharedFlow()

    // 输入框草稿：init 时从持久化恢复，击键即存盘；仅发送或用户手动删空时清空
    private val _draftText = MutableStateFlow(draftStore.getDraft(companionId))
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    val isLoading: StateFlow<Boolean> = generation.isLoading
    val isTyping: StateFlow<Boolean> = generation.isTyping
    val typingText: StateFlow<String> = generation.typingText
    val isRegenerating: StateFlow<Boolean> = generation.isRegenerating
    val chatTtsConfig: StateFlow<ChatTtsConfig> = generation.chatTtsConfig
    val ttsState: StateFlow<ChatTtsState> = generation.ttsState

    val confirmationRequest: StateFlow<ToolConfirmationRequest?> = generation.confirmationRequest

    fun respondToConfirmation(id: Long, confirmed: Boolean) = generation.respondToConfirmation(id, confirmed)

    private var avatarUnsubscribe: (() -> Unit)? = null
    private var nicknameUnsubscribe: (() -> Unit)? = null

    init {
        observeMessageMetadata()
        observeCachedMessages()
        observeCompanion()
        observeApiConfigs()
        observeGenerationEvents()
        observeUserProfile()
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendText -> {
                generation.sendText(intent.content)
                clearDraft()
            }
            is ChatIntent.SendImage -> generation.sendImage(intent.imagePath)
            is ChatIntent.SendVideo -> sendVideo(intent.videoPath)
            is ChatIntent.SendVoice -> sendVoice(intent.audioPath, intent.duration)
            is ChatIntent.SendSticker -> sendSticker(intent.sticker)
            ChatIntent.ShareLocation -> _events.tryEmit(ChatUiEvent.Error("位置分享暂不可用"))
            is ChatIntent.SwitchApi -> switchApi(intent.provider)
            ChatIntent.LoadEarlier -> loadEarlierMessages()
            is ChatIntent.Recall -> recallMessage(intent.message)
            is ChatIntent.Regenerate -> generation.regenerate(intent.message)
            is ChatIntent.QuoteReply,
            is ChatIntent.CopyText,
            is ChatIntent.OpenMedia -> Unit
            is ChatIntent.NavigateToMessage -> navigateToMessage(intent.messageId)
        }
    }

    /** 更新输入框草稿并持久化；空文本表示用户手动删空，同时移除存盘草稿。 */
    fun setDraftText(text: String) {
        if (_draftText.value == text) return
        _draftText.value = text
        draftStore.setDraft(companionId, text)
    }

    private fun clearDraft() = setDraftText("")

    fun refreshCompanionData() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { companionRepository.getCompanionById(companionId) }
                .onSuccess { _companionData.value = it }
                .onFailure { SecureLog.e("ChatViewModel", "Refresh companion failed", it) }
        }
    }

    fun markAsRead() {
        viewModelScope.launch(Dispatchers.IO) {
            chatRepository.markReadThroughLatest(companionId)
        }
    }

    fun clearChatHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.clearChatHistory(companionId) }
                .onSuccess {
                    _olderMessages.value = emptyList()
                    reachedHistoryStart = true
                    publishMessages()
                }
                .onFailure { SecureLog.e("ChatViewModel", "Clear chat history failed", it) }
        }
    }

    fun setTtsMode(mode: ChatTtsMode) = generation.setTtsMode(mode)

    fun stopTts() = generation.stopTts()

    fun setCallActive(active: Boolean) = generation.setCallActive(active)

    suspend fun sendVoiceCallMessage(text: String): String? {
        val companion = _companionData.value ?: return null
        return runCatching {
            messageWriter.enqueueChat(
                ChatMessage(companionId = companionId, content = text, isFromUser = true, timestamp = System.currentTimeMillis())
            )
            val rawHistory = contextResolver.getHistoryForAi(companionId)
                .filterNot { !it.isFromUser && it.content.replace("\u200B", "").isBlank() }
            val history = com.lianyu.ai.domain.AiDialogueHistoryPolicy.sanitizeForModel(
                rawHistory.map { it.toAiChatMessage() }
            )
            val tools = if (ChatToolIntent.shouldEnableTools(
                    text,
                    rawHistory.lastOrNull { it.isFromUser }?.content
                )
            ) {
                ToolRegistry.all()
            } else {
                emptyList()
            }
            val response = withTimeoutOrNull(TimeoutBudgets.CHAT_VM_API_TIMEOUT_MS) {
                if (tools.isEmpty()) {
                    aiService.sendMessage(companion.toAiCompanionInfo(), history, 0, false)
                } else {
                    aiService.sendMessage(companion.toAiCompanionInfo(), history, 0, false, tools)
                }
            } ?: return null
            val toastMsg = com.lianyu.ai.domain.AiOperationalMessages.asToastMessage(response.content)
            if (toastMsg != null) {
                _events.tryEmit(ChatUiEvent.Error(toastMsg))
                return@runCatching null
            }
            response.content.takeIf { it.isNotBlank() }?.also { content ->
                messageWriter.enqueueChat(
                    ChatMessage(companionId = companionId, content = content, isFromUser = false, timestamp = System.currentTimeMillis())
                )
            }
        }.onFailure { SecureLog.e("ChatViewModel", "Voice call message failed", it) }.getOrNull()
    }

    private fun observeMessageMetadata() {
        // 缓存命中时先给出 hasMore 乐观值，DB 结果回来后再校正
        if (cachedRecent.isNotEmpty()) {
            _hasMoreMessages.value = cachedRecent.size >= ChatConstants.CHAT_PAGE_SIZE
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 缓存未命中时尽快 hydrate，减少首进冷加载
                var metadataSeeded = cachedRecent.isNotEmpty()
                if (!metadataSeeded) {
                    runCatching {
                        chatRepository.hydrateRecent(companionId, ChatConstants.CHAT_PAGE_SIZE)
                    }
                    val hydrated = chatRepository.getCachedRecent(companionId).orEmpty()
                    if (hydrated.isNotEmpty()) {
                        _recentMessages.value = hydrated
                        _messages.value = hydrated
                        _messageBodies.value = hydrated.associate { it.id to MessageBodyState.Ready(it) }
                        _messageMetadata.value =
                            hydrated.takeLast(ChatConstants.CHAT_PAGE_SIZE).map { it.toMetadataMessage() }
                        metadataSeeded = true
                    }
                }
                // 缓存/Hydrate 已预填 metadata 时，不再用 Room 结果覆盖，避免 recompose 闪烁
                if (!metadataSeeded) {
                    val recentMetadata = chatRepository
                        .getRecentMetadata(companionId, ChatConstants.CHAT_PAGE_SIZE)
                        .reversed()
                    _messageMetadata.value = recentMetadata
                    seedBodiesFromCache(recentMetadata)
                }
                _hasMoreMessages.value =
                    _messageMetadata.value.size < chatRepository.getMessageCount(companionId)
                chatRepository.observeRecentMetadata(companionId, ChatConstants.CHAT_PAGE_SIZE).collect { recent ->
                    // Room 元数据 + L1 流式临时行（负 id）合并，思考过程复用消息列表链路
                    val recentIds = recent.mapTo(HashSet(recent.size)) { it.id }
                    val streamingMeta = chatRepository.getCachedRecent(companionId)
                        .orEmpty()
                        .filter { it.id < 0L }
                        .map { it.toMetadataMessage() }
                    val olderIds = _messageMetadata.value.filter { current ->
                        current.id >= 0L && current.id !in recentIds
                    }
                    val merged = (olderIds + recent.reversed() + streamingMeta)
                        .distinctBy { it.id }
                        .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                    // 仅在数据确实变化时才更新 StateFlow，防止内容相同的 List 触发
                    // recompose → chatItems 重建 → LazyColumn layout → 最后一条消息闪烁
                    if (merged != _messageMetadata.value) {
                        _messageMetadata.value = merged
                        seedBodiesFromCache(merged)
                    }
                    _hasMoreMessages.value = !reachedHistoryStart &&
                        merged.count { it.id >= 0L } < chatRepository.getMessageCount(companionId)
                    if (recent.isNotEmpty()) markAsRead()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                SecureLog.e("ChatViewModel", "Message observation failed", exception)
                _events.tryEmit(ChatUiEvent.Error("消息加载失败"))
            }
        }
    }

    /**
     * 观察 L1 MessageCache：流式 REASONING 临时消息与终态缓存更新都经此进入列表。
     * Room metadata Flow 不会感知纯缓存写入，故必须单独订阅。
     */
    private fun observeCachedMessages() {
        viewModelScope.launch {
            chatRepository.observeCachedRecent(companionId).collect { cached ->
                val streaming = cached.filter { it.id < 0L }
                val streamingIds = streaming.mapTo(HashSet()) { it.id }
                val withoutStaleStreaming = _messageMetadata.value.filterNot {
                    it.id < 0L && it.id !in streamingIds
                }
                val streamingMeta = streaming.map { it.toMetadataMessage() }
                val mergedMeta = (withoutStaleStreaming + streamingMeta)
                    .distinctBy { it.id }
                    .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                if (mergedMeta != _messageMetadata.value) {
                    _messageMetadata.value = mergedMeta
                }
                // 正文：流式更新 + 终态缓存命中一并 Ready
                val bodyUpdates = cached.mapNotNull { msg ->
                    val existing = _messageBodies.value[msg.id]
                    if (existing is MessageBodyState.Ready && existing.value == msg) null
                    else msg.id to MessageBodyState.Ready(msg)
                }
                if (bodyUpdates.isNotEmpty()) {
                    _messageBodies.value = _messageBodies.value + bodyUpdates
                    // 清理已从缓存移除的流式 id
                    val staleStreamingBodyIds = _messageBodies.value.keys.filter {
                        it < 0L && it !in streamingIds
                    }
                    if (staleStreamingBodyIds.isNotEmpty()) {
                        _messageBodies.value = _messageBodies.value - staleStreamingBodyIds.toSet()
                    }
                    publishLoadedMessages()
                } else if (mergedMeta != withoutStaleStreaming) {
                    // 仅 metadata 变化（例如流式行移除）也同步 messages
                    val staleStreamingBodyIds = _messageBodies.value.keys.filter {
                        it < 0L && it !in streamingIds
                    }
                    if (staleStreamingBodyIds.isNotEmpty()) {
                        _messageBodies.value = _messageBodies.value - staleStreamingBodyIds.toSet()
                    }
                    publishLoadedMessages()
                }
            }
        }
    }

    /** 将 L1 已有正文直接标 Ready，跳过 BodyLoading 闪烁。 */
    private fun seedBodiesFromCache(metadata: List<Message>) {
        if (metadata.isEmpty()) return
        val cachedById = chatRepository.getCachedRecent(companionId)
            ?.associateBy { it.id }
            .orEmpty()
        if (cachedById.isEmpty()) return
        val ready = metadata.mapNotNull { item ->
            val body = cachedById[item.id] ?: return@mapNotNull null
            when (_messageBodies.value[item.id]) {
                is MessageBodyState.Ready -> null
                else -> item.id to MessageBodyState.Ready(body)
            }
        }
        if (ready.isNotEmpty()) {
            _messageBodies.value = _messageBodies.value + ready
            publishLoadedMessages()
        }
    }

    fun loadVisibleMessageBodies(messageIds: Set<Long>) {
        val metadata = _messageMetadata.value.filter { it.id in messageIds }
        val pending = metadata.filter { item ->
            when (_messageBodies.value[item.id]) {
                null, is MessageBodyState.Error -> true
                else -> false
            }
        }
        if (pending.isEmpty()) return

        _messageBodies.value = _messageBodies.value + pending.associate {
            it.id to MessageBodyState.Loading
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.loadMessages(pending) }
                .onSuccess { loaded ->
                    _messageBodies.value = _messageBodies.value + pending.associate { metadataItem ->
                        val message = loaded[metadataItem.id]
                        metadataItem.id to if (message != null) {
                            MessageBodyState.Ready(message)
                        } else {
                            MessageBodyState.Error("正文不存在")
                        }
                    }
                    publishLoadedMessages()
                }
                .onFailure { error ->
                    _messageBodies.value = _messageBodies.value + pending.associate {
                        it.id to MessageBodyState.Error(error.message ?: "正文加载失败")
                    }
                }
        }
    }

    fun retryMessageBody(messageId: Long) {
        _messageBodies.value = _messageBodies.value - messageId
        loadVisibleMessageBodies(setOf(messageId))
    }

    private fun observeCompanion() {
        viewModelScope.launch(Dispatchers.IO) {
            companionRepository.getCompanionByIdFlow(companionId).collect { _companionData.value = it }
        }
    }

    private fun observeApiConfigs() {
        viewModelScope.launch(Dispatchers.IO) {
            apiConfigRepository.getAllConfiguredConfigs().collect { configs ->
                _availableApis.value = configs.map { it.toProviderInfo() }
                _currentApi.value = apiConfigRepository.getActiveEnabledConfig()?.toProviderInfo()
            }
        }
    }

    private fun observeGenerationEvents() {
        viewModelScope.launch { generation.events.collect { _events.emit(it) } }
    }

    private fun observeUserProfile() {
        val provider = ServiceRegistry.get(UserProfileProvider::class.java)
        _userName.value = provider?.getNickname() ?: "我"
        _userAvatar.value = provider?.getAvatar()
        // 持续订阅资料流，避免改头像/昵称后聊天页仍显示旧缓存
        avatarUnsubscribe = provider?.observeAvatar { _userAvatar.value = it }
        nicknameUnsubscribe = provider?.observeNickname { _userName.value = it }
    }

    private fun loadEarlierMessages() {
        if (_isLoadingMore.value || !_hasMoreMessages.value) return
        _isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cursor = _messageMetadata.value.firstOrNull()
                val page = chatRepository.getMetadataBefore(
                    companionId,
                    cursor?.timestamp ?: Long.MAX_VALUE,
                    cursor?.id ?: Long.MAX_VALUE,
                    ChatConstants.CHAT_LOAD_MORE_SIZE
                )
                if (page.isNotEmpty()) {
                    _messageMetadata.value = (page.reversed() + _messageMetadata.value).distinctBy { it.id }
                }
                reachedHistoryStart = page.size < ChatConstants.CHAT_LOAD_MORE_SIZE
                _hasMoreMessages.value = !reachedHistoryStart
                publishMessages()
            } catch (exception: Exception) {
                SecureLog.e("ChatViewModel", "Loading earlier messages failed", exception)
                _events.tryEmit(ChatUiEvent.Error("历史消息加载失败"))
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    private fun navigateToMessage(messageId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = chatRepository.getMessageById(messageId)
            if (target == null || target.companionId != companionId) {
                _events.emit(ChatUiEvent.Error("原消息已不存在"))
                return@launch
            }
            if (_messages.value.none { it.id == messageId }) {
                val halfPage = ChatConstants.CHAT_LOAD_MORE_SIZE / 2
                val before = chatRepository.getMessagesBeforeSync(
                    companionId,
                    target.timestamp,
                    target.id,
                    halfPage
                ).reversed()
                val after = chatRepository.getMessagesAfterSync(
                    companionId,
                    target.timestamp,
                    target.id,
                    halfPage
                )
                val recentIds = _recentMessages.value.mapTo(HashSet()) { it.id }
                _olderMessages.value = (before + target + after + _olderMessages.value)
                    .filterNot { it.id in recentIds }
                    .distinctBy { it.id }
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                reachedHistoryStart = before.size < halfPage
                _hasMoreMessages.value = !reachedHistoryStart
                publishMessages()
            }
            _events.emit(ChatUiEvent.MessageReadyToNavigate(messageId))
        }
    }

    private fun publishMessages() {
        _messages.value = contextResolver.capUiMessages(
            (_olderMessages.value + _recentMessages.value).distinctBy { it.id }
        ).first
    }

    private fun publishLoadedMessages() {
        val loaded = _messageMetadata.value.mapNotNull { metadata ->
            (_messageBodies.value[metadata.id] as? MessageBodyState.Ready)?.value
        }
        _recentMessages.value = loaded.takeLast(ChatConstants.CHAT_PAGE_SIZE)
        _olderMessages.value = loaded.dropLast(_recentMessages.value.size)
        publishMessages()
    }

    private fun switchApi(apiInfo: ApiProviderInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val config = apiInfo.configId?.let { apiConfigRepository.getConfigById(it) }
                    ?: apiConfigRepository.getConfigByProvider(ApiProvider.valueOf(apiInfo.name))
                    ?: return@runCatching
                apiConfigRepository.disableOtherConfigs(config.id)
                apiConfigRepository.saveConfig(config.copy(isEnabled = true))
            }.onFailure {
                SecureLog.e("ChatViewModel", "Switch API failed", it)
                _events.tryEmit(ChatUiEvent.Error("API 切换失败"))
            }
        }
    }

    private fun recallMessage(message: ChatMessage) {
        // 乐观更新：立即从 UI 消息列表中移除该消息，避免等待 Room Flow 重新发射的延迟
        _messages.value = _messages.value.filterNot { it.id == message.id }
        _recentMessages.value = _recentMessages.value.filterNot { it.id == message.id }
        _olderMessages.value = _olderMessages.value.filterNot { it.id == message.id }
        _events.tryEmit(ChatUiEvent.Info("消息已撤回"))
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { chatRepository.deleteMessage(message) }
                .onFailure {
                    SecureLog.e("ChatViewModel", "Recall message failed", it)
                    _events.tryEmit(ChatUiEvent.Error("撤回失败"))
                }
        }
    }

    private fun sendSticker(sticker: StickerInfo) {
        val stickerId = sticker.description
            ?: sticker.fileName?.removePrefix("sticker_")?.removeSuffix(".png")?.takeIf(String::isNotBlank)
            ?: sticker.name
        generation.sendText("[$stickerId]")
    }

    private fun sendVoice(audioPath: String, duration: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                messageWriter.enqueueChat(
                    ChatMessage(
                        companionId = companionId,
                        content = "[语音] $duration\"",
                        isFromUser = true,
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.VOICE,
                        linkString = audioPath
                    )
                )
                withTimeoutOrNull(AndroidSttProvider.RECOGNITION_TIMEOUT_MS) { sttService.recognize(audioPath) }
                    ?.takeIf(String::isNotBlank)
                    ?.let(generation::sendText)
            }.onFailure {
                SecureLog.e("ChatViewModel", "Voice message failed", it)
                _events.tryEmit(ChatUiEvent.Error("语音发送失败"))
            }
        }
    }

    private fun sendVideo(videoPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                messageWriter.enqueueChat(
                    ChatMessage(
                        companionId = companionId,
                        content = "[视频]",
                        isFromUser = true,
                        timestamp = System.currentTimeMillis(),
                        type = MessageType.VIDEO,
                        linkString = videoPath
                    )
                )
            }.onFailure {
                SecureLog.e("ChatViewModel", "Video message failed", it)
                _events.tryEmit(ChatUiEvent.Error("视频发送失败"))
            }
        }
    }

    private fun ApiConfig.toProviderInfo(): ApiProviderInfo {
        val base = ApiProviderInfo.fromName(provider.name)
        return base.copy(displayName = name.ifBlank { base.displayName }, configId = id)
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

    private fun ChatMessage.toAiChatMessage() = AiChatMessage(
        isFromUser = isFromUser,
        content = content,
        timestamp = timestamp,
        type = if (type == MessageType.IMAGE) AiMessageType.IMAGE else AiMessageType.TEXT,
        companionId = companionId
    )

    /** 从 L1 明文消息推导列表元数据，仅用于首帧渲染 / 流式临时行。 */
    private fun ChatMessage.toMetadataMessage(): Message = Message(
        id = id,
        conversationId = companionId,
        conversationType = "chat",
        isFromUser = isFromUser,
        timestamp = timestamp,
        type = type,
        fileFormat = fileFormat,
        turnId = turnId,
        eventIndex = eventIndex,
        durationMs = durationMs,
        anchorMessageId = anchorMessageId,
    )

    override fun onCleared() {
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = null
        nicknameUnsubscribe?.invoke()
        nicknameUnsubscribe = null
        // 兜底 flush 最新草稿：击键即 apply（内存同步），此处确保任何时序下都不丢
        draftStore.setDraft(companionId, _draftText.value)
        // 对齐 ChatTtsController 约定：离开聊天页时停止朗读并释放 MediaPlayer
        generation.stopTts()
        contextResolver.clearCache(companionId)
        // 释放生成器引用；进行中的 AI 不会被取消，空闲后由 ChatGenerationManager 延迟回收
        ChatGenerationManager.release(companionId)
        super.onCleared()
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