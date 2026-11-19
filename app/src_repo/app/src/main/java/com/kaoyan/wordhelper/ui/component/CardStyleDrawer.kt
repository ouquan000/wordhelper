package com.kaoyan.wordhelper.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.CardMotionMode
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * 学习页右侧「卡片材质」抽屉的状态。
 *
 * progress 是 0..1 的展开进度，直接驱动 offset/alpha；动画只在起止时跑，
 * 拖动时直接写值，避免一帧一个协程。
 */
@Stable
class CardStyleDrawerState {
    var progress by mutableFloatStateOf(0f)
        private set
    var isOpen by mutableStateOf(false)
        private set

    private var animation: Job? = null

    fun open(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            isOpen = true
            animateTo(1f, 0f)
        }
    }

    fun close(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            animateTo(0f, 0f)
        }
    }

    fun dragBy(deltaFraction: Float) {
        animation?.cancel()
        if (!isOpen) isOpen = true
        progress = (progress + deltaFraction).coerceIn(0f, 1f)
    }

    suspend fun settle(velocity: Float) {
        // velocity 是「progress 的变化速度」：正=正在展开，负=正在收起。
        val target = when {
            velocity > 0.55f -> 1f
            velocity < -0.55f -> 0f
            progress > 0.50f -> 1f
            else -> 0f
        }
        animateTo(target, velocity)
    }

    private suspend fun animateTo(target: Float, initialVelocity: Float) {
        animation?.cancel()
        animation = currentCoroutineContext()[Job]
        val animatable = Animatable(progress)
        animatable.animateTo(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f),
            initialVelocity = initialVelocity,
        ) {
            // P0 接缝：高初速弹簧会过冲（>1 或 <0）。面板位置按 (1-progress)*width 算，
            // 一过冲就整块移出屏幕边，露出底下的根底色。这里只钳展示值，弹簧参数不变：
            // 视觉上仍是原来的弹速与阻尼，但边缘始终贴着屏幕。
            progress = value.coerceIn(0f, 1f)
        }
        progress = target
        isOpen = target != 0f
        animation = null
    }
}

@Composable
fun rememberCardStyleDrawerState(): CardStyleDrawerState = remember { CardStyleDrawerState() }

