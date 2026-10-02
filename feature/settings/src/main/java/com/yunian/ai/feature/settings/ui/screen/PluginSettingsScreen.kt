@file:OptIn(ExperimentalMaterial3Api::class)

package com.yunian.ai.feature.settings.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunian.ai.domain.ServiceRegistry
import com.yunian.ai.domain.plugin.LianYuPlugin
import com.yunian.ai.domain.plugin.PluginEnablementStore
import com.yunian.ai.domain.plugin.PluginHost
import com.yunian.ai.feature.settings.R
import com.yunian.ai.feature.settings.plugin.PluginToggleController
import com.yunian.ai.feature.settings.plugin.PluginToggleResult
import com.yunian.ai.feature.settings.plugin.PluginSettingsBoard
import com.yunian.ai.feature.settings.plugin.PluginSettingsRow
import com.yunian.ai.uicommon.component.glass.GlassPageScaffold
import com.yunian.ai.uicommon.component.glass.GlassTopBar
import com.yunian.ai.uicommon.icon.AppIcons
import com.yunian.ai.uicommon.plugin.PluginSettingsCategory
import com.yunian.ai.uicommon.plugin.PluginSettingsPresentation
import com.yunian.ai.uicommon.plugin.PluginSettingsSection
import com.yunian.ai.uicommon.plugin.PluginSettingsSections
import com.yunian.ai.uicommon.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「插件设置」页：消息通道 / 通用插件两个 Tab 的插件清单，逐条开关与内联配置。
 *
 * ## 数据源
 *
 * 唯一数据源是 [ServiceRegistry] 里的 [PluginHost]（`:app` 在
 * `YuNianApplication` 注册）——**不硬编码任何插件 id**：列表来自
 * [PluginHost.plugins]，分类来自 [LianYuPlugin.kind]，开关来自 [PluginHost.isLoaded]。
 * 因此新增插件不需要动本文件。
 *
 * ## 三件事分别由谁负责
 *
 * | 关注点 | 落点 |
 * |---|---|
 * | 分类规则 / 搜索匹配 / 行构造 / 开关推导 | [PluginSettingsBoard]（纯逻辑，JVM 单测覆盖） |
 * | 渲染与接线（Tab、Pager、开关副作用） | 本文件 |
 * | 每个插件的配置界面 | 插件自己注册的 [PluginSettingsSection]（`core:ui-common` 契约） |
 *
 * ## 页面结构
 *
 * ```
 * GlassTopBar「插件设置」（带返回）
 * 搜索框
 * TabRow：消息通道 │ 通用插件   ←→ HorizontalPager（双向同步）
 * 列表（当前 Tab 的条目；每行 = 开关 + 动作提示）
 * 页内全屏浮层（点行 → 该行的 FULL_PAGE 设置区；同一时刻最多一个）
 * ```
 *
 * ## 呈现方式分流（[PluginSettingsPresentation]）
 *
 * 列表项**不**无条件内联渲染 `section.Content()`：整页设置区（自带
 * `GlassPageScaffold` / 滚动容器 / 背景层）塞进外层 `LazyColumn` 的 item 里
 * 会拿到**无界最大高度约束**、运行期抛异常。分流规则由 [PluginSettingsBoard] 在行构造时
 * 算好（[PluginSettingsRow.action]），本文件只照着画：
 *
 * | [PluginSettingsPresentation] | 点行 | 渲染位置 |
 * |---|---|---|
 * | `INLINE` | 展开 / 收起 | 行的展开区（[PluginSettingsRows] 的 `AnimatedVisibility`） |
 * | `FULL_PAGE` | 打开浮层 | 页内全屏浮层（本文件末尾的 `AnimatedVisibility`） |
 *
 * 「工具授权」（`tool.grant`）声明的正是 `FULL_PAGE`，于是它与两个通道设置区
 * **共用同一条分支**，不再是一段按 id 硬编码的特例。
 *
 * ## 开关的副作用顺序
 *
 * 拨动开关时**先动宿主、再落存储**：`unload` / `load` 是本次操作真正生效的部分，
 * 存储只是让它跨重启保留。顺序反过来会出现「存储写成功但宿主没改」的假成功。
 * 宿主调用的 `synchronized(this)` 段可能执行插件 `setup` / 撤销 `effect`（含 IO），
 * 所以整段放在 [Dispatchers.IO] 上，不占主线程。
 *
 * 开关仅显示宿主运行态；事务检查装载结果和卸载后的实际状态，再保存并读回校验。
 * 存储缺失或校验失败显示「当前生效但未保存，重启可能恢复」，不回滚宿主。
 * 重复点击被拒绝，协程取消向上传播。
 *
 * @param onNavigateBack 返回上一级；路由由 `:app` 挂载，本页不感知导航。
 */
