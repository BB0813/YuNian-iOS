package com.lianyu.ai.feature.backup

import com.lianyu.ai.uicommon.theme.AppTheme
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * 数据备份与恢复界面。
 *
 * 导出流程：点击导出 → 进入联系人选择页 → 选择数据 → 设置密码 → 导出加密备份
 * 导入流程：点击导入 → SAF 选择文件 → 密码弹窗 → import(uri, password)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    onExportSelect: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = AppTheme.colors
    val scope = rememberCoroutineScope()
    val viewModel: BackupViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(80); isVisible = true }

    // 密码弹窗状态
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordMode by remember { mutableStateOf(PasswordMode.EXPORT) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }

    // SAF 导出：收到加密数据后打开保存对话框
    var pendingExportBytes by remember { mutableStateOf<ByteArray?>(null) }

    val exportSaveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { dest ->
            pendingExportBytes?.let { bytes ->
                scope.launch {
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            context.contentResolver.openOutputStream(dest)?.use { it.write(bytes) }
                        }
                        viewModel.onExportComplete()
                    } catch (e: Exception) {
                        viewModel.onError("保存文件失败: ${e.localizedMessage}")
                    }
                    pendingExportBytes = null
                }
            }
        }
    }

    // 收集导出结果 → 打开 SAF
    LaunchedEffect(Unit) {
        viewModel.exportResult.collect { encryptedBytes ->
            pendingExportBytes = encryptedBytes
            exportSaveLauncher.launch("lianyu_backup_${dateString()}.lybk")
        }
    }

    // SAF 导入：选择文件
    val importFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            pendingImportUri = it
            passwordMode = PasswordMode.IMPORT
            showPasswordDialog = true
        }
    }

    // 密码弹窗
    if (showPasswordDialog) {
        PasswordDialog(
            mode = passwordMode,
            isLoading = uiState is BackupViewModel.UiState.Exporting || uiState is BackupViewModel.UiState.Importing,
            onConfirm = { password ->
                when (passwordMode) {
                    PasswordMode.EXPORT -> viewModel.export(password)
                    PasswordMode.IMPORT -> {
                        pendingImportUri?.let { viewModel.import(it, password) }
                        pendingImportUri = null
                    }
                }
                showPasswordDialog = false
            },
            onDismiss = {
                showPasswordDialog = false
                pendingImportUri = null
                if (uiState !is BackupViewModel.UiState.Exporting &&
                    uiState !is BackupViewModel.UiState.Importing) {
                    viewModel.resetState()
                }
            }
        )
    }

    // 结果 Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState) {
        when (val s = uiState) {
            is BackupViewModel.UiState.Success -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetState()
            }
            is BackupViewModel.UiState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.resetState()
            }
            else -> {}
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().background(colorScheme.background),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.backup_back), tint = colorScheme.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // 导出卡片
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 4 }
            ) {
                BackupCard(
                    icon = Icons.Filled.SaveAlt,
                    title = stringResource(R.string.backup_export_title),
                    description = stringResource(R.string.backup_export_desc),
                    buttonText = stringResource(R.string.backup_export_btn),
                    buttonColor = AppTheme.colors.success,
                    isLoading = uiState is BackupViewModel.UiState.Exporting,
                    onClick = { onExportSelect() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 导入卡片
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(400, delayMillis = 80)) + slideInVertically(tween(400, delayMillis = 80)) { it / 4 }
            ) {
                BackupCard(
                    icon = Icons.Filled.FileOpen,
                    title = stringResource(R.string.backup_import_title),
                    description = stringResource(R.string.backup_import_desc),
                    buttonText = stringResource(R.string.backup_import_btn),
                    buttonColor = AppTheme.colors.danger,
                    isLoading = uiState is BackupViewModel.UiState.Importing,
                    onClick = { importFileLauncher.launch(arrayOf("application/octet-stream", "*/*")) }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 说明文字
            AnimatedVisibility(
                visible = isVisible,
                enter = fadeIn(tween(500, delayMillis = 160))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceVariant)
                        .padding(16.dp)
                ) {
                    Text(
                        stringResource(R.string.backup_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

// ============================================================================
// 密码弹窗
// ============================================================================

enum class PasswordMode { EXPORT, IMPORT }

/**
 * 生成高强度随机密码（24 位，含大小写字母 + 数字 + 特殊符号）。
 * 使用 SecureRandom，避免用户设置弱密码导致备份文件易被暴力破解。
 */
internal fun generateStrongPassword(length: Int = 24): String {
    val upper = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    val lower = "abcdefghijkmnpqrstuvwxyz"
    val digits = "23456789"
    val symbols = "!@#$%^&*()-_=+"
    val all = upper + lower + digits + symbols
    val random = SecureRandom()
    val sb = StringBuilder(length)
    // 保证每类字符至少出现一次
    sb.append(upper[random.nextInt(upper.length)])
    sb.append(lower[random.nextInt(lower.length)])
    sb.append(digits[random.nextInt(digits.length)])
    sb.append(symbols[random.nextInt(symbols.length)])
    repeat(length - 4) { sb.append(all[random.nextInt(all.length)]) }
    // Fisher-Yates 洗牌，避免固定前缀模式
    val chars = sb.toString().toCharArray()
    for (i in chars.size - 1 downTo 1) {
        val j = random.nextInt(i + 1)
        val tmp = chars[i]
        chars[i] = chars[j]
        chars[j] = tmp
    }
    return String(chars)
}

@Composable
internal fun PasswordDialog(
    mode: PasswordMode,
    isLoading: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    // EXPORT 模式：自动生成 24 位强密码（每次打开弹窗生成一次）
    val generated = remember(mode) {
        if (mode == PasswordMode.EXPORT) generateStrongPassword() else ""
    }
    var password by remember(mode) { mutableStateOf(generated) }
    var showPassword by remember { mutableStateOf(mode == PasswordMode.EXPORT) }
    var copied by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = {
            Text(
                if (mode == PasswordMode.EXPORT) "设置备份密码" else "输入备份密码",
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                if (mode == PasswordMode.EXPORT) {
                    Text(
                        "已自动生成高强度密码，点击复制保存。导入时需要输入此密码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                } else {
                    Text(
                        "请输入备份时设置的密码",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTheme.colors.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null; copied = false },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            if (mode == PasswordMode.EXPORT) {
                                // 重新生成
                                IconButton(
                                    onClick = {
                                        password = generateStrongPassword()
                                        copied = false
                                    },
                                    enabled = !isLoading
                                ) {
                                    Icon(Icons.Filled.Refresh, "重新生成密码")
                                }
                                // 一键复制
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(password))
                                        copied = true
                                    },
                                    enabled = !isLoading
                                ) {
                                    Icon(
                                        if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                        if (copied) "已复制" else "复制密码"
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showPassword = !showPassword },
                                enabled = !isLoading
                            ) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码"
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )
                if (mode == PasswordMode.EXPORT && copied) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已复制到剪贴板",
                        color = AppTheme.colors.success,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = AppTheme.colors.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when {
                        password.length < 6 -> error = "密码至少6位"
                        else -> onConfirm(password)
                    }
                },
                enabled = !isLoading
            ) {
                Text(if (isLoading) "处理中..." else "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text("取消")
            }
        }
    )
}

// ============================================================================
// 功能卡片
// ============================================================================

@Composable
private fun BackupCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    buttonColor: Color,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val colorScheme = AppTheme.colors
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(colorScheme.surfaceVariant)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, title, Modifier.size(28.dp), tint = buttonColor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp), color = colorScheme.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor),
            enabled = !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(20.dp), color = AppTheme.colors.staticWhite, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isLoading) "处理中..." else buttonText, color = AppTheme.colors.staticWhite)
        }
    }
}

// ============================================================================
// 工具函数
// ============================================================================

internal fun dateString(): String {
    val cal = java.util.Calendar.getInstance()
    return "${cal.get(java.util.Calendar.YEAR)}-${(cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')}-${cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')}"
}
