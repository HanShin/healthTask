package com.hanshin.healthtask.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.hanshin.healthtask.data.db.*
import com.hanshin.healthtask.domain.*
import com.hanshin.healthtask.running.RunningPhase
import com.hanshin.healthtask.running.RunningUiState
import com.hanshin.healthtask.shared.*
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UiPresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun freeRunningStartRemainsVisibleWithLargerText() {
        var starts = 0
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                TodayRunningTheme {
                    Surface(Modifier.fillMaxSize()) {
                        RunningDashboard(RunningUiState(), { starts++ }, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("start-run").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
        screenshot("running-large-text")
    }

    @Test fun pausedRunningStillOffersResumeAndFinish() {
        var resumes = 0
        var finishes = 0
        compose.setContent { TodayRunningTheme {
            Surface(Modifier.fillMaxSize()) {
                RunningDashboard(RunningUiState(phase = RunningPhase.PAUSED, elapsedMillis = 1_800_000L, distanceMeters = 5_240.0),
                    {}, {}, { resumes++ }, { finishes++ }, {})
            }
        } }
        compose.onNodeWithTag("pause-resume-run").assertIsDisplayed().performClick()
        compose.onNodeWithTag("finish-run").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, resumes); assertEquals(1, finishes) }
        screenshot("running-paused")
    }

    @Test fun populatedHistoryRetainsDetailsAndDeleteConfirmation() {
        var deleted: String? = null
        val session = WorkoutSessionEntity(id = "ui-only-run", title = "아침 러닝", sessionDate = "2026-09-24",
            status = WorkoutStatus.COMPLETED, source = WorkoutSource.LOCAL, startedAt = 0L, endedAt = 1_800_000L,
            distanceKm = 5.24, activeDurationMillis = 1_800_000L)
        compose.setContent { TodayRunningTheme {
            Surface(Modifier.fillMaxSize()) { RunningHistoryContent(listOf(WorkoutSessionWithItems(session, emptyList()))) { deleted = it } }
        } }
        compose.onNodeWithText("아침 러닝").assertIsDisplayed()
        screenshot("history-populated")
        compose.onNodeWithText("아침 러닝").performClick()
        compose.onNodeWithTag("running-history").performScrollToNode(hasText("기록 삭제"))
        compose.onNodeWithText("기록 삭제").performClick()
        compose.onNodeWithText("러닝 기록 삭제").assertIsDisplayed()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertEquals(null, deleted) }
    }

    @Test fun detailedSensorSummaryShowsMeasuredValuesWithoutInventingUnsupportedElevation() {
        val telemetry = RunTelemetry(metadata = RunSensorMetadata("ui-only-sensors", 0, 0, 0,
            "Asia/Seoul", "test", "test", 36, emptyList(), emptyList(), listOf("elevationGainMeters")), records = listOf(
            RunSensorRecord("sample", 1_000, activeElapsedMillis = 1_000, metric = "heartRateBpm", value = 140.0),
            RunSensorRecord("sample", 2_000, activeElapsedMillis = 2_000, metric = "heartRateBpm", value = 160.0),
            RunSensorRecord("sample", 2_000, metric = "cadenceStepsPerMinute", value = 174.0),
            RunSensorRecord("sample", 2_000, metric = "stepsTotal", value = 5_200.0),
        ))
        val session = WorkoutSessionEntity("ui-only-sensors", title = "센서 기록 테스트", sessionDate = "2026-09-24",
            status = WorkoutStatus.COMPLETED, source = WorkoutSource.LOCAL, startedAt = 0, endedAt = 1_800_000,
            distanceKm = 5.0, activeDurationMillis = 1_800_000,
            sensorSummaryJson = RunTelemetryCodec.encodeSummary(telemetry.summarize()))
        compose.setContent { TodayRunningTheme { Surface(Modifier.fillMaxSize()) {
            RunningHistoryContent(listOf(WorkoutSessionWithItems(session, emptyList()))) {}
        } } }
        compose.onNodeWithText("센서 기록 테스트").performClick()
        compose.onNodeWithTag("running-history").performScrollToNode(hasText("상세 센서 기록"))
        compose.onNodeWithText("심박 140–160 bpm · 2회 측정").assertExists()
        compose.onNodeWithText("평균 케이던스 174 걸음/분").assertExists()
        compose.onAllNodes(hasText("누적 상승", substring = true)).assertCountEquals(0)
        screenshot("sensor-summary")
    }

    @Test fun bodyChartAndDatedRecordsShowActualValues() {
        val metrics = listOf(
            measurement("old", "2026-09-01", HealthMetricType.SKELETAL_MUSCLE_KG, 32.4),
            measurement("new", "2026-09-24", HealthMetricType.SKELETAL_MUSCLE_KG, 33.1),
            measurement("weight", "2026-09-24", HealthMetricType.WEIGHT_KG, 72.5),
        )
        compose.setContent { TodayRunningTheme {
            Surface(Modifier.fillMaxSize()) { InBodyRecordsContent(metrics, onAdd = {}, onImport = {}, onEdit = {}, onDelete = {}) }
        } }
        compose.onNodeWithTag("inbody-trend-list").performScrollToNode(hasTestTag("body-selection-SKELETAL_MUSCLE_KG"))
        compose.onNode(hasText("33.1 kg") and hasAnyAncestor(hasTestTag("body-selection-SKELETAL_MUSCLE_KG"))).assertIsDisplayed()
        screenshot("inbody-populated")
        compose.onNodeWithTag("body-records-tab").performClick()
        compose.onNodeWithTag("inbody-records").performScrollToNode(hasTestTag("body-daily-2026-09-24"))
        compose.onAllNodesWithText("72.5 kg").onLast().assertIsDisplayed()
    }

    @Test fun calendarCanBeOpenedWithoutChangingMeasurement() {
        compose.setContent { TodayRunningTheme { HealthEntryDialog(onDismiss = {}, onSave = { _, _, _ -> }) } }
        compose.onNodeWithContentDescription("측정일 선택").performClick()
        compose.onNodeWithText("선택").assertIsDisplayed()
        screenshot("measurement-calendar")
        compose.onAllNodesWithText("취소").onLast().performClick()
        compose.onNodeWithTag("body-entry-date").assertIsDisplayed()
    }

    private fun measurement(id: String, date: String, type: HealthMetricType, value: Double) =
        HealthMeasurementEntity(id = id, recordDate = date, measuredAt = if (id == "old") 1L else 2L,
            type = type, value = value, source = WorkoutSource.LOCAL)

    // Test-only fixtures are rendered in memory; no sample is inserted into the user's database.
    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "ui-review").apply { mkdirs() }
        compose.waitForIdle()
        // Capture all windows, including the calendar dialog above the entry dialog.
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
