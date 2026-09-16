@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen
import com.yunian.ai.uicommon.component.glass.GlassButton
import com.yunian.ai.uicommon.icon.AppIcons


import com.yunian.ai.uicommon.theme.AppTheme
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
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
import com.yunian.ai.database.model.ApiProviderPreset
import com.yunian.ai.feature.settings.ui.viewmodel.SettingsViewModel
import com.yunian.ai.uicommon.theme.PetalPrimary
import com.yunian.ai.uicommon.theme.ThemeMode
import com.yunian.ai.uicommon.theme.ThemeViewModel
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.common.AppSettingsStore
import com.yunian.ai.common.SecureLog
import kotlinx.coroutines.delay
import androidx.compose.foundation.isSystemInDarkTheme

/**
 * 本地模型卡片 UI 开关。
 *
 * 本地模型的实际使用体验较差，暂时隐藏该区域入口；为保证「不影响任何现有功能」，
 * 逻辑层（SettingsViewModel 的 modelStates / downloadModel / selectModel / enableGemma /
 * disableGemma / deleteGemma 等）与数据流、localmodel 模块、ServiceRegistry 注册均保持不变。
 * 仅隐藏 UI 入口，隐藏时零渲染、零占位（连专属的 16dp Spacer 一并隐藏，避免底部留白）。
 *
 * 如需恢复入口，将本常量改为 true 即可（组件 ModelSelectionCard 仍完整保留）。
 */
