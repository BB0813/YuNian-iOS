@file:OptIn(ExperimentalMaterial3Api::class)

package com.lianyu.ai.feature.settings.ui.screen

import com.lianyu.ai.uicommon.theme.AppTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lianyu.ai.domain.InjectionPosition
import com.lianyu.ai.domain.EntryRole
import com.lianyu.ai.domain.Lorebook
import com.lianyu.ai.domain.LorebookEntry
import com.lianyu.ai.domain.LorebookProvider
import com.lianyu.ai.domain.ServiceRegistry
import kotlinx.coroutines.launch

/** 世界书列表页 */
@Composable
fun WorldbookScreen(onNavigateBack: () -> Unit, onNavigateToDetail: (Long) -> Unit = {}) {
    val colorScheme = AppTheme.colors
    val scope = rememberCoroutineScope()
    var lorebooks by remember { mutableStateOf<List<Lorebook>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val provider = ServiceRegistry.get(LorebookProvider::class.java)
            if (provider != null) lorebooks = provider.getAllLorebooks()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showCreateDialog) {
        CreateLorebookDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, desc ->
                showCreateDialog = false
                scope.launch {
                    val provider = ServiceRegistry.get(LorebookProvider::class.java)
                    if (provider != null) {
                        val now = System.currentTimeMillis()
                        provider.createLorebook(
                            Lorebook(id = 0, name = name, description = desc, companionId = null, enabled = true, createdAt = now, updatedAt = now),
                            emptyList()
                        )
                    }
                    refresh()
                }
            }
        )
    }

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("世界书（知识库）", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colorScheme.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }, containerColor = colorScheme.primary, contentColor = colorScheme.onPrimary) {
                Icon(Icons.Default.Add, null)
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (lorebooks.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Settings, null, Modifier.size(64.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Spacer(Modifier.height(16.dp))
                Text("暂无世界书", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Text("点击右下角 + 新建", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(lorebooks) { lorebook ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onNavigateToDetail(lorebook.id) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(lorebook.name, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = colorScheme.onSurface)
                            if (lorebook.description.isNotBlank()) {
                                Text(lorebook.description, color = colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 创建世界书弹窗 */
@Composable
private fun CreateLorebookDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建世界书", fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("描述（可选）") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim(), desc.trim()) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 世界书条目页 */
@Composable
fun WorldbookDetailScreen(worldbookId: Long, onNavigateBack: () -> Unit) {
    val colorScheme = AppTheme.colors
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<LorebookEntry>>(emptyList()) }
    var worldbookName by remember { mutableStateOf("世界书") }
    var loading by remember { mutableStateOf(true) }
    var showCreateDialog by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val provider = ServiceRegistry.get(LorebookProvider::class.java)
            if (provider != null) {
                val detail = provider.getLorebookWithEntries(worldbookId)
                worldbookName = detail?.lorebook?.name ?: "世界书"
                entries = detail?.entries ?: emptyList()
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { refresh() }

    if (showCreateDialog) {
        CreateEntryDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { entry ->
                showCreateDialog = false
                scope.launch {
                    val provider = ServiceRegistry.get(LorebookProvider::class.java)
                    if (provider != null) provider.upsertEntry(entry)
                    refresh()
                }
            }
        )
    }

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(worldbookName, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = colorScheme.onSurface) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }, containerColor = colorScheme.primary, contentColor = colorScheme.onPrimary) {
                Icon(Icons.Default.Add, null)
            }
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("暂无条目", color = colorScheme.onSurfaceVariant, fontSize = 16.sp)
                Text("点击右下角 + 添加条目", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries) { entry ->
                    EntryCard(entry = entry)
                }
            }
        }
    }
}

/** 条目卡片 */
@Composable
private fun EntryCard(entry: LorebookEntry) {
    val colorScheme = AppTheme.colors
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("关键词：", fontWeight = FontWeight.Medium, color = colorScheme.onSurface, fontSize = 14.sp)
                Text(entry.keywords.joinToString(" / "), color = colorScheme.primary, fontSize = 14.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(entry.content, color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text("位置：${entry.injectionPosition.name}  角色：${entry.role.name}  优先级：${entry.priority}", color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 11.sp)
        }
    }
}

/** 创建条目弹窗 */
@Composable
private fun CreateEntryDialog(onDismiss: () -> Unit, onConfirm: (LorebookEntry) -> Unit) {
    var keywords by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var position by remember { mutableStateOf(InjectionPosition.BEFORE_SYSTEM_PROMPT) }
    var role by remember { mutableStateOf(EntryRole.SYSTEM) }
    var priority by remember { mutableStateOf("0") }
    var showPositionMenu by remember { mutableStateOf(false) }
    var showRoleMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加条目", fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = keywords, onValueChange = { keywords = it }, label = { Text("关键词（逗号分隔）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("注入内容") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))

                // 注入位置选择
                ExposedDropdownMenuBox(expanded = showPositionMenu, onExpandedChange = { showPositionMenu = it }) {
                    OutlinedTextField(value = positionLabel(position), onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(), label = { Text("注入位置") })
                    ExposedDropdownMenu(expanded = showPositionMenu, onDismissRequest = { showPositionMenu = false }) {
                        InjectionPosition.values().forEach { p ->
                            DropdownMenuItem(text = { Text(positionLabel(p)) }, onClick = { position = p; showPositionMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                // 角色选择
                ExposedDropdownMenuBox(expanded = showRoleMenu, onExpandedChange = { showRoleMenu = it }) {
                    OutlinedTextField(value = roleLabel(role), onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor(), label = { Text("角色") })
                    ExposedDropdownMenu(expanded = showRoleMenu, onDismissRequest = { showRoleMenu = false }) {
                        EntryRole.values().forEach { r ->
                            DropdownMenuItem(text = { Text(roleLabel(r)) }, onClick = { role = r; showRoleMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(value = priority, onValueChange = { priority = it.filter { c -> c.isDigit() } }, label = { Text("优先级（越大越优先注入）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val kwList = keywords.split(Regex("[，,、/\\s]+")).filter { it.isNotBlank() }
                    if (kwList.isNotEmpty() && content.isNotBlank()) {
                        onConfirm(
                            LorebookEntry(
                                id = 0, lorebookId = 0, keywords = kwList, content = content.trim(),
                                injectionPosition = position, priority = priority.toIntOrNull() ?: 0,
                                injectDepth = null, role = role, caseSensitive = false, scanDepth = 10,
                                constantActive = false, enabled = true,
                                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                },
                enabled = keywords.isNotBlank() && content.isNotBlank()
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun positionLabel(p: InjectionPosition): String = when (p) {
    InjectionPosition.BEFORE_SYSTEM_PROMPT -> "系统提示词之前"
    InjectionPosition.AFTER_SYSTEM_PROMPT -> "系统提示词之后"
    InjectionPosition.TOP_OF_CHAT -> "对话历史顶部"
    InjectionPosition.BOTTOM_OF_CHAT -> "对话历史底部"
    InjectionPosition.AT_DEPTH -> "指定深度"
}

private fun roleLabel(r: EntryRole): String = when (r) {
    EntryRole.SYSTEM -> "系统"
    EntryRole.USER -> "用户"
    EntryRole.ASSISTANT -> "助手"
}