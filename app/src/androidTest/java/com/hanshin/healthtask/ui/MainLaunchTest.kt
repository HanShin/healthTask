package com.hanshin.healthtask.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.hanshin.healthtask.MainActivity
import org.junit.Rule
import org.junit.Test

class MainLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensRunningWithoutAskingForAWeeklyGoal() {
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithTag("start-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("start-run").assertIsDisplayed()
        compose.onNodeWithText("러닝 기록").assertIsDisplayed()
        compose.onNodeWithText("인바디").assertIsDisplayed()
        compose.onNodeWithText("설정").assertIsDisplayed()
        compose.onNodeWithText("일주일에 몇 번 달릴까요?").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-start").assertDoesNotExist()
        compose.onNodeWithText("이번 주 러닝").assertDoesNotExist()
        compose.onNodeWithText("회 달성", substring = true).assertDoesNotExist()
    }

    @Test fun settingsOfferRunningPreferencesAndBackupWithoutWeeklyGoals() {
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithTag("start-run").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("설정").performClick()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasText("러닝 안내"))
        compose.onNodeWithText("주간 러닝 목표").assertDoesNotExist()
        compose.onNodeWithTag("settings-page").performScrollToNode(hasText("JSON 백업"))
        compose.onNodeWithText("JSON 백업").assertIsDisplayed()
        compose.onNodeWithText("주간 러닝 목표").assertDoesNotExist()
    }
}
