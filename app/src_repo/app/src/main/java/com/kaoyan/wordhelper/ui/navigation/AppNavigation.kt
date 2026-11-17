package com.kaoyan.wordhelper.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaoyan.wordhelper.ui.component.AppDrawerContent
import com.kaoyan.wordhelper.ui.component.DrawerDestination
import com.kaoyan.wordhelper.ui.screen.AILabScreen
import com.kaoyan.wordhelper.ui.screen.BookBuildGuideScreen
import com.kaoyan.wordhelper.ui.screen.BookManageScreen
import com.kaoyan.wordhelper.ui.screen.LearningMode
import com.kaoyan.wordhelper.ui.screen.LearningScreen
import com.kaoyan.wordhelper.ui.screen.ProfileScreen
import com.kaoyan.wordhelper.ui.screen.SearchScreen
import com.kaoyan.wordhelper.ui.screen.StatsScreen
import com.kaoyan.wordhelper.ui.screen.SyncStatusScreen
import com.kaoyan.wordhelper.ui.screen.TodayNewWordsSelectScreen
import com.kaoyan.wordhelper.ui.screen.UiDemoScreen
import kotlinx.coroutines.launch

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Learning : Screen("learning", "学习", Icons.Filled.MenuBook)
    data object Search : Screen("search", "查词", Icons.Filled.Search)
    data object BookManage : Screen("book_manage", "词库", Icons.Outlined.LibraryBooks)
    data object Profile : Screen("profile", "我的", Icons.Filled.Person)
    data object AILab : Screen("ai_lab", "实验室", Icons.Filled.Person)
    data object Stats : Screen("stats", "学习数据", Icons.Filled.MenuBook)
    data object BookBuildGuide : Screen("book_build_guide", "词书构建教程", Icons.Outlined.LibraryBooks)
    data object SyncStatus : Screen("sync_status", "同步状态", Icons.Filled.Sync)
    data object UiDemo : Screen("ui_demo", "UI 动效沙盒", Icons.Filled.AutoAwesome)
    data object TodayNewWordSelect : Screen("today_new_word_select/{bookId}", "今日新词", Icons.Filled.MenuBook) {
        fun createRoute(bookId: Long): String = "today_new_word_select/$bookId"
    }
}

/**
 * 1.4.7 改版：去掉底部导航与顶部模式条，统一用左侧抽屉承载
 * 「模式切换」和「学习 / 词库 / 我的」三个一级入口。
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var learningMode by rememberSaveable { mutableStateOf(LearningMode.RECOGNITION) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    val density = LocalDensity.current
    val swipeOpenThresholdPx = with(density) {
        (LocalConfiguration.current.screenWidthDp * 0.18f).dp.toPx()
    }
    // 只在三个主页面响应「任意位置右滑打开抽屉」，二级页不掺和
    val drawerSwipeRoutes = remember {
        setOf(Screen.Learning.route, Screen.BookManage.route, Screen.Profile.route)
    }

    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }

    val navigateFromDrawer: (DrawerDestination) -> Unit = { destination ->
        scope.launch { drawerState.close() }
        val route = when (destination) {
            DrawerDestination.LEARNING -> Screen.Learning.route
            DrawerDestination.BOOKS -> Screen.BookManage.route
            DrawerDestination.PROFILE -> Screen.Profile.route
        }
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 从屏幕左边缘右滑可拉出抽屉；抽屉上左滑可关，单手可操作
        gesturesEnabled = true,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(drawerWidth),
                drawerShape = RectangleShape,
                drawerContainerColor = Color.White,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                // 抽屉从状态栏下方开始，系统状态栏那一行留给系统
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                AppDrawerContent(
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    currentMode = learningMode,
                    currentRoute = currentRoute,
                    onModeChange = { learningMode = it },
                    onNavigate = navigateFromDrawer
                )
            }
        }
    ) {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (currentRoute in drawerSwipeRoutes && drawerState.isClosed) {
                            Modifier.pointerInput(currentRoute) {
                                var dragged = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragged = 0f },
                                    onDragCancel = { dragged = 0f },
                                    onDragEnd = { dragged = 0f },
                                    onHorizontalDrag = { _, dragAmount ->
                                        dragged = if (dragAmount > 0f) dragged + dragAmount else 0f
                                        if (dragged >= swipeOpenThresholdPx) {
                                            dragged = 0f
                                            scope.launch { drawerState.open() }
                                        }
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Learning.route,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(Screen.Learning.route) {
                        LearningScreen(
                            initialMode = learningMode,
                            onOpenDrawer = openDrawer
                        )
                    }
                    composable(Screen.Search.route) { SearchScreen() }
                    composable(Screen.BookManage.route) {
                        BookManageScreen(
                            onOpenDrawer = openDrawer,
                            onStartSpelling = {
                                learningMode = LearningMode.SPELLING
                                navController.navigate(Screen.Learning.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            onOpenBuildGuide = { navController.navigate(Screen.BookBuildGuide.route) },
                            onOpenTodayNewWordSelect = { bookId ->
                                navController.navigate(Screen.TodayNewWordSelect.createRoute(bookId))
                            }
                        )
                    }
                    composable(Screen.Profile.route) {
                        ProfileScreen(
                            onOpenDrawer = openDrawer,
                            onOpenStats = { navController.navigate(Screen.Stats.route) },
                            onOpenAiLab = { navController.navigate(Screen.AILab.route) },
                            onOpenSyncStatus = { navController.navigate(Screen.SyncStatus.route) },
                            onOpenUiDemo = { navController.navigate(Screen.UiDemo.route) }
                        )
                    }
                    composable(Screen.UiDemo.route) {
                        UiDemoScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Screen.SyncStatus.route) {
                        SyncStatusScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Screen.AILab.route) {
                        AILabScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Screen.Stats.route) {
                        StatsScreen(onBack = { navController.popBackStack() })
                    }
                    composable(Screen.BookBuildGuide.route) {
                        BookBuildGuideScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = Screen.TodayNewWordSelect.route,
                        arguments = listOf(navArgument("bookId") { type = NavType.LongType })
                    ) {
                        TodayNewWordsSelectScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}