@Composable
fun PluginSettingsScreen(
    onNavigateBack: () -> Unit,
) {
    // 当前打开的 FULL_PAGE 设置区（null = 没有浮层）。用 pluginId 而不是 Boolean：
    // 浮层渲染的永远是「点开那一行自己注册的」设置区，不会串台。
    var overlayPluginId by remember { mutableStateOf<String?>(null) }

    // 启停操作的失败反馈出口。范式与**同一个模块**的 [CapabilityGrantScreen] /
    // [SettingsScreen] / [CheckUpdateScreen] 逐字一致：状态提升到这里、由
    // [GlassPageScaffold] 的 snackbarHost 槽渲染、内容由下方页面主体 showSnackbar 投递。
    //
    // 为什么必须放在最外层（而不是页面主体内部）：Scaffold 在本函数里创建，
    // snackbarHost 槽只能挂到它上面；放到 [PluginSettingsPageContent] 里就够不着了。
    val snackbarHostState = remember { SnackbarHostState() }

    // ── 「工具授权」保留条目的注册点 ──
    //
    // 为什么注册在这里（而不是插件的 setup()、也不是 :app 的宿主启动代码）：
    //
    // 1. 它的生命周期**不属于任何插件**——「能力授权」是设置页自己的一个固定入口，
    //    没有 LianYuPlugin 实例、没有装载态、也不会被卸载。挂在插件 setup() 上会
    //    凭空造一个「假插件」，挂在 :app 又会把设置页的私有 UI 契约（本模块的
    //    CapabilityGrantScreen）泄漏到宿主模块，两边都不成立。
    // 2. 它是**本页自己的一个条目**，就由本页注册：进入本页即可见、离开本页即撤销，
    //    与「页面自己拥有这一行」的直觉一致。
    // 3. 用户裁定 Q2/Q6 要求它出现在通用插件列表里，且**必须经
    //    PluginSettingsSections 走同一套「有没有设置区」的判定**（见
    //    [PluginSettingsBoard.buildRows] 的 availableSections 参数）——这样
    //    「展开后渲染谁」在页面里只有一条代码路径，合成条目和真实插件不会分叉。
    //
    // 代价（明确记录）：`PluginSettingsSections.forPlugin("tool.grant")` 在本页
    // 不处于组合期时返回 null。本仓库当前没有第二个消费者，将来若需要「在别处也
    // 查得到」，把这个 DisposableEffect 上移到 :app 的宿主启动代码即可——契约本身
    // （`core:ui-common` 的 PluginSettingsSections）不需要改。
    val onNavigateBackStable = remember { onNavigateBack }
    val toolGrantSection = remember(onNavigateBackStable) { ToolGrantSettingsSection(onNavigateBackStable) }
    var sectionsRevision by remember { mutableIntStateOf(0) }
    DisposableEffect(toolGrantSection) {
        PluginSettingsSections.register(toolGrantSection)
        sectionsRevision++
        onDispose {
            PluginSettingsSections.unregister(toolGrantSection.pluginId)
            sectionsRevision++
        }
    }

    GlassPageScaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.plugin_settings_title),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        PluginSettingsPageContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            sectionsRevision = sectionsRevision,
            snackbarHostState = snackbarHostState,
            onOpenFullPage = { pluginId -> overlayPluginId = pluginId },
        )
    }

    // 集中拦截系统返回键：浮层打开时先关它，而不是直接弹出整个插件设置页。
    BackHandler(enabled = overlayPluginId != null) { overlayPluginId = null }

    // 全屏覆盖层（与 SettingsScreen 里「视觉模型 / AI 生图」等二级页同一种写法）：
    // 不新增导航路由，也不改动 :app 的路由表。
    //
    // 渲染的正是**点开那一行**注册进 PluginSettingsSections 的设置区：列表里的条目与
    // 这里的内容来自同一个对象，不会出现「列表说能进去、点开是空的」。
    // 为什么必须是整页：这类设置区自带 `GlassPageScaffold`（Scaffold + 背景层 +
    // 滚动容器），内联进列表项会形成纵向嵌套滚动（内层高度无界 → 运行期抛异常），
    // 而且它需要 `LocalPageBackdrop` 这个**同窗口**的背景（跨窗口采样在
    // 「切后台→回前台」后会失效）。
    val overlayId = overlayPluginId
    AnimatedVisibility(
        visible = overlayId != null,
        modifier = Modifier.fillMaxSize(),
        enter = slideInHorizontally(animationSpec = tween(280)) { it / 4 } + fadeIn(tween(220)),
        exit = slideOutHorizontally(animationSpec = tween(220)) { it / 4 } + fadeOut(tween(160))
    ) {
        if (overlayId != null) {
            PluginSettingsSections.forPlugin(overlayId)?.Content()
        }
    }
}

