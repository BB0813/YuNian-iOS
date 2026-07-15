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
import com.lianyu.ai.database.AppDatabase
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

    private val database = AppDatabase.getDatabase(application)
    private val chatRepository = ServiceRegistry.getOrThrow(ChatRepository::class.java)
    private val messageWriter = ServiceRegistry.getOrThrow(MessageWriteCoordinator::class.java)
    private val companionRepository = CompanionRepository(database.companionDao())
    private val apiConfigRepository = ApiConfigRepository(database.apiConfigDao())
    private val contextResolver = ChatContextResolver(chatRepository)
    private val generation = ChatGenerationManager.get(application, companionId)
    private val aiService = ServiceRegistry.get(AiServiceProvider::class.java)
        ?: throw IllegalStateException("AiServiceProvider not registered in ServiceRegistry")
    private val sttService = SttService.getInstance(application)

    private val _recentMessages = MutableStateFlow(chatRepository.getCachedRecent(companionId).orEmpty())
    private val _olderMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _messages = MutableStateFlow(_recentMessages.value)
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _messageMetadata = MutableStateFlow<List<Message>>(emptyList())
    val messageMetadata: StateFlow<List<Message>> = _messageMetadata.asStateFlow()

    private val _messageBodies = MutableStateFlow<Map<Long, MessageBodyState<ChatMessage>>>(
        _recentMessages.value.associate { it.id to MessageBodyState.Ready(it) }
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

    val isLoading: StateFlow<Boolean> = generation.isLoading
    val isTyping: StateFlow<Boolean> = generation.isTyping
    val typingText: StateFlow<String> = generation.typingText
    val isRegenerating: StateFlow<Boolean> = generation.isRegenerating
    val reasoningText: StateFlow<String> = generation.reasoningText
    val isReasoning: StateFlow<Boolean> = generation.isReasoning
    val chatTtsConfig: StateFlow<ChatTtsConfig> = generation.chatTtsConfig
    val ttsState: StateFlow<ChatTtsState> = generation.ttsState

    private var avatarUnsubscribe: (() -> Unit)? = null

    init {
        observeMessageMetadata()
        observeCompanion()
        observeApiConfigs()
        observeGenerationEvents()
        observeUserProfile()
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.SendText -> generation.sendText(intent.content)
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
            val history = rawHistory
                .map { it.toAiChatMessage() }
            val tools = if (ChatToolIntent.shouldEnableTools(text, rawHistory.lastOrNull { it.isFromUser }?.content)) {
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
            response.content.takeUnless { it.startsWith("[TOAST]") }?.also { content ->
                messageWriter.enqueueChat(
                    ChatMessage(companionId = companionId, content = content, isFromUser = false, timestamp = System.currentTimeMillis())
                )
            }
        }.onFailure { SecureLog.e("ChatViewModel", "Voice call message failed", it) }.getOrNull()
    }

    private fun observeMessageMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _messageMetadata.value = chatRepository
                    .getRecentMetadata(companionId, ChatConstants.CHAT_PAGE_SIZE)
                    .reversed()
                _hasMoreMessages.value =
                    _messageMetadata.value.size < chatRepository.getMessageCount(companionId)
                chatRepository.observeRecentMetadata(companionId, ChatConstants.CHAT_PAGE_SIZE).collect { recent ->
                    val olderIds = _messageMetadata.value
                        .asSequence()
                        .filterNot { current -> recent.any { it.id == current.id } }
                        .toList()
                    _messageMetadata.value = (olderIds + recent.reversed())
                        .distinctBy { it.id }
                        .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
                    _hasMoreMessages.value = !reachedHistoryStart &&
                        _messageMetadata.value.size < chatRepository.getMessageCount(companionId)
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
        avatarUnsubscribe = provider?.observeAvatar { _userAvatar.value = it }
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

    override fun onCleared() {
        avatarUnsubscribe?.invoke()
        avatarUnsubscribe = null
        contextResolver.clearCache(companionId)
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