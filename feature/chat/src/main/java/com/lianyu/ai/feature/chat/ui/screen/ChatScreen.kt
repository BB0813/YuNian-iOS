package com.lianyu.ai.feature.chat.ui.screen

import android.util.Log

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asImageBitmap
import com.lianyu.ai.uicommon.theme.ThemeViewModel
import com.lianyu.ai.uicommon.theme.ThemeMode
import com.lianyu.ai.common.PerformanceTrace
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlin.math.abs
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.lianyu.ai.feature.chat.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.lianyu.ai.database.model.ChatMessage
import com.lianyu.ai.database.model.CompanionEntity as CompanionModel
import com.lianyu.ai.database.model.MessageType
import com.lianyu.ai.feature.chat.ui.message.ChatListItemRenderer
import com.lianyu.ai.feature.chat.ui.message.RegeneratingItem
import com.lianyu.ai.feature.chat.ui.message.TypingIndicatorItem
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatViewModel
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatViewModelFactory
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatIntent
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatListItem
import com.lianyu.ai.feature.chat.ui.viewmodel.ChatUiEvent
import com.lianyu.ai.feature.chat.ui.viewmodel.QuoteReply
import com.lianyu.ai.feature.chat.ui.viewmodel.encodeQuotedMessage
import com.lianyu.ai.feature.chat.ui.viewmodel.toQuoteReply
import com.lianyu.ai.feature.chat.ui.viewmodel.toChatListItems
import com.lianyu.ai.feature.chat.data.ChatDetailSettingsStore
import com.lianyu.ai.common.StickerManager
import com.lianyu.ai.common.StickerInfo
import com.lianyu.ai.uicommon.component.UserAvatar
import com.lianyu.ai.uicommon.component.VoiceRecorder
import com.lianyu.ai.uicommon.component.VoiceMessageBubble
import com.lianyu.ai.uicommon.component.getChatBackgroundByKey
import com.lianyu.ai.uicommon.component.getChatBackgroundKey
import com.lianyu.ai.uicommon.component.isCustomBackground
import com.lianyu.ai.uicommon.component.rememberBackgroundBitmap
import com.lianyu.ai.uicommon.component.resolveEffectiveChatBackgroundKey
import com.lianyu.ai.network.tts.ChatTtsMode
import com.lianyu.ai.uicommon.image.viewer.FullscreenImageViewer
import com.lianyu.ai.uicommon.picker.ui.CustomImagePicker
import com.lianyu.ai.uicommon.theme.AdaptiveSizing
import com.lianyu.ai.uicommon.theme.AppTheme
import com.lianyu.ai.uicommon.theme.rememberAdaptiveSizing
import com.lianyu.ai.common.HardwareInfo
import com.lianyu.ai.common.MessageBodyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    companionId: Long,
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit = {},
    onNavigateToUserProfile: () -> Unit = {},
    onNavigateToVoiceCall: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModelFactory(context.applicationContext as Application, companionId)
    )
    val currentOnIntent by rememberUpdatedState(viewModel::handleIntent)
    val onIntent: (ChatIntent) -> Unit = remember { { intent -> currentOnIntent(intent) } }
    var quoteReply by remember { mutableStateOf<QuoteReply?>(null) }
    var previewImagePath by remember { mutableStateOf<String?>(null) }
    var showExtensionPanel by remember { mutableStateOf(false) }
    var showStickerPanel by remember { mutableStateOf(false) }
    // 自研图片选择器（多选模式，复选框）
    var showImagePicker by remember { mutableStateOf(false) }

    // File picker for sticker import
    val stickerPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val path = copyUriToCache(context, it)
                    if (path != null) {
                        val count = StickerManager.getInstance(context).importStickerZip(path)
                        snackbarHostState.showSnackbar("成功导入 $count 个表情包")
                    } else {
                        snackbarHostState.showSnackbar("文件读取失败")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("导入失败: ${e.message}")
                }
            }
        }
    }

    // Camera launcher for taking photos
    var cameraPhotoUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success: Boolean ->
        if (success) {
            showExtensionPanel = false
            cameraPhotoUri?.let { photoUri ->
                scope.launch {
                    try {
                        val inputStream = context.contentResolver.openInputStream(photoUri)
                        val cacheFile = java.io.File(context.cacheDir, "sent_photo_${System.currentTimeMillis()}.jpg")
                        inputStream?.use { input ->
                            cacheFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        onIntent(ChatIntent.SendImage(cacheFile.absolutePath))
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar("拍照失败: ${e.message}")
                    }
                }
            }
        }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            val photoFile = java.io.File(context.cacheDir, "camera_photo_${System.currentTimeMillis()}.jpg")
            photoFile.parentFile?.mkdirs()
            photoFile.createNewFile()
            val photoUri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.lianyu.fileprovider", photoFile
            )
            cameraPhotoUri = photoUri
            cameraLauncher.launch(photoUri)
        } else {
            scope.launch { snackbarHostState.showSnackbar("相机权限被拒绝") }
        }
    }

    // Audio recording permission launcher with pending action
    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            pendingAudioAction?.invoke()
        } else {
            scope.launch { snackbarHostState.showSnackbar("需要麦克风权限才能使用语音功能") }
        }
        pendingAudioAction = null
    }

    // Video picker for album
    val videoPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            showExtensionPanel = false
            scope.launch {
                try {
                    val videoPath = copyUriToCache(context, it)
                    if (videoPath != null) {
                        onIntent(ChatIntent.SendVideo(videoPath))
                    } else {
                        snackbarHostState.showSnackbar("视频读取失败")
                    }
                } catch (e: Exception) {
                    snackbarHostState.showSnackbar("视频处理失败: ${e.message}")
                }
            }
        }
    }

    val userAvatar by viewModel.userAvatar.collectAsStateWithLifecycle()
    val userName by viewModel.userName.collectAsStateWithLifecycle()
    // 不要强制 emptyList：ViewModel 已用 MessageCache 预填首帧
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val messageMetadata by viewModel.messageMetadata.collectAsStateWithLifecycle()
    val messageBodies by viewModel.messageBodies.collectAsStateWithLifecycle()
    val appSettingsStore = remember(context) { com.lianyu.ai.common.AppSettingsStore(context) }
    val showReasoning by appSettingsStore.showReasoningFlow.collectAsStateWithLifecycle(initialValue = false)
    val autoCollapseReasoning by appSettingsStore.autoCollapseReasoningFlow.collectAsStateWithLifecycle(initialValue = true)
    val chatItems = remember(messageMetadata, messageBodies, showReasoning) {
        toChatListItems(messageMetadata, messageBodies, showReasoning = showReasoning)
    }
    // 时间正序图片列表：配合 reverseLayout 实现「左滑上一张、右滑下一张」
    val chatImagePaths = remember(chatItems) {
        chatItems.mapNotNull { item ->
            val message = (item as? ChatListItem.ImageMessage)?.message ?: return@mapNotNull null
            message.linkString.ifBlank { message.content }.takeIf { it.isNotBlank() }
        }.distinct()
    }
    val visibleChatItems = remember(chatItems) { chatItems.asReversed() }
    val visibleMessagesReady = messageMetadata.lastOrNull()?.let { latest ->
        messageBodies[latest.id] is MessageBodyState.Ready
    } == true
    val isLoadingMore by viewModel.isLoadingMore.collectAsStateWithLifecycle()
    val hasMoreMessages by viewModel.hasMoreMessages.collectAsStateWithLifecycle()
    val companionData by viewModel.companionData.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isTyping by viewModel.isTyping.collectAsStateWithLifecycle()
    val typingText by viewModel.typingText.collectAsStateWithLifecycle()
    val isRegenerating by viewModel.isRegenerating.collectAsStateWithLifecycle()
    val availableApis by viewModel.availableApis.collectAsStateWithLifecycle()
    val currentApi by viewModel.currentApi.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 聊天页 TTS 朗读状态
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    val ttsConfig by viewModel.chatTtsConfig.collectAsStateWithLifecycle()

    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val perfTier = remember { HardwareInfo.tier }
    val adaptiveSizing = rememberAdaptiveSizing()

    // Per-companion chat settings
    val appContext = remember(context) { context.applicationContext }
    val settingsStore = remember(appContext) { ChatDetailSettingsStore(appContext) }
    val detailSettingsFlow = remember(settingsStore, companionId) { settingsStore.settingsFlow(companionId) }
    val detailSettings by detailSettingsFlow.collectAsStateWithLifecycle(
        initialValue = com.lianyu.ai.feature.chat.data.CompanionChatDetailSettings()
    )

    LaunchedEffect(Unit) {
        viewModel.markAsRead()
        viewModel.refreshCompanionData()
    }

    var pendingNavigationMessageId by remember { mutableStateOf<Long?>(null) }

    // 收集 ViewModel 一次性副作用：运营/配置错误走产品 Toast，不污染消息库
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ChatUiEvent.Error -> com.lianyu.ai.uicommon.component.LianYuToast.error(event.message)
                is ChatUiEvent.ContentBlocked -> com.lianyu.ai.uicommon.component.LianYuToast.warning("内容已拦截: ${event.reason}")
                is ChatUiEvent.Info -> com.lianyu.ai.uicommon.component.LianYuToast.info(event.message)
                is ChatUiEvent.StreamCompleted -> { /* 流式完成，不需要用户感知 */ }
                is ChatUiEvent.MessageReadyToNavigate -> pendingNavigationMessageId = event.messageId
            }
        }
    }

    var initialBottomScrollSettled by remember { mutableStateOf(false) }

    // LazyColumn 实际 item 总数（与 LazyColumn 内部 item 声明保持一致）
    // 思考过程已并入 chatItems（消息链路），不再额外 +1
    val itemCount = chatItems.size +
        (if (isLoadingMore) 1 else 0) +
        (if (isTyping) 1 else 0) +
        (if (isRegenerating) 1 else 0)

    // 列表状态始终存在；空消息列表时不显示转圈，而是正常展示输入栏
    val listState = remember { LazyListState() }
    val handleChatIntent: (ChatIntent) -> Unit = { intent ->
        when (intent) {
            is ChatIntent.QuoteReply -> quoteReply = intent.message.toQuoteReply(
                companionName = companionData?.name,
                userName = userName
            )
            is ChatIntent.CopyText -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("聊天消息", intent.text))
                scope.launch { snackbarHostState.showSnackbar("已复制") }
            }
            is ChatIntent.OpenMedia -> {
                if (intent.mimeType.startsWith("image/")) {
                    // 打开当前图；列表由 chatItems 中全部图片消息构成，支持左右滑切换
                    previewImagePath = intent.path
                } else {
                    scope.launch {
                        val result = openChatMedia(context, intent.path, intent.mimeType)
                        if (!result) snackbarHostState.showSnackbar("无法打开该文件")
                    }
                }
            }
            is ChatIntent.NavigateToMessage -> {
                onIntent(intent)
            }
            else -> onIntent(intent)
        }
    }

    LaunchedEffect(pendingNavigationMessageId, visibleChatItems) {
        val targetId = pendingNavigationMessageId ?: return@LaunchedEffect
        val messageIndex = visibleChatItems.indexOfFirst { it.messageOrNull?.id == targetId }
        if (messageIndex >= 0) {
            listState.animateScrollToItem(messageIndex)
            pendingNavigationMessageId = null
        }
    }

    // 反向布局中 index 0 就是视觉底部，首帧天然落在最新消息。
    LaunchedEffect(messages.isNotEmpty()) {
        if (messages.isNotEmpty() && !initialBottomScrollSettled) {
            initialBottomScrollSettled = true
        }
    }

    // 检查当前是否在底部附近
    val isAtBottom = remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) return@derivedStateOf true
            val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
            firstVisible != null && firstVisible.index <= 1
        }
    }

    // 通过 snapshotFlow 追踪用户是否在底部附近，避免布局更新时序导致的误判
    var wasAtBottom by remember { mutableStateOf(true) }
    var unreadNewMessages by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow { isAtBottom.value }.collect { nearBottom ->
            wasAtBottom = nearBottom
            if (nearBottom) unreadNewMessages = 0
        }
    }

    LaunchedEffect(listState, visibleChatItems) {
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                (item.key as? String)
                    ?.removePrefix("message-")
                    ?.takeIf { it != item.key }
                    ?.toLongOrNull()
            }.toSet()
        }.collect(viewModel::loadVisibleMessageBodies)
    }

    // 新消息时自动滚动到底部：用户消息始终滚动，AI 消息仅在用户位于底部时滚动
    val lastMessageId = messages.lastOrNull()?.id
    LaunchedEffect(lastMessageId) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastMessage = messages.lastOrNull()
        val isMyMessage = lastMessage?.isFromUser == true
        if (wasAtBottom || isMyMessage) {
            listState.scrollToItem(0)
            unreadNewMessages = 0
        } else {
            unreadNewMessages += 1
        }
    }

    // AI 流式回复时持续滚动到底部（typingText 变化但消息数不变）
    LaunchedEffect(typingText) {
        if (typingText.isNotBlank() && wasAtBottom) {
            listState.scrollToItem(0)
        }
    }

    // 流式思考过程走消息列表：chatItems 变化时若在底部则贴底
    val streamingReasoningActive = remember(chatItems) {
        chatItems.any { it is ChatListItem.ReasoningMessage && it.isStreaming }
    }
    LaunchedEffect(streamingReasoningActive, chatItems.lastOrNull()?.stableId) {
        if (streamingReasoningActive && wasAtBottom) {
            listState.scrollToItem(0)
        }
    }

    // 键盘弹出时滚动到底部，并收起“+”/表情扩展面板（输入框下方面板与键盘互斥）
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (imeBottom > 0) {
            showExtensionPanel = false
            showStickerPanel = false
            if (itemCount > 0) listState.scrollToItem(0)
        }
    }

    // ── 上划加载历史消息 ──
    var historyRestoreKey by remember { mutableStateOf<String?>(null) }
    var historyRestoreScrollOffset by remember { mutableStateOf(0) }
    var isLoadingMoreTriggered by remember { mutableStateOf(false) }

    LaunchedEffect(listState, initialBottomScrollSettled) {
        if (!initialBottomScrollSettled) return@LaunchedEffect
        snapshotFlow {
            val maxVisibleIndex = listState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: 0
            Triple(maxVisibleIndex >= listState.layoutInfo.totalItemsCount - 6, hasMoreMessages, isLoadingMore)
        }.collect { (nearTop, hasMore, loading) ->
            if (!nearTop) isLoadingMoreTriggered = false
            if (nearTop && hasMore && !loading && !isLoadingMoreTriggered) {
                val anchorItem = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key != "load_more_indicator" }
                historyRestoreKey = (anchorItem?.key as? String)
                    ?: visibleChatItems.getOrNull(listState.firstVisibleItemIndex)?.stableId
                historyRestoreScrollOffset = anchorItem?.offset?.let { -it }
                    ?: listState.firstVisibleItemScrollOffset
                isLoadingMoreTriggered = true
                onIntent(ChatIntent.LoadEarlier)
            }
        }
    }

    // 加载完成后恢复滚动位置 — 不等待 isLoadingMore 变 false，
    // 只要 chatItems 中出现锚点就立即同步滚动，避免用户看到漂移闪烁
    LaunchedEffect(historyRestoreKey, visibleChatItems) {
        val restoreKey = historyRestoreKey ?: return@LaunchedEffect
        val anchorIndex = visibleChatItems.indexOfFirst { it.stableId == restoreKey }
        if (anchorIndex >= 0) {
            listState.scrollToItem(anchorIndex, historyRestoreScrollOffset)
            historyRestoreKey = null
            historyRestoreScrollOffset = 0
        }
    }

    // Background: per-companion > global > default
    // 解析结果直接驱动绘制；仅 default 跟随主题底色，禁止深色模式整页覆盖用户背景
    // SharedPreferences 不会驱动重组：从设置页返回时需在 ON_RESUME 重读全局 key
    val colors = AppTheme.colors
    val defaultBackground = colors.background
    var resolvedBgKey by remember { mutableStateOf("default") }
    var targetBgColor by remember { mutableStateOf(defaultBackground) }
    var chatBgGradient by remember { mutableStateOf<Brush?>(null) }
    var isCustomBg by remember { mutableStateOf(false) }
    var customBgKey by remember { mutableStateOf("") }
    val customBgPainter = if (isCustomBg && customBgKey.isNotEmpty()) {
        rememberBackgroundBitmap(customBgKey)
    } else null
    var globalBgKey by remember { mutableStateOf(getChatBackgroundKey(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                globalBgKey = getChatBackgroundKey(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(detailSettings, isDarkTheme, globalBgKey) {
        withContext(Dispatchers.IO) {
            val effectiveKey = resolveEffectiveChatBackgroundKey(
                useGlobalBackground = detailSettings.useGlobalBackground,
                companionBackgroundKey = detailSettings.backgroundKey,
                globalBackgroundKey = globalBgKey
            )
            val (color, gradient) = if (isCustomBackground(effectiveKey)) {
                Color.Transparent to null
            } else {
                getChatBackgroundByKey(context, effectiveKey, isDarkTheme)
            }
            val custom = isCustomBackground(effectiveKey)
            withContext(Dispatchers.Main) {
                resolvedBgKey = effectiveKey
                targetBgColor = color
                chatBgGradient = gradient
                isCustomBg = custom
                customBgKey = if (custom) effectiveKey else ""
            }
        }
    }

    val chatBgColor by animateColorAsState(targetBgColor, tween(300), label = "bgColor")
    val backgroundColor = when {
        isCustomBg -> Color.Transparent
        resolvedBgKey == "default" -> colors.background
        else -> chatBgColor
    }

    val glassIntensity = when (perfTier) {
        HardwareInfo.Tier.ULTRA -> 1.0f
        HardwareInfo.Tier.HIGH -> 0.85f
        HardwareInfo.Tier.MEDIUM -> 0.5f
        HardwareInfo.Tier.LOW -> 0.2f
    }

    // Voice recording
    var showVoiceRecorder by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingDuration by remember { mutableStateOf(0) }
    var isCanceling by remember { mutableStateOf(false) }
    val voiceRecorder = remember { VoiceRecorder.getInstance(context) }

    val exitChat = {
        focusManager.clearFocus(force = true)
        showExtensionPanel = false
        showStickerPanel = false
        showVoiceRecorder = false
        onNavigateBack()
    }

    BackHandler(enabled = previewImagePath == null, onBack = exitChat)

    // Voice recording timer & actual recording
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDuration = 0
            voiceRecorder.start()
            while (isRecording) {
                delay(1000)
                if (isRecording) recordingDuration++
            }
        }
    }

    // Sticker data loaded from StickerManager
    val stickers = remember { mutableStateListOf<StickerInfo>() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                PerformanceTrace.markChatShellDrawn()
                drawContent()
            }
            .testTag("chat_shell_ready")
    ) {
        // 局部 Snackbar：仅保留导入/权限等非运营提示；配置/API 错误走 MainActivity 全局 LianYuToastHost
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
                .zIndex(10f)
        )

        // Background layer
        if (isCustomBg && customBgPainter != null) {
            Image(
                painter = customBgPainter,
                contentDescription = stringResource(R.string.chat_background),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // Main content - Column with messages and input only
        // imePadding is handled by ChatInputRegion only, so the root layout
        // does not shift when the keyboard is dismissed during exit.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when {
                        isCustomBg -> Modifier.background(Color.Transparent)
                        // 预设渐变在深/浅色下都生效；default 已在 backgroundColor 中跟随主题
                        chatBgGradient != null && resolvedBgKey != "default" ->
                            Modifier.background(chatBgGradient!!)
                        else -> Modifier.background(backgroundColor)
                    }
                )
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Messages list - takes full space, top bar is overlay
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            drawContent()
                            if (visibleMessagesReady) PerformanceTrace.markChatMessagesDrawn()
                        }
                        .then(
                            if (visibleMessagesReady) Modifier.testTag("chat_messages_ready")
                            else Modifier
                        )
                        .nestedScroll(rememberHorizontalSwipeGuard())
                        .pointerInput(Unit) {
                            detectTapGestures {
                                keyboardController?.hide()
                                focusManager.clearFocus(force = true)
                                showExtensionPanel = false
                                showStickerPanel = false
                            }
                        },
                    contentPadding = PaddingValues(
                        start = adaptiveSizing.listHorizontalPadding, end = adaptiveSizing.listHorizontalPadding,
                        top = ChatTopBarOverlayDefaults.ContentTopPadding,
                        bottom = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (isRegenerating) {
                        item(key = "regenerating_indicator") {
                            RegeneratingItem(
                                companionData = companionData,
                                adaptiveSizing = adaptiveSizing
                            )
                        }
                    }

                    if (isTyping && typingText.isNotBlank()) {
                        item(key = "typing_indicator") {
                            TypingIndicatorItem(
                                companionData = companionData,
                                typingText = typingText,
                                adaptiveSizing = adaptiveSizing,
                                isDarkTheme = isDarkTheme
                            )
                        }
                    }


                    items(
                        items = visibleChatItems,
                        key = { it.stableId }
                    ) { item ->
                        ChatListItemRenderer(
                            item = item,
                            companionData = companionData,
                            userAvatar = userAvatar,
                            userName = userName,
                            onIntent = handleChatIntent,
                            adaptiveSizing = adaptiveSizing,
                            isDarkTheme = isDarkTheme,
                            onRetryBody = viewModel::retryMessageBody,
                            onCompanionAvatarClick = { onNavigateToDetail(companionId) },
                            onUserAvatarClick = onNavigateToUserProfile,
                            autoCollapseReasoning = autoCollapseReasoning,
                        )
                    }

                    if (isLoadingMore) {
                        item(key = "load_more_indicator") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = colors.metadataContent
                                    )
                                    Text(
                                        "加载更早的消息...",
                                        fontSize = 12.sp,
                                        color = colors.metadataContent
                                    )
                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = unreadNewMessages > 0,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Surface(
                        modifier = Modifier.clickable {
                            scope.launch {
                                listState.animateScrollToItem(0)
                                unreadNewMessages = 0
                            }
                        },
                        shape = RoundedCornerShape(999.dp),
                        color = colors.primary,
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.KeyboardArrowDown,
                                contentDescription = null,
                                tint = colors.onPrimary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${unreadNewMessages} 条新消息",
                                color = colors.onPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            ChatInputRegion(
                isBlocked = detailSettings.blocked,
                isLoading = isLoading,
                quoteReply = quoteReply,
                ttsState = ttsState,
                showStickerPanel = showStickerPanel,
                showExtensionPanel = showExtensionPanel,
                availableApis = availableApis,
                currentApi = currentApi,
                onStopTtsClick = { viewModel.stopTts() },
                onStickerClick = { sticker ->
                    onIntent(ChatIntent.SendSticker(sticker))
                    showStickerPanel = false
                },
                onImportStickersClick = { stickerPickerLauncher.launch("application/zip") },
                onDeleteAllStickersClick = {
                    scope.launch {
                        val manager = StickerManager.getInstance(context)
                        val success = manager.deleteAllImportedStickers()
                        if (success) {
                            snackbarHostState.showSnackbar("已删除全部表情包")
                        } else {
                            snackbarHostState.showSnackbar("删除失败")
                        }
                    }
                },
                onSwitchApi = { provider -> onIntent(ChatIntent.SwitchApi(provider)) },
                onClearQuoteReply = { quoteReply = null },
                onAlbumClick = {
                    showExtensionPanel = false
                    showImagePicker = true
                },
                onCameraClick = { cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA) },
                onVideoCallClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        onNavigateToVoiceCall(companionId)
                    } else {
                        pendingAudioAction = { onNavigateToVoiceCall(companionId) }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVoiceCallClick = {
                    showExtensionPanel = false
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        onNavigateToVoiceCall(companionId)
                    } else {
                        pendingAudioAction = { onNavigateToVoiceCall(companionId) }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onTtsModeClick = {
                    val nextMode = when (ttsConfig.mode) {
                        ChatTtsMode.SILENT -> ChatTtsMode.READ_ALOUD
                        ChatTtsMode.READ_ALOUD -> ChatTtsMode.VOICE_BAR
                        ChatTtsMode.VOICE_BAR -> ChatTtsMode.SILENT
                    }
                    viewModel.setTtsMode(nextMode)
                    showExtensionPanel = false
                    scope.launch { snackbarHostState.showSnackbar("朗读模式：${nextMode.displayName}") }
                },
                onLocationClick = { onIntent(ChatIntent.ShareLocation) },
                onVoiceInputClick = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        showExtensionPanel = false
                        showVoiceRecorder = true
                    } else {
                        pendingAudioAction = {
                            showExtensionPanel = false
                            showVoiceRecorder = true
                        }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onStickerPanelClick = {
                    val opening = !showStickerPanel
                    showExtensionPanel = false
                    if (opening) {
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        showStickerPanel = true
                    } else {
                        showStickerPanel = false
                    }
                },
                onSendMessage = { msg ->
                    val currentQuote = quoteReply
                    val content = if (currentQuote != null) encodeQuotedMessage(currentQuote, msg) else msg
                    quoteReply = null
                    onIntent(ChatIntent.SendText(content))
                },
                onPlusClick = {
                    val opening = !showExtensionPanel
                    showStickerPanel = false
                    if (opening) {
                        // 收起键盘后在输入框下方展开面板，输入区整体上移
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        showExtensionPanel = true
                    } else {
                        showExtensionPanel = false
                    }
                },
                onVoiceRecordStart = {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        showExtensionPanel = false
                        showStickerPanel = false
                        isCanceling = false
                        isRecording = true
                        showVoiceRecorder = true
                    } else {
                        pendingAudioAction = {
                            showExtensionPanel = false
                            showStickerPanel = false
                            isCanceling = false
                            isRecording = true
                            showVoiceRecorder = true
                        }
                        audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                },
                onVoiceRecordStop = {
                    val audioPath = voiceRecorder.stop()
                    isRecording = false
                    showVoiceRecorder = false
                    if (audioPath != null && recordingDuration >= 1) {
                        onIntent(ChatIntent.SendVoice(audioPath, recordingDuration))
                    }
                },
                onVoiceRecordCancel = {
                    voiceRecorder.cancel()
                    isRecording = false
                    showVoiceRecorder = false
                }
            )
        }

        // Voice recording overlay
        if (showVoiceRecorder) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.scrim.copy(alpha = 0.5f))
                    .clickable { },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "录音中...",
                        fontSize = 18.sp,
                        color = colors.inverseContent,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "${recordingDuration}s",
                        fontSize = 48.sp,
                        color = colors.inverseContent,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isCanceling) "松开取消" else "松开发送，上滑取消",
                        fontSize = 13.sp,
                        color = if (isCanceling) colors.danger else colors.inverseContent.copy(alpha = 0.72f)
                    )
                }
            }
        }

        ChatTopBarRegion(
            companionId = companionId,
            companionData = companionData,
            isLoading = isLoading,
            onBackClick = exitChat,
            onDetailClick = onNavigateToDetail,
            adaptiveSizing = adaptiveSizing
        )

        // 全屏图片预览：AtomicImageViewer + 多图左右滑
        val previewPath = previewImagePath
        val previewModels = remember(previewPath, chatImagePaths) {
            when {
                previewPath == null -> emptyList()
                chatImagePaths.contains(previewPath) -> chatImagePaths
                else -> listOf(previewPath)
            }
        }
        val previewIndex = remember(previewPath, previewModels) {
            previewPath?.let { previewModels.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        }
        FullscreenImageViewer(
            models = previewModels,
            initialIndex = previewIndex,
            visible = previewPath != null && previewModels.isNotEmpty(),
            onDismiss = { previewImagePath = null },
            onOpenFailed = { failed ->
                val failedPath = failed as? String ?: previewImagePath
                previewImagePath = null
                if (failedPath != null) {
                    scope.launch {
                        val result = openChatMedia(context, failedPath, "image/*")
                        if (!result) snackbarHostState.showSnackbar("无法打开该文件")
                    }
                }
            },
            scrimColor = colors.scrim
        )

        // ═══ 自研图片选择器（多选，复选框） ═══
        if (showImagePicker) {
            CustomImagePicker(
                maxSelection = 9,
                onConfirmed = { uris ->
                    showImagePicker = false
                    if (uris.isNotEmpty()) {
                        scope.launch {
                            for (uri in uris) {
                                try {
                                    val path = copyUriToCache(context, uri)
                                    if (path != null) {
                                        onIntent(ChatIntent.SendImage(path))
                                    }
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("图片处理失败: ${e.message}")
                                }
                            }
                        }
                    }
                },
                onDismiss = { showImagePicker = false }
            )
        }
    }
}

private fun openChatMedia(context: Context, path: String, mimeType: String): Boolean {
    val file = java.io.File(path)
    if (!file.exists()) return false

    return try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.lianyu.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }
}

