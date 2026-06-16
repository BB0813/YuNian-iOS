package com.lianyu.ai.feature.companion.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.lianyu.ai.feature.companion.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.lianyu.ai.database.model.CompanionEntity
import com.lianyu.ai.feature.companion.ui.viewmodel.CreateCompanionViewModel
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatDarkCard
import com.lianyu.ai.uicommon.theme.WeChatDarkDivider
import com.lianyu.ai.uicommon.theme.WeChatDarkTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatDarkTextSecondary
import com.lianyu.ai.uicommon.theme.WeChatLightBackground
import com.lianyu.ai.uicommon.theme.WeChatLightCard
import com.lianyu.ai.uicommon.theme.WeChatLightDivider
import com.lianyu.ai.uicommon.theme.WeChatLightTextPrimary
import com.lianyu.ai.uicommon.theme.WeChatLightTextSecondary
import kotlinx.coroutines.delay
import com.lianyu.ai.uicommon.theme.ThemeViewModel
import com.lianyu.ai.uicommon.theme.ThemeMode
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.draw.alpha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateCompanionScreen(
    companionId: Long? = null,
    onNavigateBack: () -> Unit,
    viewModel: CreateCompanionViewModel = viewModel()
) {
    val context = LocalContext.current
    val isEditMode = companionId != null
    val existingCompanion by viewModel.existingCompanion.collectAsState()

    var name by remember { mutableStateOf("") }
    var age by remember { mutableStateOf("") }
    var rawPrompt by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var avatarUri by remember { mutableStateOf<String?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showImportErrorDialog by remember { mutableStateOf(false) }
    var importErrorMessage by remember { mutableStateOf("") }
    var referenceCharacter by remember { mutableStateOf("") }
    val isGenerating by viewModel.isGenerating.collectAsState()

    // 监听 saveCompleted 跳转
    val saveCompleted by viewModel.saveCompleted.collectAsState()
    LaunchedEffect(saveCompleted) {
        if (saveCompleted) onNavigateBack()
    }

    fun populateExistingCompanion(companion: CompanionEntity) {
        name = companion.name
        age = companion.age?.toString().orEmpty()
        rawPrompt = companion.rawPrompt.orEmpty()
        systemPrompt = companion.systemPrompt.orEmpty()
        avatarUri = companion.avatarUrl
    }

    fun handleImportError(message: String) {
        importErrorMessage = message
        showImportErrorDialog = true
    }

    fun updateRawPromptFromImport(content: String) {
        rawPrompt = content
    }

    fun handleGenerateResult(result: String) {
        if (result.isNotBlank()) rawPrompt = result
    }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { avatarUri = it.toString() }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { fileUri ->
            try {
                context.contentResolver.openInputStream(fileUri)?.bufferedReader().use { reader ->
                    val content = reader?.readText().orEmpty()
                    if (content.isNotBlank()) {
                        updateRawPromptFromImport(content)
                    } else {
                        handleImportError("文件内容为空")
                    }
                }
            } catch (e: Exception) {
                handleImportError("读取文件失败")
            }
        }
    }

    LaunchedEffect(Unit) {
        if (isEditMode && companionId != null) {
            viewModel.loadCompanion(companionId)
        }
        delay(100)
        isVisible = true
    }

    LaunchedEffect(existingCompanion) {
        existingCompanion?.let(::populateExistingCompanion)
    }

    val isFormValid = name.isNotBlank() && rawPrompt.isNotBlank()
    val buttonScale by animateFloatAsState(
        targetValue = if (isFormValid) 1f else 0.95f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy),
        label = "buttonScale"
    )

    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val isImeVisible = with(density) { imeInsets.getBottom(density) > 0 }

    LaunchedEffect(isImeVisible) {
        if (isImeVisible) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    val themeViewModel: ThemeViewModel = viewModel()
    val themeMode by themeViewModel.themeMode.collectAsState()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val backgroundColor = if (isDark) WeChatDarkBackground else WeChatLightBackground
    val cardColor = if (isDark) WeChatDarkCard else WeChatLightCard
    val textPrimary = if (isDark) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDark) WeChatDarkTextSecondary else WeChatLightTextSecondary
    val dividerColor = if (isDark) WeChatDarkDivider else WeChatLightDivider

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        if (isEditMode) stringResource(R.string.edit_companion) else stringResource(R.string.create_companion),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 17.sp
                        ),
                        color = textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel),
                            tint = textPrimary
                        )
                    }
                },
                actions = {
                    if (isEditMode) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = backgroundColor
                ),
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
                .padding(top = paddingValues.calculateTopPadding())
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { it / 3 }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        )
                                    )
                                )
                                .clickable { imagePicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            if (avatarUri != null) {
                                AsyncImage(
                                    model = avatarUri,
                                    contentDescription = stringResource(R.string.add_avatar),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.add_avatar),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(R.string.select_avatar),
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 11.sp
                                        ),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = if (name.isBlank()) stringResource(R.string.name_hint) else name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            ),
                            color = if (name.isBlank()) textSecondary.copy(alpha = 0.7f) else textPrimary
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 100,
                    label = stringResource(R.string.name_label),
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.name_placeholder),
                    imeAction = ImeAction.Next,
                    isDarkTheme = isDark
                )

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 150,
                    label = stringResource(R.string.age_label),
                    value = age,
                    onValueChange = { age = it },
                    placeholder = stringResource(R.string.age_placeholder),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                    isDarkTheme = isDark
                )

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(400, delayMillis = 200)) +
                            slideInVertically(tween(400, delayMillis = 200)) { it / 3 }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardColor),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 0.dp else 2.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.role_setting),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 13.sp
                                    ),
                                    color = textPrimary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = !isGenerating) {
                                                val effectiveName = name.trim().takeIf { it.isNotBlank() }
                                                    ?: referenceCharacter.trim().takeIf { it.isNotBlank() }
                                                    ?: "未知角色"
                                                viewModel.generatePersonaByAi(
                                                    name = effectiveName,
                                                    referenceCharacter = referenceCharacter.trim().takeIf { it.isNotBlank() && it != effectiveName },
                                                    onResult = { result ->
                                                        if (result.isNotBlank()) {
                                                            rawPrompt = result
                                                        }
                                                    }
                                                )
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isGenerating) Icons.Filled.Edit else Icons.Filled.AutoAwesome,
                                            contentDescription = "AI生成设定",
                                            tint = if (isGenerating) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (isGenerating) "生成中..." else "AI 生成",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = if (isGenerating) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                            else MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable { filePicker.launch("*/*") }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.FileOpen,
                                            contentDescription = stringResource(R.string.import_from_file),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.import_from_file),
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 12.sp
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedTextField(
                                value = referenceCharacter,
                                onValueChange = { referenceCharacter = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                    unfocusedBorderColor = dividerColor.copy(alpha = 0.5f),
                                    focusedContainerColor = if (isDark) Color.Transparent else Color.White.copy(alpha = 0.3f),
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = textSecondary,
                                    unfocusedTextColor = textSecondary
                                ),
                                placeholder = {
                                    Text(
                                        "参考角色（如：明日方舟-阿米娅，可选）",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                        color = textSecondary.copy(alpha = 0.5f)
                                    )
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp)
                            )

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.role_hint),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.sp
                                ),
                                color = textSecondary.copy(alpha = 0.8f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = rawPrompt,
                                onValueChange = { rawPrompt = it },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                                    unfocusedBorderColor = dividerColor,
                                    focusedLabelColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    unfocusedLabelColor = textSecondary.copy(alpha = 0.7f),
                                    focusedContainerColor = if (isDark) cardColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.4f),
                                    unfocusedContainerColor = if (isDark) cardColor.copy(alpha = 0.3f) else Color(0xFFF5F5F5).copy(alpha = 0.2f),
                                    focusedTextColor = textPrimary,
                                    unfocusedTextColor = textPrimary
                                ),
                                minLines = 6,
                                maxLines = 10,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(
                                    fontSize = 14.sp
                                )
                            )
                        }
                    }
                }

                AnimatedFormField(
                    visible = isVisible,
                    delayMillis = 300,
                    label = stringResource(R.string.system_prompt),
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    placeholder = stringResource(R.string.system_prompt_hint),
                    imeAction = ImeAction.Done,
                    minLines = 3,
                    isDarkTheme = isDark
                )

                Spacer(modifier = Modifier.height(20.dp))

                AnimatedVisibility(
                    visible = isVisible,
                    enter = fadeIn(tween(500, delayMillis = 350)) +
                            slideInVertically(tween(500, delayMillis = 350)) { it / 2 }
                ) {
                    Button(
                        onClick = {
                            if (isFormValid) {
                                val companion = CompanionEntity(
                                    id = companionId ?: 0,
                                    name = name.trim(),
                                    avatarUrl = avatarUri,
                                    age = age.toIntOrNull(),
                                    personality = rawPrompt.trim(),
                                    backstory = null,
                                    speakingStyle = null,
                                    tags = null,
                                    rawPrompt = rawPrompt.trim(),
                                    systemPrompt = systemPrompt.trim().takeIf { it.isNotBlank() }
                                )
                                viewModel.saveCompanion(companion, isEditMode)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .scale(buttonScale),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            disabledContainerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        ),
                        enabled = isFormValid,
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    if (isFormValid) {
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                            )
                                        )
                                    } else {
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                                            )
                                        )
                                    },
                                    RoundedCornerShape(25.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = if (isEditMode) Icons.Filled.Edit else Icons.Filled.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    if (isEditMode) stringResource(R.string.save_changes) else stringResource(R.string.create),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp
                                    )
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = {
                Text(
                    stringResource(R.string.confirm_delete),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textPrimary
                )
            },
            text = {
                Text(
                    stringResource(R.string.delete_confirm_msg, name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        existingCompanion?.let {
                            viewModel.deleteCompanion(it)
                        }
                        showDeleteDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel), color = textSecondary)
                }
            },
            containerColor = cardColor
        )
    }

    if (showImportErrorDialog) {
        AlertDialog(
            onDismissRequest = { showImportErrorDialog = false },
            title = {
                Text(
                    stringResource(R.string.import_failed),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textPrimary
                )
            },
            text = {
                Text(
                    importErrorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { showImportErrorDialog = false }) {
                    Text(stringResource(R.string.ok), color = MaterialTheme.colorScheme.primary)
                }
            },
            containerColor = cardColor
        )
    }

}

