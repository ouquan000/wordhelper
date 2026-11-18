package com.kaoyan.wordhelper.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    fun open(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            isOpen = true
            animateTo(1f, 0f)
        }
    }

    fun close(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            animateTo(0f, 0f)
            isOpen = false
        }
    }

    fun dragBy(deltaFraction: Float) {
        if (!isOpen) isOpen = true
        progress = (progress + deltaFraction).coerceIn(0f, 1f)
    }

    suspend fun settle(velocity: Float) {
        // velocity 是「progress 的变化速度」：正=正在展开，负=正在收起。
        val target = when {
            velocity > 0.55f -> 1f
            velocity < -0.55f -> 0f
            progress > 0.35f -> 1f
            else -> 0f
        }
        animateTo(target, velocity)
        if (target == 0f) isOpen = false
    }

    private suspend fun animateTo(target: Float, initialVelocity: Float) {
        val animatable = Animatable(progress)
        animatable.animateTo(
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 420f),
            initialVelocity = initialVelocity,
        ) {
            progress = value
        }
        progress = target
    }
}

@Composable
fun rememberCardStyleDrawerState(): CardStyleDrawerState = remember { CardStyleDrawerState() }

/**
 * 从右侧滑出的卡片样式抽屉。自带 30dp 右边缘左滑热区、遮罩、返回键优先关闭；
 * 与左侧导航抽屉完全独立（左抽屉的右滑只在左缘触发，这里只在右缘 30dp 内响应）。
 */
@Composable
fun CardStyleDrawer(
    state: CardStyleDrawerState,
    selectedStyleId: String,
    glossHigh: Boolean,
    motionEnabled: Boolean,
    onSelectStyle: (HoloCardStyle) -> Unit,
    onGlossHighChange: (Boolean) -> Unit,
    onMotionEnabledChange: (Boolean) -> Unit,
    ambientBackdropId: String = "auto",
    onAmbientBackdropChange: (String) -> Unit = {},
    navDrawerOpen: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val drawerWidth = configuration.screenWidthDp.dp * 0.74f
    val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }
    val shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
    val selectedStyle = HoloCardStyle.fromId(selectedStyleId)
    val selectedAmbient = AmbientBackdropMode.fromId(ambientBackdropId)

    BackHandler(enabled = state.isOpen) {
        state.close(scope)
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩：展开时才挂载，避免关闭状态吞掉学习页点击。
        if (state.isOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = 0.36f * state.progress }
                    .background(Color(0xFF0F172A))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { state.close(scope) }
            )
        }

        // 右边缘 30dp 左滑热区：只认水平拖动，靠 systemGestureExclusion 保护系统手势。
        // 拖动热区铺满整条右边（好用），但排除区单独用 200dp 高的透明层：Android 每条边最多
        // 排除 200dp，铺满整条会让排除整段失效（实测 Android 14 从右缘左滑直接退出 App）。
        // 拖动层不能随 isOpen 卸载：dragBy 第一帧就把 isOpen 置 true，一卸载手势立刻断掉。
        // 展开后面板会盖住这条窄条，事件自然落到面板上。
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(30.dp)
                .height(200.dp)
                .systemGestureExclusion()
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(30.dp)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = !navDrawerOpen,
                    state = rememberDraggableState { delta ->
                        state.dragBy(-delta / drawerWidthPx)
                    },
                    onDragStopped = { velocity ->
                        scope.launch { state.settle(-velocity / drawerWidthPx) }
                    },
                )
        )

        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(drawerWidth)
                .offset {
                    IntOffset(((1f - state.progress) * drawerWidthPx).roundToInt(), 0)
                }
                .shadow(12.dp, shape)
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                    shape = shape,
                )
                .systemGestureExclusion(),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 2.dp,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "卡片材质与光影",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "选择主卡片的表面质感，改变不会影响学习数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 4.dp,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(HoloCardStyle.entries, key = { it.id }) { style ->
                        StyleRow(
                            style = style,
                            selected = style == selectedStyle,
                            onClick = { onSelectStyle(style) },
                        )
                    }
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "背景氛围",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            AmbientBackdropMode.entries.forEach { mode ->
                                AmbientBackdropCapsule(
                                    label = mode.label,
                                    selected = selectedAmbient == mode,
                                    modifier = Modifier.weight(1f),
                                    onClick = { onAmbientBackdropChange(mode.id) },
                                )
                            }
                        }
                    }
                    Text(
                        text = "光影控制",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CapsuleToggle(
                        label = "流光亮度",
                        left = "璀璨炫光",
                        right = "柔和护眼",
                        rightSelected = !glossHigh,
                        onSelect = { right -> onGlossHighChange(!right) },
                    )
                    CapsuleToggle(
                        label = "流光动态",
                        left = "呼吸流动",
                        right = "静止省电",
                        rightSelected = !motionEnabled,
                        onSelect = { right -> onMotionEnabledChange(!right) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleRow(
    style: HoloCardStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "cardStyleRowScale",
    )
    val rowShape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(rowShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .border(if (selected) 1.2.dp else 0.5.dp, borderColor, rowShape)
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HoloStyleSwatch(
            style = style,
            modifier = Modifier
                .size(42.dp)
                .shadow(4.dp, RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = style.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = style.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "已选中",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 背景氛围胶囊：5 个并排、31dp 高、11sp，最多两行；只改氛围，不影响材质列表。 */
@Composable
private fun AmbientBackdropCapsule(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "ambientCapsuleScale",
    )
    Box(
        modifier = modifier
            .height(31.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.45f)
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                CircleShape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            textAlign = TextAlign.Center,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun CapsuleToggle(
    label: String,
    left: String,
    right: String,
    rightSelected: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp),
        )
        Spacer(Modifier.width(6.dp))
        Row(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f))
                .border(
                    0.5.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.20f),
                    CircleShape,
                )
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            CapsuleOption(
                text = left,
                selected = !rightSelected,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(false) },
            )
            CapsuleOption(
                text = right,
                selected = rightSelected,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(true) },
            )
        }
    }
}

@Composable
private fun CapsuleOption(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "capsuleScale",
    )
    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent
            )
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private val HoloCardStyle.subtitle: String
    get() = when (this) {
        HoloCardStyle.CLASSIC -> "微蓝玻璃 · 轻盈悬浮"
        HoloCardStyle.PRISM -> "深海视窗 · 粉紫流光"
        HoloCardStyle.RAINBOW -> "金属箔 · 独立光栅"
        HoloCardStyle.PEARL -> "珍珠母贝 · 柔光白"
        HoloCardStyle.SPARKLE -> "碎钻星尘 · 夜蓝"
        HoloCardStyle.CHROME -> "液态银 · 冲压雕刻"
        HoloCardStyle.ETCHED -> "哑光黑 · CD 刻纹"
    }

/** 顶部入口：48dp 热区 + 当前款式色点。 */
@Composable
fun CardStyleEntryButton(
    style: HoloCardStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = rememberHoloPalette(style)
    Box(modifier = modifier.size(48.dp)) {
        Icon(
            imageVector = Icons.Outlined.AutoAwesome,
            contentDescription = "卡片材质",
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
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = LocalIndication.current,
                    onClick = onClick,
                )
        )
    }
}
