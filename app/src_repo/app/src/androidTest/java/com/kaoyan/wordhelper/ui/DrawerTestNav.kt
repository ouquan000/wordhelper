package com.kaoyan.wordhelper.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput

/**
 * 1.4.7：底部导航改成左侧抽屉后，测试统一从这里进出。
 * 先等冷启动的「准备学习内容中…」过渡页过去（菜单按钮那时还不存在），
 * 再点左上角菜单按钮、然后点抽屉里的条目。
 */
private fun ComposeTestRule.awaitMainUi() {
    waitUntil(timeoutMillis = 20_000) {
        onAllNodesWithTag("app_menu_button").fetchSemanticsNodes().isNotEmpty()
    }
}

fun ComposeTestRule.navigateViaDrawer(tag: String) {
    awaitMainUi()
    onNodeWithTag("app_menu_button").performClick()
    onNodeWithTag(tag).performClick()
}

/** 模式切换也搬进了抽屉：开抽屉 → 展开模式栏 → 点选项 → 点右侧蒙版关抽屉。 */
fun ComposeTestRule.switchLearningMode(optionTag: String) {
    awaitMainUi()
    onNodeWithTag("app_menu_button").performClick()
    onNodeWithTag("learning_mode_toggle").performClick()
    onNodeWithTag(optionTag).performClick()
    // 抽屉收起的那两行后仍然开着，点右侧露出的蒙版把它关掉。
    // 蒙版没有语义节点，只能按坐标点页面最右侧。
    onRoot().performTouchInput { click(Offset(width * 0.95f, height * 0.5f)) }
}
