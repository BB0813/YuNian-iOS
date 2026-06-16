package com.lianyu.ai.feature.settings.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.database.model.ApiConfig
import com.lianyu.ai.database.model.ApiProvider
import com.lianyu.ai.feature.settings.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.delay

// ============================================================
// Petal Color Constants
// ============================================================

internal val PetalBackgroundStart = Color(0xFFFBF9F8)
internal val PetalBackgroundEnd = Color(0xFFFFF0F3)
internal val PetalPrimary = Color(0xFF894C5C)
internal val PetalPrimaryContainer = Color(0xFFF4A7B9)
internal val PetalOnPrimaryContainer = Color(0xFF733949)
internal val PetalSurface = Color(0xFFFFFFFF)
internal val PetalSurfaceContainer = Color(0xFFEFEDED)
internal val PetalSurfaceContainerLow = Color(0xFFF5F3F3)
internal val PetalSecondaryContainer = Color(0xFFEBDCDF)
internal val PetalOnSurface = Color(0xFF1B1C1C)
internal val PetalOnSurfaceVariant = Color(0xFF524346)
internal val PetalOutlineVariant = Color(0xFFD6C1C5)
internal val PetalError = Color(0xFFBA1A1A)
internal val PetalErrorContainer = Color(0xFFFFDAD6)
internal val PetalGreen = Color(0xFF10A37F)
internal val PetalGreenLight = Color(0xFFE8F5E9)
internal val PetalOrange = Color(0xFFFFA726)

// ============================================================
// PetalStatChip - 状态标签小组件
// ============================================================