private const val SHOW_LOCAL_MODEL_CARDS = false

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val configs by viewModel.configs.collectAsState(initial = emptyList())
    val providerPresets by viewModel.providerPresets.collectAsState(initial = emptyList())
    val modelStates by viewModel.modelStates.collectAsState()
    val localModelState by viewModel.localModelState.collectAsState()
    val fetchedModels by viewModel.fetchedModels.collectAsState()
    val modelFetchStates by viewModel.modelFetchStates.collectAsState()
    val balanceInfo by viewModel.balanceInfo.collectAsState()
    val balanceQueryFailed by viewModel.balanceQueryFailed.collectAsState()

    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val testedConfigs by viewModel.testedConfigs.collectAsState()
    val visionEnabled by viewModel.visionEnabled.collectAsState()
    val visionModel by viewModel.visionModel.collectAsState()
    val diaryEnabled by viewModel.diaryEnabled.collectAsState()
    val diaryModel by viewModel.diaryModel.collectAsState()
    var expandedProvider by remember { mutableStateOf<ApiProvider?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var newConfigDialog by remember { mutableStateOf<ApiConfig?>(null) }
    var showProviderPicker by remember { mutableStateOf(false) }
    var showVisionModelSettings by remember { mutableStateOf(false) }
    var showDiaryModelSettings by remember { mutableStateOf(false) }
    var showImageGenSettings by remember { mutableStateOf(false) }

    var showApiTestDialog by remember { mutableStateOf(false) }
    var apiTestResult by remember { mutableStateOf<ApiTestDialogData?>(null) }
    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.saveResult.collect { result ->
            when (result) {
                is SettingsViewModel.SaveResult.Success -> snackbarHostState.showSnackbar(result.message)
                is SettingsViewModel.SaveResult.Error -> snackbarHostState.showSnackbar(result.message)
            }
        }
    }

    val colorScheme = AppTheme.colors
    val backgroundColor = colorScheme.background
    val textPrimaryColor = colorScheme.onSurface
    val textSecondaryColor = colorScheme.onSurfaceVariant
    val textTertiaryColor = colorScheme.outlineVariant
    val dividerColor = colorScheme.outline
    val cardBackground = colorScheme.surfaceVariant
    val pageBackdrop = LocalPageBackdrop.current

    LaunchedEffect(Unit) {
        viewModel.refreshLocalModel()
        viewModel.refreshConnectionStatus()
        viewModel.refreshPartnerQuota()
        delay(100)
        isVisible = true
    }

    LaunchedEffect(Unit) {
        viewModel.testCompletionEvent.collect { event ->
            apiTestResult = ApiTestDialogData(
                isSuccess = event.isSuccess,
                providerName = event.providerName,
                latencyMs = event.latencyMs,
                errorMessage = event.errorMessage
            )
            showApiTestDialog = true
        }
    }

    GlassPageScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "API 设置",
                onBack = onNavigateBack,
                actions = {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.Heart,
                            contentDescription = "Favorite",
                            tint = PetalPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()

                .padding(top = paddingValues.calculateTopPadding())
                .verticalScroll(rememberScrollState())
        ) {

            Spacer(modifier = Modifier.height(16.dp))

            ApiCardsSection(
                isVisible = isVisible,
                configs = configs,
                providerPresets = providerPresets,
                viewModel = viewModel,
                expandedProvider = expandedProvider,
                onExpandedProviderChange = { expandedProvider = it },
                fetchedModels = fetchedModels,
                modelFetchStates = modelFetchStates,
                isDarkTheme = isDarkTheme,
                textPrimaryColor = textPrimaryColor,
                textSecondaryColor = textSecondaryColor,
                balanceInfo = balanceInfo,
                balanceQueryFailed = balanceQueryFailed,
                showProviderPicker = showProviderPicker,
                onShowProviderPickerChange = { showProviderPicker = it },

                connectionStatus = connectionStatus,
                testedConfigs = testedConfigs
            )

            Spacer(modifier = Modifier.height(20.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 200)) +
                        slideInVertically(tween(400, delayMillis = 200)) { it / 4 }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .drawGlass(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = AppTheme.colors.surfaceVariant
                        )
                        .clickable { showVisionModelSettings = true }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Eye,
                                contentDescription = null,
                                tint = PetalPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "视觉模型设置",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimaryColor
                            )
                            Text(
                                text = if (visionEnabled) "已启用 - ${AppSettingsStore.VisionModels.getVisionModelDisplayName(visionModel)}" else "点击配置图片识别",
                                fontSize = 12.sp,
                                color = textSecondaryColor
                            )
                        }
                    }

                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = "进入设置",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 250)) +
                        slideInVertically(tween(400, delayMillis = 250)) { it / 4 }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .drawGlass(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = AppTheme.colors.surfaceVariant
                        )
                        .clickable { showDiaryModelSettings = true }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Book,
                                contentDescription = null,
                                tint = PetalPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "日记模型设置",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimaryColor
                            )
                            Text(
                                text = if (diaryEnabled) "已启用 - ${diaryModel.ifBlank { "自定义模型" }}" else "AI 生成日记使用主 API",
                                fontSize = 12.sp,
                                color = textSecondaryColor
                            )
                        }
                    }

                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = "进入设置",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 300)) +
                        slideInVertically(tween(400, delayMillis = 300)) { it / 4 }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .drawGlass(
                            backdrop = pageBackdrop,
                            shape = RoundedCornerShape(20.dp),
                            surfaceColor = AppTheme.colors.surfaceVariant
                        )
                        .clickable { showImageGenSettings = true }
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(PetalPrimaryContainer.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = AppIcons.Sparkles,
                                contentDescription = null,
                                tint = PetalPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Column {
                            Text(
                                text = "AI 生图",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = textPrimaryColor
                            )
                            Text(
                                text = "接入主流生图接口，支持概率与关键词自动配图",
                                fontSize = 12.sp,
                                color = if (isDarkTheme) textSecondaryColor else Color.Black
                            )
                        }
                    }

                    Icon(
                        imageVector = AppIcons.ArrowLeft,
                        contentDescription = "进入设置",
                        tint = textSecondaryColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (SHOW_LOCAL_MODEL_CARDS) {
                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 250)) +
                            slideInVertically(tween(400, delayMillis = 250)) { it / 4 }
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val modelEntries = modelStates.entries.toList()
                        modelEntries.forEach { (modelId, state) ->
                            ModelSelectionCard(
                                state = state,
                                onSelect = { viewModel.selectModel(modelId) },
                                onDownload = { viewModel.downloadModel(modelId) },
                                onCancel = { viewModel.cancelGemmaDownload() },
                                onEnable = { viewModel.enableGemma() },
                                onDisable = { viewModel.disableGemma() },
                                onDelete = { viewModel.deleteGemma() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    newConfigDialog?.let { newConfig ->
        val providerModels = fetchedModels[newConfig.provider.name] ?: emptyList()
        val fetchState = modelFetchStates[newConfig.provider.name] ?: SettingsViewModel.ModelFetchState()
        ApiConfigEditDialog(
            config = newConfig,
            connectionResult = SettingsViewModel.ConnectionResult(SettingsViewModel.ConnectionStatus.UNKNOWN),
            onDismiss = { newConfigDialog = null },
            onSave = { config: ApiConfig ->
                viewModel.saveConfig(config)
                newConfigDialog = null
            },
            onTest = { testConfig: ApiConfig -> viewModel.testConnection(testConfig) },
            onFetchModels = { baseUrl: String, apiKey: String ->
                viewModel.fetchModels(baseUrl, apiKey, newConfig.provider.name)
            },
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            availableModels = providerModels,
            modelFetchState = fetchState
        )
    }

    if (showProviderPicker) {
        val visiblePresets = providerPresets.filter { it.provider != ApiProvider.PARTNER }
        val cardBackground = AppTheme.colors.surfaceVariant
        val dividerColor = AppTheme.colors.outline

        GlassEditDialog(
            onDismissRequest = { showProviderPicker = false },
            title = "选择 API 提供商",
            titleColor = textPrimaryColor,
            actions = {
                GlassButton(
                    onClick = { showProviderPicker = false },
                    height = 44.dp,
                    horizontalPadding = 16.dp
                ) {
                    Text("取消", color = textSecondaryColor)
                }
            }
        ) {
            // 注意：这里不要再加 verticalScroll —— GlassEditDialog 内部内容区已经是滚动容器，
            // 嵌套同方向滚动会让内层拿到无限高约束，抛 IllegalStateException 闪退
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                visiblePresets.forEach { preset ->
                    GlassButton(
                        onClick = {
                            showProviderPicker = false
                            newConfigDialog = ApiConfig(
                                name = preset.displayName,
                                provider = preset.provider,
                                apiKey = "",
                                baseUrl = preset.baseUrl,
                                model = preset.model,
                                formatHint = preset.formatHint
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        height = 60.dp,
                        horizontalPadding = 16.dp
                    ) {
                        // GlassButton 内部 Row 是"水平居中"排列，列表项需要占满宽度左对齐，
                        // 所以这里再包一层 fillMaxWidth 的 Row 承载图标与文字
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ProviderLogo(
                                provider = preset.provider,
                                size = 36.dp,
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.displayName,
                                    color = textPrimaryColor,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = preset.baseUrl,
                                    color = textSecondaryColor,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 集中拦截系统返回键 / 返回手势：当任一子页面打开时，优先关闭子页面，
    // 避免直接弹出整个设置页回到主页面（仅当子页面可见时才启用，保证设置页自身返回行为不受影响）。
    BackHandler(
        enabled = showVisionModelSettings || showDiaryModelSettings || showImageGenSettings
    ) {
        when {
            showVisionModelSettings -> showVisionModelSettings = false
            showDiaryModelSettings -> showDiaryModelSettings = false
            showImageGenSettings -> showImageGenSettings = false
        }
    }

    // 三个子页面为全屏覆盖层，用 AnimatedVisibility 做「从右侧滑入 / 退回右侧」的过渡动画，
    // 符合「进入下一级 / 返回上一级」的心理模型；容器补 fillMaxSize 避免动画期间尺寸跳动或被裁剪。
    AnimatedVisibility(
        visible = showVisionModelSettings,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(animationSpec = tween(280)) { it / 4 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(220)) { it / 4 } + fadeOut(tween(160))
    ) {
        VisionModelSettingsScreen(
            onNavigateBack = { showVisionModelSettings = false },
            viewModel = viewModel
        )
    }

    AnimatedVisibility(
        visible = showDiaryModelSettings,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(animationSpec = tween(280)) { it / 4 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(220)) { it / 4 } + fadeOut(tween(160))
    ) {
        DiaryModelSettingsScreen(
            onNavigateBack = { showDiaryModelSettings = false },
            viewModel = viewModel
        )
    }

    AnimatedVisibility(
        visible = showImageGenSettings,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(animationSpec = tween(280)) { it / 4 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(220)) { it / 4 } + fadeOut(tween(160))
    ) {
        ImageGenSettingsScreen(
            onNavigateBack = { showImageGenSettings = false }
        )
    }

    if (showApiTestDialog && apiTestResult != null) {
        ApiTestResultDialog(
            data = apiTestResult!!,
            isDarkTheme = isDarkTheme,
            onDismiss = { showApiTestDialog = false }
        )
    }
}

data class ApiTestDialogData(
    val isSuccess: Boolean,
    val providerName: String,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

@Composable
private fun ApiTestResultDialog(
    data: ApiTestDialogData,
    isDarkTheme: Boolean,
    onDismiss: () -> Unit
) {
    GlassEditDialog(
        onDismissRequest = onDismiss,
        title = if (data.isSuccess) "连接成功！" else "连接失败",
        titleColor = if (data.isSuccess) PetalGreen else PetalError,
        actions = {
            GlassButton(
                onClick = onDismiss,
                height = 44.dp,
                horizontalPadding = 20.dp
            ) {
                Text(
                    "知道了",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (data.isSuccess) AppIcons.Check else AppIcons.X,
                contentDescription = null,
                tint = if (data.isSuccess) PetalGreen else PetalError,
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = if (data.isSuccess) "${data.providerName} API 连接测试通过" else "${data.providerName} API 无法连接，请检查配置",
                color = AppTheme.colors.onSurface,
                fontSize = 14.sp
            )

            if (data.isSuccess && data.latencyMs > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(PetalGreen.copy(alpha = 0.08f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Clock, null, Modifier.size(14.dp), tint = PetalGreen)
                        Spacer(Modifier.width(4.dp))
                        Text(text = "响应延迟: ${data.latencyMs}ms", color = PetalGreen, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    val (latencyIcon, latencyText) = when {
                        data.latencyMs < 500 -> AppIcons.Zap to "延迟优秀，连接速度很快"
                        data.latencyMs < 1500 -> AppIcons.Check to "延迟正常，可以正常使用"
                        else -> AppIcons.TriangleAlert to "延迟较高，可能影响体验"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(latencyIcon, null, Modifier.size(14.dp), tint = AppTheme.colors.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(latencyText, color = AppTheme.colors.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }

            if (!data.isSuccess && data.errorMessage != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(PetalErrorContainer.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(text = data.errorMessage, color = PetalError, fontSize = 13.sp)
                }

                Text(
                    text = "常见问题：\n• API Key 是否正确\n• Base URL 是否填到 /chat/completions 的父层级（如 https://api.openai.com/v1）\n• 网络连接是否正常\n• 该服务商是否支持当前模型",
                    color = AppTheme.colors.onSurfaceVariant,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun ApiCardsSection(
    isVisible: Boolean,
    configs: List<ApiConfig>,
    providerPresets: List<ApiProviderPreset>,
    viewModel: SettingsViewModel,
    expandedProvider: ApiProvider?,
    onExpandedProviderChange: (ApiProvider?) -> Unit,
    fetchedModels: Map<String, List<String>>,
    modelFetchStates: Map<String, SettingsViewModel.ModelFetchState>,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    balanceInfo: com.yunian.ai.network.AiService.BalanceInfo?,
    balanceQueryFailed: Boolean,
    showProviderPicker: Boolean,
    onShowProviderPickerChange: (Boolean) -> Unit,

    connectionStatus: Map<String, SettingsViewModel.ConnectionResult>,
    testedConfigs: Map<String, ApiConfig>
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(400, delayMillis = 150)) +
                slideInVertically(tween(400, delayMillis = 150)) { it / 4 }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val partnerPreset = providerPresets.firstOrNull { it.provider == ApiProvider.PARTNER }
            val partnerConfig = configs.firstOrNull { it.provider == ApiProvider.PARTNER }
                ?: ApiConfig(
                    provider = ApiProvider.PARTNER,
                    apiKey = "",
                    extraApiKeys = "",
                    baseUrl = partnerPreset?.baseUrl ?: ApiProvider.PARTNER.defaultBaseUrl,
                    model = partnerPreset?.model ?: ApiProvider.PARTNER.defaultModel,
                    formatHint = partnerPreset?.formatHint ?: "openai"
                )

            val partnerExpanded = expandedProvider == ApiProvider.PARTNER

            PetalApiCard(
                config = partnerConfig,
                connectionResult = connectionStatus[viewModel.connectionKey(partnerConfig)]
                    ?: SettingsViewModel.ConnectionResult(SettingsViewModel.ConnectionStatus.UNKNOWN),
                isExpanded = partnerExpanded,
                isActive = partnerConfig.isEnabled,
                onExpandToggle = {
                    onExpandedProviderChange(if (partnerExpanded) null else ApiProvider.PARTNER)
                },
                onEdit = { selectedConfig: ApiConfig ->
                    viewModel.saveConfig(selectedConfig)
                },
                onTest = { selectedConfig: ApiConfig ->
                    viewModel.testConnection(selectedConfig)
                },
                onToggleEnabled = { viewModel.toggleConfigEnabled(partnerConfig) },
                onSelectActive = { viewModel.selectActiveConfig(partnerConfig) },
                onFetchModels = { baseUrl: String, apiKey: String, provider: String ->
                    viewModel.fetchModels(baseUrl, apiKey, provider)
                },
                fetchedModels = fetchedModels,
                modelFetchStates = modelFetchStates,
                testedConfigs = testedConfigs,
                connectionKey = viewModel.connectionKey(partnerConfig),
                isDarkTheme = isDarkTheme,
                textPrimaryColor = textPrimaryColor,
                textSecondaryColor = textSecondaryColor,
                balanceInfo = null,
                balanceQueryFailed = false,
                onQueryBalance = null
            )

            configs.filter { it.provider != ApiProvider.PARTNER }.forEach { config ->
                val result = connectionStatus[viewModel.connectionKey(config)]
                    ?: SettingsViewModel.ConnectionResult(SettingsViewModel.ConnectionStatus.UNKNOWN)
                val isExpanded = expandedProvider == config.provider

                PetalSavedApiCard(
                    config = config,
                    connectionResult = result,
                    isExpanded = isExpanded,
                    isActive = config.isEnabled,
                    onExpandToggle = {
                        onExpandedProviderChange(if (isExpanded) null else config.provider)
                    },
                    onEdit = { it: ApiConfig -> viewModel.saveConfig(it) },
                    onDelete = { viewModel.deleteConfig(config) },
                    onTest = { viewModel.testConnection(config) },
                    onToggleEnabled = { viewModel.toggleConfigEnabled(config) },
                    onSelectActive = { viewModel.selectActiveConfig(config) },
                    onFetchModels = { baseUrl: String, apiKey: String, provider: String ->
                        viewModel.fetchModels(baseUrl, apiKey, provider)
                    },
                    fetchedModels = fetchedModels,
                    modelFetchStates = modelFetchStates,
                    testedConfigs = testedConfigs,
                    connectionKey = viewModel.connectionKey(config),
                    isDarkTheme = isDarkTheme,
                    textPrimaryColor = textPrimaryColor,
                    textSecondaryColor = textSecondaryColor
                )
            }

            PetalAddApiButton(
                onClick = {
                    onShowProviderPickerChange(true)
                },
                isDarkTheme = isDarkTheme
            )
        }
    }
}

@Composable
private fun VisionModelSection(
    isVisible: Boolean,
    visionEnabled: Boolean,
    visionModel: String,
    isDarkTheme: Boolean,
    textPrimaryColor: Color,
    textSecondaryColor: Color,
    onVisionEnabledChanged: (Boolean) -> Unit,
    onVisionModelChanged: (String) -> Unit
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(400, delayMillis = 200)) +
                slideInVertically(tween(400, delayMillis = 200)) { it / 4 }
    ) {
        VisionModelSettingsCard(
            visionEnabled = visionEnabled,
            visionModel = visionModel,
            isDarkTheme = isDarkTheme,
            textPrimaryColor = textPrimaryColor,
            textSecondaryColor = textSecondaryColor,
            onVisionEnabledChanged = onVisionEnabledChanged,
            onVisionModelChanged = onVisionModelChanged
        )

        Spacer(modifier = Modifier.height(8.dp))

        ApiTutorialCard(isDarkTheme, textPrimaryColor, textSecondaryColor)
    }
}

@Composable
fun ApiConfigEditDialog(
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
    val cardBackground = AppTheme.colors.surfaceVariant
    val dividerColor = AppTheme.colors.outline
    val textTertiary = AppTheme.colors.outlineVariant
    // 玻璃底上的输入框用半透明容器色，保证文字可读
    val fieldContainerColor = cardBackground.copy(alpha = 0.62f)

    var apiKey by remember { mutableStateOf(config.apiKey) }
    var apiName by remember { mutableStateOf(config.name) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var model by remember { mutableStateOf(config.model) }
    var temperature by remember { mutableFloatStateOf(config.temperature) }
    var maxTokens by remember { mutableStateOf(config.maxTokens?.toString() ?: "") }
    var showModelDropdown by remember { mutableStateOf(false) }
    var formatHint by remember { mutableStateOf(config.formatHint) }
    var lastFetchedParams by remember { mutableStateOf("") }
    val context = LocalContext.current

    val isPartner = config.provider == ApiProvider.PARTNER
    val isCustom = config.provider == ApiProvider.CUSTOM
    val isCustomAnthropic = isCustom && formatHint == "anthropic"
    val canFetchOpenAiModels = onFetchModels != null && !isCustomAnthropic && baseUrl.isNotBlank() && (isPartner || apiKey.isNotBlank())
    val isValid = apiKey.isNotBlank() || baseUrl.isNotBlank() || isPartner
    val hasModels = availableModels.isNotEmpty()

    val selectedModelText = when {
        modelFetchState.isLoading -> "正在获取模型列表..."
        hasModels -> model.ifEmpty { "请选择模型" }
        isPartner && apiKey.isBlank() -> "密钥将从服务器自动获取，直接点击测试"
        else -> "填写密钥后自动拉取模型"
    }

    LaunchedEffect(apiKey, baseUrl, config.provider, formatHint) {
        val fetchParams = baseUrl.trim() + "|" + apiKey.trim()

        val shouldFetch = canFetchOpenAiModels && fetchParams != lastFetchedParams
        if (shouldFetch) {
            delay(600)
            lastFetchedParams = fetchParams

            val keyToUse = apiKey.trim()
            onFetchModels?.invoke(baseUrl, keyToUse)
        }
    }

    LaunchedEffect(availableModels, config.provider) {
        if ((isPartner || isCustom) && availableModels.isNotEmpty()) {
            model = if (isPartner) {

                val serverModel = com.yunian.ai.common.RemoteKeyProvider.getRandomModel(context)
                    ?.takeIf { it.isNotBlank() && availableModels.contains(it) }
                val chosenModel = if (availableModels.size > 1) {
                    com.yunian.ai.network.AiService.familyBalancedRandom(availableModels)
                } else {
                    serverModel ?: availableModels.first()
                }
                SecureLog.d("SettingsScreen", "PARTNER auto-select model: chosen=$chosenModel, server=$serverModel, available=${availableModels.size}")
                chosenModel
            } else {

                if (model.isBlank()) availableModels.first() else model
            }
        }
    }

    GlassEditDialog(
        onDismissRequest = onDismiss,
        title = "${config.name.ifBlank { config.provider.displayName }} 配置",
        titleColor = textPrimaryColor,
        content = {

                if (isCustom) {
                    OutlinedTextField(
                        value = apiName,
                        onValueChange = { apiName = it },
                        label = { Text("API 名称", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PetalPrimary,
                            unfocusedBorderColor = dividerColor,
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor,
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true,
                        placeholder = {
                            Text("如：我的DeepSeek、公司代理API", color = textTertiary, fontSize = 12.sp)
                        }
                    )
                }

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = fieldContainerColor,
                        unfocusedContainerColor = fieldContainerColor,
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
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL", color = textSecondaryColor) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PetalPrimary,
                        unfocusedBorderColor = dividerColor,
                        focusedContainerColor = fieldContainerColor,
                        unfocusedContainerColor = fieldContainerColor,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = "填到能拼 /chat/completions 的那一层，如 https://api.openai.com/v1",
                            color = textTertiary,
                            fontSize = 11.sp
                        )
                    }
                )

                if (isCustom) {
                    var showFormatDropdown by remember { mutableStateOf(false) }
                    val formatOptions = mapOf(
                        "openai" to "OpenAI 兼容",
                        "anthropic" to "Anthropic 兼容"
                    )
                    val selectedFormatText = formatOptions[formatHint] ?: "OpenAI 兼容"

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = selectedFormatText,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("API 格式", color = textSecondaryColor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showFormatDropdown = !showFormatDropdown },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PetalPrimary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = fieldContainerColor,
                                unfocusedContainerColor = fieldContainerColor,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            trailingIcon = {
                                Icon(
                                    imageVector = if (showFormatDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                                    contentDescription = "展开",
                                    modifier = Modifier.clickable { showFormatDropdown = !showFormatDropdown },
                                    tint = textSecondaryColor
                                )
                            },
                            singleLine = true
                        )
                        DropdownMenu(
                            expanded = showFormatDropdown,
                            onDismissRequest = { showFormatDropdown = false },
                            modifier = Modifier.fillMaxWidth(0.8f)
                        ) {
                            formatOptions.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label, color = textPrimaryColor, fontSize = 14.sp) },
                                    onClick = {
                                        formatHint = key
                                        showFormatDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (isCustomAnthropic) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model", color = textSecondaryColor) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PetalPrimary,
                            unfocusedBorderColor = dividerColor,
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor,
                            focusedTextColor = textPrimaryColor,
                            unfocusedTextColor = textPrimaryColor
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        singleLine = true
                    )
                    Text(
                        text = "Anthropic 兼容模式通常不支持自动拉取模型，请手动填写模型名",
                        fontSize = 12.sp,
                        color = textSecondaryColor
                    )
                } else if (onFetchModels != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = if (hasModels) selectedModelText else model,
                            onValueChange = { if (!hasModels) model = it },
                            readOnly = hasModels,
                            label = { Text("Model", color = textSecondaryColor) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = hasModels) { showModelDropdown = !showModelDropdown },
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = PetalPrimary,
                                unfocusedBorderColor = dividerColor,
                                focusedContainerColor = fieldContainerColor,
                                unfocusedContainerColor = fieldContainerColor,
                                focusedTextColor = textPrimaryColor,
                                unfocusedTextColor = textPrimaryColor
                            ),
                            trailingIcon = if (hasModels) {
                                {
                                    Icon(
                                        imageVector = if (showModelDropdown) AppIcons.ChevronUp else AppIcons.ChevronDown,
                                        contentDescription = "展开",
                                        modifier = Modifier.clickable { showModelDropdown = !showModelDropdown },
                                        tint = textSecondaryColor
                                    )
                                }
                            } else null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            singleLine = true
                        )
                        if (hasModels && !modelFetchState.isLoading) {
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
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = PetalPrimary)
                        Text("正在获取模型列表...", fontSize = 12.sp, color = textSecondaryColor)
                    }

                    modelFetchState.errorMessage?.let { error ->
                        Text(text = error, fontSize = 12.sp, color = PetalError)
                    }

                    GlassButton(
                        onClick = {
                            model = ""
                            lastFetchedParams = ""
                            onFetchModels.invoke(baseUrl, apiKey.trim())
                        },
                        enabled = canFetchOpenAiModels && !modelFetchState.isLoading,
                        modifier = Modifier.fillMaxWidth(),
                        height = 44.dp
                    ) {
                        Text(
                            if (hasModels) "重新拉取模型" else "一键拉取模型",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
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
                            focusedContainerColor = fieldContainerColor,
                            unfocusedContainerColor = fieldContainerColor,
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
                        focusedContainerColor = fieldContainerColor,
                        unfocusedContainerColor = fieldContainerColor,
                        focusedTextColor = textPrimaryColor,
                        unfocusedTextColor = textPrimaryColor
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    singleLine = true
                )
        },
        actions = {
            GlassButton(
                onClick = onDismiss,
                height = 44.dp,
                horizontalPadding = 16.dp
            ) {
                Text("取消", color = textSecondaryColor)
            }

            GlassButton(
                onClick = {
                    val currentConfig = config.copy(
                        name = apiName.trim(),
                        apiKey = apiKey.trim(),
                        extraApiKeys = "",
                        baseUrl = baseUrl.trim(),
                        model = model.trim(),
                        temperature = temperature,
                        maxTokens = maxTokens.toIntOrNull(),
                        formatHint = formatHint
                    )
                    onTest(currentConfig)
                },
                enabled = isValid && connectionResult.status != SettingsViewModel.ConnectionStatus.TESTING,
                modifier = Modifier.weight(1f),
                height = 44.dp,
                horizontalPadding = 12.dp
            ) {
                if (connectionResult.status == SettingsViewModel.ConnectionStatus.TESTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("测试", color = MaterialTheme.colorScheme.onSurface)
                }
            }

            GlassButton(
                onClick = {
                    onSave(
                        config.copy(
                            name = apiName.trim(),
                            apiKey = apiKey.trim(),
                            extraApiKeys = "",
                            baseUrl = baseUrl.trim(),
                            model = model.trim(),
                            temperature = temperature,
                            maxTokens = maxTokens.toIntOrNull(),
                            formatHint = formatHint
                        )
                    )
                },
                enabled = isValid,
                modifier = Modifier.weight(1f),
                height = 44.dp,
                horizontalPadding = 12.dp
            ) {
                Text("保存", color = MaterialTheme.colorScheme.onSurface)
            }
        }
    )
}
