package com.kaoyan.wordhelper.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.ui.screen.LearningMode

private val LearningMode.label: String
    get() = if (this == LearningMode.RECOGNITION) "认词模式" else "拼写模式"

/** 抽屉里的三个一级入口，替代原底部导航。 */
enum class DrawerDestination(val label: String, val icon: ImageVector) {
    LEARNING("学习", Icons.AutoMirrored.Filled.MenuBook),
    BOOKS("词库", Icons.AutoMirrored.Outlined.LibraryBooks),
    PROFILE("我的", Icons.Filled.Person)
}

/** 左上角菜单按钮：图标 24dp，点击热区 48dp。 */
@Composable
fun AppMenuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .testTag("app_menu_button")
    ) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = "打开菜单",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * 三个一级页共用的页头：菜单按钮 + 标题 + 右侧动作。
 * 和学习页用同一套内边距，保证三页的「三条横线」落在同一个位置。
 *
 * 1.4.7.4：先让开系统状态栏（隐藏时为 0），再让开挖孔安全边距，
 * 因此全屏沉浸开启 / 关闭两种状态下，三页的「≡」都落在同一像素位置，也不会被前置摄像头压住。
 */
@Composable
fun AppPageHeader(
    title: String,
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .displayCutoutPadding(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppMenuButton(onClick = onOpenDrawer)
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
        Spacer(modifier = Modifier.weight(1f))
        actions()
    }
}

/**
 * 抽屉内容（1.4.7 改版）：
 * 顶部是模式状态栏，点开才向下拉出「认词模式 / 拼写模式」两个选项；
 * 三个一级入口紧跟模式栏下方，展开时一起被顶下去。
 */
@Composable
fun AppDrawerContent(
    currentMode: LearningMode,
    currentRoute: String?,
    onModeChange: (LearningMode) -> Unit,
    onNavigate: (DrawerDestination) -> Unit,
    modifier: Modifier = Modifier,
    // 抽屉开合进度：0f = 全关，1f = 全开。默认 1f，兼容不传的旧调用方
    drawerProgress: () -> Float = { 1f }
) {
    var modeExpanded by rememberSaveable { mutableStateOf(false) }
    val arrowRotation by animateFloatAsState(
        targetValue = if (modeExpanded) 180f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 450f),
        label = "drawerModeArrow"
    )
    // 模式展开 / 收起共用同一条强调减速曲线
    val modeEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

    Column(
        modifier = modifier
            .fillMaxHeight()
            // 抽屉内容相对抽屉本体做微视差（少位移 14dp）+ 淡入，随开合进度连续变化
            .graphicsLayer {
                val progress = drawerProgress()
                translationX = -(1f - progress) * 14.dp.toPx()
                alpha = 0.45f + 0.55f * progress
            }
            .padding(horizontal = 10.dp)
            .padding(bottom = 12.dp)
    ) {
        // 模式状态栏：只显示当前模式，点它才展开
        val modeBarInteraction = remember { MutableInteractionSource() }
        // 模式胶囊：透明 Surface + 白玻璃渐变 + 1dp 白描边。
        // 抽屉底色永远是深色玻璃，这里不能再用 primaryContainer 之类的主题实心色。
        val modeBarShape = RoundedCornerShape(18.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .pressScaleModifier(modeBarInteraction)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.02f)
                        )
                    ),
                    modeBarShape
                )
                .border(1.dp, Color.White.copy(alpha = 0.20f), modeBarShape)
                .clickable(
                    interactionSource = modeBarInteraction,
                    indication = LocalIndication.current
                ) { modeExpanded = !modeExpanded }
                .testTag("learning_mode_toggle"),
            shape = modeBarShape,
            color = Color.Transparent
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentMode.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFF1F5F9),
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (modeExpanded) "收起模式" else "展开模式",
                    tint = Color(0xFFCBD5E1),
                    modifier = Modifier
                        .size(22.dp)
                        .rotate(arrowRotation)
                )
            }
        }

        // 两个模式选项：比抽屉窄、往右缩进
        AnimatedVisibility(
            visible = modeExpanded,
            enter = expandVertically(
                animationSpec = tween(260, easing = modeEasing),
                expandFrom = Alignment.Top
            ) + fadeIn(tween(260, easing = modeEasing)),
            exit = shrinkVertically(
                animationSpec = tween(200, easing = modeEasing),
                shrinkTowards = Alignment.Top
            ) + fadeOut(tween(200, easing = modeEasing))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeOptionRow(
                    text = "认词模式",
                    selected = currentMode == LearningMode.RECOGNITION,
                    testTag = "learning_mode_recognition",
                    onClick = {
                        onModeChange(LearningMode.RECOGNITION)
                        modeExpanded = false
                    }
                )
                ModeOptionRow(
                    text = "拼写模式",
                    selected = currentMode == LearningMode.SPELLING,
                    testTag = "learning_mode_spelling",
                    onClick = {
                        onModeChange(LearningMode.SPELLING)
                        modeExpanded = false
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 三个一级入口贴底部，当前所在项高亮
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            DrawerDestination.entries.forEach { destination ->
                DrawerNavRow(
                    destination = destination,
                    selected = when (destination) {
                        DrawerDestination.LEARNING -> currentRoute == "learning"
                        DrawerDestination.BOOKS -> currentRoute == "book_manage"
                        DrawerDestination.PROFILE -> currentRoute == "profile"
                    },
                    onClick = { onNavigate(destination) }
                )
            }
        }
    }
}

