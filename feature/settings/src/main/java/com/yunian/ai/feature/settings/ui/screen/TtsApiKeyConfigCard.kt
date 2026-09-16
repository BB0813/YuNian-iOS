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
internal fun ApiKeyConfigCard(
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
internal fun TtsTextField(
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