@Composable
fun PetalStatChip(icon: String, text: String, color: Color, isDarkTheme: Boolean) {
    val bgColor = if (isDarkTheme) color.copy(alpha = 0.12f) else PetalSurfaceContainerLow

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = icon,
                color = color,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = text,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

// ============================================================
// ApiConfigEditDialog - 内联 API 配置编辑弹窗
// ============================================================

@Composable
private fun PetalApiConfigEditDialog(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    onDismiss: () -> Unit,
    onSave: (ApiConfig) -> Unit,
    onTest: (ApiConfig) -> Unit,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onFetchModels: ((String, String) -> Unit)? = null,
    availableModels: List<String> = emptyList(),
    modelFetchState: SettingsViewModel.ModelFetchState = SettingsViewModel.ModelFetchState()
) {
    val cardBackground = if (isDarkTheme) PetalPrimaryContainer.copy(alpha = 0.15f) else PetalSurface
    val dividerColor = if (isDarkTheme) PetalOutlineVariant.copy(alpha = 0.3f) else PetalSurfaceContainer
    val textTertiary = if (isDarkTheme) PetalOutlineVariant else PetalOutlineVariant

    var apiKey by remember { mutableStateOf(config.apiKey) }
    var extraApiKeys by remember { mutableStateOf(config.extraApiKeys) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }
    var temperature by remember { mutableFloatStateOf(config.temperature) }
    var maxTokens by remember { mutableStateOf(config.maxTokens?.toString() ?: "") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var lastFetchedParams by remember { mutableStateOf("") }

    val isPartner = config.provider == ApiProvider.PARTNER
    val isValid = apiKey.isNotBlank() || baseUrl.isNotBlank() || isPartner
    val hasModels = availableModels.isNotEmpty()

    val selectedModelText = when {
        modelFetchState.isLoading -> "正在获取模型列表..."
        hasModels -> model.ifEmpty { "请选择模型" }
        isPartner && apiKey.isBlank() -> "密钥将从服务器自动获取，直接点击测试"
        else -> "填写密钥后自动拉取模型"
    }

    LaunchedEffect(apiKey, baseUrl, isPartner) {
        val fetchParams = baseUrl.trim() + "|" + apiKey.trim()
        val shouldFetch = isPartner && baseUrl.isNotBlank() && fetchParams != lastFetchedParams
        if (shouldFetch) {
            delay(600)
            lastFetchedParams = fetchParams
            val keyToUse = apiKey.trim()
            onFetchModels?.invoke(baseUrl, keyToUse)
        }
    }

    LaunchedEffect(availableModels, isPartner) {
        if (isPartner && model.isBlank() && availableModels.isNotEmpty()) {
            model = availableModels.first()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardBackground,
        title = {
            Text(
                text = "${config.provider.displayName} 配置",
                color = textPrimaryColor,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（主）", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true
                )

                OutlinedTextField(
                    value = extraApiKeys,
                    onValueChange = { extraApiKeys = it },
                    label = { Text("备用 API Key（逗号分隔，轮询切换）", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next
                    ),
                    singleLine = true,
                    placeholder = {
                        Text("sk-xxx,sk-xxx,...", color = textTertiary, fontSize = 12.sp)
                    }
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true
                )

                if (hasModels && onFetchModels != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedModelText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Model", color = textSecondaryColor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showModelDropdown = !showModelDropdown },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PetalPrimary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = cardBackground,
                                unfocusedContainerColor = cardBackground,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            trailingIcon = {
                                Icon(
                                    imageVector = if (showModelDropdown) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = "展开",
                                    modifier = Modifier.clickable { showModelDropdown = !showModelDropdown },
                                    tint = textSecondaryColor
                                )
                            },
                            singleLine = true
                        )
                        if (!modelFetchState.isLoading) {
                            DropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false },
                                modifier = Modifier.fillMaxWidth(0.8f)
                            ) {
                                availableModels.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text(m, color = textPrimaryColor, fontSize = 14.sp) },
                                        onClick = {
                                            model = m
                                            showModelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    if (modelFetchState.isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = PetalPrimary
                        )
                        Text(
                            text = "正在获取模型列表...",
                            fontSize = 12.sp,
                            color = textSecondaryColor
                        )
                    }

                    modelFetchState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            fontSize = 12.sp,
                            color = PetalError
                        )
                    }

                    if (hasModels && !modelFetchState.isLoading) {
                        Button(
                            onClick = {
                                val keyToUse = apiKey.trim().ifBlank {
                                    ApiConfig.BUILTIN_KEYS[ApiProvider.PARTNER]?.firstOrNull() ?: ""
                                }
                                onFetchModels?.invoke(baseUrl, keyToUse)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PetalPrimary.copy(alpha = 0.15f),
                                contentColor = PetalPrimary
                            )
                        ) {
                            Text(if (hasModels) "重新获取模型列表" else "获取模型列表", fontSize = 13.sp)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PetalPrimary,
                            unfocusedBorderColor = dividerColor,
                            focusedContainerColor = cardBackground,
                            unfocusedContainerColor = cardBackground,
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true
                    )
                }

                Text(
                    text = "Temperature: ${String.format("%.1f", temperature)}",
                    color = textPrimaryColor,
                    fontSize = 14.sp
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..2f,
                    steps = 19,
                    colors = SliderDefaults.colors(
                        thumbColor = PetalPrimary,
                        activeTrackColor = PetalPrimary
                    )
                )

                OutlinedTextField(
                    value = maxTokens,
                    onValueChange = { maxTokens = it.filter { c -> c.isDigit() } },
                    label = { Text("Max Tokens", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = cardBackground,
                        unfocusedContainerColor = cardBackground,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val currentConfig = config.copy(
                            apiKey = apiKey.trim(),
                            extraApiKeys = extraApiKeys.trim(),
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            temperature = temperature,
                            maxTokens = maxTokens.toIntOrNull()
                        )
                        onTest(currentConfig)
                    },
                    enabled = isValid && connectionResult.status != SettingsViewModel.ConnectionStatus.TESTING,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PetalGreenLight,
                        contentColor = PetalGreen
                    )
                ) {
                    if (connectionResult.status == SettingsViewModel.ConnectionStatus.TESTING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = PetalGreen,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("测试")
                    }
                }

                Button(
                    onClick = {
                        onSave(
                            config.copy(
                                apiKey = apiKey.trim(),
                                extraApiKeys = extraApiKeys.trim(),
                                baseUrl = baseUrl.trim(),
                                model = model.trim(),
                                temperature = temperature,
                                maxTokens = maxTokens.toIntOrNull()
                            )
                        )
                    },
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = PetalPrimary)
                ) {
                    Text("保存")
                }
            }
        },
        dismissButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = textSecondaryColor
                )
            ) {
                Text("取消")
            }
        }
    )
}

// ============================================================
// PetalApiCard - 主要 API 配置卡片 (Clove/PARTNER)
// ============================================================