/**
 * 「工具授权」保留条目的设置区实现。
 *
 * [pluginId] 取 [PluginSettingsBoard.TOOL_GRANT_ID]（`tool.grant`），
 * [category] 取 [PluginSettingsCategory.GENERAL]（用户裁定：并入通用插件）。
 *
 * [Content] 渲染**已存在的** [CapabilityGrantScreen]——不复制它的实现，
 * 于是「工具授权」页只有一份代码，旧入口删掉之后也不会留下两套行为。
 */
private class ToolGrantSettingsSection(
    private val onNavigateBack: () -> Unit,
) : PluginSettingsSection {
    override val pluginId: String = PluginSettingsBoard.TOOL_GRANT_ID
    override val category: PluginSettingsCategory = PluginSettingsCategory.GENERAL

    /**
     * 保留条目也**显式声明** [PluginSettingsPresentation.FULL_PAGE]：它的内容
     * [CapabilityGrantScreen] 是整页（自带 [GlassPageScaffold]，含 `LazyColumn` 与
     * 整页背景层），塞进外层 `LazyColumn` 的一个 item 里会形成**纵向嵌套滚动**
     * （内层 `LazyColumn` 高度无界 → 运行期抛异常），而且它需要
     * `LocalPageBackdrop` 这个**同窗口**的背景才能渲染出液态玻璃
     * （跨窗口采样在「切后台→回前台」后会失效，见 GlassEditDialog 的注释）。
     *
     * 把它**声明出来**（而不是像以前那样由页面硬编码一个 `showToolGrants` 分支）
     * 之后，「工具授权」与两个通道设置区**共用同一条呈现分支**：页面只认
     * [PluginSettingsPresentation]，不再认识任何具体插件的 id。
     */
    override val presentation: PluginSettingsPresentation = PluginSettingsPresentation.FULL_PAGE

    /**
     * 渲染**已存在的** [CapabilityGrantScreen]——不复制它的实现，
     * 于是「工具授权」页只有一份代码，旧入口删掉之后也不会留下两套行为。
     *
     * 页内全屏浮层的实现在 [PluginSettingsScreen] 里（与 SettingsScreen 的
     * 「视觉模型 / AI 生图」二级页完全一致）。
     */
    @Composable
    override fun Content() {
        CapabilityGrantScreen(onNavigateBack = onNavigateBack)
    }
}

