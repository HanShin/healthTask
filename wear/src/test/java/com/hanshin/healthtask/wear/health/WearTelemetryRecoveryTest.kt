package com.hanshin.healthtask.wear.health

import com.hanshin.healthtask.shared.*
import org.junit.Assert.*
import org.junit.Test

class WearTelemetryRecoveryTest {
    @Test fun summaryAndRouteCanBeRecoveredAfterProcessRecreation() {
        val telemetry = RunTelemetry(metadata = RunSensorMetadata("run", 1_000, 1_000, 0,
            "Asia/Seoul", "test", "test", 36, emptyList(), emptyList(), emptyList()), records = listOf(
            RunSensorRecord("sample", 2_000, metric = "distanceMeters", value = 1_250.0),
            RunSensorRecord("sample", 2_000, metric = "caloriesKcal", value = 120.0),
            RunSensorRecord("sample", 2_000, metric = "heartRateBpm", value = 150.0),
            RunSensorRecord("checkpoint", 2_000, metric = "activeDurationMillis", value = 600_000.0),
            RunSensorRecord("location", 2_000, activeElapsedMillis = 1_000, latitude = 37.5, longitude = 127.0,
                horizontalAccuracyMeters = 4.0),
        ))
        val metrics = WearMetrics().withTelemetryFallback(telemetry)
        assertEquals(1.25, metrics.distanceKm!!, 0.0)
        assertEquals(150.0, metrics.averageHeartRateBpm!!, 0.0)
        assertEquals(600_000L, metrics.activeDurationMillis)
        assertEquals(listOf(WearRoutePoint(37.5, 127.0, 1_000)), metrics.route)
        assertEquals(1.3, metrics.copy(distanceKm = 1.3).withTelemetryFallback(telemetry).distanceKm!!, 0.0)
    }
}
