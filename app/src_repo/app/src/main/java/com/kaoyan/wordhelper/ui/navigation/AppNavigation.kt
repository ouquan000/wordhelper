package com.kaoyan.wordhelper.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
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
 *
 * 1.4.8：抽屉不再用 ModalNavigationDrawer 的「过阈值才弹开」，
 * 改成整屏可拖、抽屉实时跟手——按住往右拉抽屉就跟着出来，
 * 按住往左拉就跟着回去，中途反向也立刻响应；松手按速度/位置吸附。
 */
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var learningMode by rememberSaveable { mutableStateOf(LearningMode.RECOGNITION) }

    val scope = rememberCoroutineScope()
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    // 0f = 全关，1f = 全开。拖动时直接改这个值，所以抽屉是跟手的。
    val drawerProgress = remember { Animatable(0f) }

    // 只在三个主页面响应全屏拖动抽屉，二级页不掺和
    val drawerSwipeRoutes = remember {
        setOf(Screen.Learning.route, Screen.BookManage.route, Screen.Profile.route)
    }
    val canDragDrawer = currentRoute in drawerSwipeRoutes

    val openDrawer: () -> Unit = {
        scope.launch { drawerProgress.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
    }
    val closeDrawer: () -> Unit = {
        scope.launch { drawerProgress.animateTo(0f, tween(220, easing = FastOutSlowInEasing)) }
    }

    // 只在「开/关」真正翻转时重组，拖动过程中不逐帧重组
    val drawerOpen by remember { derivedStateOf { drawerProgress.value > 0.01f } }

    val navigateFromDrawer: (DrawerDestination) -> Unit = { destination ->
        closeDrawer()
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

    val dragState = rememberDraggableState { delta ->
        scope.launch {
            drawerProgress.snapTo((drawerProgress.value + delta / drawerWidthPx).coerceIn(0f, 1f))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                enabled = canDragDrawer,
                onDragStopped = { velocity ->
                    val target = when {
                        velocity > 600f -> 1f
                        velocity < -600f -> 0f
                        drawerProgress.value > 0.32f -> 1f
                        else -> 0f
                    }
                    drawerProgress.animateTo(
                        target,
                        tween(
                            durationMillis = if (target == 1f) 260 else 220,
                            easing = FastOutSlowInEasing
                        )
                    )
                }
            )
    ) {
        // 屏幕最左边缘那条窄带会被系统的「返回」手势抢走，导致从这里起手右滑直接退出 App。
        // 申请一段系统手势排除区（系统上限 200dp 高），这一段里的横向滑动交给抽屉。
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(28.dp)
                .height(200.dp)
                .systemGestureExclusion()
        )
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
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

        // 遮罩：跟着抽屉进度一起淡入淡出；抽屉开着时点它关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = drawerProgress.value }
                .background(Color.Black.copy(alpha = 0.32f))
                .then(
                    if (drawerOpen) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { closeDrawer() }
                    } else {
                        Modifier
                    }
                )
        )

        // 抽屉本体：整块跟着手指横向平移
        // BackHandler 放在 NavHost 之后组合，才能先于导航的返回处理拿到返回键
        BackHandler(enabled = drawerOpen) { closeDrawer() }
        Box(
            modifier = Modifier
                .width(drawerWidth)
                .fillMaxHeight()
                .graphicsLayer { translationX = -size.width * (1f - drawerProgress.value) }
        ) {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxSize(),
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
    }
}




