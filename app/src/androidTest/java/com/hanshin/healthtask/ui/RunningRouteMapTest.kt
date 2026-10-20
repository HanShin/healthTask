package com.hanshin.healthtask.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.hanshin.healthtask.running.RunningPoint
import org.junit.Rule
import org.junit.Test

class RunningRouteMapTest {
    @get:Rule val compose = createComposeRule()
    private val route = listOf(
        RunningPoint(37.5665, 126.9780, 0L),
        RunningPoint(37.5670, 126.9800, 30_000L),
        RunningPoint(37.5690, 126.9810, 60_000L),
    )

    @Test fun offlineDisplaysTheRecordedTraceWithoutStartingNativeMap() {
        compose.setContent { MaterialTheme {
            RunningRouteMap(route, Modifier.fillMaxWidth().height(260.dp), networkAvailable = false)
        } }
        compose.onNodeWithTag("route-map-fallback").assertIsDisplayed()
        compose.onNodeWithTag("running-native-map").assertDoesNotExist()
        compose.onNodeWithText("오프라인 · 이동 궤적을 표시합니다").assertIsDisplayed()
    }

    @Test fun localStyleRendersRouteAndKeepsAttributionVisible() {
        compose.setContent { MaterialTheme {
            RunningRouteMap(route, Modifier.fillMaxWidth().height(400.dp), interactive = true,
                styleJson = LOCAL_STYLE, networkAvailable = true)
        } }
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithTag("osm-attribution").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("running-native-map").assertIsDisplayed()
        compose.onNodeWithTag("osm-attribution").assertIsDisplayed()
        compose.onNodeWithTag("fit-route-map").performClick()
        compose.onNodeWithTag("route-map-fallback").assertDoesNotExist()
    }

    @Test fun invalidStyleFallsBackToTraceAndOffersRetry() {
        compose.setContent { MaterialTheme {
            RunningRouteMap(route, Modifier.fillMaxWidth().height(260.dp),
                styleJson = "invalid json", networkAvailable = true)
        } }
        compose.waitUntil(20_000L) {
            compose.onAllNodesWithTag("retry-route-map").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("route-map-fallback").assertIsDisplayed()
        compose.onNodeWithTag("retry-route-map").assertIsDisplayed()
    }

    private companion object {
        // No automated panning, prefetch or test traffic against OSM's public servers.
        const val LOCAL_STYLE = """{"version":8,"sources":{},"layers":[
          {"id":"background","type":"background","paint":{"background-color":"#edf1e9"}}
        ]}"""
    }
}