@Composable
fun CardStyleDrawer(
    state: CardStyleDrawerState,
    selectedStyleId: String,
    activeStyle: HoloCardStyle,
    glossHigh: Boolean,
    motionMode: CardMotionMode,
    onSelectStyle: (String) -> Unit,
    onGlossHighChange: (Boolean) -> Unit,
    onMotionModeChange: (CardMotionMode) -> Unit,
    ambientBackdropId: String,
    onAmbientBackdropChange: (String) -> Unit,
    navDrawerOpen: Boolean,
    lightTime: () -> Float,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val drawerWidth = (LocalConfiguration.current.screenWidthDp.dp * 0.74f).coerceAtMost(360.dp)
    val widthPx = with(LocalDensity.current) { drawerWidth.toPx() }.coerceAtLeast(1f)
    val shape = RoundedCornerShape(topStart = 23.dp, bottomStart = 23.dp)
    BackHandler(enabled = state.isOpen) { state.close(scope) }

    Box(modifier.fillMaxSize()) {
        if (state.isOpen) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 0.32f * state.progress }
                    .background(Color(0xFF0B1325))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "关闭卡片设置"
                    ) { state.close(scope) }
            )
        }
        // P0 点击遮挡：这里原来叠了两条 30dp 全高透明条——一条 systemGestureExclusion、
        // 一条 draggable。draggable 会在右边缘竖条内抢占 pointer 输入，把主页面「跳过」
        // 按钮右侧约 18dp 的点击吃掉。开/合右抽屉的整页左滑/右滑手势由 LearningScreen 的
        // styleDrawerLeftSwipe（右侧 40% 起手、整页）负责，这条 sibling 纯属重复消费，删掉。
        // 保留手势排除条：右边缘这一段仍不让系统手势抢走横向滑动。
        Box(Modifier.align(Alignment.CenterEnd).width(30.dp).height(200.dp).systemGestureExclusion())

        if (state.isOpen) {
            var expanded by remember { mutableStateOf<CardSection?>(null) }
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(drawerWidth)
                    // 开度在展示位置再钳一次 0..1：弹簧过冲时面板不能越过屏幕右边，
                    // 否则右边缘会露出一条根底色接缝。
                    .offset {
                        IntOffset(((1f - state.progress.coerceIn(0f, 1f)) * widthPx).roundToInt(), 0)
                    }
                    .shadow(14.dp, shape),
                shape = shape,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(studyContentInsets())) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 17.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("卡片设置", style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { state.close(scope) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "关闭卡片设置")
                        }
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        CardSection.entries.forEach { section ->
                            item(section.name) {
                                SectionHeading(
                                    section.label,
                                    expanded == section,
                                    onClick = { expanded = if (expanded == section) null else section }
                                )
                            }
                            if (expanded == section) {
                                when (section) {
                                    CardSection.MATERIAL -> {
                                        item("random") {
                                            DrawerChoice(
                                                label = "每词随机",
                                                detail = "当前显示 · " + activeStyle.label,
                                                selected = selectedStyleId == "random",
                                                onClick = { onSelectStyle("random") }
                                            )
                                        }
                                        items(HoloCardStyle.entries, key = { it.id }) { style ->
                                            DrawerChoice(
                                                label = style.label,
                                                selected = selectedStyleId == style.id,
                                                onClick = { onSelectStyle(style.id) }
                                            ) {
                                                HoloStyleSwatch(
                                                    style = style,
                                                    modifier = Modifier.size(33.dp, 39.dp),
                                                    timeSeconds = if (
                                                        style == activeStyle && motionMode != CardMotionMode.STATIC
                                                    ) lightTime else StillPreview,
                                                    glossHigh = glossHigh
                                                )
                                            }
                                        }
                                    }
                                    CardSection.MOTION -> items(CardMotionMode.entries, key = { it.id }) { mode ->
                                        DrawerChoice(
                                            label = mode.label,
                                            detail = mode.detail,
                                            selected = motionMode == mode,
                                            onClick = { onMotionModeChange(mode) }
                                        )
                                    }
                                    CardSection.LIGHTING -> {
                                        items(AmbientBackdropMode.entries, key = { it.id }) { mode ->
                                            DrawerChoice(
                                                label = mode.label,
                                                selected = ambientBackdropId == mode.id,
                                                onClick = { onAmbientBackdropChange(mode.id) }
                                            )
                                        }
                                        item("gloss") {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 5.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text("加强流光", style = MaterialTheme.typography.bodyMedium)
                                                Switch(checked = glossHigh, onCheckedChange = onGlossHighChange)
                                            }
                                        }
                                    }
                                }
                                item(section.name + "-end") { Spacer(Modifier.height(7.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val StillPreview: () -> Float = { 0f }

private enum class CardSection(val label: String) {
    MATERIAL("卡片材质"),
    MOTION("动效模式"),
    LIGHTING("背景与光影")
}

@Composable
private fun SectionHeading(label: String, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .clickable(onClickLabel = if (expanded) "折叠" else "展开", onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Icon(
            if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DrawerChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    detail: String? = null,
    preview: (@Composable () -> Unit)? = null
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        preview?.invoke()
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        }
    }
}


/** 顶部入口：48dp 热区 + 当前款式色点。 */
@Composable
fun CardStyleEntryButton(
    style: HoloCardStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberHoloPalette(style)
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = LocalIndication.current,
            onClick = onClick
        )
    ) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = "卡片设置",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.Center)
                .size(24.dp),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 9.dp, bottom = 9.dp)
                .size(9.dp)
                .clip(CircleShape)
                .background(palette.accent)
                .border(1.dp, MaterialTheme.colorScheme.surface, CircleShape)
        )
    }
}
