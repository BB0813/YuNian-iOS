package com.lianyu.ai

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lianyu.ai.common.BatteryOptimizationHelper
import com.lianyu.ai.common.PerformanceTrace
import com.lianyu.ai.feature.update.AppUpdateManager
import com.lianyu.ai.uicommon.component.UpdateDialog
import com.lianyu.ai.uicommon.component.WindowMainBackground
import com.lianyu.ai.uicommon.component.getMainBackgroundKey
import com.lianyu.ai.uicommon.component.getCustomBackgroundUri
import com.lianyu.ai.uicommon.component.isCustomBackground
import com.lianyu.ai.uicommon.component.rememberBackgroundBitmap
import com.lianyu.ai.uicommon.component.rememberBackgroundImageBitmap
import com.lianyu.ai.uicommon.component.resolveBackgroundPalette
import com.lianyu.ai.uicommon.theme.ThemeViewModel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOutline
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.lianyu.ai.uicommon.component.glass.ProvidePageBackdrop
import com.lianyu.ai.uicommon.theme.WeChatDarkBackground
import com.lianyu.ai.uicommon.theme.WeChatLightBackground

/**
 * 主界面根壳层：维护全局导航状态、tab chrome 和全局弹窗。
 *
 * 状态空间模型:
 *   S ∈ {Home, Contacts, Profile, Chat(id), ChatDetail(id), VoiceCall(id),
 *        GroupChat(id), GroupDetail(id), CreateGroup, Settings, Theme, Language,
 *        CheckUpdate, About, FrameRate, Team, Support, Memory,
 *        TtsSettings, TokenUsage, WeChatSettings, WeChatBind,
 *        AgreementView, CreateCompanion, EditCompanion(id)}
 *   差分方程: S[k+1] = f(S[k], E[k])
 *   验证: 所有状态出度 ≥ 1 (popBackStack 保证)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainActivity: Activity) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    val valActivity = context as ComponentActivity

    val updateManager = remember {
        (valActivity as? MainActivity)?.updateManager ?: AppUpdateManager(context)
    }
    val showUpdateDialog by updateManager.showUpdateDialog.collectAsState()
    val updateInfo by updateManager.updateInfo.collectAsState()
    val downloadProgress by updateManager.downloadProgress.collectAsState()

    // 导航防抖：防止连续快速点击多次触发页面转场动画
    var lastNavTime by remember { mutableStateOf(0L) }

    fun openCompanionChat(companionId: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNavTime < 500) return
        lastNavTime = now
        PerformanceTrace.startChat()
        LastOpenedCompanionStore.save(context, companionId)
        navController.navigate(MainRoute.Chat(companionId).route)
    }

    // 只响应明确的通知/深链跳转；普通冷启动应停留在首页。
    LaunchedEffect(Unit) {
        val intent = valActivity.intent
        if (intent.getBooleanExtra("open_chat", false)) {
            val companionId = intent.getLongExtra("companion_id", -1L)
            if (companionId != -1L) {
                openCompanionChat(companionId)
                intent.removeExtra("open_chat")
                intent.removeExtra("companion_id")
                return@LaunchedEffect
            }
        }
    }

    // 引导弹窗
    MainScreenDialogs(
        onAutoStartSettings = { BatteryOptimizationHelper.openAutoStartSettings(context) },
        onBatterySettings = { BatteryOptimizationHelper.requestIgnoreBatteryOptimizations(context) }
    )

    val bottomNavItems = listOf(
        BottomNavItem(stringResource(R.string.nav_love), Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, MainRoute.Home.route),
        BottomNavItem(stringResource(R.string.nav_contacts), Icons.Filled.Group, Icons.Outlined.Group, MainRoute.Contacts.route),
        BottomNavItem(stringResource(R.string.nav_profile), Icons.Filled.Person, Icons.Outlined.PersonOutline, MainRoute.Profile.route)
    )

    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    // 记住离开 tab 页面前的 pager 位置，返回时恢复（用 rememberSaveable 防止 NavHost 过渡重建丢失）
    var lastTabPage by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(currentRoute) {
        if (!MainRoute.isMainTabRoute(currentRoute)) {
            // 离开 tab 页面（进入聊天等）—— 记住当前位置
            lastTabPage = pagerState.currentPage
            return@LaunchedEffect
        }
        // 返回 tab 页面 —— 恢复到离开前的位置，而非强制定位到首页
        val targetPage = when (currentRoute) {
            MainRoute.Contacts.route -> 1
            MainRoute.Profile.route -> 2
            else -> lastTabPage
        }
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    val themeViewModel: ThemeViewModel = viewModel()
    val isDark by themeViewModel.isDarkTheme.collectAsStateWithLifecycle()

    // 主界面背景：窗口层绘制（windowBackground / setBackgroundDrawable）
    // 同时用全局 backdrop 捕获块把同一 drawable 画进 Compose 捕获层，
    // 使玻璃组件（导航栏/卡片/按钮）能采样到真实主背景；背景切换时以 bgTick 驱动重绘
    var bgTick by remember { mutableIntStateOf(0) }
    fun syncWindowMainBackground() {
        val key = getMainBackgroundKey(context)
        WindowMainBackground.apply(mainActivity.window, context, key, isDark)
        bgTick++
    }
    // 只在深色模式切换时同步背景；tab 之间切换不再重复触发，
    // 避免 backdrop 反复重捕获导致玻璃闪一下变透明。
    LaunchedEffect(isDark) {
        if (MainRoute.isMainTabRoute(currentRoute)) {
            syncWindowMainBackground()
        }
    }
    // 冷启动时 backdrop 创建得比背景层早，可能拍到占位色。
    // 延迟几帧后强制重新创建 Backdrop，确保捕获到已渲染的真实背景。
    LaunchedEffect(Unit) {
        withFrameNanos {}
        withFrameNanos {}
        withFrameNanos {}
        bgTick++
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isDark) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncWindowMainBackground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 全局主 backdrop：捕获主 tab pager 内容（含 Compose 背景层），供导航栏玻璃组件采样。
    // 直接把背景画进 backdrop lambda，而不是依赖 layerBackdrop 子节点异步捕获。
    // 这样背景 Brush/ImageBitmap 变化时，lambda 会自然重组并重新 capture。
    val bgKey = getMainBackgroundKey(context)
    val bgImage = rememberBackgroundImageBitmap(bgKey)
    val (bgColor, bgGradient) = resolveBackgroundPalette(bgKey, isDark)
    val mainBackdrop = rememberLayerBackdrop {
        when {
            bgImage != null -> {
                drawImage(
                    bgImage,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            }
            bgGradient != null -> drawRect(brush = bgGradient)
            else -> drawRect(color = bgColor)
        }
        drawContent()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 背景由 window 层与 backdrop 捕获层共同负责；此处仅保留启动绘制标记与测试锚点
            .drawWithContent {
                drawContent()
                PerformanceTrace.markStartupDrawn()
            }
            .testTag("app_main_ready")
    ) {
        ProvidePageBackdrop(mainBackdrop) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 背景层进入 backdrop，仅底部导航栏使用真实液态玻璃。
                // 自定义图片异步加载完成后重新捕获 backdrop，避免启动时 backdrop
                // 拍到的是加载前的占位色，导致导航栏玻璃看起来透明。
                MainBackgroundLayer(
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize().layerBackdrop(mainBackdrop),
                    onPainterReady = { bgTick++ }
                )
                MainNavHost(
                    navController = navController,
                    pagerState = pagerState,
                    mainActivity = mainActivity,
                    isDarkTheme = isDark,
                    openCompanionChat = ::openCompanionChat,
                    bottomNavItems = bottomNavItems,
                    coroutineScope = coroutineScope,
                    lastTabPage = lastTabPage,
                    onLastTabPageChanged = { lastTabPage = it },
                    backdrop = mainBackdrop
                )
            }
        }

        if (showUpdateDialog && updateInfo != null) {
            UpdateDialog(
                updateInfo = updateInfo!!,
                downloadProgress = downloadProgress,
                onUpdate = {
                    updateInfo?.let { info ->
                        if (info.updateUrl.isNotEmpty()) {
                            if (updateManager.checkInstallPermission()) updateManager.startDownload(info.updateUrl)
                            else updateManager.requestInstallPermission(valActivity)
                        }
                    }
                },
                onCancel = { updateManager.ignoreThisVersion() },
                onDismiss = { updateManager.dismissUpdate() }
            )
        }
    }
}

/**
 * 主界面背景层。
 * 将预设渐变 / 纯色 / 自定义图片统一在 Compose 层绘制，使 backdrop 捕获层能采样到
 * 真实背景，从而保证导航栏、列表项等玻璃组件在自定义图片背景下也有液态玻璃效果。
 *
 * @param onPainterReady 自定义图片异步加载完成后的回调，用于触发 backdrop 重新捕获。
 */
