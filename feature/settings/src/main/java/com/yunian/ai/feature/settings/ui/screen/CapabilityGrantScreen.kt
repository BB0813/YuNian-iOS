package com.yunian.ai.feature.settings.ui.screen

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunian.ai.feature.settings.capability.CompanionOption
import com.yunian.ai.feature.settings.capability.GrantScope
import com.yunian.ai.feature.settings.capability.GrantToolItem
import com.yunian.ai.feature.settings.capability.scopeDisplayName
import com.yunian.ai.feature.settings.ui.viewmodel.CapabilityGrantViewModel
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.component.glass.LocalPageBackdrop
import com.yunian.ai.uicommon.component.glass.drawGlass
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.theme.AppTheme

/**
 * 「工具授权」设置页。
 *
 * 用途：把「工具授权」这条机制暴露给用户——声明了 `requiresConfirmation = true` 的工具
 * 在**没有确认界面**的通道上原本会被确认门拦下（通道侧 fail-closed 自动拒绝），
 * 用户在这里逐条同意后，装配期就把这些工具放行；反过来，用户也可以把本来不需要确认的工具
 * 显式改成「必须先确认」。
 *
 * 页面结构（P2-2d 重构后）：`授权对象`（伴侣选择器，默认「全部伴侣（通配）」）→
 * **一张列出全部 Agent tools 的清单**，每个工具一个开关。
 * **不再按通道分组**：授权对象是工具能力本身，同一条授权在 App 内单聊、群聊、QQ、微信上
 * 得到完全相同的结果；通道之间的差异只剩「有没有确认界面」。
 *
 * 入口：位于「API 设置」页（[SettingsScreen]）的独立玻璃卡片，与视觉模型 / AI 生图两个二级页
 * 同一种写法（页内 AnimatedVisibility 全屏浮层）。**没有新增导航路由**——本模块既有的二级页
 * 全部走这种页内浮层，遵循既有风格、也避免改动 app 模块的路由表。
 *
 * 生效时机：折叠点在每回合装配期读取授权，故此处拨动开关**立即生效，无需重启**。
 *
 * 页面逻辑（清单构造 / 合并展示 / 开关映射）在 com.yunian.ai.feature.settings.capability.CapabilityGrantBoard，
 * 由 JVM 单测覆盖；本文件只做渲染与接线。
 */