/** 按下缩到 0.97f、松手弹簧回弹 1f 的通用按压手感。 */
@Composable
private fun Modifier.pressScaleModifier(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "drawerPressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
private fun ModeOptionRow(
    text: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    // 选中项 = 蓝紫流光胶囊；未选中 = 极淡的白玻璃。两者都自带描边，
    // 不再依赖主题色，亮色主题下也不会变成一块看不清的浅色实心块。
    val fillBrush = if (selected) {
        Brush.horizontalGradient(
            listOf(
                Color(0xFF7C9BFF).copy(alpha = 0.38f),
                Color(0xFF9B7CFF).copy(alpha = 0.22f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.02f)
            )
        )
    }
    Surface(
        modifier = Modifier
            .width(200.dp)
            .height(48.dp)
            .pressScaleModifier(interactionSource)
            .background(fillBrush, shape)
            .border(
                width = 1.dp,
                color = if (selected) {
                    Color.White.copy(alpha = 0.24f)
                } else {
                    Color.White.copy(alpha = 0.10f)
                },
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .testTag(testTag),
        shape = shape,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) Color(0xFFF8FAFC) else Color(0xFFCBD5E1),
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "当前模式",
                    tint = Color(0xFFBFD0FF),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DrawerNavRow(
    destination: DrawerDestination,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary
    // 玻璃行：未选中是 4% 白底 + 10% 白描边，选中是 primary→紫 的横向渐变 + 发光。
    // 渐变两端、描边、辉光、文字/图标色都走 200ms 过渡，切换时不会硬切。
    val fillStart by animateColorAsState(
        targetValue = if (selected) {
            primary.copy(alpha = 0.34f)
        } else {
            Color.White.copy(alpha = 0.04f)
        },
        animationSpec = tween(200),
        label = "drawerNavFillStart"
    )
    val fillEnd by animateColorAsState(
        targetValue = if (selected) {
            Color(0xFF9B7CFF).copy(alpha = 0.10f)
        } else {
            Color.White.copy(alpha = 0.04f)
        },
        animationSpec = tween(200),
        label = "drawerNavFillEnd"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            primary.copy(alpha = 0.45f)
        } else {
            Color.White.copy(alpha = 0.10f)
        },
        animationSpec = tween(200),
        label = "drawerNavBorder"
    )
    val glow by animateDpAsState(
        targetValue = if (selected) 10.dp else 0.dp,
        animationSpec = tween(200),
        label = "drawerNavGlow"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) Color.White else Color(0xFFE2E8F0),
        animationSpec = tween(200),
        label = "drawerNavLabel"
    )
    val iconColor by animateColorAsState(
        targetValue = if (selected) Color(0xFFBFD0FF) else Color(0xFF94A3B8),
        animationSpec = tween(200),
        label = "drawerNavIcon"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pressScaleModifier(interactionSource)
            .shadow(glow, shape, ambientColor = primary, spotColor = primary)
            .background(Brush.horizontalGradient(listOf(fillStart, fillEnd)), shape)
            .border(1.dp, borderColor, shape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
            .testTag(
                when (destination) {
                    DrawerDestination.LEARNING -> "drawer_nav_learning"
                    DrawerDestination.BOOKS -> "drawer_nav_books"
                    DrawerDestination.PROFILE -> "drawer_nav_profile"
                }
            ),
        shape = shape,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = destination.icon,
                contentDescription = destination.label,
                tint = iconColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                text = destination.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor
            )
            Box(modifier = Modifier.weight(1f))
        }
    }
}


