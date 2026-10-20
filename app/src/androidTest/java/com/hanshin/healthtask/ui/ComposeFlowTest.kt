package com.hanshin.healthtask.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.hanshin.healthtask.domain.HealthMetricType
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ComposeFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bodyEntrySavesSelectedDateAndValue() {
        var saved: Triple<LocalDate, HealthMetricType, Double>? = null
        compose.setContent {
            TodayRunningTheme {
                HealthEntryDialog(onDismiss = {}) { date, type, value -> saved = Triple(date, type, value) }
            }
        }
        compose.onNodeWithTag("save-body-entry").assertIsNotEnabled()
        compose.onNodeWithTag("body-entry-date").performTextReplacement("2026-08-20")
        compose.onNodeWithTag("body-entry-value").performTextReplacement("72.5")
        compose.onNodeWithTag("save-body-entry").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(Triple(LocalDate.of(2026, 8, 20), HealthMetricType.WEIGHT_KG, 72.5), saved) }
    }

    @Test fun bodyEntryRejectsFutureDatesAndNonFiniteValues() {
        compose.setContent { TodayRunningTheme { HealthEntryDialog(onDismiss = {}, onSave = { _, _, _ -> }) } }
        compose.onNodeWithTag("body-entry-value").performTextReplacement("NaN")
        compose.onNodeWithTag("save-body-entry").assertIsNotEnabled()
        compose.onNodeWithTag("body-entry-value").performTextReplacement("70")
        compose.onNodeWithTag("body-entry-date").performTextReplacement(LocalDate.now().plusDays(1).toString())
        compose.onNodeWithTag("save-body-entry").assertIsNotEnabled()
        compose.onNodeWithTag("body-entry-date").performTextReplacement(LocalDate.now().toString())
        compose.onNodeWithTag("save-body-entry").assertIsEnabled()
    }
}