/** 页面主体：搜索框 + TabRow + HorizontalPager。 */
@Composable
private fun PluginSettingsPageContent(
    modifier: Modifier,
    sectionsRevision: Int,
    snackbarHostState: SnackbarHostState,
    onOpenFullPage: (String) -> Unit,
) {
    val host = remember { runCatching { ServiceRegistry.get(PluginHost::class.java) }.getOrNull() }
    val enablementStore = remember {
        runCatching { ServiceRegistry.get(PluginEnablementStore::class.java) }.getOrNull()
    }

    // 宿主态快照：宿主是普通对象、不是 Compose 状态，所以由 [hostRevision] 显式驱动刷新
    // （每次开关 / 每次进页面后自增）。放进 remember 是为了避免每帧都全量拷贝一次。
    var hostRevision by remember { mutableIntStateOf(0) }
    val plugins: List<LianYuPlugin> = remember(host, hostRevision) {
        host?.plugins().orEmpty()
    }
    val hostLoadedIds: Set<String> = remember(host, hostRevision) {
        host?.loadedIds().orEmpty()
    }

    // 已注册设置区 → 呈现方式。行的分流规则（内联 / 整页浮层）就从这里进入
    // PluginSettingsBoard.buildRows，Composable 自己不做任何呈现方式判断。
    val sectionPresentations: Map<String, PluginSettingsPresentation> = remember(sectionsRevision) {
        PluginSettingsSections.all().associate { it.pluginId to it.presentation }
    }

    var query by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var expandedPluginId by remember { mutableStateOf<String?>(null) }

    val pagerState = rememberPagerState(initialPage = 0) { PluginSettingsBoard.TAB_CATEGORIES.size }
    val scope = rememberCoroutineScope()

    // Tab ←→ Pager 双向同步：滑动手势与点击 Tab 等价（与 feature:memory 的 MemoryScreen 同一写法）。
    LaunchedEffect(pagerState.currentPage) {
        if (selectedTab != pagerState.currentPage) {
            selectedTab = pagerState.currentPage
        }
    }

    val loadSucceededMessage = stringResource(R.string.plugin_settings_load_succeeded)
    val toggleFailedMessage = stringResource(R.string.plugin_settings_toggle_failed)
    val notSavedMessage = stringResource(R.string.plugin_settings_not_saved)
    val controller = remember(host, enablementStore) {
        host?.let { pluginHost ->
            PluginToggleController(
                load = { pluginHost.load(it, null) },
                unload = pluginHost::unload,
                isLoaded = pluginHost::isLoaded,
                store = enablementStore,
            )
        }
    }
    var toggleBusy by remember { mutableStateOf(false) }
    val toggle: (String, Boolean) -> Unit = { pluginId, enabled ->
        if (controller != null && !toggleBusy) {
            // Set before launch: even two callbacks in the same frame cannot queue duplicate work.
            toggleBusy = true
            scope.launch {
                val result = try {
                    withContext(Dispatchers.IO) { controller.toggle(pluginId, enabled) }
                } finally {
                    // Includes exceptions and cancellation after synchronous host side effects.
                    hostRevision++
                    toggleBusy = false
                }
                val name = displayNameOf(plugins, pluginId)
                val message = when (result) {
                    PluginToggleResult.Saved -> if (enabled) loadSucceededMessage.format(name) else null
                    PluginToggleResult.NotSaved -> notSavedMessage.format(name)
                    is PluginToggleResult.Failed -> toggleFailedMessage.format(name, result.reason)
                    PluginToggleResult.Busy -> null
                }
                if (message != null) snackbarHostState.showSnackbar(message)
            }
        }
    }

    Column(modifier = modifier) {
        PluginSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 4.dp, bottom = 10.dp),
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = AppTheme.colors.primary.copy(alpha = 0.6f)
                )
            }
        ) {
            PluginSettingsBoard.TAB_CATEGORIES.forEachIndexed { index, category ->
                Tab(
                    selected = selectedTab == index,
                    onClick = {
                        selectedTab = index
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    text = {
                        Text(
                            text = stringResource(categoryTitleRes(category)),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Medium else FontWeight.Normal
                            ),
                            color = if (selectedTab == index) {
                                AppTheme.colors.primary
                            } else {
                                AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                        )
                    }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val category = PluginSettingsBoard.categoryOfTab(page) ?: return@HorizontalPager
            val rows = PluginSettingsBoard.buildRows(
                plugins = plugins,
                category = category,
                query = query,
                availableSections = sectionPresentations,
            )
            val switchStates = PluginSettingsBoard.deriveSwitchStates(
                rows = rows,
                hostLoadedIds = hostLoadedIds,
                disabledIds = null,
            )
            PluginRowList(
                rows = rows,
                switchStates = switchStates,
                query = query,
                expandedPluginId = expandedPluginId,
                onToggleExpanded = { pluginId ->
                    expandedPluginId = if (expandedPluginId == pluginId) null else pluginId
                },
                onToggleEnabled = toggle,
                onOpenFullPage = onOpenFullPage,
                hostAvailable = host != null && !toggleBusy,
            )
        }
    }
}

/** 当前 Tab 的条目列表；空态**不留白**（区分「没匹配」与「暂无插件」）。 */
@Composable
private fun PluginRowList(
    rows: List<PluginSettingsRow>,
    switchStates: Map<String, Boolean>,
    query: String,
    expandedPluginId: String?,
    onToggleExpanded: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onOpenFullPage: (String) -> Unit,
    hostAvailable: Boolean,
) {
    if (rows.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = PluginSettingsBoard.emptyHint(query),
                fontSize = 13.sp,
                color = AppTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items = rows, key = { it.pluginId }) { row ->
            PluginSettingsRowItem(
                row = row,
                enabled = switchStates[row.pluginId] ?: false,
                hostAvailable = hostAvailable,
                expanded = expandedPluginId == row.pluginId,
                onToggleExpanded = { onToggleExpanded(row.pluginId) },
                onToggleEnabled = { onToggleEnabled(row.pluginId, it) },
                onOpenFullPage = onOpenFullPage,
            )
        }
    }
}

