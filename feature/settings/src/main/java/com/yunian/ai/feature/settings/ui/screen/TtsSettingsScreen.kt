@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import java.util.Locale
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.network.tts.TtsConfig
import com.yunian.ai.network.tts.TtsProvider
import com.yunian.ai.network.tts.TtsVoice
import com.yunian.ai.network.tts.MiMoTtsProvider
import com.yunian.ai.network.tts.ChatTtsConfig
import com.yunian.ai.network.tts.ChatTtsMode
import com.yunian.ai.network.tts.LocalTtsCatalog
import com.yunian.ai.network.tts.LocalTtsUiState
import com.yunian.ai.network.tts.LocalTtsUiStatus
import com.yunian.ai.feature.settings.ui.viewmodel.TtsSettingsViewModel
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.PetalPrimaryContainer
import com.yunian.ai.uicommon.theme.PetalOnPrimaryContainer
import com.yunian.ai.uicommon.theme.PetalGreen
import com.yunian.ai.uicommon.theme.PetalError
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold

@Composable
fun TtsSettingsScreen(
    onNavigateBack: () -> Unit,
    isDarkTheme: Boolean = false,
    settingsViewModel: TtsSettingsViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ttsService = remember { settingsViewModel.getTtsService() }

    var isVisible by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf(TtsProvider.entries.first()) }
    var selectedVoiceId by remember { mutableStateOf("") }
    var showProviderDropdown by remember { mutableStateOf(false) }
    var showVoiceDropdown by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    var previewPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPreviewPlaying by remember { mutableStateOf(false) }
    var previewAudioPath by remember { mutableStateOf<String?>(null) }

    var previewCacheKey by remember { mutableStateOf("") }

    fun stopPreview() {
        previewPlayer?.let { player ->
            runCatching {
                if (player.isPlaying) player.stop()
                player.release()
            }
        }
        previewPlayer = null
        isPreviewPlaying = false
    }

    fun playPreview(path: String) {
        stopPreview()
        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(path)
                setOnCompletionListener {
                    stopPreview()
                    testResult = "播放完成"
                }
                setOnErrorListener { _, _, _ ->
                    stopPreview()
                    testResult = "播放中断"
                    true
                }
                prepare()
            }
            previewPlayer = player
            player.start()
            isPreviewPlaying = true

            val durationMs = runCatching { player.duration.toLong() }.getOrDefault(0L).coerceAtLeast(0L)
            scope.launch {
                delay(durationMs + 1500L)
                if (previewPlayer === player) {
                    stopPreview()
                    testResult = "播放完成"
                }
            }
        } catch (e: Exception) {
            stopPreview()
            val reason = e.message ?: e.javaClass.simpleName
            testResult = "播放失败：$reason"
            scope.launch { snackbarHostState.showSnackbar(testResult!!) }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopPreview() }
    }

    val config = remember {
        TtsConfig.fromSharedPreferences(context)
    }

    var aliyunKey by remember { mutableStateOf(config.aliyunKeyId) }
    var aliyunSecret by remember { mutableStateOf(config.aliyunKeySecret) }
    var aliyunAppKey by remember { mutableStateOf(config.aliyunAppKey) }
    var baiduKey by remember { mutableStateOf(config.baiduApiKey) }
    var baiduSecret by remember { mutableStateOf(config.baiduSecretKey) }
    var xunfeiAppId by remember { mutableStateOf(config.xunfeiAppId) }
    var xunfeiKey by remember { mutableStateOf(config.xunfeiApiKey) }
    var xunfeiSecret by remember { mutableStateOf(config.xunfeiApiSecret) }
    var azureKey by remember { mutableStateOf(config.azureSubscriptionKey) }
    var azureRegion by remember { mutableStateOf(config.azureRegion) }
    var volcengineAppId by remember { mutableStateOf(config.volcengineAppId) }
    var volcengineToken by remember { mutableStateOf(config.volcengineToken) }
    var volcengineCluster by remember { mutableStateOf(config.volcengineCluster) }
    var sfApiKey by remember { mutableStateOf(config.siliconflowApiKey) }
    var sfCustomVoiceId by remember { mutableStateOf(config.siliconflowCustomVoiceId) }
    var sfUseGlobalKey by remember { mutableStateOf(config.siliconflowUseGlobalKey) }
    var sfTtsModel by remember { mutableStateOf(config.siliconflowTtsModel) }
    var sfSpeed by remember { mutableStateOf(config.siliconflowSpeed) }
    var sfGain by remember { mutableStateOf(config.siliconflowGain) }
    var sfSampleRate by remember { mutableStateOf(config.siliconflowSampleRate) }
    var customTtsUrl by remember { mutableStateOf(config.customTtsUrl) }
    var customTtsApiKey by remember { mutableStateOf(config.customTtsApiKey) }
    var customTtsModel by remember { mutableStateOf(config.customTtsModel) }
    var customTtsVoiceId by remember { mutableStateOf(config.customTtsVoiceId) }
    var customTtsResponseFormat by remember { mutableStateOf(config.customTtsResponseFormat) }
    var mimoApiKey by remember { mutableStateOf(config.mimoApiKey) }
    var mimoBaseUrl by remember { mutableStateOf(config.mimoBaseUrl) }
    var mimoModel by remember { mutableStateOf(config.mimoModel) }
    var mimoVoiceId by remember { mutableStateOf(config.mimoVoiceId) }
    var mimoVoiceDesignPrompt by remember { mutableStateOf(config.mimoVoiceDesignPrompt) }
    var mimoVoiceClonePath by remember { mutableStateOf(config.mimoVoiceClonePath) }
    var mimoOptimizeTextPreview by remember { mutableStateOf(config.mimoOptimizeTextPreview) }
    var showSfModelDropdown by remember { mutableStateOf(false) }
    var showSfRateDropdown by remember { mutableStateOf(false) }
    var showCustomFormatDropdown by remember { mutableStateOf(false) }
    var showMimoModelDropdown by remember { mutableStateOf(false) }

    var localTtsSpeed by remember { mutableStateOf(config.localTtsSpeed) }
    var localTtsSid by remember { mutableStateOf(config.localTtsSid) }
    val localTtsManager = remember { ttsService.localTtsManager }
    val localTtsState by localTtsManager.state.collectAsState()
    var showLocalTtsModelDropdown by remember { mutableStateOf(false) }

    val chatTtsCfg = remember { ChatTtsConfig.fromSharedPreferences(context) }
    var chatTtsMode by remember { mutableStateOf(chatTtsCfg.mode) }
    var skipParentheses by remember { mutableStateOf(chatTtsCfg.skipParentheses) }
    var chatTtsAutoDedup by remember { mutableStateOf(chatTtsCfg.autoDedup) }
    var chatTtsBeautify by remember { mutableStateOf(chatTtsCfg.beautify) }
    var showChatTtsModeDropdown by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        ttsEnabled = prefs.getBoolean("tts_enabled", true)
        val providerName = prefs.getString("tts_provider", null)

        selectedProvider = TtsProvider.entries.find { it.name == providerName } ?: TtsProvider.entries.first()

        val legacyCustom = prefs.getBoolean("sf_use_custom_tts", false)
        if (
            selectedProvider == TtsProvider.SILICONFLOW &&
            legacyCustom &&
            customTtsUrl.isNotBlank()
        ) {
            selectedProvider = TtsProvider.OPENAI_COMPAT
            prefs.edit()
                .putString("tts_provider", TtsProvider.OPENAI_COMPAT.name)
                .putBoolean("sf_use_custom_tts", false)
                .apply()
        }
        selectedVoiceId = prefs.getString("tts_voice_${selectedProvider.name}", "") ?: ""

        delay(100)
        isVisible = true
    }

    val voices = remember(selectedProvider) {
        ttsService.getVoices(selectedProvider)
    }

    val colorScheme = AppTheme.colors
    val backgroundColor = colorScheme.background
    val textPrimaryColor = colorScheme.onSurface
    val textSecondaryColor = colorScheme.onSurfaceVariant
    val textTertiaryColor = colorScheme.outlineVariant
    val cardBg = colorScheme.surfaceVariant

    fun saveSettings() {
        val newConfig = TtsConfig(
            aliyunKeyId = aliyunKey,
            aliyunKeySecret = aliyunSecret,
            aliyunAppKey = aliyunAppKey,
            baiduApiKey = baiduKey,
            baiduSecretKey = baiduSecret,
            xunfeiAppId = xunfeiAppId,
            xunfeiApiKey = xunfeiKey,
            xunfeiApiSecret = xunfeiSecret,
            azureSubscriptionKey = azureKey,
            azureRegion = azureRegion,
            volcengineAppId = volcengineAppId,
            volcengineToken = volcengineToken,
            volcengineCluster = volcengineCluster,
            siliconflowApiKey = sfApiKey,
            siliconflowCustomVoiceId = sfCustomVoiceId,
            siliconflowUseGlobalKey = sfUseGlobalKey,
            siliconflowTtsModel = sfTtsModel,
            siliconflowSpeed = sfSpeed,
            siliconflowGain = sfGain,
            siliconflowSampleRate = sfSampleRate,
            customTtsUrl = customTtsUrl,
            customTtsApiKey = customTtsApiKey,
            customTtsModel = customTtsModel,
            customTtsVoiceId = customTtsVoiceId,
            customTtsResponseFormat = customTtsResponseFormat,
            mimoApiKey = mimoApiKey,
            mimoBaseUrl = mimoBaseUrl,
            mimoModel = mimoModel,
            mimoVoiceId = mimoVoiceId,
            mimoVoiceDesignPrompt = mimoVoiceDesignPrompt,
            mimoVoiceClonePath = mimoVoiceClonePath,
            mimoOptimizeTextPreview = mimoOptimizeTextPreview,
            localTtsSpeed = localTtsSpeed,
            localTtsSid = localTtsSid
        )
        settingsViewModel.saveSettings(
            config = newConfig,
            ttsEnabled = ttsEnabled,
            provider = selectedProvider,
            voiceId = selectedVoiceId
        )
    }

    fun synthesizeAndPlay(hint: String) {
        scope.launch {
            isSynthesizing = true
            testResult = null
            saveSettings()

            val audioPath = ttsService.testWithSampleText(
                selectedProvider,
                "你好，这是一个语音合成测试。",
                selectedVoiceId
            )
            isSynthesizing = false
            if (audioPath != null) {
                previewAudioPath = audioPath
                previewCacheKey = "${selectedProvider.name}|$selectedVoiceId|$sfCustomVoiceId|$mimoModel|$mimoVoiceClonePath|$mimoVoiceDesignPrompt|$mimoOptimizeTextPreview"
                testResult = hint
                snackbarHostState.showSnackbar(testResult!!)
                playPreview(audioPath)
            } else {
                testResult = "合成失败：${ttsService.lastSynthesisError ?: "未知原因"}"
                snackbarHostState.showSnackbar(testResult!!)
            }
        }
    }

    fun saveChatTtsSettings() {
        val cfg = ChatTtsConfig(
            mode = chatTtsMode,
            skipParentheses = skipParentheses,
            autoDedup = chatTtsAutoDedup,
            beautify = chatTtsBeautify
        )
        settingsViewModel.saveChatTtsSettings(cfg)
    }

    GlassPageScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "TTS 语音设置",
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 }
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    TtsToggleCard(
                        enabled = ttsEnabled,
                        onToggle = {
                            ttsEnabled = it
                            saveSettings()
                        },
                        isDarkTheme = isDarkTheme,
                        cardBg = cardBg,
                        textPrimaryColor = textPrimaryColor,
                        textSecondaryColor = textSecondaryColor
                    )

                    AnimatedVisibility(
                        visible = ttsEnabled,
                        enter = expandVertically(tween(300)) + fadeIn(tween(300)),
                        exit = shrinkVertically(tween(300)) + fadeOut(tween(300))
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            ProviderSelectionCard(
                                selectedProvider = selectedProvider,
                                onProviderSelect = {
                                    selectedProvider = it
                                    selectedVoiceId = ""
                                    showVoiceDropdown = false
                                    saveSettings()
                                },
                                showDropdown = showProviderDropdown,
                                onDropdownToggle = {
                                    showProviderDropdown = it
                                    if (it) showVoiceDropdown = false
                                },
                                cardBg = cardBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor
                            )

                            if (selectedProvider != TtsProvider.MIMO ||
                                MiMoTtsProvider.normalizeModel(mimoModel) == MiMoTtsProvider.MODEL_TTS
                            ) {
                                VoiceSelectionCard(
                                    voices = voices,
                                    selectedVoiceId = selectedVoiceId,
                                    onVoiceSelect = {
                                        selectedVoiceId = it
                                        saveSettings()
                                    },
                                    showDropdown = showVoiceDropdown,
                                    onDropdownToggle = {
                                        showVoiceDropdown = it
                                        if (it) showProviderDropdown = false
                                    },
                                    cardBg = cardBg,
                                    textPrimaryColor = textPrimaryColor,
                                    textSecondaryColor = textSecondaryColor
                                )
                            }

                            if (selectedProvider == TtsProvider.SHERPA_LOCAL) {

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
                                        .padding(20.dp)
                                ) {
                                    Text(
                                        text = "${selectedProvider.displayName} 配置",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = textPrimaryColor
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LocalTtsConfigContent(
                                        localTtsState = localTtsState,
                                        localTtsSpeed = localTtsSpeed,
                                        onLocalTtsSpeedChange = { localTtsSpeed = it; saveSettings() },
                                        localTtsSid = localTtsSid,
                                        onLocalTtsSidChange = { localTtsSid = it; saveSettings() },
                                        showModelDropdown = showLocalTtsModelDropdown,
                                        onShowModelDropdown = { showLocalTtsModelDropdown = it },
                                        onSelectModel = {
                                            scope.launch { localTtsManager.selectModel(it) }
                                        },
                                        onDownload = {
                                            scope.launch {
                                                localTtsManager.startDownload(localTtsState.modelId)
                                            }
                                        },
                                        onCancelDownload = {
                                            scope.launch { localTtsManager.cancelDownload() }
                                        },
                                        onEnable = {
                                            scope.launch { localTtsManager.enable() }
                                        },
                                        onDisable = {
                                            scope.launch { localTtsManager.disable() }
                                        },
                                        onDelete = {
                                            scope.launch { localTtsManager.deleteDownloadedModel() }
                                        },
                                        isDarkTheme = isDarkTheme,
                                        cardBg = cardBg,
                                        textPrimaryColor = textPrimaryColor,
                                        textSecondaryColor = textSecondaryColor,
                                        context = context
                                    )
                                }
                            } else {
                                ApiKeyConfigCard(
                                    provider = selectedProvider,
                                    aliyunKey = aliyunKey,
                                    onAliyunKeyChange = { aliyunKey = it },
                                    aliyunSecret = aliyunSecret,
                                    onAliyunSecretChange = { aliyunSecret = it },
                                    aliyunAppKey = aliyunAppKey,
                                    onAliyunAppKeyChange = { aliyunAppKey = it },
                                    baiduKey = baiduKey,
                                    onBaiduKeyChange = { baiduKey = it },
                                    baiduSecret = baiduSecret,
                                    onBaiduSecretChange = { baiduSecret = it },
                                    xunfeiAppId = xunfeiAppId,
                                    onXunfeiAppIdChange = { xunfeiAppId = it },
                                    xunfeiKey = xunfeiKey,
                                    onXunfeiKeyChange = { xunfeiKey = it },
                                    xunfeiSecret = xunfeiSecret,
                                    onXunfeiSecretChange = { xunfeiSecret = it },
                                    azureKey = azureKey,
                                    onAzureKeyChange = { azureKey = it },
                                    azureRegion = azureRegion,
                                    onAzureRegionChange = { azureRegion = it },
                                    volcengineAppId = volcengineAppId,
                                    onVolcengineAppIdChange = { volcengineAppId = it },
                                    volcengineToken = volcengineToken,
                                    onVolcengineTokenChange = { volcengineToken = it },
                                    volcengineCluster = volcengineCluster,
                                    onVolcengineClusterChange = { volcengineCluster = it },
                                    sfApiKey = sfApiKey,
                                    onSfApiKeyChange = { sfApiKey = it },
                                    sfCustomVoiceId = sfCustomVoiceId,
                                    onSfCustomVoiceIdChange = { sfCustomVoiceId = it },
                                    sfUseGlobalKey = sfUseGlobalKey,
                                    onSfUseGlobalKeyChange = { sfUseGlobalKey = it },
                                    sfTtsModel = sfTtsModel,
                                    onSfTtsModelChange = { sfTtsModel = it },
                                    sfSpeed = sfSpeed,
                                    onSfSpeedChange = { sfSpeed = it },
                                    sfGain = sfGain,
                                    onSfGainChange = { sfGain = it },
                                    sfSampleRate = sfSampleRate,
                                    onSfSampleRateChange = { sfSampleRate = it },
                                    customTtsUrl = customTtsUrl,
                                    onCustomTtsUrlChange = { customTtsUrl = it },
                                    customTtsApiKey = customTtsApiKey,
                                    onCustomTtsApiKeyChange = { customTtsApiKey = it },
                                    customTtsModel = customTtsModel,
                                    onCustomTtsModelChange = { customTtsModel = it },
                                    customTtsVoiceId = customTtsVoiceId,
                                    onCustomTtsVoiceIdChange = { customTtsVoiceId = it },
                                    customTtsResponseFormat = customTtsResponseFormat,
                                    onCustomTtsResponseFormatChange = { customTtsResponseFormat = it },
                                    mimoApiKey = mimoApiKey,
                                    onMimoApiKeyChange = { mimoApiKey = it },
                                    mimoBaseUrl = mimoBaseUrl,
                                    onMimoBaseUrlChange = { mimoBaseUrl = it },
                                    mimoModel = mimoModel,
                                    onMimoModelChange = { mimoModel = it },
                                    mimoVoiceId = mimoVoiceId,
                                    onMimoVoiceIdChange = { mimoVoiceId = it },
                                    mimoVoiceDesignPrompt = mimoVoiceDesignPrompt,
                                    onMimoVoiceDesignPromptChange = { mimoVoiceDesignPrompt = it },
                                    mimoVoiceClonePath = mimoVoiceClonePath,
                                    onMimoVoiceClonePathChange = { mimoVoiceClonePath = it; saveSettings() },
                                    mimoOptimizeTextPreview = mimoOptimizeTextPreview,
                                    onMimoOptimizeTextPreviewChange = { mimoOptimizeTextPreview = it; saveSettings() },
                                    showMimoModelDropdown = showMimoModelDropdown,
                                    onShowMimoModelDropdown = { showMimoModelDropdown = it },
                                    showSfModelDropdown = showSfModelDropdown,
                                    onShowSfModelDropdown = { showSfModelDropdown = it },
                                    showSfRateDropdown = showSfRateDropdown,
                                    onShowSfRateDropdown = { showSfRateDropdown = it },
                                    showCustomFormatDropdown = showCustomFormatDropdown,
                                    onShowCustomFormatDropdown = { showCustomFormatDropdown = it },
                                    isDarkTheme = isDarkTheme,
                                    cardBg = cardBg,
                                    textPrimaryColor = textPrimaryColor,
                                    textSecondaryColor = textSecondaryColor,
                                    textTertiaryColor = textTertiaryColor
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTesting = true
                                            testResult = null
                                            saveSettings()
                                            val result = ttsService.testProvider(selectedProvider)
                                            isTesting = false
                                            testResult = if (result) "连接成功" else "连接失败，请检查配置"
                                            snackbarHostState.showSnackbar(testResult!!)
                                        }
                                    },
                                    enabled = !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PetalGreen.copy(alpha = 0.15f),
                                        contentColor = PetalGreen
                                    )
                                ) {
                                    if (isTesting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = PetalGreen,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = AppIcons.RefreshCw,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("测试连接", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }

                                Button(
                                    onClick = {

                                        if (isPreviewPlaying) {
                                            stopPreview()
                                            return@Button
                                        }

                                        val cacheKey = "${selectedProvider.name}|$selectedVoiceId|$sfCustomVoiceId|$mimoModel|$mimoVoiceClonePath|$mimoVoiceDesignPrompt|$mimoOptimizeTextPreview"
                                        if (previewAudioPath != null && previewCacheKey == cacheKey) {
                                            testResult = "重播中…"
                                            scope.launch { snackbarHostState.showSnackbar(testResult!!) }
                                            playPreview(previewAudioPath!!)
                                            return@Button
                                        }
                                        synthesizeAndPlay("合成成功，播放中…")
                                    },
                                    enabled = !isSynthesizing && !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PetalPrimaryContainer,
                                        contentColor = PetalOnPrimaryContainer
                                    )
                                ) {
                                    when {
                                        isSynthesizing -> {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                color = PetalOnPrimaryContainer,
                                                strokeWidth = 2.dp
                                            )
                                        }
                                        isPreviewPlaying -> {
                                            Icon(
                                                imageVector = AppIcons.Square,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("停止", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                        else -> {
                                            Icon(
                                                imageVector = AppIcons.Play,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("试听", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        }
                                    }
                                }

                                Button(
                                    onClick = { synthesizeAndPlay("重新合成，播放中…") },
                                    enabled = !isSynthesizing && !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PetalPrimary.copy(alpha = 0.10f),
                                        contentColor = PetalPrimary
                                    )
                                ) {
                                    if (isSynthesizing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = PetalPrimary,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = AppIcons.RefreshCw,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("重新合成", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                }
                            }

                            testResult?.let {
                                Text(
                                    text = it,
                                    fontSize = 13.sp,
                                    color = if (it.contains("成功")) PetalGreen else PetalError,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            ChatReadAloudSettingsCard(
                                chatTtsMode = chatTtsMode,
                                onModeSelect = { chatTtsMode = it; saveChatTtsSettings() },
                                showModeDropdown = showChatTtsModeDropdown,
                                onModeDropdownToggle = { showChatTtsModeDropdown = it },
                                skipParentheses = skipParentheses,
                                onSkipParenthesesChange = { skipParentheses = it; saveChatTtsSettings() },
                                autoDedup = chatTtsAutoDedup,
                                onAutoDedupChange = { chatTtsAutoDedup = it; saveChatTtsSettings() },
                                beautify = chatTtsBeautify,
                                onBeautifyChange = { chatTtsBeautify = it; saveChatTtsSettings() },
                                isDarkTheme = isDarkTheme,
                                cardBg = cardBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TtsToggleCard(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "启用 TTS 语音",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = textPrimaryColor
            )
            Text(
                text = "开启后 AI 回复将使用语音播放",
                fontSize = 12.sp,
                color = textSecondaryColor
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = AppTheme.colors.onPrimary,
                checkedTrackColor = AppTheme.colors.primaryContainer,
                uncheckedThumbColor = AppTheme.colors.outline,
                uncheckedTrackColor = AppTheme.colors.surfaceVariant
            )
        )
    }
}

@Composable
private fun ProviderSelectionCard(
    selectedProvider: TtsProvider,
    onProviderSelect: (TtsProvider) -> Unit,
    showDropdown: Boolean,
    onDropdownToggle: (Boolean) -> Unit,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
            .padding(20.dp)
    ) {
        Text(
            text = "语音提供商",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Text(
            text = "选择合成引擎，不同提供商音色与配置不同",
            fontSize = 12.sp,
            color = textSecondaryColor,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .clickable { onDropdownToggle(!showDropdown) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedProvider.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = selectedProvider.description,
                        fontSize = 12.sp,
                        color = textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    imageVector = if (showDropdown) AppIcons.ChevronUp
                    else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { onDropdownToggle(false) },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(AppTheme.colors.surface)
            ) {
                TtsProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = provider.displayName,
                                    fontSize = 14.sp,
                                    color = textPrimaryColor
                                )
                                Text(
                                    text = provider.description,
                                    fontSize = 12.sp,
                                    color = textSecondaryColor
                                )
                            }
                        },
                        onClick = {
                            onProviderSelect(provider)
                            onDropdownToggle(false)
                        },
                        leadingIcon = {
                            if (provider == selectedProvider) {
                                Icon(
                                    imageVector = AppIcons.Check,
                                    contentDescription = null,
                                    tint = PetalGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceSelectionCard(
    voices: List<TtsVoice>,
    selectedVoiceId: String,
    onVoiceSelect: (String) -> Unit,
    showDropdown: Boolean,
    onDropdownToggle: (Boolean) -> Unit,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    val selectedVoice = voices.find { it.id == selectedVoiceId }
    val triggerEnabled = voices.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
            .padding(20.dp)
    ) {
        Text(
            text = "选择音色",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Text(
            text = if (voices.isEmpty()) "当前提供商暂无可用音色" else "从下拉列表选择合成音色",
            fontSize = 12.sp,
            color = textSecondaryColor,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (triggerEnabled) AppTheme.colors.surface
                        else AppTheme.colors.surfaceVariant.copy(alpha = 0.55f)
                    )
                    .clickable(enabled = triggerEnabled) { onDropdownToggle(!showDropdown) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedVoice?.let { "${it.name}（${it.gender}）" }
                            ?: if (voices.isEmpty()) "暂无可用音色" else "请选择音色",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (selectedVoice != null) textPrimaryColor else textSecondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val detail = selectedVoice?.let { voice ->
                        buildString {
                            if (voice.language.isNotBlank()) append(voice.language)
                            if (voice.description.isNotBlank()) {
                                if (isNotEmpty()) append(" · ")
                                append(voice.description)
                            }
                        }
                    }.orEmpty()
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            fontSize = 12.sp,
                            color = textSecondaryColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
                Icon(
                    imageVector = if (showDropdown) AppIcons.ChevronUp
                    else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showDropdown && triggerEnabled,
                onDismissRequest = { onDropdownToggle(false) },
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .background(AppTheme.colors.surface)
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "${voice.name}（${voice.gender}）",
                                    fontSize = 14.sp,
                                    color = textPrimaryColor
                                )
                                val detail = buildString {
                                    if (voice.language.isNotBlank()) append(voice.language)
                                    if (voice.description.isNotBlank()) {
                                        if (isNotEmpty()) append(" · ")
                                        append(voice.description)
                                    }
                                }
                                if (detail.isNotBlank()) {
                                    Text(
                                        text = detail,
                                        fontSize = 12.sp,
                                        color = textSecondaryColor
                                    )
                                }
                            }
                        },
                        onClick = {
                            onVoiceSelect(voice.id)
                            onDropdownToggle(false)
                        },
                        leadingIcon = {
                            if (voice.id == selectedVoiceId) {
                                Icon(
                                    imageVector = AppIcons.Check,
                                    contentDescription = null,
                                    tint = PetalGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyConfigCard(
    provider: TtsProvider,
    aliyunKey: String,
    onAliyunKeyChange: (String) -> Unit,
    aliyunSecret: String,
    onAliyunSecretChange: (String) -> Unit,
    aliyunAppKey: String,
    onAliyunAppKeyChange: (String) -> Unit,
    baiduKey: String,
    onBaiduKeyChange: (String) -> Unit,
    baiduSecret: String,
    onBaiduSecretChange: (String) -> Unit,
    xunfeiAppId: String,
    onXunfeiAppIdChange: (String) -> Unit,
    xunfeiKey: String,
    onXunfeiKeyChange: (String) -> Unit,
    xunfeiSecret: String,
    onXunfeiSecretChange: (String) -> Unit,
    azureKey: String,
    onAzureKeyChange: (String) -> Unit,
    azureRegion: String,
    onAzureRegionChange: (String) -> Unit,
    volcengineAppId: String,
    onVolcengineAppIdChange: (String) -> Unit,
    volcengineToken: String,
    onVolcengineTokenChange: (String) -> Unit,
    volcengineCluster: String,
    onVolcengineClusterChange: (String) -> Unit,
    sfApiKey: String,
    onSfApiKeyChange: (String) -> Unit,
    sfCustomVoiceId: String,
    onSfCustomVoiceIdChange: (String) -> Unit,
    sfUseGlobalKey: Boolean,
    onSfUseGlobalKeyChange: (Boolean) -> Unit,
    sfTtsModel: String,
    onSfTtsModelChange: (String) -> Unit,
    sfSpeed: String,
    onSfSpeedChange: (String) -> Unit,
    sfGain: String,
    onSfGainChange: (String) -> Unit,
    sfSampleRate: Int,
    onSfSampleRateChange: (Int) -> Unit,
    customTtsUrl: String,
    onCustomTtsUrlChange: (String) -> Unit,
    customTtsApiKey: String,
    onCustomTtsApiKeyChange: (String) -> Unit,
    customTtsModel: String,
    onCustomTtsModelChange: (String) -> Unit,
    customTtsVoiceId: String,
    onCustomTtsVoiceIdChange: (String) -> Unit,
    customTtsResponseFormat: String,
    onCustomTtsResponseFormatChange: (String) -> Unit,
    mimoApiKey: String,
    onMimoApiKeyChange: (String) -> Unit,
    mimoBaseUrl: String,
    onMimoBaseUrlChange: (String) -> Unit,
    mimoModel: String,
    onMimoModelChange: (String) -> Unit,
    mimoVoiceId: String,
    onMimoVoiceIdChange: (String) -> Unit,
    mimoVoiceDesignPrompt: String,
    onMimoVoiceDesignPromptChange: (String) -> Unit,
    mimoVoiceClonePath: String,
    onMimoVoiceClonePathChange: (String) -> Unit,
    mimoOptimizeTextPreview: Boolean,
    onMimoOptimizeTextPreviewChange: (Boolean) -> Unit,
    showMimoModelDropdown: Boolean,
    onShowMimoModelDropdown: (Boolean) -> Unit,
    showSfModelDropdown: Boolean,
    onShowSfModelDropdown: (Boolean) -> Unit,
    showSfRateDropdown: Boolean,
    onShowSfRateDropdown: (Boolean) -> Unit,
    showCustomFormatDropdown: Boolean,
    onShowCustomFormatDropdown: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textTertiaryColor: Color
) {
    val dividerColor = AppTheme.colors.outline
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
            .padding(20.dp)
    ) {
        Text(
            text = "${provider.displayName} 配置",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Spacer(modifier = Modifier.height(12.dp))

        when (provider) {
            TtsProvider.ALIYUN -> {
                TtsTextField(value = aliyunKey, onValueChange = onAliyunKeyChange, label = "Access Key ID", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = aliyunSecret, onValueChange = onAliyunSecretChange, label = "Access Key Secret", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = aliyunAppKey, onValueChange = onAliyunAppKeyChange, label = "App Key", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.BAIDU -> {
                TtsTextField(value = baiduKey, onValueChange = onBaiduKeyChange, label = "API Key", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = baiduSecret, onValueChange = onBaiduSecretChange, label = "Secret Key", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.XUNFEI -> {
                TtsTextField(value = xunfeiAppId, onValueChange = onXunfeiAppIdChange, label = "App ID", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = xunfeiKey, onValueChange = onXunfeiKeyChange, label = "API Key", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = xunfeiSecret, onValueChange = onXunfeiSecretChange, label = "API Secret", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.MICROSOFT -> {
                TtsTextField(value = azureKey, onValueChange = onAzureKeyChange, label = "Subscription Key", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = azureRegion, onValueChange = onAzureRegionChange, label = "Region (如: eastasia)", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.VOLCENGINE -> {
                TtsTextField(value = volcengineAppId, onValueChange = onVolcengineAppIdChange, label = "App ID", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = volcengineToken, onValueChange = onVolcengineTokenChange, label = "Token", isPassword = true, isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = volcengineCluster, onValueChange = onVolcengineClusterChange, label = "Cluster (可选)", isDarkTheme = isDarkTheme, dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
            }
            TtsProvider.SILICONFLOW -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("复用全局 SiliconFlow API Key", fontSize = 13.sp, color = textPrimaryColor)
                    Switch(checked = sfUseGlobalKey, onCheckedChange = onSfUseGlobalKeyChange,
                        colors = SwitchDefaults.colors(checkedTrackColor = PetalPrimary))
                }
                if (!sfUseGlobalKey) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TtsTextField(value = sfApiKey, onValueChange = onSfApiKeyChange,
                        label = "API Key", isPassword = true, isDarkTheme = isDarkTheme,
                        dividerColor = dividerColor, textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("TTS 模型", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowSfModelDropdown(!showSfModelDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sfTtsModel.ifBlank { "未选择" }, fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showSfModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = showSfModelDropdown, onDismissRequest = { onShowSfModelDropdown(false) }) {
                        listOf(
                            "FunAudioLLM/CosyVoice2-0.5B" to "CosyVoice2 (推荐)",
                            "fnlp/MOSS-TTSD-v0.5" to "MOSS-TTSD"
                        ).forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                onSfTtsModelChange(value); onShowSfModelDropdown(false)
                            })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("采样率", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowSfRateDropdown(!showSfRateDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${sfSampleRate} Hz", fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showSfRateDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(expanded = showSfRateDropdown, onDismissRequest = { onShowSfRateDropdown(false) }) {
                        listOf(8000, 16000, 22050, 44100).forEach { rate ->
                            DropdownMenuItem(text = { Text("$rate Hz") }, onClick = {
                                onSfSampleRateChange(rate); onShowSfRateDropdown(false)
                            })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("语速: ${sfSpeed}", fontSize = 13.sp, color = textSecondaryColor)
                Slider(
                    value = sfSpeed.toFloatOrNull() ?: 1.0f,
                    onValueChange = { onSfSpeedChange(String.format(Locale.US, "%.1f", it)) },
                    valueRange = 0.5f..2.0f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text("增益: ${sfGain} dB", fontSize = 13.sp, color = textSecondaryColor)
                Slider(
                    value = sfGain.toFloatOrNull() ?: 0f,
                    onValueChange = { onSfGainChange(String.format("%.0f", it)) },
                    valueRange = -10f..10f,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
                )

                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(value = sfCustomVoiceId, onValueChange = onSfCustomVoiceIdChange,
                    label = "自定义音色 (名称或 speech: URI)", isDarkTheme = isDarkTheme, dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor, textSecondaryColor = textSecondaryColor)

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "克隆音色：可填音色名称（如 dp_42824，自动解析 URI）或直接粘贴完整 URI（speech:...）",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )

                Spacer(modifier = Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Text("没有自定义音色？", fontSize = 12.sp, color = textSecondaryColor)
                    Text("前往添加>>", fontSize = 12.sp, color = PetalPrimary,
                        modifier = Modifier.clickable {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                            intent.data = android.net.Uri.parse("https://voice.gbkgov.cn/")
                            context.startActivity(intent)
                        })
                }
            }
            TtsProvider.MIMO -> {
                Text(
                    text = "MiMo TTS 走 /v1/chat/completions + audio 字段（非 OpenAI speech）",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = mimoBaseUrl,
                    onValueChange = onMimoBaseUrlChange,
                    label = "Base URL (https://api.xiaomimimo.com/v1)",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = mimoApiKey,
                    onValueChange = onMimoApiKeyChange,
                    label = "API Key",
                    isPassword = true,
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("模型", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowMimoModelDropdown(!showMimoModelDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mimoModel.ifBlank { "未选择" }, fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showMimoModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showMimoModelDropdown,
                        onDismissRequest = { onShowMimoModelDropdown(false) },
                        modifier = Modifier.background(AppTheme.colors.surface)
                    ) {
                        listOf(
                            MiMoTtsProvider.MODEL_TTS to "预置精品音色 · 支持唱歌模式",
                            MiMoTtsProvider.MODEL_TTS_VOICEDESIGN to "文本描述定制音色",
                            MiMoTtsProvider.MODEL_TTS_VOICECLONE to "音频样本复刻音色"
                        ).forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label, fontSize = 13.sp) },
                                onClick = {
                                    onMimoModelChange(id)
                                    onShowMimoModelDropdown(false)
                                },
                                leadingIcon = if (id == mimoModel) {
                                    { Icon(AppIcons.Check, null, tint = PetalGreen, modifier = Modifier.size(18.dp)) }
                                } else null
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                val mimoModelNote = when (MiMoTtsProvider.normalizeModel(mimoModel)) {
                    MiMoTtsProvider.MODEL_TTS_VOICEDESIGN ->
                        "通过文本描述定制音色；不支持唱歌模式、预置音色与音色复刻。"
                    MiMoTtsProvider.MODEL_TTS_VOICECLONE ->
                        "基于音频样本复刻音色；不支持唱歌模式、预置音色与音色设计。"
                    else ->
                        "支持唱歌模式（文本开头加 (唱歌) 标签）；不支持音色设计与音色复刻。"
                }
                Text(mimoModelNote, fontSize = 12.sp, color = textSecondaryColor)

                when (MiMoTtsProvider.normalizeModel(mimoModel)) {
                    MiMoTtsProvider.MODEL_TTS_VOICEDESIGN -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mimoVoiceDesignPrompt,
                            onValueChange = onMimoVoiceDesignPromptChange,
                            label = { Text("音色描述（必填，1-4 句）", color = textSecondaryColor) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PetalPrimary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = AppTheme.colors.surface,
                                unfocusedContainerColor = AppTheme.colors.surface,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            minLines = 3,
                            maxLines = 6
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "描述越具体越生动：性别年龄、音色质感、情绪语气、语速节奏等，支持中英文。不要写混响/回声等后期效果词，避免矛盾特征（如稚嫩童声 + 总裁气场）。",
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("智能润色合成文本", fontSize = 13.sp, color = textPrimaryColor)
                                Text("optimize_text_preview：让模型润色目标播报文本", fontSize = 11.sp, color = textSecondaryColor)
                            }
                            Switch(
                                checked = mimoOptimizeTextPreview,
                                onCheckedChange = onMimoOptimizeTextPreviewChange,
                                colors = SwitchDefaults.colors(checkedTrackColor = PetalPrimary)
                            )
                        }
                    }
                    MiMoTtsProvider.MODEL_TTS_VOICECLONE -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        var clonePickError by remember { mutableStateOf<String?>(null) }
                        val cloneScope = rememberCoroutineScope()
                        val cloneLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.GetContent()
                        ) { uri ->
                            if (uri != null) {
                                cloneScope.launch {
                                    val outcome = withContext(Dispatchers.IO) {
                                        val mime = context.contentResolver.getType(uri) ?: ""
                                        val ext = when (mime) {
                                            "audio/mpeg", "audio/mp3" -> "mp3"
                                            "audio/wav" -> "wav"
                                            else -> ""
                                        }
                                        if (ext.isEmpty()) {
                                            return@withContext Pair(false, "仅支持 mp3 / wav 格式的音频样本")
                                        }
                                        val targetDir = File(context.filesDir, "tts_mimo_clone").apply { mkdirs() }
                                        val outFile = File(targetDir, "mimo_clone_sample.$ext")
                                        runCatching {
                                            context.contentResolver.openInputStream(uri)?.use { input ->
                                                outFile.outputStream().use { out -> input.copyTo(out) }
                                            } ?: return@withContext Pair(false, "无法读取所选文件")
                                            if (outFile.length() <= 0L) {
                                                outFile.delete()
                                                return@withContext Pair(false, "音频样本为空")
                                            }
                                            if (outFile.length() > 10 * 1024 * 1024L) {
                                                outFile.delete()
                                                return@withContext Pair(false, "音频样本超过 10MB 限制")
                                            }
                                            Pair(true, outFile.absolutePath)
                                        }.getOrElse { e ->
                                            outFile.delete()
                                            Pair(false, "复制样本失败：${e.message ?: e.javaClass.simpleName}")
                                        }
                                    }
                                    if (outcome.first) {
                                        onMimoVoiceClonePathChange(outcome.second)
                                        clonePickError = null
                                    } else {
                                        clonePickError = outcome.second
                                    }
                                }
                            }
                        }
                        Button(
                            onClick = { cloneLauncher.launch("audio/*") },
                            modifier = Modifier.fillMaxWidth().height(44.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PetalPrimaryContainer,
                                contentColor = PetalOnPrimaryContainer
                            )
                        ) {
                            Text(
                                if (mimoVoiceClonePath.isBlank()) "选择音频样本" else "更换音频样本",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (mimoVoiceClonePath.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${File(mimoVoiceClonePath).name}",
                                    fontSize = 13.sp,
                                    color = PetalGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    onMimoVoiceClonePathChange("")
                                    clonePickError = null
                                }) {
                                    Text("移除", fontSize = 12.sp, color = PetalError)
                                }
                            }
                        }
                        clonePickError?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, fontSize = 12.sp, color = PetalError)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "支持 mp3 / wav，样本不超过 10MB。合成时读取该文件发送给 MiMo 复刻音色。",
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                    }
                    else -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        TtsTextField(
                            value = mimoVoiceId,
                            onValueChange = onMimoVoiceIdChange,
                            label = "自定义 voice (可选，留空使用上方预置音色)",
                            isDarkTheme = isDarkTheme,
                            dividerColor = dividerColor,
                            textPrimaryColor = textPrimaryColor,
                            textSecondaryColor = textSecondaryColor
                        )
                    }
                }
            }
            TtsProvider.OPENAI_COMPAT -> {
                Text(
                    text = "兼容 OpenAI Audio Speech：POST /v1/audio/speech\n可填 Base URL（如 https://xxx/v1）或完整 speech 地址\n局域网自建服务可用 http://192.168.x.x:port/v1",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsUrl,
                    onValueChange = onCustomTtsUrlChange,
                    label = "Base URL / Speech URL",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsApiKey,
                    onValueChange = onCustomTtsApiKeyChange,
                    label = "API Key",
                    isPassword = true,
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsModel,
                    onValueChange = onCustomTtsModelChange,
                    label = "模型 (如 tts-1 / tts-1-hd)",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                TtsTextField(
                    value = customTtsVoiceId,
                    onValueChange = onCustomTtsVoiceIdChange,
                    label = "voice (如 alloy / nova)",
                    isDarkTheme = isDarkTheme,
                    dividerColor = dividerColor,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("response_format", fontSize = 13.sp, color = textSecondaryColor)
                Spacer(modifier = Modifier.height(4.dp))
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .drawGlass(
                                backdrop = LocalPageBackdrop.current,
                                shape = RoundedCornerShape(12.dp),
                                surfaceColor = AppTheme.colors.surface
                            )
                            .clickable { onShowCustomFormatDropdown(!showCustomFormatDropdown) }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(customTtsResponseFormat.ifBlank { "mp3" }, fontSize = 14.sp, color = textPrimaryColor)
                        Icon(
                            if (showCustomFormatDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = showCustomFormatDropdown,
                        onDismissRequest = { onShowCustomFormatDropdown(false) }
                    ) {
                        listOf("mp3", "opus", "aac", "flac", "wav", "pcm").forEach { format ->
                            DropdownMenuItem(text = { Text(format) }, onClick = {
                                onCustomTtsResponseFormatChange(format)
                                onShowCustomFormatDropdown(false)
                            })
                        }
                    }
                }
            }
            TtsProvider.SHERPA_LOCAL -> {

            }
        }
    }
}

@Composable
private fun LocalTtsConfigContent(
    localTtsState: LocalTtsUiState,
    localTtsSpeed: Float,
    onLocalTtsSpeedChange: (Float) -> Unit,
    localTtsSid: Int,
    onLocalTtsSidChange: (Int) -> Unit,
    showModelDropdown: Boolean,
    onShowModelDropdown: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    context: Context
) {
    val model = localTtsState.model
    val status = localTtsState.status
    val isDownloading = status == LocalTtsUiStatus.DOWNLOADING
    val isReady = status == LocalTtsUiStatus.READY
    val isEnabled = status == LocalTtsUiStatus.ENABLED
    val canDownload = model.files.any { it.downloadUrl.isNotBlank() } && !isDownloading && !isEnabled

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Text("本地模型", fontSize = 13.sp, color = textSecondaryColor)
        Box {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .clickable { onShowModelDropdown(!showModelDropdown) }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(model.displayName, fontSize = 14.sp, color = textPrimaryColor)
                Icon(
                    if (showModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = null, tint = textSecondaryColor, modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(expanded = showModelDropdown, onDismissRequest = { onShowModelDropdown(false) }) {
                LocalTtsCatalog.all.forEach { m ->
                    DropdownMenuItem(
                        text = { Text(m.displayName, fontSize = 14.sp) },
                        onClick = { onSelectModel(m.id); onShowModelDropdown(false) },
                        leadingIcon = if (m.id == model.id) {
                            { Icon(AppIcons.Check, null, tint = PetalGreen, modifier = Modifier.size(18.dp)) }
                        } else null
                    )
                }
            }
        }

        val statusText = when (status) {
            LocalTtsUiStatus.NOT_DOWNLOADED -> "未下载"
            LocalTtsUiStatus.DOWNLOADING -> {
                val pct = localTtsState.progressPercent
                val fi = localTtsState.currentFileIndex + 1
                val tot = localTtsState.totalFiles
                "下载中 $pct% ($fi/$tot)"
            }
            LocalTtsUiStatus.READY -> "就绪（点击启用）"
            LocalTtsUiStatus.ENABLED -> "已启用"
            LocalTtsUiStatus.FAILED -> "失败: ${localTtsState.errorMessage ?: "未知"}"
        }
        val statusColor = when (status) {
            LocalTtsUiStatus.ENABLED -> PetalGreen
            LocalTtsUiStatus.READY -> PetalPrimary
            LocalTtsUiStatus.FAILED -> PetalError
            LocalTtsUiStatus.DOWNLOADING -> textSecondaryColor
            LocalTtsUiStatus.NOT_DOWNLOADED -> textSecondaryColor
        }
        Text(statusText, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.Medium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (canDownload) {
                Button(
                    onClick = onDownload,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalPrimaryContainer, contentColor = PetalOnPrimaryContainer)
                ) { Text("下载", fontSize = 13.sp) }
            }
            if (isDownloading) {
                Button(
                    onClick = onCancelDownload,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalError.copy(alpha = 0.15f), contentColor = PetalError)
                ) { Text("取消", fontSize = 13.sp) }
            }
            if (isReady) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalGreen.copy(alpha = 0.15f), contentColor = PetalGreen)
                ) { Text("启用", fontSize = 13.sp) }
            }
            if (isEnabled) {
                Button(
                    onClick = onDisable,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.surfaceVariant, contentColor = textPrimaryColor)
                ) { Text("禁用", fontSize = 13.sp) }
            }
            if (status != LocalTtsUiStatus.NOT_DOWNLOADED && !isDownloading) {
                Button(
                    onClick = onDelete,
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PetalError.copy(alpha = 0.1f), contentColor = PetalError)
                ) { Text("删除", fontSize = 13.sp) }
            }
        }

        if (status == LocalTtsUiStatus.NOT_DOWNLOADED && model.files.all { it.downloadUrl.isBlank() }) {
            Text(
                text = "未配置下载源。请手动将模型文件放入：\n${model.modelDir(context).absolutePath}",
                fontSize = 12.sp, color = textSecondaryColor
            )
        }

        if (model.numSpeakers > 1) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("音色 sid: $localTtsSid / ${model.numSpeakers - 1}", fontSize = 13.sp, color = textSecondaryColor)
            Slider(
                value = localTtsSid.toFloat(),
                onValueChange = { onLocalTtsSidChange(it.toInt()) },
                valueRange = 0f..(model.numSpeakers - 1).toFloat(),
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text("语速: ${String.format(Locale.US, "%.1f", localTtsSpeed)}", fontSize = 13.sp, color = textSecondaryColor)
        Slider(
            value = localTtsSpeed,
            onValueChange = { onLocalTtsSpeedChange(String.format(Locale.US, "%.1f", it).toFloat()) },
            valueRange = 0.5f..2.0f,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(thumbColor = PetalPrimary, activeTrackColor = PetalPrimary)
        )

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "sherpa-onnx 端上推理，无需联网。\n模型文件需放入上述目录后点击「启用」。",
            fontSize = 12.sp, color = textSecondaryColor
        )
    }
}

@Composable
private fun TtsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isPassword: Boolean = false,
    isDarkTheme: Boolean,
    dividerColor: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = textSecondaryColor) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PetalPrimary,
            unfocusedBorderColor = dividerColor,
            focusedContainerColor = AppTheme.colors.surface,
            unfocusedContainerColor = AppTheme.colors.surface,
            focusedTextColor = textPrimaryColor,
            unfocusedTextColor = textPrimaryColor
        ),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true
    )
}

@Composable
private fun ChatReadAloudSettingsCard(
    chatTtsMode: ChatTtsMode,
    onModeSelect: (ChatTtsMode) -> Unit,
    showModeDropdown: Boolean,
    onModeDropdownToggle: (Boolean) -> Unit,
    skipParentheses: Boolean,
    onSkipParenthesesChange: (Boolean) -> Unit,
    autoDedup: Boolean,
    onAutoDedupChange: (Boolean) -> Unit,
    beautify: Boolean,
    onBeautifyChange: (Boolean) -> Unit,
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {

    @Suppress("UNUSED_PARAMETER")
    val unusedAutoDedup = autoDedup
    @Suppress("UNUSED_PARAMETER")
    val unusedOnAutoDedup = onAutoDedupChange

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = cardBg
                )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "聊天页语音",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Text(
            text = "语音条模式会在 AI 回复入库时合成音频，单条消息同时显示语音条和文字；重进聊天不会重复合成",
            fontSize = 12.sp,
            color = textSecondaryColor
        )

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawGlass(
                        backdrop = LocalPageBackdrop.current,
                        shape = RoundedCornerShape(12.dp),
                        surfaceColor = AppTheme.colors.surface
                    )
                    .clickable { onModeDropdownToggle(!showModeDropdown) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "语音模式",
                        fontSize = 14.sp,
                        color = textPrimaryColor
                    )
                    Text(
                        text = chatTtsMode.displayName + " · " + chatTtsMode.description,
                        fontSize = 12.sp,
                        color = textSecondaryColor
                    )
                }
                Icon(
                    imageVector = if (showModeDropdown) AppIcons.ChevronUp
                    else AppIcons.ChevronDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showModeDropdown,
                onDismissRequest = { onModeDropdownToggle(false) },
                modifier = Modifier.background(AppTheme.colors.surface)
            ) {
                ChatTtsMode.selectableModes.forEach { mode ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(mode.displayName, fontSize = 14.sp, color = textPrimaryColor)
                                Text(mode.description, fontSize = 12.sp, color = textSecondaryColor)
                            }
                        },
                        onClick = { onModeSelect(mode) },
                        leadingIcon = {
                            if (mode == chatTtsMode) {
                                Icon(AppIcons.Check, contentDescription = null, tint = PetalGreen, modifier = Modifier.size(18.dp))
                            }
                        }
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("跳过括号内心戏", fontSize = 14.sp, color = textPrimaryColor)
                Text("合成时跳过 <...> (...) （...） 内的内容", fontSize = 12.sp, color = textSecondaryColor)
            }
            Switch(
                checked = skipParentheses,
                onCheckedChange = onSkipParenthesesChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppTheme.colors.onPrimary,
                    checkedTrackColor = AppTheme.colors.primaryContainer,
                    uncheckedThumbColor = AppTheme.colors.outline,
                    uncheckedTrackColor = AppTheme.colors.surfaceVariant
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("音频美化", fontSize = 14.sp, color = textPrimaryColor)
                Text("点击语音条播放时使用均衡器预设（部分设备不支持）", fontSize = 12.sp, color = textSecondaryColor)
            }
            Switch(
                checked = beautify,
                onCheckedChange = onBeautifyChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppTheme.colors.onPrimary,
                    checkedTrackColor = AppTheme.colors.primaryContainer,
                    uncheckedThumbColor = AppTheme.colors.outline,
                    uncheckedTrackColor = AppTheme.colors.surfaceVariant
                )
            )
        }
    }
}
