import re, os

path = r'C:\Users\27194\Desktop\LianYu\app\src\main\java\com\yunian\ai\MainNavGraph.kt'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# --- Step 1: Add imports ---
imports_to_add = [
    'import androidx.compose.animation.core.Spring',
    'import androidx.compose.animation.core.spring',
    'import androidx.compose.animation.core.tween',
    'import androidx.compose.animation.slideInHorizontally',
    'import androidx.compose.animation.slideOutHorizontally',
    'import androidx.compose.foundation.layout.Box',
    'import androidx.compose.ui.Alignment',
]
# Insert after ExitTransition import
old = 'import androidx.compose.animation.ExitTransition'
new = old + '\n' + '\n'.join(imports_to_add)
content = content.replace(old, new, 1)

# Add kotlinx.coroutines.launch after navArgument
old = 'import androidx.navigation.navArgument'
new = old + '\nimport kotlinx.coroutines.launch'
content = content.replace(old, new, 1)

# --- Step 2: Add slideTransitionSpec variable ---
old = 'internal fun MainNavHost('
new = '''    val slideTransitionSpec = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

internal fun MainNavHost('''
content = content.replace(old, new, 1)

# --- Step 3: Update MainNavHost signature ---
old_sig = '''internal fun MainNavHost(
    navController: NavHostController,
    pagerState: PagerState,
    mainActivity: Activity,
    isDarkTheme: Boolean,
    openCompanionChat: (Long) -> Unit
) {'''
new_sig = '''internal fun MainNavHost(
    navController: NavHostController,
    pagerState: PagerState,
    mainActivity: Activity,
    isDarkTheme: Boolean,
    openCompanionChat: (Long) -> Unit,
    bottomNavItems: List<BottomNavItem>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    lastTabPage: Int,
    onLastTabPageChanged: (Int) -> Unit
) {'''
content = content.replace(old_sig, new_sig, 1)

# --- Step 4: Replace Home route with MainTabScreen ---
old_home = '''        composable(MainRoute.Home.route) {
            MainTabPager(
                pagerState = pagerState,
                navController = navController,
                openCompanionChat = openCompanionChat
            )
        }'''
new_home = '''        composable(MainRoute.Home.route) {
            MainTabScreen(
                pagerState = pagerState,
                navController = navController,
                openCompanionChat = openCompanionChat,
                bottomNavItems = bottomNavItems,
                coroutineScope = coroutineScope,
                onLastTabPageChanged = onLastTabPageChanged
            )
        }'''
content = content.replace(old_home, new_home, 1)

# --- Step 5: Add slide transitions to ALL non-tab composables ---
# simple composable(Route) -> composable(Route, transitions)
simple_routes = [
    'MainRoute.Settings.route',
    'MainRoute.TtsSettings.route',
    'MainRoute.TokenUsage.route',
    'MainRoute.Memory.route',
    'MainRoute.Theme.route',
    'MainRoute.Language.route',
    'MainRoute.CheckUpdate.route',
    'MainRoute.AgreementView.route',
    'MainRoute.FrameRate.route',
    'MainRoute.Team.route',
    'MainRoute.Support.route',
    'MainRoute.ThanksFullList.route',
    'MainRoute.OriginOSAdaption.route',
    'MainRoute.WeChatBind.route',
    'MainRoute.QQBotSettings.route',
    'MainRoute.DataBackup.route',
    'MainRoute.CoffeeToken.route',
    'MainRoute.CoffeeOrderQuery.route',
]

transition_block = '''enterTransition = { slideInHorizontally(initialOffsetX = { it }, animationSpec = slideTransitionSpec) },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(targetOffsetX = { it }, animationSpec = slideTransitionSpec) }'''

for route in simple_routes:
    old = f'composable({route}) {{'
    new = f'''composable(
            {route},
            {transition_block}
        ) {{'''
    content = content.replace(old, new, 1)

# Routes with arguments (companion/group IDs)
arg_routes = [
    'MainRoute.EditCompanion(0).route.replace("0", "{companionId}")',
    'MainRoute.Chat(0).route.replace("0", "{companionId}")',
    'MainRoute.ChatDetail(0).route.replace("0", "{companionId}")',
    'MainRoute.VoiceCall(0).route.replace("0", "{companionId}")',
    'MainRoute.GroupChat(0).route.replace("0", "{groupId}")',
    'MainRoute.GroupDetail(0).route.replace("0", "{groupId}")',
]

