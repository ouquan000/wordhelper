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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.graphics.Brush
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.data.model.CardMotionMode
import com.kaoyan.wordhelper.data.repository.UserSettings
import com.kaoyan.wordhelper.ui.component.AmbientBackdrop
import com.kaoyan.wordhelper.ui.component.AmbientBackdropMode
import com.kaoyan.wordhelper.ui.component.AppDrawerContent
import com.kaoyan.wordhelper.ui.component.DrawerDestination
import com.kaoyan.wordhelper.ui.component.HoloCardStyle
import com.kaoyan.wordhelper.ui.component.rememberCardStyleDrawerState
import com.kaoyan.wordhelper.ui.component.studyContentInsets
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
import com.kaoyan.wordhelper.ui.theme.LocalAppDarkTheme
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
    // 全局暗夜烟雾要跟着「卡片材质 / 氛围 / 呼吸动效」三个设置走，所以在根层级订阅一次 settingsFlow。
    // Application 用 remember 缓存，别每次重组都做一次 applicationContext 强转。
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext as KaoyanWordApp }
    val uiSettings by appContext.settingsRepository.settingsFlow
        .collectAsStateWithLifecycle(initialValue = UserSettings())
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    // 首帧 backStack 还没就绪时 route 为 null，此时也按学习页处理：
    // startDestination 就是学习页，否则第一帧会先套一次 systemBars 再跳变，卡片同样会抖一下。
    val isLearningRoute = currentRoute == null || currentRoute == Screen.Learning.route
    var learningMode by rememberSaveable { mutableStateOf(LearningMode.RECOGNITION) }

    val scope = rememberCoroutineScope()
    // 抽屉玻璃的日/夜配色跟主题保持一致（主题由 settings.darkThemeEnabled 驱动）。
    val darkTheme = LocalAppDarkTheme.current
    // 360dp 是 ModalDrawerSheet 内部 DrawerDefaults.MaximumDrawerWidth 的上限：
    // 抽屉本体（和它内部的 Column）都会被夹到 360，宽屏上若还按 78% 算，
    // 右侧就会多出一条没内容的透明缝，所以这里先夹住。
    val drawerWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
        .coerceAtMost(360.dp)
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    // 只有右侧两条边是圆角：抽屉必须紧贴屏幕左/上/下，左上、左下不能有圆角缝隙。
    val drawerShape = RoundedCornerShape(
        topStart = 0.dp,
        bottomStart = 0.dp,
        topEnd = 24.dp,
        bottomEnd = 24.dp
    )
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

    // 左抽屉景深缩放的唯一公式：1f - 0.032f * progress。
    // 二级页由 Scaffold 整层缩；学习页不缩整根 Scaffold（否则 AmbientBackdrop 与
    // 圆角背板会一起缩、四周露出未缩放的根底色，形成矩形接缝），改为把这个
    // 同一 lambda 传给 LearningScreen，只乘到它自己的内容层上。
    // 抽屉全关时恒为 1f，等于什么都没做。
    val drawerDepthScale: () -> Float = remember {
        { 1f - 0.032f * drawerProgress.value }
    }

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
            // 这里不再铺不透明背景：整屏底色交给最底层的 AmbientBackdrop。
            // 抽屉打开时 Scaffold 会整体缩到 0.968，四周露出的是同一层烟雾，
            // 而不是 Activity 的窗口白底（夜间尤其明显）。
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
        // 学习页：Scaffold 不再整层缩放，学习页自己的 AmbientBackdrop 铺满整屏，
        // 这里只留一层静态底色兜底（帧间切换时那一瞬）。不再挂第二份 AmbientBackdrop，
        // 也就没有「缩了一层、根底另一套」的色差接缝。
        if (isLearningRoute) {
            Box(Modifier.fillMaxSize().background(if (darkTheme) Color(0xFF080D1A) else Color(0xFFFCF9F7)))
        } else {
            AmbientBackdrop(
                mode = AmbientBackdropMode.fromId(uiSettings.ambientBackdropId),
                cardStyle = HoloCardStyle.fromId(uiSettings.cardStyleId),
                motionMode = CardMotionMode.STATIC,
                timeSeconds = { 0f },
                modifier = Modifier.fillMaxSize()
            )
        }

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
                // 底层主屏景深微退（拖动时只重绘、不重组）。
                // 学习页在这里不缩：缩放交给 LearningScreen 的内容层，让
                // AmbientBackdrop 与圆角背板始终铺满全屏，杜绝四周露底接缝。
                // 其他路由照旧整层缩。
                .graphicsLayer {
                    val depthScale = if (isLearningRoute) 1f else drawerDepthScale()
                    scaleX = depthScale
                    scaleY = depthScale
                    transformOrigin = TransformOrigin.Center
                },
            // The learning content and both drawers apply their own safe areas.
            contentWindowInsets = if (isLearningRoute) {
                WindowInsets(0, 0, 0, 0)
            } else {
                ScaffoldDefaults.contentWindowInsets
            },
            // 透明容器：页面自己不带底色时露出的是底层的 AmbientBackdrop 烟雾
            containerColor = Color.Transparent
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
                            navDrawerOpen = drawerOpen,
                            navDrawerDepthScale = drawerDepthScale
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
                            onOpenUiDemo = { navController.navigate(Screen.UiDemo.route) },
                            darkThemeEnabled = uiSettings.darkThemeEnabled,
                            onDarkThemeEnabledChange = { enabled ->
                                scope.launch { appContext.settingsRepository.updateDarkThemeEnabled(enabled) }
                            }
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
                // 展示位置按钳到 0..1 的开度算：甩动时弹簧会过冲（progress > 1），
                // 不钳的话抽屉会越过屏幕左边缘，在边缝露出一条未缩放的根底色。
                // 弹簧参数 / 手势输入 / 阴影圆角都不动，只修这个露底。
                .graphicsLayer {
                    val shown = drawerProgress.value.coerceIn(0f, 1f)
                    translationX = -size.width * (1f - shown)
                }
        ) {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxSize()
                    .shadow(12.dp, drawerShape)
                    // 液态玻璃：先铺一层不透明同色底（夜间 0xFF070A11 / 日间 0xFFF4F6FA），
                    // 再叠 0.95 不透明度左右的同色系垂直玻璃渐变。两层叠加后底色完全不透，
                    // 底层卡片的文字不会再透过抽屉，只留玻璃质感、不留字影。
                    .background(if (darkTheme) Color(0xFF070A11) else Color(0xFFF4F6FA), drawerShape)
                    .background(
                        Brush.verticalGradient(
                            if (darkTheme) {
                                listOf(
                                    Color(0xFF0B101C).copy(alpha = 0.95f),
                                    Color(0xFF070A11).copy(alpha = 0.97f)
                                )
                            } else {
                                listOf(
                                    Color.White.copy(alpha = 0.95f),
                                    Color(0xF7F4F6FA).copy(alpha = 0.97f)
                                )
                            }
                        ),
                        drawerShape
                    )
                    .border(
                        width = 1.dp,
                        color = if (darkTheme) {
                            Color.White.copy(alpha = 0.22f)
                        } else {
                            Color(0xFF0F172A).copy(alpha = 0.12f)
                        },
                        shape = drawerShape
                    ),
                drawerShape = drawerShape,
                drawerContainerColor = Color.Transparent,
                drawerContentColor = if (darkTheme) Color(0xFFE2E8F0) else Color(0xFF0F172A),
                drawerTonalElevation = 0.dp,
                // 抽屉严格贴住屏幕左/上/下边，系统栏边距由内容自己用 padding 让
                windowInsets = WindowInsets(0, 0, 0, 0)
            ) {
                // 内容外包一层：底层画顶部环境高光，上层才是原来的抽屉内容。
                // 高光层只是绘制，不挂 clickable，不会挡住下面的点击。
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to if (darkTheme) {
                                        Color.White.copy(alpha = 0.10f)
                                    } else {
                                        Color.White.copy(alpha = 0.45f)
                                    },
                                    0.30f to Color.Transparent
                                )
                            )
                    )
                    AppDrawerContent(
                        modifier = Modifier.windowInsetsPadding(studyContentInsets()),
                        currentMode = learningMode,
                        currentRoute = currentRoute,
                        onModeChange = { learningMode = it },
                        onNavigate = navigateFromDrawer,
                        drawerProgress = { drawerProgress.value },
                        darkThemeEnabled = uiSettings.darkThemeEnabled,
                        onDarkThemeEnabledChange = { enabled ->
                            scope.launch { appContext.settingsRepository.updateDarkThemeEnabled(enabled) }
                        }
                    )
                }
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
