@file:OptIn(ExperimentalMaterial3Api::class)

package com.lianyu.ai.feature.settings.ui.screen

import android.content.Context
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.network.tts.TtsConfig
import com.lianyu.ai.network.tts.TtsProvider
import com.lianyu.ai.network.tts.TtsService
import com.lianyu.ai.network.tts.TtsVoice
import com.lianyu.ai.uicommon.theme.PetalPrimary
import com.lianyu.ai.uicommon.theme.PetalPrimaryContainer
import com.lianyu.ai.uicommon.theme.PetalOnPrimaryContainer
import com.lianyu.ai.uicommon.theme.PetalOnSurfaceVariant
import com.lianyu.ai.uicommon.theme.PetalSurface
import com.lianyu.ai.uicommon.theme.PetalSurfaceContainer
import com.lianyu.ai.uicommon.theme.PetalGreen
import com.lianyu.ai.uicommon.theme.PetalError
import com.lianyu.ai.uicommon.theme.WeChatDarkCard
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextTertiary
import com.lianyu.ai.uicommon.theme.WeChatDarkDivider
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TtsSettingsScreen(
    onNavigateBack: () -> Unit,
    isDarkTheme: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val ttsService = remember { TtsService.getInstance(context) }

    var isVisible by remember { mutableStateOf(false) }
    var ttsEnabled by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf(TtsProvider.ANDROID) }
    var selectedVoiceId by remember { mutableStateOf("") }
    var showProviderDropdown by remember { mutableStateOf(false) }
    var showVoiceDropdown by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var isSynthesizing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

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

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        ttsEnabled = prefs.getBoolean("tts_enabled", false)
        val providerName = prefs.getString("tts_provider", TtsProvider.ANDROID.name)
        selectedProvider = TtsProvider.entries.find { it.name == providerName } ?: TtsProvider.ANDROID
        selectedVoiceId = prefs.getString("tts_voice_${selectedProvider.name}", "") ?: ""

        delay(100)
        isVisible = true
    }

    val voices = remember(selectedProvider) {
        ttsService.getVoices(selectedProvider)
    }

    val backgroundColor = if (isDarkTheme) WeChatDarkBackground else Color(0xFFFBF9F8)
    val textPrimaryColor = if (isDarkTheme) WeChatDarkTextPrimary else Color(0xFF1B1C1C)
    val textSecondaryColor = if (isDarkTheme) WeChatDarkTextSecondary else Color(0xFF524346)
    val textTertiaryColor = if (isDarkTheme) WeChatDarkTextTertiary else Color(0xFFD6C1C5)
    val cardBg = if (isDarkTheme) WeChatDarkCard else PetalSurface

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
            volcengineCluster = volcengineCluster
        )
        
        TtsConfig.saveToSharedPreferences(context, newConfig)
        ttsService.updateConfig(newConfig)
        
        val prefs = context.getSharedPreferences("tts_settings", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("tts_enabled", ttsEnabled)
            putString("tts_provider", selectedProvider.name)
            putString("tts_voice_${selectedProvider.name}", selectedVoiceId)
            apply()
        }
        ttsService.setProvider(selectedProvider)
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 24.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isDarkTheme) WeChatDarkCard else Color.White.copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onNavigateBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = PetalPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    text = "TTS 语音设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor
                )
                Box(modifier = Modifier.size(40.dp))
            }

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
                                    saveSettings()
                                },
                                showDropdown = showProviderDropdown,
                                onDropdownToggle = { showProviderDropdown = it },
                                isDarkTheme = isDarkTheme,
                                cardBg = cardBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor
                            )

                            VoiceSelectionCard(
                                voices = voices,
                                selectedVoiceId = selectedVoiceId,
                                onVoiceSelect = {
                                    selectedVoiceId = it
                                    saveSettings()
                                },
                                showDropdown = showVoiceDropdown,
                                onDropdownToggle = { showVoiceDropdown = it },
                                isDarkTheme = isDarkTheme,
                                cardBg = cardBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor
                            )

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
                                isDarkTheme = isDarkTheme,
                                cardBg = cardBg,
                                textPrimaryColor = textPrimaryColor,
                                textSecondaryColor = textSecondaryColor,
                                textTertiaryColor = textTertiaryColor
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTesting = true
                                            testResult = null
                                            saveSettings()
                                            val result = ttsService.testProvider(selectedProvider)
                                            isTesting = false
                                            testResult = if (result) "✓ 连接成功" else "✗ 连接失败，请检查配置"
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
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("测试连接", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }

                                Button(
                                    onClick = {
                                        scope.launch {
                                            isSynthesizing = true
                                            testResult = null
                                            saveSettings()
                                            
                                            if (selectedProvider == TtsProvider.ANDROID) {
                                                ttsService.setProvider(TtsProvider.ANDROID)
                                            }
                                            
                                            val audioPath = ttsService.testWithSampleText(
                                                selectedProvider,
                                                "你好，这是一个语音合成测试。"
                                            )
                                            isSynthesizing = false
                                            testResult = if (audioPath != null) "✓ 合成成功: ${audioPath.substringAfterLast("/")}" else "✗ 合成失败"
                                            snackbarHostState.showSnackbar(testResult!!)
                                        }
                                    },
                                    enabled = !isSynthesizing && !isTesting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = PetalPrimaryContainer,
                                        contentColor = PetalOnPrimaryContainer
                                    )
                                ) {
                                    if (isSynthesizing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = PetalOnPrimaryContainer,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("试听", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
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
            .background(cardBg)
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
                checkedThumbColor = Color.White,
                checkedTrackColor = PetalPrimaryContainer,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFFD6C1C5)
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
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .padding(20.dp)
    ) {
        Text(
            text = "语音提供商",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDarkTheme) Color(0xFF3D2F36) else PetalSurfaceContainer.copy(alpha = 0.5f))
                    .clickable { onDropdownToggle(!showDropdown) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedProvider.displayName,
                    fontSize = 14.sp,
                    color = textPrimaryColor
                )
                Icon(
                    imageVector = if (showDropdown) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { onDropdownToggle(false) },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                TtsProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = provider.displayName,
                                    color = textPrimaryColor,
                                    fontSize = 14.sp
                                )
                                if (provider == selectedProvider) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = PetalPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            onProviderSelect(provider)
                            onDropdownToggle(false)
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
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    val selectedVoice = voices.find { it.id == selectedVoiceId }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .padding(20.dp)
    ) {
        Text(
            text = "选择音色",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = textPrimaryColor
        )
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isDarkTheme) Color(0xFF3D2F36) else PetalSurfaceContainer.copy(alpha = 0.5f))
                    .clickable { onDropdownToggle(!showDropdown) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = selectedVoice?.let { "${it.name} (${it.gender})" } ?: "请选择音色",
                    fontSize = 14.sp,
                    color = if (selectedVoice != null) textPrimaryColor else textSecondaryColor
                )
                Icon(
                    imageVector = if (showDropdown) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = textSecondaryColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            DropdownMenu(
                expanded = showDropdown,
                onDismissRequest = { onDropdownToggle(false) },
                modifier = Modifier.fillMaxWidth(0.8f)
            ) {
                voices.forEach { voice ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column {
                                    Text(
                                        text = "${voice.name} (${voice.gender})",
                                        color = textPrimaryColor,
                                        fontSize = 14.sp
                                    )
                                    if (voice.description.isNotEmpty()) {
                                        Text(
                                            text = voice.description,
                                            color = textSecondaryColor,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                if (voice.id == selectedVoiceId) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = PetalPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        },
                        onClick = {
                            onVoiceSelect(voice.id)
                            onDropdownToggle(false)
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
    isDarkTheme: Boolean,
    cardBg: Color,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    textTertiaryColor: Color
) {
    val dividerColor = if (isDarkTheme) WeChatDarkDivider else PetalSurfaceContainer

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
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
            TtsProvider.ANDROID -> {
                Text(
                    text = "使用系统内置 TTS 引擎，无需配置 API Key\n建议在系统设置中安装高质量TTS引擎以获得更好效果",
                    fontSize = 13.sp,
                    color = textSecondaryColor
                )
            }
        }
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
            focusedContainerColor = if (isDarkTheme) Color(0xFF241B20) else Color.White,
            unfocusedContainerColor = if (isDarkTheme) Color(0xFF241B20) else Color.White,
            focusedTextColor = textPrimaryColor,
            unfocusedTextColor = textPrimaryColor
        ),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true
    )
}
