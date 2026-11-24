package com.kaoyan.wordhelper.ui.screen

import android.provider.Settings
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kaoyan.wordhelper.ui.component.CardStyleDrawerState
import kotlinx.coroutines.launch

/**
 * 整页横向滑动拉出/收回「卡片材质」抽屉。
 *
 * 手势仲裁规则（与左侧导航抽屉共用）：
 * - 左抽屉开着或正在关（progress > 0.01）时本手势直接让位，左滑归左抽屉关自己；
 * - 材质抽屉关着时，只有屏幕右侧 40% 内起手、明确左移超过 touchSlop 才认领；
 * - 材质抽屉开着时，整块面板都能右滑收回（右移超过 touchSlop 才认领）；
 * - 认领后按手指位移实时改 progress（跟手），松手按位置/速度做弹簧吸附。
 */
internal fun Modifier.styleDrawerLeftSwipe(
    state: CardStyleDrawerState,
    navDrawerOpen: Boolean
): Modifier = composed {
    val currentState by rememberUpdatedState(state)
    val currentNavOpen by rememberUpdatedState(navDrawerOpen)
    val scope = rememberCoroutineScope()
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 左侧导航抽屉开着/正在关时，横向拖动归导航抽屉，材质抽屉不抢。
            if (currentNavOpen) return@awaitEachGesture
            val startedOpen = currentState.isOpen
            if (!LearningDrawerGesture.acceptsDown(down.position.x, size.width, startedOpen)) {
                return@awaitEachGesture
            }
            val gesture = LearningDrawerGesture(
                down.position.x, down.position.y, down.uptimeMillis, startedOpen,
                LearningDrawerGesture.widthPx(size.width, 360.dp.toPx())
            )
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    gesture.release(change.position.x, change.uptimeMillis)?.let { velocity ->
                        scope.launch { currentState.settle(velocity) }
                    }
                    break
                }
                gesture.move(
                    change.position.x, change.position.y, change.uptimeMillis,
                    viewConfiguration.touchSlop, change.isConsumed
                )?.let { delta ->
                    change.consume()
                    currentState.dragBy(delta)
                }
            }
        }
    }
}

/**
 * 系统「动画程序时长缩放」是否开启：0 表示系统动画被关掉。
 * 关掉时所有切卡动效退化成 180ms 纯净淡入淡出（卡面材质动效不受影响）。
 */
@Composable
internal fun rememberSystemAnimationsEnabled(): Boolean {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var enabled by remember(context) { mutableStateOf(readAnimationScale(resolver) > 0f) }
    LifecycleResumeEffect(context) {
        enabled = readAnimationScale(resolver) > 0f
        onPauseOrDispose { }
    }
    return enabled
}

private fun readAnimationScale(resolver: android.content.ContentResolver): Float = try {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (_: Throwable) {
    1f
}