@Composable
fun CapabilityGrantScreen(
    onNavigateBack: () -> Unit,
    viewModel: CapabilityGrantViewModel = viewModel()
) {
    val boardItems by viewModel.boardItems.collectAsState()
    val companions by viewModel.companions.collectAsState()
    val scope by viewModel.selectedScope.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val storageAvailable = viewModel.storageAvailable

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    GlassPageScaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = "工具授权",
                onBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(modifier = Modifier.height(4.dp))

                    GrantHintCard()

                    CompanionScopeCard(
                        scope = scope,
                        companions = companions,
                        onScopeChange = { viewModel.selectScope(it) }
                    )

                    if (!storageAvailable) {
                        GrantNoticeCard(
                            text = "授权存储不可用：本次修改无法保存。请重启 App 后重试。",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    if (!isLoading && boardItems.isEmpty()) {
                        GrantNoticeCard(
                            text = "没有可授权的工具：Agent 工具注册池当前为空。",
                            tint = AppTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }

            if (boardItems.isNotEmpty()) {
                item(key = "tool_list") {
                    ToolListCard(
                        items = boardItems,
                        scopeName = scopeDisplayName(scope, companions),
                        onToggle = { item, allowed -> viewModel.setAllowed(item, allowed) }
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

/** 顶部说明卡：讲清「这是什么 / 怎么用 / 立即生效」。 */
@Composable
private fun GrantHintCard() {
    val colorScheme = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(20.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = AppIcons.ShieldCheck,
                contentDescription = null,
                tint = colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "工具授权",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
        }
        Text(
            text = "下面列出全部 Agent 工具。开关打开 = 该工具可直接执行（不再需要确认）；" +
                "开关关闭 = 该工具需要确认（App 内会弹出确认卡，没有确认界面的通道直接拒绝）。",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariant
        )
        Text(
            text = "授权只按「伴侣 × 工具」生效，与消息通道无关；拨动开关立即生效，无需重启。" +
                "没有单独设置过的工具维持它自己的默认（行内标注「默认需确认」/「默认允许」）。",
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariant
        )
    }
}

/** 顶部伴侣选择器：「全部伴侣（通配）」或某个具体伴侣。 */
@Composable
private fun CompanionScopeCard(
    scope: GrantScope,
    companions: List<CompanionOption>,
    onScopeChange: (GrantScope) -> Unit
) {
    val colorScheme = AppTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .drawGlass(
                    backdrop = LocalPageBackdrop.current,
                    shape = RoundedCornerShape(20.dp),
                    surfaceColor = colorScheme.surfaceVariant
                )
                .clickable { expanded = true }
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "授权对象",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface
                )
                Text(
                    text = when (scope) {
                        GrantScope.AllCompanions -> "全部伴侣（通配）：对下面每一个工具的所有伴侣都生效"
                        is GrantScope.Companion -> "仅「${scopeDisplayName(scope, companions)}」生效"
                    },
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = AppIcons.ChevronDown,
                contentDescription = "选择授权对象",
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部伴侣（通配）") },
                onClick = {
                    onScopeChange(GrantScope.AllCompanions)
                    expanded = false
                }
            )
            companions.forEach { companion ->
                DropdownMenuItem(
                    text = { Text(companion.name) },
                    onClick = {
                        onScopeChange(GrantScope.Companion(companion.id))
                        expanded = false
                    }
                )
            }
        }
    }
}

/** 工具清单卡：**一张**列出全部 Agent tools 的清单，每个工具一个开关。 */
@Composable
private fun ToolListCard(
    items: List<GrantToolItem>,
    scopeName: String,
    onToggle: (GrantToolItem, Boolean) -> Unit
) {
    val colorScheme = AppTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(20.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "全部工具（${items.size}）",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.onSurface
            )
            Text(
                text = "作用于：" + scopeName,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
        }

        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(color = colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            }
            GrantToolRow(item = item, onToggle = { allowed -> onToggle(item, allowed) })
        }
    }
}

/**
 * 单个工具行：名称 / 原始工具名 / 一句描述 / 「默认需确认 · 默认允许」标注 /
 * 「当前状态来自『全部伴侣（通配）』设置」来源说明（仅 [GrantToolItem.showsWildcardSource] 为真时）/ 开关。
 */
@Composable
private fun GrantToolRow(
    item: GrantToolItem,
    onToggle: (Boolean) -> Unit
) {
    val colorScheme = AppTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            if (item.displayName != item.toolName) {
                Text(
                    text = item.toolName,
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariant
                )
            }
            if (item.description.isNotBlank()) {
                Text(
                    text = item.description,
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Text(
                text = if (item.requiresConfirmation) "默认需确认" else "默认允许",
                fontSize = 11.sp,
                color = if (item.requiresConfirmation) colorScheme.primary else colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (item.showsWildcardSource) {
                // 具体伴侣视图下的来源说明：这一行的状态来自「全部伴侣（通配）」，
                // 本伴侣没有自己的记录。没有这行说明，用户会以为「我明明没设过它」。
                // 判据在 CapabilityGrantBoard（= 通配确实有决定），渲染层不自己拼条件。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Info,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "当前状态来自「全部伴侣（通配）」设置",
                        fontSize = 11.sp,
                        color = colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Switch(
            checked = item.allowed,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

/** 一般性提示卡（存储不可用 / 无工具可授权）。 */
@Composable
private fun GrantNoticeCard(text: String, tint: Color) {
    val colorScheme = AppTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .drawGlass(
                backdrop = LocalPageBackdrop.current,
                shape = RoundedCornerShape(16.dp),
                surfaceColor = colorScheme.surfaceVariant
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = AppIcons.TriangleAlert,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = text, fontSize = 12.sp, color = tint)
    }
}
