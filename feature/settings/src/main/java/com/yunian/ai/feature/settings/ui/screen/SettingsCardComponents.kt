package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.icon.AppIcons

import com.yunian.ai.uicommon.theme.AppTheme
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.PetalPrimaryContainer
import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.yunian.ai.database.model.ApiProvider
import com.yunian.ai.domain.ModelState
import com.yunian.ai.domain.ModelStatus
import com.yunian.ai.feature.settings.R
import com.yunian.ai.common.AppSettingsStore
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelSelectionCard(
    state: ModelState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBackground = AppTheme.colors.surfaceVariant
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shape = RoundedCornerShape(12.dp)
    val surfaceColor = if (isPressed || state.isSelected) {
        cardBackground.copy(alpha = 0.85f)
    } else {
        cardBackground
    }

    Column(
        modifier = modifier
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = shape,
                surfaceColor = surfaceColor
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect
            )
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = state.displayName,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = AppTheme.colors.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        val sizeText = formatModelSize(state.expectedBytes)
        Text(
            text = sizeText,
            fontSize = 12.sp,
            color = AppTheme.colors.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        when (state.status) {
            ModelStatus.NOT_DOWNLOADED -> {
                if (state.downloadUrl.isBlank()) {
                    Text(
                        text = "手动部署",
                        fontSize = 12.sp,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                } else {
                    GlassButton(
                        onClick = onDownload,
                        height = 40.dp,
                        horizontalPadding = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("下载", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            ModelStatus.DOWNLOADING -> {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${state.progressPercent}%",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    GlassButton(
                        onClick = onCancel,
                        height = 40.dp,
                        horizontalPadding = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("取消", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ModelStatus.DOWNLOADED -> {
                if (state.isSelected) {
                    GlassButton(
                        onClick = onEnable,
                        height = 40.dp,
                        horizontalPadding = 12.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("启用", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    Text(
                        text = "已下载",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            ModelStatus.ENABLED -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    GlassButton(
                        onClick = onDisable,
                        height = 40.dp,
                        horizontalPadding = 12.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("禁用", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    GlassButton(
                        onClick = onDelete,
                        height = 40.dp,
                        horizontalPadding = 12.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("删除", fontSize = 10.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            ModelStatus.FAILED -> {
                Column {
                    Text(
                        text = state.errorMessage ?: "下载失败",
                        fontSize = 12.sp,
                        color = AppTheme.colors.error
                    )
                    if (state.downloadUrl.isNotBlank()) {
                        GlassButton(
                            onClick = onDownload,
                            height = 40.dp,
                            horizontalPadding = 12.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重试", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        if (state.isSelected && state.status == ModelStatus.ENABLED) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "当前使用",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

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

private fun formatModelSize(bytes: Long): String =
    if (bytes >= 1_000_000_000L) {
        String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    } else {
        String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
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
