package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons

import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.PetalPrimaryContainer
import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.database.model.ApiConfig
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.feature.settings.R
import com.yunian.ai.feature.settings.ui.viewmodel.SettingsViewModel
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.common.AppSettingsStore
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ProviderLogo(
    provider: ApiProvider,
    size: androidx.compose.ui.unit.Dp = 32.dp,
    cornerRadius: androidx.compose.ui.unit.Dp = 8.dp
) {
    val logoRes = when (provider) {
        ApiProvider.OPENAI -> R.drawable.logo_openai
        ApiProvider.ANTHROPIC -> R.drawable.logo_anthropic
        ApiProvider.GEMINI -> R.drawable.logo_gemini
        ApiProvider.DEEPSEEK -> R.drawable.logo_deepseek
        ApiProvider.DASHSCOPE -> R.drawable.logo_qwen
        ApiProvider.KIMI -> R.drawable.logo_kimi
        ApiProvider.XIAOMI -> R.drawable.logo_xiaomi
        ApiProvider.ZHIPU -> R.drawable.logo_zhipu
        ApiProvider.SILICONFLOW -> R.drawable.logo_siliconflow
        ApiProvider.OPENROUTER -> R.drawable.logo_openrouter
        ApiProvider.GROQ -> R.drawable.logo_groq
        else -> null
    }

    if (logoRes != null) {
        Image(
            painter = painterResource(id = logoRes),
            contentDescription = provider.displayName,
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit
        )
    } else {
        val (text, bgColor, textColor) = when (provider) {
            ApiProvider.PARTNER -> Triple("C", Color(0xFFFF69B4), AppTheme.colors.staticWhite)
            ApiProvider.IFLYTEK -> Triple("讯", Color(0xFF1677FF), AppTheme.colors.staticWhite)
            ApiProvider.CUSTOM -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
            else -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
        }

        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(cornerRadius))
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                fontSize = if (text.length > 1) (size.value * 0.35).sp else (size.value * 0.45).sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
        }
    }
}

private fun providerInitials(provider: ApiProvider): Triple<String, Color, Color> {
    return when (provider) {
        ApiProvider.OPENAI -> Triple("O", Color(0xFF10A37F), Color.White)
        ApiProvider.ANTHROPIC -> Triple("C", Color(0xFFCC785C), Color.White)
        ApiProvider.GEMINI -> Triple("G", Color(0xFF4285F4), Color.White)
        ApiProvider.DEEPSEEK -> Triple("D", Color(0xFF4D6BFA), Color.White)
        ApiProvider.DASHSCOPE -> Triple("通", Color(0xFF615CED), Color.White)
        ApiProvider.KIMI -> Triple("K", Color(0xFF10A37F), Color.White)
        ApiProvider.XIAOMI -> Triple("米", Color(0xFFFF6900), Color.White)
        ApiProvider.IFLYTEK -> Triple("讯", Color(0xFF1677FF), Color.White)
        ApiProvider.ZHIPU -> Triple("智", Color(0xFF4169E1), Color.White)
        ApiProvider.SILICONFLOW -> Triple("硅", Color(0xFF10A37F), Color.White)
        ApiProvider.OPENROUTER -> Triple("OR", Color(0xFF7B68EE), Color.White)
        ApiProvider.GROQ -> Triple("G", Color(0xFFF4845F), Color.White)
        ApiProvider.PARTNER -> Triple("C", Color(0xFFFF69B4), Color.White)
        ApiProvider.CUSTOM -> Triple("?", Color(0xFF888888).copy(alpha = 0.15f), Color(0xFF888888))
    }
}