@Composable
fun AnimatedFormField(
    visible: Boolean,
    delayMillis: Int,
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    minLines: Int = 1,
    isDarkTheme: Boolean
) {
    val cardColor = if (isDarkTheme) WeChatDarkCard else WeChatLightCard
    val textPrimary = if (isDarkTheme) WeChatDarkTextPrimary else WeChatLightTextPrimary
    val textSecondary = if (isDarkTheme) WeChatDarkTextSecondary else WeChatLightTextSecondary
    val dividerColor = if (isDarkTheme) WeChatDarkDivider else WeChatLightDivider

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400, delayMillis = delayMillis)) +
                slideInVertically(tween(400, delayMillis = delayMillis)) { it / 3 }
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = cardColor),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isDarkTheme) 0.dp else 2.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 13.sp
                    ),
                    color = textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp
                            ),
                            color = textSecondary.copy(alpha = 0.7f)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        unfocusedBorderColor = dividerColor,
                        focusedLabelColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        unfocusedLabelColor = textSecondary.copy(alpha = 0.7f),
                        focusedContainerColor = if (isDarkTheme) cardColor.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.4f),
                        unfocusedContainerColor = if (isDarkTheme) cardColor.copy(alpha = 0.3f) else Color(0xFFF5F5F5).copy(alpha = 0.2f),
                        focusedTextColor = textPrimary,
                        unfocusedTextColor = textPrimary
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction
                    ),
                    minLines = minLines,
                    maxLines = if (minLines > 1) 5 else 1,
                    singleLine = minLines == 1,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp
                    )
                )
            }
        }
    }
}
