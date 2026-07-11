package com.lianyu.ai

import android.app.Activity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.lianyu.ai.common.YandereModeManager
import com.lianyu.ai.domain.ServiceRegistry
import com.lianyu.ai.feature.backup.BackupScreen
import com.lianyu.ai.feature.chat.ui.screen.ChatDetailScreen
import com.lianyu.ai.feature.chat.ui.screen.ChatScreen
import com.lianyu.ai.feature.chat.ui.screen.VoiceCallScreen
import com.lianyu.ai.feature.coffee.ui.CoffeeOrderQueryScreen
import com.lianyu.ai.feature.coffee.ui.CoffeeScreen
import com.lianyu.ai.feature.coffee.ui.CoffeeSettingsScreen
import com.lianyu.ai.feature.coffee.ui.CoffeeTokenInputScreen
import com.lianyu.ai.feature.coffee.ui.ProductDetailScreen
import com.lianyu.ai.feature.companion.ui.screen.ContactsScreen
import com.lianyu.ai.feature.companion.ui.screen.CreateCompanionScreen
import com.lianyu.ai.feature.groupchat.ui.CreateGroupScreen
import com.lianyu.ai.feature.groupchat.ui.GroupChatScreen
import com.lianyu.ai.feature.groupchat.ui.GroupDetailScreen
import com.lianyu.ai.feature.memory.MemoryScreen
import com.lianyu.ai.feature.profile.AboutScreen
import com.lianyu.ai.feature.profile.AgreementViewScreen
import com.lianyu.ai.feature.profile.GeneralSettingsScreen
import com.lianyu.ai.feature.profile.HomeScreen
import com.lianyu.ai.feature.profile.OriginOSAdaptionScreen
import com.lianyu.ai.feature.profile.ProfileScreen
import com.lianyu.ai.feature.profile.RoleManagerScreen
import com.lianyu.ai.feature.profile.SupportScreen
import com.lianyu.ai.feature.profile.TeamScreen
import com.lianyu.ai.feature.profile.ThanksFullListScreen
import com.lianyu.ai.feature.profile.ThanksScreen
import com.lianyu.ai.feature.qqbot.ui.QQBotSettingsScreen
import com.lianyu.ai.feature.settings.ui.screen.CheckUpdateScreen
import com.lianyu.ai.feature.settings.ui.screen.ExperimentalFeaturesScreen
import com.lianyu.ai.feature.settings.ui.screen.FrameRateScreen
import com.lianyu.ai.feature.settings.ui.screen.LanguageScreen
import com.lianyu.ai.feature.settings.ui.screen.SettingsScreen
import com.lianyu.ai.feature.settings.ui.screen.ThemeScreen
import com.lianyu.ai.feature.settings.ui.screen.TokenUsageScreen
import com.lianyu.ai.feature.settings.ui.screen.TtsSettingsScreen
import com.lianyu.ai.feature.settings.ui.screen.YandereModeScreen
import com.lianyu.ai.feature.wechat.ui.WeChatBindScreen
import com.lianyu.ai.feature.wechat.ui.WeChatSettingsScreen

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MainNavHost(
    navController: NavHostController,
    pagerState: PagerState,
    mainActivity: Activity,
    isDarkTheme: Boolean,
    openCompanionChat: (Long) -> Unit,
    bottomNavItems: List<BottomNavItem>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    lastTabPage: Int,
    onLastTabPageChanged: (Int) -> Unit
) {
    val slideTransitionSpec = spring<IntOffset>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    NavHost(
        navController = navController,
        startDestination = MainRoute.Home.route,
        enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = slideTransitionSpec) },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = slideTransitionSpec) }
    ) {
        composable(
            MainRoute.Home.route,
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None }
        ) {
            MainTabScreen(
                pagerState = pagerState,
                navController = navController,
                openCompanionChat = openCompanionChat,
                bottomNavItems = bottomNavItems,
                coroutineScope = coroutineScope,
                onLastTabPageChanged = onLastTabPageChanged
            )
        }

        composable(MainRoute.CreateCompanion.route) {
            CreateCompanionScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            MainRoute.EditCompanion(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            CreateCompanionScreen(
                companionId = companionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            MainRoute.Chat(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            ChatScreen(
                companionId = companionId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { navController.navigate(MainRoute.ChatDetail(it).route) },
                onNavigateToVoiceCall = { navController.navigate(MainRoute.VoiceCall(it).route) }
            )
        }
        composable(
            MainRoute.ChatDetail(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val detailCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            ChatDetailScreen(
                companionId = detailCompanionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        composable(
            MainRoute.VoiceCall(0).route.replace("0", "{companionId}"),
            arguments = listOf(navArgument("companionId") { type = NavType.LongType })
        ) { backStackEntry ->
            val callCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
            VoiceCallScreen(
                companionId = callCompanionId,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            MainRoute.GroupChat(0).route.replace("0", "{groupId}"),
            arguments = listOf(navArgument("groupId") { type = NavType.LongType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
            GroupChatScreen(
                groupId = groupId,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToDetail = { navController.navigate(MainRoute.GroupDetail(it).route) }
            )
        }
        composable(
            MainRoute.GroupDetail(0).route.replace("0", "{groupId}"),
            arguments = listOf(navArgument("groupId") { type = NavType.LongType })
        ) { backStackEntry ->
            val groupId = backStackEntry.arguments?.getLong("groupId") ?: 0L
            GroupDetailScreen(
                groupId = groupId,
                onNavigateBack = { navController.popBackStack() },
                onGroupDeleted = { navController.popBackStack(MainRoute.GroupChat(groupId).route, inclusive = true) }
            )
        }
        composable(MainRoute.CreateGroup.route) {
            CreateGroupScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(MainRoute.Settings.route) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.TtsSettings.route) {
            TtsSettingsScreen(onNavigateBack = { navController.popBackStack() }, isDarkTheme = isDarkTheme)
        }
        composable(MainRoute.TokenUsage.route) {
            TokenUsageScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Memory.route) {
            MemoryScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.RoleManager.route) {
            val roleManagerViewModel: com.lianyu.ai.feature.profile.ProfileViewModel = viewModel()
            val managerCurrentRole by roleManagerViewModel.selectedRole.collectAsStateWithLifecycle()
            val managerSwitchState by roleManagerViewModel.switchState.collectAsStateWithLifecycle()
            RoleManagerScreen(
                currentRole = managerCurrentRole,
                switchState = managerSwitchState,
                onSwitchRole = { role -> roleManagerViewModel.switchRole(role) { navController.popBackStack() } },
                onNavigateBack = { navController.popBackStack() },
                onConsumeError = { roleManagerViewModel.consumeSwitchError() }
            )
        }
        composable(MainRoute.Theme.route) {
            ThemeScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity)
        }
        composable(MainRoute.Language.route) {
            LanguageScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity)
        }
        composable(MainRoute.CheckUpdate.route) {
            CheckUpdateScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.About.route) {
            AboutScreen(
                onNavigateBack = { navController.popBackStack() },
                onAgreementClick = { navController.navigate(MainRoute.AgreementView.route) }
            )
        }
        composable(MainRoute.AgreementView.route) {
            AgreementViewScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.FrameRate.route) {
            FrameRateScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity)
        }
        composable(MainRoute.YandereMode.route) {
            val manager = ServiceRegistry.get(YandereModeManager::class.java)
            if (manager != null) {
                YandereModeScreen(
                    onNavigateBack = { navController.popBackStack() },
                    yandereModeManager = manager
                )
            }
        }
        composable(MainRoute.Team.route) {
            TeamScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Support.route) {
            SupportScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Thanks.route) {
            ThanksScreen(
                onNavigateBack = { navController.popBackStack() },
                onViewFullList = { navController.navigate(MainRoute.ThanksFullList.route) }
            )
        }
        composable(MainRoute.ThanksFullList.route) {
            ThanksFullListScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.OriginOSAdaption.route) {
            OriginOSAdaptionScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.ExperimentalFeatures.route) {
            ExperimentalFeaturesScreen(
                onNavigateBack = { navController.popBackStack() },
                onYandereModeClick = { navController.navigate(MainRoute.YandereMode.route) }
            )
        }
        composable(MainRoute.GeneralSettings.route) {
            GeneralSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onLanguageClick = { navController.navigate(MainRoute.Language.route) },
                onFrameRateClick = { navController.navigate(MainRoute.FrameRate.route) },
                onTtsSettingsClick = { navController.navigate(MainRoute.TtsSettings.route) },
                onTokenUsageClick = { navController.navigate(MainRoute.TokenUsage.route) },
                onCheckUpdateClick = { navController.navigate(MainRoute.CheckUpdate.route) },
                onWeChatClick = { navController.navigate(MainRoute.WeChatSettings.route) },
                onQQBotClick = { navController.navigate(MainRoute.QQBotSettings.route) },
                onDataBackupClick = { navController.navigate(MainRoute.DataBackup.route) },
                onOriginOSAdaptionClick = { navController.navigate(MainRoute.OriginOSAdaption.route) },
                onCoffeeClick = { navController.navigate(MainRoute.Coffee.route) },
                onExperimentalFeaturesClick = { navController.navigate(MainRoute.ExperimentalFeatures.route) }
            )
        }
        composable(MainRoute.WeChatSettings.route) {
            WeChatSettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onBindClick = { navController.navigate(MainRoute.WeChatBind.route) }
            )
        }
        composable(MainRoute.WeChatBind.route) {
            WeChatBindScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.QQBotSettings.route) {
            QQBotSettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.DataBackup.route) {
            BackupScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(MainRoute.Coffee.route) {
            CoffeeScreen(
                onBack = { navController.popBackStack() },
                onProductClick = { deptId, productId ->
                    navController.navigate(MainRoute.CoffeeProduct(deptId, productId).route)
                },
                onSettingsClick = { navController.navigate(MainRoute.CoffeeSettings.route) },
                onOrderQueryClick = { navController.navigate(MainRoute.CoffeeOrderQuery.route) }
            )
        }
        composable(
            MainRoute.CoffeeProduct(0, 0).route.replace("0", "{deptId}/{productId}"),
            arguments = listOf(
                navArgument("deptId") { type = NavType.LongType },
                navArgument("productId") { type = NavType.LongType }
            )
        ) { backStackEntry ->
            val deptId = backStackEntry.arguments?.getLong("deptId") ?: 0L
            val productId = backStackEntry.arguments?.getLong("productId") ?: 0L
            ProductDetailScreen(
                deptId = deptId,
                productId = productId,
                onBack = { navController.popBackStack() },
                onAddedToCart = { navController.popBackStack() }
            )
        }
        composable(MainRoute.CoffeeSettings.route) {
            CoffeeSettingsScreen(
                onBack = { navController.popBackStack() },
                onReplaceToken = { navController.navigate(MainRoute.CoffeeToken.route) },
                onQueryOrder = { orderId -> navController.navigate(MainRoute.CoffeeOrderQueryWithId(orderId).route) }
            )
        }
        composable(MainRoute.CoffeeToken.route) {
            CoffeeTokenInputScreen(onBack = { navController.popBackStack() })
        }
        composable(MainRoute.CoffeeOrderQuery.route) {
            CoffeeOrderQueryScreen(onBack = { navController.popBackStack() })
        }
        composable(
            MainRoute.CoffeeOrderQueryWithId("placeholder").route.replace("placeholder", "{orderId}"),
            arguments = listOf(navArgument("orderId") { type = NavType.StringType; nullable = false })
        ) { backStackEntry ->
            val orderId = backStackEntry.arguments?.getString("orderId").orEmpty()
            CoffeeOrderQueryScreen(
                initialOrderId = orderId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTabPager(
    pagerState: PagerState,
    navController: NavHostController,
    openCompanionChat: (Long) -> Unit
) {
    HorizontalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize()
    ) { page ->
        when (page) {
            0 -> HomeScreen(
                onCompanionClick = openCompanionChat,
                onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) }
            )
            1 -> ContactsScreen(
                onCompanionClick = openCompanionChat,
                onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                onEditClick = { navController.navigate(MainRoute.EditCompanion(it).route) },
                onGroupClick = { navController.navigate(MainRoute.GroupChat(it).route) },
                onCreateGroupClick = { navController.navigate(MainRoute.CreateGroup.route) }
            )
            2 -> ProfileScreen(
                onMemoryClick = { navController.navigate(MainRoute.Memory.route) },
                onSettingsClick = { navController.navigate(MainRoute.Settings.route) },
                onThemeClick = { navController.navigate(MainRoute.Theme.route) },
                onGeneralSettingsClick = { navController.navigate(MainRoute.GeneralSettings.route) },
                onRoleManagerClick = { navController.navigate(MainRoute.RoleManager.route) },
                onTeamClick = { navController.navigate(MainRoute.Team.route) },
                onSupportClick = { navController.navigate(MainRoute.Support.route) },
                onThanksClick = { navController.navigate(MainRoute.Thanks.route) },
                onAboutClick = { navController.navigate(MainRoute.About.route) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MainTabScreen(
    pagerState: PagerState,
    navController: NavHostController,
    openCompanionChat: (Long) -> Unit,
    bottomNavItems: List<BottomNavItem>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onLastTabPageChanged: (Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        MainTabPager(
            pagerState = pagerState,
            navController = navController,
            openCompanionChat = openCompanionChat
        )
        FloatingGlassBottomNav(
            items = bottomNavItems,
            currentIndex = pagerState.currentPage,
            onItemClick = { index ->
                onLastTabPageChanged(index)
                coroutineScope.launch {
                    pagerState.animateScrollToPage(index)
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}