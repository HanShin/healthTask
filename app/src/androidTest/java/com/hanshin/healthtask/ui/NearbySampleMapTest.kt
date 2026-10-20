package com.hanshin.healthtask.ui

import android.location.Location
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NearbySampleMapTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sampleIsExplicitlyUnsavedReadsLocationOnceAndCanBeClosed() {
        val open = mutableStateOf(true)
        var reads = 0
        compose.setContent { MaterialTheme {
            if (open.value) NearbySampleMapDialog(
                onDismiss = { open.value = false },
                locationReader = { reads++; sampleLocation() },
                mapContent = { points, _ ->
                    assertEquals(points.first().latitude, points.last().latitude, 0.0)
                    assertEquals(points.first().longitude, points.last().longitude, 0.0)
                    Text("샘플 경로 ${points.size}개", Modifier.testTag("sample-route-fixture"))
                },
            )
        } }
        compose.onNodeWithTag("sample-not-saved").assertIsDisplayed()
        compose.onNodeWithTag("sample-route-fixture").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, reads) }
        compose.onNodeWithTag("close-sample-map").performClick()
        compose.onNodeWithTag("sample-not-saved").assertDoesNotExist()
    }

    @Test fun unavailableLocationDoesNotInventACenterAndOffersRetry() {
        var reads = 0
        compose.setContent { MaterialTheme {
            NearbySampleMapDialog(
                onDismiss = {},
                locationReader = { if (++reads == 1) null else sampleLocation() },
                mapContent = { _, _ -> Text("샘플 경로", Modifier.testTag("sample-route-fixture")) },
            )
        } }
        compose.onNodeWithTag("sample-route-fixture").assertDoesNotExist()
        compose.onNodeWithTag("retry-sample-location").performClick()
        compose.onNodeWithTag("sample-route-fixture").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, reads) }
    }

    private fun sampleLocation() = Location("test").apply {
        latitude = 37.5
        longitude = 127.0
        accuracy = 20f
    }
}