for route in arg_routes:
    old = f'''composable(
            {route},
            arguments = listOf'''
    new = f'''composable(
            {route},
            arguments = listOf'''
    # Need to find the pattern: composable(route, arguments=...) -> composable(route, arguments=..., transitions)
    # Actually these have nested closing paren. Let me use a different approach.
    pass

# Let me handle arg routes with a regex approach
# Pattern: composable(\n            ROUTE,\n            arguments = ...\n        ) { backStackEntry ->
# Replace with composable(\n            ROUTE,\n            arguments = ...,\n            transitions\n        ) { backStackEntry ->

for route in arg_routes:
    pattern = re.escape(f'composable(\n            {route},')
    replacement = f'composable(\n            {route},'
    # we need to find the closing ) before { backStackEntry
    # Use regex to match the whole block
    full_pattern = re.compile(
        re.escape(f'composable(\n            {route},\n            arguments = ')
        + r'[^)]+'
        + re.escape(')\n        ) { backStackEntry ->')
    )
    match = full_pattern.search(content)
    if match:
        old_block = match.group(0)
        # Insert transition block before the closing )\n        )
        new_block = old_block.replace(
            ')\n        ) { backStackEntry ->',
            f',\n            {transition_block}\n        ) {{ backStackEntry ->'
        )
        content = content.replace(old_block, new_block, 1)
        print(f'  Fixed arg route: {route[-30:]}')
    else:
        print(f'  NOT FOUND: {route[-30:]}')

# Routes with complex bodies (CreateCompanion, CreateGroup, About, etc.)
complex_routes = [
    'MainRoute.CreateCompanion.route',
    'MainRoute.CreateGroup.route',
    'MainRoute.RoleManager.route',
    'MainRoute.About.route',
    'MainRoute.YandereMode.route',
    'MainRoute.Thanks.route',
    'MainRoute.ExperimentalFeatures.route',
    'MainRoute.GeneralSettings.route',
    'MainRoute.WeChatSettings.route',
    'MainRoute.Coffee.route',
    'MainRoute.CoffeeSettings.route',
]

for route in complex_routes:
    old = f'composable({route}) {{'
    new = f'''composable(
            {route},
            {transition_block}
        ) {{'''
    content = content.replace(old, new, 1)
    if old in content:
        print(f'  WARNING: still more instances of {route[-30:]}')

# CoffeeProduct with double args
old_cp = '''composable(
            MainRoute.CoffeeProduct(0, 0).route.replace("0", "{deptId}/{productId}"),
            arguments = listOf(
                navArgument("deptId") { type = NavType.LongType },
                navArgument("productId") { type = NavType.LongType }
            )
        ) { backStackEntry ->'''
new_cp = f'''composable(
            MainRoute.CoffeeProduct(0, 0).route.replace("0", "{{deptId}}/{{productId}}"),
            arguments = listOf(
                navArgument("deptId") {{ type = NavType.LongType }},
                navArgument("productId") {{ type = NavType.LongType }}
            ),
            {transition_block}
        ) {{ backStackEntry ->'''
content = content.replace(old_cp, new_cp, 1)

# CoffeeOrderQueryWithId
old_co = '''composable(
            MainRoute.CoffeeOrderQueryWithId("placeholder").route.replace("placeholder", "{orderId}"),
            arguments = listOf(navArgument("orderId") { type = NavType.StringType; nullable = false })
        ) { backStackEntry ->'''
new_co = f'''composable(
            MainRoute.CoffeeOrderQueryWithId("placeholder").route.replace("placeholder", "{{orderId}}"),
            arguments = listOf(navArgument("orderId") {{ type = NavType.StringType; nullable = false }}),
            {transition_block}
        ) {{ backStackEntry ->'''
content = content.replace(old_co, new_co, 1)

# --- Step 6: Replace old MainTabPager and add MainTabScreen ---
old_pager = '''@OptIn(ExperimentalFoundationApi::class)
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
}'''

new_pager_and_tabscreen = '''@OptIn(ExperimentalFoundationApi::class)
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
}'''

content = content.replace(old_pager, new_pager_and_tabscreen, 1)

# Write back
with open(path, 'w', encoding='utf-8', newline='\n') as f:
    f.write(content)

print('All done!')