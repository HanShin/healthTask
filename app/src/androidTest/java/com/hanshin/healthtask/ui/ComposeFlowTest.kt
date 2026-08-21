package com.hanshin.healthtask.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.hanshin.healthtask.data.SeedData
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposeFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onboardingSelectsGoalAndStarts() {
        var result: Pair<Int, Boolean>? = null
        compose.setContent { MaterialTheme { OnboardingContent { goal, templates -> result = goal to templates } } }
        compose.onNodeWithTag("onboarding-start").performClick()
        compose.runOnIdle { assertEquals(3 to true, result) }
    }

    @Test fun routineEditorRequiresANameAndExerciseThenSaves() {
        var saved: Pair<String, List<String>>? = null
        compose.setContent {
            MaterialTheme {
                RoutineDialog(null, SeedData.exercises.take(2), onDismiss = {}) { name, exerciseIds ->
                    saved = name to exerciseIds
                }
            }
        }
        compose.onNodeWithTag("routine-name").performTextInput("테스트 루틴")
        compose.onNodeWithTag("exercise-bench-press").performClick()
        compose.onNodeWithTag("routine-save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("테스트 루틴" to listOf("bench-press"), saved) }
    }
}
