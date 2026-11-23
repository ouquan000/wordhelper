package com.kaoyan.wordhelper.ui.screen

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.kaoyan.wordhelper.data.model.RewriteModels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RewriteBarGestureTest {
    @get:Rule
    val rule = createComposeRule()

    private val models = RewriteModels.DEFAULT
    private val selectedModel = mutableStateOf(models.first().id)
    private val modelSelections = mutableListOf<String>()
    private var upCommits = 0
    private val downReasons = mutableListOf<List<String>>()
    private val rewrites = mutableListOf<String>()
    private val scrollState = ScrollState(0)

    private fun launch() {
        rule.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                Spacer(Modifier.height(100.dp))
                RewriteBar(
                    wordId = 1L,
                    mark = null,
                    models = models,
                    selectedModelId = selectedModel.value,
                    onSelectModel = { modelSelections.add(it); selectedModel.value = it },
                    onRewrite = { rewrites.add(it) },
                    onCancel = {},
                    onInstruction = {},
                    onFeedbackUp = { count, _, _ -> upCommits += count },
                    onFeedbackDown = { _, reasons, _ -> downReasons.add(reasons) },
                    feedback = null,
                    likeCount = 0,
                    enabled = true,
                    progress = null
                )
                Spacer(Modifier.height(1500.dp))
            }
        }
    }

    private fun holdOn(tag: String): SemanticsNodeInteraction {
        val button = rule.onNodeWithTag(tag)
        button.performTouchInput { down(center) }
        rule.waitUntil(2_500) { rule.onAllNodesWithTag("rewrite_action_rail").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("rewrite_action_rail").assertIsDisplayed()
        return button
    }

    @Test
    fun swipeStartingOnLikeScrollsTheParentWithoutFeedback() {
        launch()
        rule.onNodeWithTag("rewrite_feedback_up").performTouchInput {
            down(center)
            moveBy(Offset(0f, -25.dp.toPx()), delayMillis = 48)
            moveBy(Offset(0f, -55.dp.toPx()), delayMillis = 48)
            up()
        }
        rule.runOnIdle {
            assertTrue("Outer ScrollState must move", scrollState.value > 0)
            assertEquals(0, upCommits)
        }
    }

    @Test
    fun holdDislikeAndSlideToThirdChoiceCommitsAndKeepsScrollStill() {
        launch()
        val button = holdOn("rewrite_feedback_down")
        val targetX = rule.onNodeWithTag("rewrite_rail_option_2").fetchSemanticsNode().boundsInRoot.center.x
        val buttonX = button.fetchSemanticsNode().boundsInRoot.center.x
        button.performTouchInput {
            advanceEventTime(250)
            moveBy(Offset(targetX - buttonX, 0f))
            up()
        }
        rule.runOnIdle {
            assertEquals(listOf(listOf("太烂了")), downReasons)
            assertEquals(listOf("太烂了"), rewrites)
            assertEquals(0, scrollState.value)
        }
    }

    @Test
    fun holdingLikeAndDraggingUp110DpCancelsWithoutCommit() {
        launch()
        val button = holdOn("rewrite_feedback_up")
        button.performTouchInput {
            advanceEventTime(250)
            moveBy(Offset(0f, -110.dp.toPx()))
        }
        rule.onNodeWithTag("rewrite_rail_canceled").assertIsDisplayed()
        button.performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(0, upCommits)
            assertTrue(rewrites.isEmpty())
            assertEquals(0, scrollState.value)
        }
    }

    @Test
    fun holdingLikeAndDraggingDown60DpStillCommits() {
        launch()
        val button = holdOn("rewrite_feedback_up")
        button.performTouchInput {
            advanceEventTime(250)
            moveBy(Offset(0f, 60.dp.toPx()))
        }
        rule.onNodeWithTag("rewrite_action_rail").assertIsDisplayed()
        rule.onNodeWithTag("rewrite_rail_canceled").assertDoesNotExist()
        button.performTouchInput { up() }
        rule.runOnIdle {
            assertEquals(1, upCommits)
            assertTrue(rewrites.isEmpty())
            assertEquals(0, scrollState.value)
        }
    }

    @Test
    fun fourShortTapsCycleThroughEveryModel() {
        launch()
        repeat(4) { rule.onNodeWithTag("rewrite_model_pill").performTouchInput { click() } }
        rule.runOnIdle {
            assertEquals(models.drop(1).map { it.id } + models.first().id, modelSelections)
        }
    }
}