@Composable
fun PetalApiCard(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    isExpanded: Boolean,
    isActive: Boolean,
    onExpandToggle: () -> Unit,
    onEdit: (ApiConfig) -> Unit,
    onTest: (ApiConfig) -> Unit,
    onToggleEnabled: () -> Unit,
    onSelectActive: () -> Unit,
    onFetchModels: (String, String, String) -> Unit,
    fetchedModels: Map<String, List<String>>,
    modelFetchStates: Map<String, SettingsViewModel.ModelFetchState>,
    testedConfigs: Map<String, ApiConfig>,
    connectionKey: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    balanceInfo: com.lianyu.ai.network.AiService.BalanceInfo? = null,
    balanceQueryFailed: Boolean = false,
    onQueryBalance: (() -> Unit)? = null
) {
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }
    val cardColor = if (isDarkTheme) PetalPrimaryContainer.copy(alpha = 0.15f) else PetalSurface

    // 余额查询：连接成功后才自动查一次
    var balanceQueried by remember { mutableStateOf(false) }
    val isConnected = connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED
    LaunchedEffect(isConnected) {
        if (isConnected && !balanceQueried) {
            onQueryBalance?.invoke()
            balanceQueried = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.provider.displayName,
                        color = textPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (config.name.isNotEmpty()) {
                        Text(
                            text = config.name,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Connection status indicator
                    val statusColor = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED -> PetalGreen
                        SettingsViewModel.ConnectionStatus.FAILED -> PetalError
                        SettingsViewModel.ConnectionStatus.TESTING -> PetalOrange
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> textSecondaryColor
                    }
                    val statusText = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED -> "已连接"
                        SettingsViewModel.ConnectionStatus.FAILED -> "失败"
                        SettingsViewModel.ConnectionStatus.TESTING -> "测试中"
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> "未测试"
                    }
                    PetalStatChip(
                        icon = "\u25CF",
                        text = statusText,
                        color = statusColor,
                        isDarkTheme = isDarkTheme
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                // Model info
                if (config.model.isNotEmpty()) {
                    Text(
                        text = "模型: ${config.model}",
                        color = textSecondaryColor,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Base URL
                if (config.baseUrl.isNotEmpty()) {
                    Text(
                        text = "地址: ${config.baseUrl}",
                        color = textSecondaryColor.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Balance info
                if (balanceInfo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PetalSurfaceContainerLow)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "全服余额",
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        if (balanceInfo.remainingBalance != null) {
                            val bal = balanceInfo.remainingBalance
                            Text(
                                text = "$${"%.2f".format(bal)}",
                                color = PetalGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "查询中...",
                                color = textSecondaryColor,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (balanceQueryFailed) {
                    Text(
                        text = "余额查询失败",
                        color = PetalError,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        editingConfig = testedConfigs[connectionKey] ?: config
                    }) {
                        Text("编辑", color = PetalPrimary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { onTest(config) }) {
                        Text("测试", color = PetalPrimary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onSelectActive) {
                        Text("设为活跃", color = PetalPrimary, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // 编辑弹窗 - INLINE inside PetalApiCard
    editingConfig?.let { editing ->
        val providerModels = fetchedModels[editing.provider.name] ?: emptyList()
        val fetchState = modelFetchStates[editing.provider.name] ?: SettingsViewModel.ModelFetchState()
        PetalApiConfigEditDialog(
            config = editing,
            connectionResult = connectionResult,
            onDismiss = { editingConfig = null },
            onSave = { updated: ApiConfig ->
                onEdit(updated)
                editingConfig = null
            },
            onTest = { testConfig: ApiConfig -> onTest(testConfig) },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            onFetchModels = { baseUrl: String, apiKey: String ->
                onFetchModels(baseUrl, apiKey, editing.provider.name)
            },
            availableModels = providerModels,
            modelFetchState = fetchState
        )
    }
}

// ============================================================
// PetalSavedApiCard - 已保存的其他 API 配置卡片
// ============================================================

@Composable
fun PetalSavedApiCard(
    config: ApiConfig,
    connectionResult: SettingsViewModel.ConnectionResult,
    isExpanded: Boolean,
    isActive: Boolean,
    onExpandToggle: () -> Unit,
    onEdit: (ApiConfig) -> Unit,
    onDelete: () -> Unit,
    onTest: (ApiConfig) -> Unit,
    onToggleEnabled: () -> Unit,
    onSelectActive: () -> Unit,
    onFetchModels: (String, String, String) -> Unit,
    fetchedModels: Map<String, List<String>>,
    modelFetchStates: Map<String, SettingsViewModel.ModelFetchState>,
    testedConfigs: Map<String, ApiConfig>,
    connectionKey: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    balanceInfo: com.lianyu.ai.network.AiService.BalanceInfo? = null,
    balanceQueryFailed: Boolean = false,
    onQueryBalance: (() -> Unit)? = null
) {
    var editingConfig by remember { mutableStateOf<ApiConfig?>(null) }
    val cardColor = if (isDarkTheme) PetalPrimaryContainer.copy(alpha = 0.08f) else PetalSurfaceContainer

    // 余额查询：连接成功后才自动查一次
    var balanceQueried by remember { mutableStateOf(false) }
    val isConnected = connectionResult.status == SettingsViewModel.ConnectionStatus.CONNECTED
    LaunchedEffect(isConnected) {
        if (isConnected && !balanceQueried) {
            onQueryBalance?.invoke()
            balanceQueried = true
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onExpandToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = config.provider.displayName,
                        color = textPrimaryColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    if (config.name.isNotEmpty()) {
                        Text(
                            text = config.name,
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Active indicator
                    if (isActive) {
                        PetalStatChip(
                            icon = "\u2713",
                            text = "活跃",
                            color = PetalGreen,
                            isDarkTheme = isDarkTheme
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    // Connection status indicator
                    val statusColor = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED -> PetalGreen
                        SettingsViewModel.ConnectionStatus.FAILED -> PetalError
                        SettingsViewModel.ConnectionStatus.TESTING -> PetalOrange
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> textSecondaryColor
                    }
                    val statusText = when (connectionResult.status) {
                        SettingsViewModel.ConnectionStatus.CONNECTED -> "已连接"
                        SettingsViewModel.ConnectionStatus.FAILED -> "失败"
                        SettingsViewModel.ConnectionStatus.TESTING -> "测试中"
                        SettingsViewModel.ConnectionStatus.UNKNOWN -> "未测试"
                    }
                    PetalStatChip(
                        icon = "\u25CF",
                        text = statusText,
                        color = statusColor,
                        isDarkTheme = isDarkTheme
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (isExpanded) "收起" else "展开",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))

                // Model info
                if (config.model.isNotEmpty()) {
                    Text(
                        text = "模型: ${config.model}",
                        color = textSecondaryColor,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Base URL
                if (config.baseUrl.isNotEmpty()) {
                    Text(
                        text = "地址: ${config.baseUrl}",
                        color = textSecondaryColor.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Balance info
                if (balanceInfo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(PetalSurfaceContainerLow)
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "余额查询",
                            color = textSecondaryColor,
                            fontSize = 12.sp
                        )
                        if (balanceInfo.remainingBalance != null) {
                            val bal = balanceInfo.remainingBalance
                            Text(
                                text = "$%.2f".format(bal),
                                color = PetalGreen,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "余额: 未知",
                                color = textSecondaryColor,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                } else if (balanceQueryFailed) {
                    Text(
                        text = "余额查询失败",
                        color = PetalError,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = PetalError, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = {
                        editingConfig = testedConfigs[connectionKey] ?: config
                    }) {
                        Text("编辑", color = PetalPrimary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { onTest(config) }) {
                        Text("测试", color = PetalPrimary, fontSize = 13.sp)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = onSelectActive) {
                        Text("设为活跃", color = PetalPrimary, fontSize = 13.sp)
                    }
                }
            }
        }
    }

    // 编辑弹窗 - INLINE inside PetalSavedApiCard
    editingConfig?.let { editing ->
        val providerModels = fetchedModels[editing.provider.name] ?: emptyList()
        val fetchState = modelFetchStates[editing.provider.name] ?: SettingsViewModel.ModelFetchState()
        PetalApiConfigEditDialog(
            config = editing,
            connectionResult = connectionResult,
            onDismiss = { editingConfig = null },
            onSave = { updated: ApiConfig ->
                onEdit(updated)
                editingConfig = null
            },
            onTest = { testConfig: ApiConfig -> onTest(testConfig) },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            onFetchModels = { baseUrl: String, apiKey: String ->
                onFetchModels(baseUrl, apiKey, editing.provider.name)
            },
            availableModels = providerModels,
            modelFetchState = fetchState
        )
    }
}

// ============================================================
// PetalAddApiButton - 添加 API 配置按钮
// ============================================================

@Composable
fun PetalAddApiButton(onClick: () -> Unit, isDarkTheme: Boolean) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = PetalPrimary
        )
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "添加API",
            modifier = Modifier.size(18.dp),
            tint = PetalPrimary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "添加API配置",
            color = PetalPrimary,
            fontSize = 14.sp
        )
    }
}
