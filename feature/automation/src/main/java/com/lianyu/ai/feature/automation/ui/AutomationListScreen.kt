package com.lianyu.ai.feature.automation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lianyu.ai.feature.automation.data.Automation
import com.lianyu.ai.feature.automation.data.AutomationType
import com.lianyu.ai.uicommon.theme.AppTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationListScreen(onNavigateBack: () -> Unit) {
    val viewModel: AutomationListViewModel = viewModel()
    val automations by viewModel.automations.collectAsStateWithLifecycle()
    var deleteTarget by remember { mutableStateOf<Automation?>(null) }
    val colorScheme = AppTheme.colors

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("自动化", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "返回",
                            tint = colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colorScheme.background)
            )
        }
    ) { padding ->
        if (automations.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "还没有自动化任务\n在对话里告诉 AI「每天早8点提醒我喝水」试试吧",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    lineHeight = 22.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(automations, key = { it.id }) { automation ->
                    AutomationRow(
                        automation = automation,
                        onToggle = { viewModel.toggleEnabled(automation.id, it) },
                        onDelete = { deleteTarget = automation }
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除自动化") },
            text = { Text("确定删除「${target.title}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("删除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AutomationRow(
    automation: Automation,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val colorScheme = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                automation.title,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                color = colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                triggerText(automation),
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = automation.enabled, onCheckedChange = onToggle)
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Filled.DeleteOutline,
                "删除",
                tint = colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun triggerText(a: Automation): String {
    val repeat = when (a.type) {
        AutomationType.ONCE -> "一次"
        AutomationType.DAILY -> "每天"
        AutomationType.WEEKLY -> {
            val name = when (a.dayOfWeek) {
                2 -> "周一"; 3 -> "周二"; 4 -> "周三"; 5 -> "周四"; 6 -> "周五"; 7 -> "周六"; 1 -> "周日"; else -> "每周"
            }
            name
        }
    }
    val time = if (a.type == AutomationType.ONCE) {
        SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(a.triggerAtMillis))
    } else {
        "${a.hourOfDay.toString().padStart(2, '0')}:${a.minuteOfHour.toString().padStart(2, '0')}"
    }
    return "$repeat · $time · ${if (a.enabled) "启用" else "停用"}"
}
