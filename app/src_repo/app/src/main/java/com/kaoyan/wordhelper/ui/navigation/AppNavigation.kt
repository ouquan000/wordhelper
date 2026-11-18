package com.kaoyan.wordhelper.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.ViewTreeObserver
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaoyan.wordhelper.ui.component.AppDrawerContent
import com.kaoyan.wordhelper.ui.component.DrawerDestination
import com.kaoyan.wordhelper.ui.component.rememberCardStyleDrawerState
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
fun AppNavigation(immersiveFullscreenEnabled: Boolean = true) {
    val navController = rememberNavController()
    // 1.4.7.4：全局全屏沉浸提到根层级统一控制，三页切换时状态栏不再反复弹出/隐藏。
    GlobalFullscreenStatusBarEffect(enabled = immersiveFullscreenEnabled)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var learningMode by rememberSaveable { mutableStateOf(LearningMode.RECOGNITION) }

    val scope = rememberCoroutineScope()
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    val drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
    // 0f = 全关，1f = 全开。拖动时直接改这个值，所以抽屉是跟手的。
    val drawerProgress = remember { Animatable(0f) }
    // 右侧「卡片材质」抽屉的状态提到这里：两个抽屉共用一套手势仲裁，
    // 材质抽屉打开时左抽屉的拖动必须让位，避免两边同时跟手叠在一起。
    val styleDrawerState = rememberCardStyleDrawerState()

    // 只在三个主页面响应全屏拖动抽屉，二级页不掺和
    val drawerSwipeRoutes = remember {
        setOf(Screen.Learning.route, Screen.BookManage.route, Screen.Profile.route)
    }
    val canDragDrawer = currentRoute in drawerSwipeRoutes

    val openDrawer: () -> Unit = {
        // 高阻尼弹簧：给一点初始速度，手感是「快甩快弹、慢拖缓停」
        scope.launch {
            drawerProgress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f)
            )
        }
    }
    val closeDrawer: () -> Unit = {
        scope.launch {
            drawerProgress.animateTo(
                targetValue = 0f,
                animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f)
            )
        }
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
            // 底层必须自己铺主题背景：抽屉打开时 Scaffold 会整体缩到 0.968，
            // 不铺的话缩放四周露出的就是 Activity 的窗口白底（夜间尤其明显）。
            .background(MaterialTheme.colorScheme.background)
            .draggable(
                state = dragState,
                orientation = Orientation.Horizontal,
                // 材质抽屉打开/拖动期间左抽屉不接手势，避免两个抽屉同时跟手叠在一起；
                // 关掉材质抽屉后右滑才重新归左抽屉。
                enabled = canDragDrawer && !styleDrawerState.isOpen,
                onDragStopped = { velocity ->
                    val target = when {
                        velocity > 600f -> 1f
                        velocity < -600f -> 0f
                        drawerProgress.value > 0.32f -> 1f
                        else -> 0f
                    }
                    drawerProgress.animateTo(
                        targetValue = target,
                        animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f),
                        // 手指速度是 px/s，归一化到 0..1 的抽屉进度单位，甩动惯性才跟手
                        initialVelocity = velocity / drawerWidthPx
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
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                // 底层主屏景深微退：在绘制阶段读 progress，拖动时只重绘、不重组
                .graphicsLayer {
                    val progress = drawerProgress.value
                    val depthScale = 1f - 0.032f * progress
                    scaleX = depthScale
                    scaleY = depthScale
                    transformOrigin = TransformOrigin.Center
                }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Learning.route,
                    modifier = Modifier.padding(innerPadding)
                ) {
                    composable(Screen.Learning.route) {
                        LearningScreen(
                            initialMode = learningMode,
                            onOpenDrawer = openDrawer,
                            styleDrawerState = styleDrawerState,
                            navDrawerOpen = drawerOpen
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
                .graphicsLayer { alpha = 0.36f * drawerProgress.value }
                .background(Color(0xFF0F172A))
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
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(12.dp, drawerShape)
                    .border(
                        width = 0.5.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                        shape = drawerShape
                    ),
                drawerShape = drawerShape,
                drawerContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                drawerContentColor = MaterialTheme.colorScheme.onSurface,
                drawerTonalElevation = 2.dp,
                // 抽屉从状态栏下方开始，系统状态栏那一行留给系统
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                AppDrawerContent(
                    modifier = Modifier.statusBarsPadding().navigationBarsPadding(),
                    currentMode = learningMode,
                    currentRoute = currentRoute,
                    onModeChange = { learningMode = it },
                    onNavigate = navigateFromDrawer,
                    drawerProgress = { drawerProgress.value }
                )
            }
        }
    }
}





/**
 * 1.4.7.4：全局全屏沉浸（隐藏系统状态栏）。
 *
 * 从 LearningScreen 提升到根层级：学习 / 词库 / 我的三页共用同一个窗口状态，
 * 切页时状态栏不会先弹出再收起，页面也不会上下跳 24dp。
 * - 开启：隐藏 statusBars，并允许顶部下滑临时呼出（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）；
 * - 关闭：立即恢复系统状态栏与原来的 systemBarsBehavior；
 * - 回到前台 / 窗口重新拿到焦点时再收一次，压掉系统先把状态栏显回来的那一帧。
 */
@Composable
private fun GlobalFullscreenStatusBarEffect(enabled: Boolean) {
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(view, context, lifecycleOwner, enabled) {
        val window = context.findActivity()?.window
        if (window == null) {
            return@DisposableEffect onDispose { }
        }
        val controller = WindowCompat.getInsetsController(window, view)
        val previousBehavior = controller.systemBarsBehavior
        fun apply() {
            if (enabled) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.statusBars())
            } else {
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior = previousBehavior
            }
        }
        apply()
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) apply()
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_START -> apply()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