@Composable
fun VisionModelSettingsCard(
    visionEnabled: Boolean,
    visionModel: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onVisionEnabledChanged: (Boolean) -> Unit,
    onVisionModelChanged: (String) -> Unit
) {
    var showVisionModelDropdown by remember { mutableStateOf(false) }
    val cardBg = AppTheme.colors.surfaceVariant
    val cardShape = RoundedCornerShape(24.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = cardShape,
                surfaceColor = cardBg
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = AppIcons.Eye,
                    contentDescription = null,
                    tint = PetalPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "视觉识别设置",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = textPrimaryColor
                )
                Text(
                    text = "配置图片识别与视觉模型选项",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "启用视觉识别",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = textPrimaryColor
                )
                Text(
                    text = if (visionEnabled) "已开启 - 发送图片时将使用视觉模型分析" else "已关闭 - 图片将作为普通附件发送",
                    fontSize = 12.sp,
                    color = textSecondaryColor
                )
            }

            Switch(
                checked = visionEnabled,
                onCheckedChange = onVisionEnabledChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppTheme.colors.primary,
                    checkedTrackColor = AppTheme.colors.primaryContainer.copy(alpha = 0.5f),
                    uncheckedThumbColor = AppTheme.colors.outline,
                    uncheckedTrackColor = AppTheme.colors.surfaceVariant
                )
            )
        }

        if (visionEnabled) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = AppSettingsStore.VisionModels.getVisionModelDisplayName(visionModel),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("视觉模型", color = textSecondaryColor) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showVisionModelDropdown = !showVisionModelDropdown },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = AppTheme.colors.outline,
                        focusedContainerColor = cardBg,
                        unfocusedContainerColor = cardBg,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    trailingIcon = {
                        Icon(
                            imageVector = if (showVisionModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                            contentDescription = "展开",
                            modifier = Modifier.clickable { showVisionModelDropdown = !showVisionModelDropdown },
                            tint = textSecondaryColor
                        )
                    },
                    singleLine = true
                )

                DropdownMenu(
                    expanded = showVisionModelDropdown,
                    onDismissRequest = { showVisionModelDropdown = false },
                    modifier = Modifier.fillMaxWidth(0.9f)
                ) {
                    AppSettingsStore.VisionModels.VISION_MODEL_OPTIONS.forEach { (modelId: String, displayName: String, description: String) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = displayName,
                                        color = if (modelId == visionModel) PetalPrimary else textPrimaryColor,
                                        fontSize = 14.sp,
                                        fontWeight = if (modelId == visionModel) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = description,
                                        color = textSecondaryColor,
                                        fontSize = 11.sp
                                    )
                                }
                            },
                            onClick = {
                                onVisionModelChanged(modelId)
                                showVisionModelDropdown = false
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun formatModelSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) {
        String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    } else {
        String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    }

@Composable
fun ApiTutorialCard(
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val cardBg = AppTheme.colors.surfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(24.dp),
                surfaceColor = cardBg
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        onClick = { expanded = !expanded }
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    @Suppress("DEPRECATION")
                    Icon(
                        imageVector = AppIcons.CircleQuestionMark,
                        contentDescription = "教程",
                        tint = PetalPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "不知道API是什么？点击查看教程",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = textPrimaryColor
                    )
                }
                Icon(
                    imageVector = if (expanded) AppIcons.ChevronUp else AppIcons.ChevronDown,
                    contentDescription = "展开",
                    tint = textSecondaryColor
                )
            }

            if (expanded) {
                HorizontalDivider(color = PetalPrimary.copy(alpha = 0.1f))

                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    TutorialStepItem(
                        stepNumber = "1",
                        title = "什么是API？",
                        content = "API就像一个\"电话号码\"，让你能和AI对话。没有API，这个软件就没法和AI聊天。",
                        isDarkTheme, textPrimaryColor, textSecondaryColor
                    )

                    TutorialStepItem(
                        stepNumber = "2",
                        title = "怎么获取API？",
                        content = "去AI平台注册账号，找到\"API密钥\"或\"API Key\"，复制一串以sk-开头的字符串就行。",
                        isDarkTheme, textPrimaryColor, textSecondaryColor
                    )

                    TutorialStepItem(
                        stepNumber = "3",
                        title = "推荐平台（新手友好）",
                        content = "• 深度求索(DeepSeek)：便宜好用，中文强\n• 硅基流动(SiliconFlow)：免费额度多\n• OpenRouter：一个Key用多个模型\n• Kimi：月之暗面出品，中文理解好",
                        isDarkTheme, textPrimaryColor, textSecondaryColor
                    )

                    TutorialStepItem(
                        stepNumber = "4",
                        title = "怎么配置？",
                        content = "1. 点击上面的「+添加API配置」\n2. 选择你的平台\n3. 把复制的API Key粘贴进去\n4. 点保存就完成了！",
                        isDarkTheme, textPrimaryColor, textSecondaryColor
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Button(
                            onClick = {
                                try {
                                    val uri = android.net.Uri.parse("https://platform.deepseek.com/api_keys")
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                                    context.startActivity(intent)
                                } catch (e: Exception) {}
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = PetalPrimary),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            @Suppress("DEPRECATION")
                            Icon(imageVector = AppIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("去DeepSeek获取免费API Key", color = AppTheme.colors.onPrimary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TutorialStepItem(
    stepNumber: String,
    title: String,
    content: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(PetalPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = stepNumber, color = PetalPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, color = textPrimaryColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = content, color = textSecondaryColor, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}
