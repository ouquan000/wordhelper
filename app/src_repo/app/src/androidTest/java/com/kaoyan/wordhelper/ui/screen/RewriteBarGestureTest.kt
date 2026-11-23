package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.kaoyan.wordhelper.data.model.RewriteModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 1.5.3v2 第二层验证（Instrumented）：RewriteBar 手势在真实 Compose 树里的行为。
 *
 * 四项验收（开工令）：
 * ① 长按连发不弹浮层（连发 count ≥ 3，且从不出现纵向选项浮层）。
 * ② 上滑呼出 3 项胶囊，且外层 verticalScroll 纹丝不动（scrollState == 0）。
 * ③ 滑回原点松手 = 取消，零提交。
 * ④ 模型胶囊左右搓切：左滑切到下一档。
 */
class RewriteBarGestureTest {

    @get:Rule
    val rule = createComposeRule()

    private val models = listOf(
        RewriteModel(id = "ds-v41-flash", name = "DeepSeek V4.1 Flash"),
        RewriteModel(id = "glm53", name = "GLM 5.3"),
        RewriteModel(id = "ds-v4-pro", name = "DeepSeek V4 Pro"),
    )

    private val selectedModel = mutableStateOf("ds-v41-flash")
    private var upCommits = 0
    private var downCommits = 0
    private var lastUpCount = 0
    private var lastUpReasons: List<String> = emptyList()
    private val scrollState = ScrollState(0)

    private fun launch() {
        rule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                RewriteBar(
                    wordId = 1L,
                    mark = null,
                    models = models,
                    selectedModelId = selectedModel.value,
                    onSelectModel = { selectedModel.value = it },
                    onRewrite = {},
                    onCancel = {},
                    onFeedbackUp = { count, reasons, _ ->
                        upCommits += 1
                        lastUpCount = count
                        lastUpReasons = reasons
                    },
                    onFeedbackDown = { _, _, _ -> downCommits += 1 },
                    feedback = null,
                    likeCount = 0,
                    enabled = true,
                    progress = null,
                    justUpdated = false,
                )
            }
        }
    }

    @Test
    fun longPressBurst_ticksWithoutOpeningMenu() {
        launch()
        rule.onNodeWithTag("rewrite_feedback_up").performTouchInput {
            down(center)
            advanceEventTime(600)
            up()
        }
        rule.waitForIdle()
        assertEquals("长按松手应恰好提交一次", 1, upCommits)
        assertTrue("600ms 长按应连发 >= 3 次，实际 $lastUpCount", lastUpCount >= 3)
        rule.onNodeWithTag("rewrite_feedback_menu").assertDoesNotExist()
    }

    @Test
    fun swipeUp_revealsThreeItems_andOuterScrollStaysStill() {
        launch()
        rule.onNodeWithTag("rewrite_feedback_up").performTouchInput {
            down(center)
            // 只滑到刚过 18dp 触发阈值：浮层刚呼出时高亮第 0 项「一秒记住」。
            moveBy(Offset(0f, -30f), 120)
            up()
        }
        rule.waitForIdle()
        assertEquals("上滑松手应恰好提交一次", 1, upCommits)
        assertEquals("默认高亮第 0 项「一秒记住」", listOf("一秒记住"), lastUpReasons)
        assertEquals("外层 verticalScroll 不得被手势抢占", 0, scrollState.value)
    }

    @Test
    fun swipeBackToOrigin_cancelsWithoutCommit() {
        launch()
        rule.onNodeWithTag("rewrite_feedback_up").performTouchInput {
            down(center)
            moveBy(Offset(0f, -80f), 120)
            moveBy(Offset(0f, 80f), 120)
            up()
        }
        rule.waitForIdle()
        assertEquals("滑回原点应零提交", 0, upCommits)
        assertEquals("滑回原点不得触发差评", 0, downCommits)
        rule.onNodeWithTag("rewrite_feedback_menu").assertDoesNotExist()
    }

    @Test
    fun modelPill_swipeLeft_switchesToNextModel() {
        launch()
        assertEquals("ds-v41-flash", selectedModel.value)
        rule.onNodeWithTag("rewrite_model_pill").performTouchInput {
            swipeLeft()
        }
        rule.waitForIdle()
        assertEquals("左滑应切到下一档 glm53", "glm53", selectedModel.value)
    }
}
