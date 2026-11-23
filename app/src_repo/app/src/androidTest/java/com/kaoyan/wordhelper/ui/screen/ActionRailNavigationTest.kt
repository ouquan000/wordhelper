package com.kaoyan.wordhelper.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.kaoyan.wordhelper.KaoyanWordApp
import com.kaoyan.wordhelper.MainActivity
import com.kaoyan.wordhelper.data.model.RewriteModels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ActionRailNavigationTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun draggingModelRailRightOnLearningScreenDoesNotOpenDrawer() {
        val settings = (rule.activity.application as KaoyanWordApp).settingsRepository
        val original = runBlocking { settings.rewriteModel() }
        val first = RewriteModels.DEFAULT.first().id
        val last = RewriteModels.DEFAULT.last().id
        try {
            runBlocking { settings.updateRewriteModel(first) }
            rule.waitUntil(20_000) { rule.onAllNodesWithTag("learning_word_card").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("learning_word_card").performTouchInput { click(center) }
            val model = rule.onNodeWithTag("rewrite_model_pill", useUnmergedTree = true)
            model.performScrollTo().assertIsDisplayed()
            val drawerItem = rule.onNodeWithTag("drawer_nav_profile")
            drawerItem.assertIsNotDisplayed()
            model.performTouchInput { down(center) }
            rule.waitUntil(2_500) { rule.onAllNodesWithTag("rewrite_action_rail", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            val targetX = rule.onNodeWithTag("rewrite_rail_option_3", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.x
            val modelX = model.fetchSemanticsNode().boundsInRoot.center.x
            model.performTouchInput {
                advanceEventTime(250)
                moveBy(Offset(targetX - modelX, 0f), delayMillis = 48)
            }
            drawerItem.assertIsNotDisplayed()
            model.performTouchInput { up() }
            rule.waitUntil(4_000) { runBlocking { settings.rewriteModel() } == last }
            drawerItem.assertIsNotDisplayed()
            rule.onNodeWithTag("learning_action_good", useUnmergedTree = true).assertIsDisplayed()
            assertEquals(last, runBlocking { settings.rewriteModel() })
        } finally {
            runBlocking { settings.updateRewriteModel(original) }
        }
    }
}
