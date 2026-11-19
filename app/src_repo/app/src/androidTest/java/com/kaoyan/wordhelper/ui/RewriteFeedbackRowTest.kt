package com.kaoyan.wordhelper.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaoyan.wordhelper.data.entity.RewriteFeedbackRating
import com.kaoyan.wordhelper.ui.screen.RewriteFeedbackRow
import com.kaoyan.wordhelper.ui.theme.KaoyanWordTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.4.7v7-bugfix P1：👎 不再弹「哪里没帮上？」，点一下直接上报一条空原因的差评。
 * 只锁这一条行为，别的不扩写。
 */
@RunWith(AndroidJUnit4::class)
class RewriteFeedbackRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun downVoteRecordsEmptyReasonWithoutDialog() {
        val calls = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            KaoyanWordTheme {
                RewriteFeedbackRow(
                    feedback = null,
                    onFeedback = { rating, reason -> calls += rating to reason },
                    enabled = true
                )
            }
        }

        composeRule.onNodeWithTag("rewrite_feedback_down").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(RewriteFeedbackRating.DOWN to ""), calls)
        // 弹窗整个删掉了：旧的原因输入框和确认按钮都不该再出现。
        assertTrue(
            composeRule.onAllNodesWithTag("rewrite_feedback_reason").fetchSemanticsNodes().isEmpty()
        )
        assertTrue(
            composeRule.onAllNodesWithTag("rewrite_feedback_down_confirm")
                .fetchSemanticsNodes().isEmpty()
        )
        composeRule.onNodeWithTag("rewrite_feedback_up").assertIsDisplayed()
    }

    @Test
    fun upVoteStillRecordsDirectly() {
        val calls = mutableListOf<Pair<String, String>>()
        composeRule.setContent {
            KaoyanWordTheme {
                RewriteFeedbackRow(
                    feedback = null,
                    onFeedback = { rating, reason -> calls += rating to reason },
                    enabled = true
                )
            }
        }

        composeRule.onNodeWithTag("rewrite_feedback_up").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(RewriteFeedbackRating.UP to ""), calls)
    }
}