/** 搜索框：只过滤列表，不触发任何数据加载。 */
@Composable
private fun PluginSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = AppTheme.colors
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        singleLine = true,
        placeholder = {
            Text(
                text = stringResource(R.string.plugin_settings_search_hint),
                fontSize = 13.sp
            )
        },
        leadingIcon = {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = AppIcons.X,
                        contentDescription = stringResource(R.string.plugin_settings_search_clear),
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colorScheme.primary,
            unfocusedBorderColor = colorScheme.outline
        )
    )
}

/** 分类 → 标题文案资源（Tab 名与列表分组名共用同一份文案）。 */
private fun categoryTitleRes(category: PluginSettingsCategory): Int = when (category) {
    PluginSettingsCategory.CHANNEL -> R.string.plugin_settings_tab_channel
    PluginSettingsCategory.GENERAL -> R.string.plugin_settings_tab_general
}

/**
 * 反馈文案里的插件显示名：优先用插件自己的 [LianYuPlugin.name]（与列表行一致，
 * 用户能对上号），查不到时退回 pluginId——**绝不返回空串**，
 * 否则 Snackbar 会出现「装载失败：」这种缺主语的句子。
 */
private fun displayNameOf(plugins: List<LianYuPlugin>, pluginId: String): String =
    plugins.firstOrNull { it.id == pluginId }?.name?.takeIf { it.isNotBlank() } ?: pluginId
