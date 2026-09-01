@file:OptIn(ExperimentalMaterial3Api::class)

package com.lianyu.ai.feature.settings.ui.screen

import com.lianyu.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.domain.LorebookProvider
import com.lianyu.ai.domain.Lorebook
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.domain.SkillManager
import com.lianyu.ai.domain.SkillMetadata
import com.lianyu.ai.domain.McpManager
import com.lianyu.ai.domain.McpServerStatus
import kotlinx.coroutines.launch

/** 技能库页面 */
@Composable
fun SkillsScreen(onNavigateBack: () -> Unit) {
    val colorScheme = AppTheme.colors
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val manager = ServiceRegistry.get(SkillManager::class.java)
        if (manager != null) skills = manager.discoverSkills()
        loading = false
    }

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("技能库", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colorScheme.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (skills.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Book, null, Modifier.size(64.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("暂无可用技能", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    Text("AI 可通过 use_skill 工具加载技能文档，获得专业领域的操作方法", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(skills) { skill ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(skill.name, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                            Text(skill.description, color = colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** MCP 服务管理页 */
@Composable
fun McpSettingsScreen(onNavigateBack: () -> Unit) {
    val colorScheme = AppTheme.colors
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<com.lianyu.ai.domain.McpServerStatus>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var manager by remember { mutableStateOf<com.lianyu.ai.domain.McpManager?>(null) }

    fun refresh() {
        scope.launch {
            val mgr = com.lianyu.ai.domain.ServiceRegistry.get(com.lianyu.ai.domain.McpManager::class.java)
            manager = mgr
            if (mgr != null) servers = mgr.getServerStatuses()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { name, url, transportType ->
                showAddDialog = false
                scope.launch {
                    val mgr = manager
                    val config = com.lianyu.ai.domain.McpServerConfig(
                        id = java.util.UUID.randomUUID().toString(),
                        name = name,
                        url = url,
                        transportType = transportType
                    )
                    mgr?.upsertServer(config)
                    refresh()
                }
            }
        )
    }

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("MCP 服务管理", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colorScheme.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }, containerColor = colorScheme.primary, contentColor = colorScheme.onPrimary) {
                Icon(Icons.Default.Add, null)
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (servers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Dns, null, Modifier.size(64.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(16.dp))
                    Text("暂无 MCP 服务", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    Text("点击右下角 + 添加 MCP 服务器，为 AI 扩展外部工具能力", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(servers) { server ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(server.config.name, fontWeight = FontWeight.Medium, color = colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Text(
                                    if (server.connected) "已连接" else "未连接",
                                    color = if (server.connected) colorScheme.primary else colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp
                                )
                            }
                            Text(server.config.url, color = colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            Text(
                                "传输: ${server.config.transportType.name}  工具数: ${server.tools.size}",
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                            if (!server.lastError.isNullOrBlank()) {
                                Text("错误: ${server.lastError}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 添加 MCP 服务器弹窗 */
@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, com.lianyu.ai.domain.TransportType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var transportType by remember { mutableStateOf(com.lianyu.ai.domain.TransportType.STREAMABLE_HTTP) }
    var showTransportMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 MCP 服务器", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("服务器 URL") }, singleLine = true,
                    placeholder = { Text("https://example.com/mcp") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                ExposedDropdownMenuBox(expanded = showTransportMenu, onExpandedChange = { showTransportMenu = it }) {
                    OutlinedTextField(
                        value = if (transportType == com.lianyu.ai.domain.TransportType.SSE) "SSE" else "Streamable HTTP",
                        onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        label = { Text("传输协议") }
                    )
                    ExposedDropdownMenu(expanded = showTransportMenu, onDismissRequest = { showTransportMenu = false }) {
                        DropdownMenuItem(text = { Text("Streamable HTTP (推荐)") }, onClick = {
                            transportType = com.lianyu.ai.domain.TransportType.STREAMABLE_HTTP
                            showTransportMenu = false
                        })
                        DropdownMenuItem(text = { Text("SSE (旧版规范)") }, onClick = {
                            transportType = com.lianyu.ai.domain.TransportType.SSE
                            showTransportMenu = false
                        })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank() && url.isNotBlank()) onConfirm(name.trim(), url.trim(), transportType) },
                enabled = name.isNotBlank() && url.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}