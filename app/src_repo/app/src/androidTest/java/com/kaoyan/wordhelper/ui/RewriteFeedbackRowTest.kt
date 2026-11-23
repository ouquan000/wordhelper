package com.kaoyan.wordhelper.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kaoyan.wordhelper.data.model.RewriteModel
import com.kaoyan.wordhelper.ui.screen.RewriteBar
import com.kaoyan.wordhelper.ui.theme.KaoyanWordTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 1.4.7v7-bugfix P1 回归（1.5.3v2 改由 RewriteBar 的 FeedbackPill 承接）：
 * 👎 不再弹「哪里没帮上？」，点一下直接上报一条空原因差评；👍 同样直接上报。
 */
@RunWith(AndroidJUnit4::class)
class RewriteFeedbackRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun launch(
        onUp: (Int, List<String>, String) -> Unit,
        onDown: (Int, List<String>, String) -> Unit,
    ) {
        composeRule.setContent {
            KaoyanWordTheme {
                RewriteBar(
                    wordId = 1L,
                    mark = null,
                    models = listOf(RewriteModel("ds-v41-flash", "DeepSeek V4.1 Flash")),
                    selectedModelId = "ds-v41-flash",
                    onSelectModel = {},
                    onRewrite = {},
                    onCancel = {},
                    onFeedbackUp = onUp,
                    onFeedbackDown = onDown,
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
    fun downVoteRecordsEmptyReasonWithoutDialog() {
        val calls = mutableListOf<Pair<Int, String>>()
        launch(
            onUp = { _, _, _ -> },
            onDown = { count, reasons, _ -> calls += count to reasons.joinToString("、") },
        )

        composeRule.onNodeWithTag("rewrite_feedback_down").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(1 to ""), calls)
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
        val calls = mutableListOf<Pair<Int, String>>()
        launch(
            onUp = { count, reasons, _ -> calls += count to reasons.joinToString("、") },
            onDown = { _, _, _ -> },
        )

        composeRule.onNodeWithTag("rewrite_feedback_up").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(1 to ""), calls)
    }
}
