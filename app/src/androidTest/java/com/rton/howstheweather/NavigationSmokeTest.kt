package com.rton.howstheweather

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun navigationBarOpensForecastAndQuantitativeRainPages() {
        composeRule.onNodeWithText("1 小時").performClick()
        composeRule.onNodeWithText("未來 1 小時累積雨量").assertIsDisplayed()

        composeRule.onNodeWithText("預報").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("逐時預報").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("逐時預報").assertIsDisplayed()

        composeRule.onNodeWithText("降水").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("未來 0–12 小時 · 累積預報").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("未來 0–12 小時 · 累積預報", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("未來 0 到 48 小時定量降水預報時間軸")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("透明度").assertCountEquals(0)
    }
}