@Composable
private fun MainBackgroundLayer(
    isDark: Boolean,
    modifier: Modifier = Modifier,
    onPainterReady: () -> Unit = {}
) {
    val context = LocalContext.current
    val key = getMainBackgroundKey(context)

    when {
        isCustomBackground(key) -> {
            val painter = rememberBackgroundBitmap(key)
            var hasNotifiedReady by remember { mutableStateOf(false) }
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = null,
                    modifier = modifier.onGloballyPositioned {
                        // 图片真正完成布局并渲染到屏幕后，再通知 backdrop 重新捕获，
                        // 避免 backdrop 拍到占位色。
                        if (!hasNotifiedReady) {
                            hasNotifiedReady = true
                            onPainterReady()
                        }
                    },
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = modifier
                        .background(if (isDark) WeChatDarkBackground else WeChatLightBackground)
                )
            }
        }
        else -> {
            val (color, gradient) = resolveBackgroundPalette(key, isDark)
            Box(
                modifier = if (gradient != null) {
                    modifier.background(gradient)
                } else {
                    modifier.background(color)
                }
            )
        }
    }
}

/**
 * 测试用背景层：强制使用红色，用于验证 backdrop 捕获是否正常。
 * 如果底部导航栏玻璃中出现红色模糊，说明 layerBackdrop / MainBackgroundLayer 工作正常。
 */
@Suppress("unused")
@Composable
private fun TestRedBackgroundLayer(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(Color.Red.copy(alpha = 0.6f)))
}
