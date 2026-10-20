package com.hanshin.healthtask.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.InBodyRecordEntity
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.INBODY_PACKAGE
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class InBodyGraphUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun singleMeasurementHasOnePointAndNoInventedTimeOrDelta() {
        render(listOf(metric("2026-09-24", HealthMetricType.WEIGHT_KG, 70.2)))
        selectedText(HealthMetricType.WEIGHT_KG, "70.2 kg").assertIsDisplayed()
        selectedText(HealthMetricType.WEIGHT_KG, "26.09.24").assertIsDisplayed()
        compose.onNodeWithTag("body-change-WEIGHT_KG").assertTextEquals("첫 측정 · 1회")
        compose.onNodeWithTag("body-previous-WEIGHT_KG").assertDoesNotExist()
        compose.onNodeWithTag("body-point-WEIGHT_KG-0").assertIsDisplayed()
        screenshot("body-history-single")
    }

    @Test fun recentShowsTenAndAllCanReachTheOldestOfTwoThousand() {
        val values = (0 until 2000).map { index ->
            metric(LocalDate.of(2020, 1, 1).plusDays(index.toLong()).toString(), HealthMetricType.WEIGHT_KG, 70.0 + (index % 10) / 10.0)
        }
        render(values)
        compose.onNodeWithTag("body-change-WEIGHT_KG").assertTextContains("10회", substring = true)
        compose.onNodeWithTag("body-history-all").performClick()
        compose.onNodeWithTag("body-change-WEIGHT_KG").assertTextContains("2000회", substring = true)
        compose.onNodeWithTag("body-plot-WEIGHT_KG").performScrollToIndex(0)
        compose.onNodeWithTag("body-point-WEIGHT_KG-0").performClick()
        selectedText(HealthMetricType.WEIGHT_KG, "20.01.01").assertIsDisplayed()
        compose.onNodeWithTag("body-previous-WEIGHT_KG").assertIsNotEnabled()
    }

    @Test fun repeatedDatesKeepBothTimesAndSelectablePoints() {
        val originals = listOf(original("2026-09-24T09:27:30"), original("2026-09-24T09:27:45"))
        val values = originals.mapIndexed { index, record -> csvMetric(record, HealthMetricType.WEIGHT_KG, 70.2 + index) }
        render(values, originals)
        compose.onNodeWithTag("body-point-WEIGHT_KG-0").assertIsDisplayed().performClick()
        selectedText(HealthMetricType.WEIGHT_KG, "26.09.24 09:27:30").assertIsDisplayed()
        selectedText(HealthMetricType.WEIGHT_KG, "70.2 kg").assertIsDisplayed()
        compose.onNodeWithTag("body-next-WEIGHT_KG").performClick()
        selectedText(HealthMetricType.WEIGHT_KG, "26.09.24 09:27:45").assertIsDisplayed()
        compose.onNodeWithTag("body-next-WEIGHT_KG").assertIsNotEnabled()
        compose.onNodeWithTag("body-previous-WEIGHT_KG").performClick()
        selectedText(HealthMetricType.WEIGHT_KG, "70.2 kg").assertIsDisplayed()
        screenshot("body-history-same-day")
    }

    @Test fun displaySettingsCancelApplyAndRecreationKeepChoicesWithoutDeletingMeasurements() {
        val restoration = StateRestorationTester(compose)
        val values = listOf(metric("2026-09-24", HealthMetricType.WEIGHT_KG, 70.2),
            metric("2026-09-24", HealthMetricType.SKELETAL_MUSCLE_KG, 31.2))
        restoration.setContent { TodayRunningTheme { Surface(Modifier.fillMaxSize()) { HealthTrendChart(values) } } }
        compose.onNodeWithTag("body-display-settings").performClick()
        compose.onNodeWithTag("body-visible-WEIGHT_KG").performClick()
        compose.onNodeWithText("취소").performClick()
        compose.onNodeWithTag("body-card-WEIGHT_KG").assertExists()
        compose.onNodeWithTag("body-display-settings").performClick()
        compose.onNodeWithTag("body-visible-WEIGHT_KG").performClick()
        compose.onNodeWithTag("body-settings-save").performClick()
        compose.onNodeWithTag("body-card-WEIGHT_KG").assertDoesNotExist()
        compose.onNodeWithTag("body-history-all").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("body-history-all").assertIsOn()
        compose.onNodeWithTag("body-card-WEIGHT_KG").assertDoesNotExist()
        compose.onNodeWithTag("body-display-settings").performClick()
        compose.onNodeWithTag("body-visible-SKELETAL_MUSCLE_KG").performClick()
        compose.onNodeWithTag("body-settings-save").assertIsNotEnabled()
        compose.onNodeWithText("모두 표시").performClick()
        compose.onNodeWithTag("body-settings-save").performClick()
        selectedText(HealthMetricType.WEIGHT_KG, "70.2 kg").assertIsDisplayed()
    }

    @Test fun ratiosRetainPrecisionAndCodesDoNotHaveCardsOrSettings() {
        render(listOf(metric("2026-09-24", HealthMetricType.ECW_RATIO, 0.371),
            metric("2026-09-24", HealthMetricType.UPPER_BALANCE_CODE, 0.0)))
        selectedText(HealthMetricType.ECW_RATIO, "0.371 비율").assertIsDisplayed()
        compose.onNodeWithTag("body-card-UPPER_BALANCE_CODE").assertDoesNotExist()
        compose.onNodeWithTag("body-display-settings").performClick()
        compose.onNodeWithTag("body-visible-UPPER_BALANCE_CODE").assertDoesNotExist()
    }

    @Test fun graphAndControlsRemainUsableWithLargeTextAndPercentPointChanges() {
        render(listOf(metric("2026-09-01", HealthMetricType.BODY_FAT_PERCENT, 24.0),
            metric("2026-09-24", HealthMetricType.BODY_FAT_PERCENT, 22.7)), fontScale = 1.5f)
        scroll("body-change-BODY_FAT_PERCENT")
        compose.onNodeWithTag("body-change-BODY_FAT_PERCENT").assertTextContains("-1.3 %p", substring = true)
        compose.onNodeWithTag("body-previous-BODY_FAT_PERCENT").assertIsDisplayed().performClick()
        selectedText(HealthMetricType.BODY_FAT_PERCENT, "24 %").assertIsDisplayed()
        screenshot("body-history-large-text")
    }

    @Test fun referenceStyleSampleShowsStackedScoreAndMetabolismCards() {
        // Synthetic display-only records, never inserted into the application database.
        val dates = listOf("2026-09-01T09:10:00", "2026-09-03T10:10:00", "2026-09-04T10:42:00",
            "2026-09-05T09:30:00", "2026-09-07T09:12:00", "2026-09-07T10:42:00", "2026-09-08T08:55:00",
            "2026-09-10T10:00:00", "2026-09-14T09:15:00", "2026-09-17T09:20:00", "2026-09-21T09:22:00", "2026-09-24T09:27:30")
        val records = dates.map(::original)
        val scores = listOf(77, 78, 81, 74, 75, 80, 79, 78, 80, 81, 79, 80)
        val metabolism = listOf(1815, 1820, 1840, 1848, 1858, 1854, 1854, 1850, 1864, 1856, 1840, 1828)
        val values = records.flatMapIndexed { index, record -> listOf(
            csvMetric(record, HealthMetricType.INBODY_SCORE, scores[index].toDouble()),
            csvMetric(record, HealthMetricType.BASAL_METABOLIC_RATE_KCAL, metabolism[index].toDouble()),
            csvMetric(record, HealthMetricType.WEIGHT_KG, 75.0 - index / 10.0),
            csvMetric(record, HealthMetricType.SKELETAL_MUSCLE_KG, 32.0 + (index % 3) / 10.0),
        ) }
        render(values, records, sample = true)
        selectedText(HealthMetricType.INBODY_SCORE, "80 점").assertIsDisplayed()
        compose.onNodeWithTag("body-card-BASAL_METABOLIC_RATE_KCAL").assertExists()
        screenshot("body-history-reference-sample")
        compose.onNodeWithTag("body-plot-INBODY_SCORE").performScrollToIndex(2)
        compose.onNodeWithTag("body-point-INBODY_SCORE-3").performClick()
        selectedText(HealthMetricType.INBODY_SCORE, "26.09.07 10:42:00").assertIsDisplayed()
        screenshot("body-history-reference-selected")
        scroll("body-card-BASAL_METABOLIC_RATE_KCAL")
        screenshot("body-history-reference-metabolism")
    }

    private fun selectedText(type: HealthMetricType, value: String) =
        compose.onNode(hasText(value) and hasAnyAncestor(hasTestTag("body-selection-${type.name}")))
    private fun scroll(tag: String) { compose.onNodeWithTag("inbody-trend-list").performScrollToNode(hasTestTag(tag)) }
    private fun metric(date: String, type: HealthMetricType, value: Double) = HealthMeasurementEntity(
        id = "$date-$type", recordDate = date, measuredAt = LocalDate.parse(date).toEpochDay() * 86_400_000,
        type = type, value = value, source = WorkoutSource.LOCAL)
    private fun original(local: String) = InBodyRecordEntity("inbody-csv-test-$local", local,
        LocalDateTime.parse(local).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli(), "Asia/Seoul", "TEST", "test-only.csv", "{}", 0, 0)
    private fun csvMetric(record: InBodyRecordEntity, type: HealthMetricType, value: Double) =
        metric(record.measuredLocal.take(10), type, value).copy(id = "${record.id}-$type", measuredAt = record.measuredAt,
            externalRecordId = record.id, sourcePackage = INBODY_PACKAGE)
    private fun render(values: List<HealthMeasurementEntity>, originals: List<InBodyRecordEntity> = emptyList(), fontScale: Float = 1f, sample: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                TodayRunningTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (sample) Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                            Text("테스트 샘플 · 가상 측정 12회 · 실제 기록 미저장", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        HealthTrendChart(values, originals)
                    }
                } }
            }
        }
    }
    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "ui-review").apply { mkdirs() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
