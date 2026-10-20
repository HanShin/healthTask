package com.hanshin.healthtask.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.hanshin.healthtask.data.db.InBodyRecordEntity
import com.hanshin.healthtask.health.InBodyCsvParser
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class InBodyCsvUiTest {
    @get:Rule val compose = createComposeRule()
    private fun preview() = InBodyCsvParser.parse("날짜,측정장비,체중(kg),근육량(kg),복부지방률,상체하체,체수분(L)\n20260924092730,H40,70.2,55.3,0.95,0,-",
        "synthetic.csv", ZoneId.of("Asia/Seoul"), LocalDate.of(2026, 9, 24))

    @Test fun previewShowsExactRatiosAndZeroCodesAndCancelDoesNotSave() {
        var saved = false
        var dismissed = false
        compose.setContent { TodayRunningTheme {
            InBodyCsvReviewDialog(preview(), { dismissed = true }, { saved = true })
        } }
        compose.onNodeWithText("인바디 CSV 확인").assertIsDisplayed()
        compose.onNodeWithTag("csv-review-list").performScrollToNode(hasText("0.95"))
        compose.onNodeWithText("0.95").assertIsDisplayed()
        screenshot("inbody-csv-review")
        compose.onNodeWithTag("csv-review-list").performScrollToNode(hasText("상체하체"))
        compose.onNodeWithText("0").assertExists()
        compose.onNodeWithText("취소").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertFalse(saved) }
    }

    @Test fun invalidTimeZoneBlocksSaveAndValidZonePreservesOriginalTime() {
        var savedZone: String? = null
        compose.setContent { TodayRunningTheme {
            InBodyCsvReviewDialog(preview(), {}, { savedZone = it.records.single().timeZoneId })
        } }
        compose.onNodeWithTag("csv-timezone").performTextReplacement("invalid/zone")
        compose.onNodeWithTag("save-inbody-csv").assertIsNotEnabled()
        compose.onNodeWithTag("csv-timezone").performTextReplacement("UTC")
        compose.onNodeWithTag("save-inbody-csv").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("UTC", savedZone) }
    }

    @Test fun savedRecordShowsEquipmentFullTimeAndAllRawFields() {
        val source = preview().records.single()
        val record = InBodyRecordEntity(source.id, source.measuredLocal, source.measuredAt, source.timeZoneId,
            source.device, "synthetic.csv", InBodyCsvParser.encodeRaw(source.rawFields), 1, 1)
        compose.setContent { TodayRunningTheme { Surface(Modifier.fillMaxSize()) {
            InBodyRecordsContent(emptyList(), onAdd = {}, onImport = {}, onEdit = {}, onDelete = {}, csvRecords = listOf(record))
        } } }
        compose.onNodeWithTag("import-inbody-csv").assertIsDisplayed()
        compose.onNodeWithText("2026.09.24 09:27:30").assertExists()
        compose.onNodeWithTag("csv-expand-details").performClick()
        compose.onNodeWithTag("inbody-records").performScrollToNode(hasText("0.95"))
        compose.onNodeWithText("0.95").assertIsDisplayed()
        screenshot("inbody-csv-detail")
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "ui-review").apply { mkdirs() }
        compose.waitForIdle()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
