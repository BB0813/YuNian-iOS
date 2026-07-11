package com.lianyu.ai

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.lianyu.ai.common.BatteryOptimizationHelper
import com.lianyu.ai.feature.update.AppUpdateManager
import com.lianyu.ai.uicommon.component.UpdateDialog
import com.lianyu.ai.uicommon.theme.ThemeViewModel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOutline

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

    fun openCompanionChat(companionId: Long) {
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

    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        MainNavHost(
            navController = navController,
            pagerState = pagerState,
            mainActivity = mainActivity,
            isDarkTheme = isDark,
            openCompanionChat = ::openCompanionChat,
            bottomNavItems = bottomNavItems,
            coroutineScope = coroutineScope,
            lastTabPage = lastTabPage,
            onLastTabPageChanged = { lastTabPage = it }
        )

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
