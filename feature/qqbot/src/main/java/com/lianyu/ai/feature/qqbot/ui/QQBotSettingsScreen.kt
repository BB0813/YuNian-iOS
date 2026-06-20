package com.lianyu.ai.feature.qqbot.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Message
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QQBotSettingsScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val viewModel: QQBotViewModel = viewModel(factory = remember { QQBotViewModelFactory(context.applicationContext as android.app.Application) })
    val uiState by viewModel.uiState.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showCompanionDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is QQBotEvent.LoginSuccess -> Toast.makeText(context, "QQ Bot 绑定成功", Toast.LENGTH_SHORT).show()
                is QQBotEvent.LoginFailed -> Toast.makeText(context, "绑定失败: ${event.error}", Toast.LENGTH_LONG).show()
                is QQBotEvent.LoggedOut -> Toast.makeText(context, "已解除绑定", Toast.LENGTH_SHORT).show()
                else -> {}
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QQ 机器人设置", color = MaterialTheme.colorScheme.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            QQBotStatusCard(
                isLoggedIn = uiState.isLoggedIn,
                accountId = uiState.account?.appId,
                customName = uiState.customBotName,
                onBindClick = { /* bind handled inline below */ },
                onUnbindClick = { showLogoutDialog = true },
                onRenameClick = { showRenameDialog = true }
            )

            if (!uiState.isLoggedIn) {
                QQBotBindForm(
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    onBind = { appId, secret, name ->
                        viewModel.saveAccount(appId, secret, name)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "功能设置",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SettingItem(
                icon = Icons.Outlined.Message,
                title = "消息通知",
                subtitle = "QQ 消息到达时推送通知",
                trailing = {
                    Switch(
                        checked = uiState.notifyEnabled,
                        onCheckedChange = { viewModel.toggleNotifyEnabled(it) }
                    )
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))

            SettingItem(
                icon = Icons.Outlined.Link,
                title = "自动回复",
                subtitle = "收到 QQ 消息后自动调用 AI 回复",
                trailing = {
                    Switch(
                        checked = uiState.autoReply,
                        onCheckedChange = { viewModel.toggleAutoReply(it) }
                    )
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))

            SettingItem(
                icon = Icons.Outlined.Message,
                title = "消息转发",
                subtitle = "将 AI 消息同步发送到 QQ",
                trailing = {
                    Switch(
                        checked = uiState.forwardEnabled,
                        onCheckedChange = { viewModel.toggleForwardEnabled(it) }
                    )
                }
            )

            if (uiState.availableCompanions.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))

                SettingItem(
                    icon = Icons.Outlined.Message,
                    title = "默认 AI 伴侣",
                    subtitle = uiState.availableCompanions.find { it.id == uiState.defaultCompanionId }?.name
                        ?: "未选择（使用第一个）",
                    trailing = {
                        TextButton(onClick = { showCompanionDialog = true }) {
                            Text("选择")
                        }
                    }
                )
            }

            if (uiState.userCompanionMappings.isNotEmpty() && uiState.availableCompanions.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))

                Text(
                    text = "QQ 用户人设分配",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                uiState.userCompanionMappings.entries.forEachIndexed { index, (qqUserId, companionId) ->
                    var showMappingDialog by remember { mutableStateOf(false) }
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    val mappedCompanionName = uiState.availableCompanions.find { it.id == companionId }?.name
                        ?: "未知 (ID: $companionId)"

                    SettingItem(
                        icon = Icons.Outlined.Link,
                        title = "用户 $qqUserId",
                        subtitle = "人设: $mappedCompanionName",
                        trailing = {
                            Row {
                                TextButton(onClick = { showMappingDialog = true }) {
                                    Text("切换")
                                }
                                IconButton(onClick = { showDeleteConfirm = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "删除映射",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    )

                    if (index < uiState.userCompanionMappings.size - 1) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 16.dp))
                    }

                    if (showMappingDialog) {
                        AlertDialog(
                            onDismissRequest = { showMappingDialog = false },
                            title = { Text("为用户 $qqUserId 选择 AI 伴侣") },
                            text = {
                                Column {
                                    uiState.availableCompanions.forEach { companion ->
                                        TextButton(
                                            onClick = {
                                                viewModel.setUserCompanionMapping(qqUserId, companion.id)
                                                showMappingDialog = false
                                            },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                companion.name,
                                                color = if (companion.id == companionId)
                                                    MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = {
                                TextButton(onClick = { showMappingDialog = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }

                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            title = { Text("删除映射") },
                            text = { Text("确定要删除用户 $qqUserId 的人设映射吗？删除后将使用默认 AI 伴侣。") },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.removeUserCompanionMapping(qqUserId)
                                    showDeleteConfirm = false
                                }) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteConfirm = false }) {
                                    Text("取消")
                                }
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "说明",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(16.dp)
            ) {
                Text(
                    text = "• 基于 QQ 官方 Bot 平台（小龙虾/Hermes 同协议）\n" +
                           "• 支持 C2C 私聊、群聊 @、频道消息\n" +
                           "• 在 QQ 开放平台创建机器人后，填写 AppID 和 ClientSecret\n" +
                           "• AccessToken 会自动刷新，无需手动维护",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("解除绑定") },
            text = { Text("确定要解除 QQ Bot 绑定吗？解除后将无法通过 QQ 接收消息。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.logout()
                    showLogoutDialog = false
                }) {
                    Text("确定", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showCompanionDialog) {
        AlertDialog(
            onDismissRequest = { showCompanionDialog = false },
            title = { Text("选择默认 AI 伴侣") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.setDefaultCompanionId(null)
                            showCompanionDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "自动分配（使用第一个）",
                            color = if (uiState.defaultCompanionId == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    uiState.availableCompanions.forEach { companion ->
                        TextButton(
                            onClick = {
                                viewModel.setDefaultCompanionId(companion.id)
                                showCompanionDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                companion.name,
                                color = if (companion.id == uiState.defaultCompanionId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCompanionDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(uiState.customBotName ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("设置机器人名字") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("名字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setCustomBotName(newName.takeIf { it.isNotBlank() })
                    showRenameDialog = false
                }) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun QQBotBindForm(
    isLoading: Boolean,
    error: String?,
    onBind: (String, String, String?) -> Unit
) {
    var appId by remember { mutableStateOf("") }
    var clientSecret by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp)
    ) {
        Text(
            text = "绑定 QQ 机器人",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            label = { Text("AppID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text("ClientSecret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text("机器人名字（可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (!error.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = error,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = { onBind(appId, clientSecret, customName.takeIf { it.isNotBlank() }) },
            enabled = !isLoading && appId.isNotBlank() && clientSecret.isNotBlank(),
            modifier = Modifier.align(Alignment.End)
        ) {
            Text(if (isLoading) "绑定中..." else "绑定")
        }
    }
}

@Composable
private fun QQBotStatusCard(
    isLoggedIn: Boolean,
    accountId: String?,
    customName: String?,
    onBindClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onRenameClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isLoggedIn) Color(0xFF4CAF50).copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (isLoggedIn) Icons.Filled.CheckCircle else Icons.Outlined.Warning,
            contentDescription = null,
            tint = if (isLoggedIn) Color(0xFF4CAF50) else Color(0xFFFFA000),
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (isLoggedIn) customName ?: "QQ 机器人已绑定" else "未绑定 QQ 机器人",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (isLoggedIn && !accountId.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "AppID: $accountId",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isLoggedIn) {
                TextButton(onClick = onRenameClick, modifier = Modifier.height(32.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("改名", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onUnbindClick, modifier = Modifier.height(32.dp)) {
                    Text("解除绑定", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onBindClick, modifier = Modifier.height(32.dp)) {
                    Text("立即绑定", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun SettingItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(text = title, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(text = subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing()
    }
}
