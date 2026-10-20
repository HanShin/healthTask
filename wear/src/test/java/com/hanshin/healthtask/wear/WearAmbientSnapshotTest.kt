package com.hanshin.healthtask.wear

import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearQuickRunPreset
import com.hanshin.healthtask.shared.toRoutinePayload
import com.hanshin.healthtask.wear.health.WearMetrics
import org.junit.Assert.*
import org.junit.Test

class WearAmbientSnapshotTest {
    private val start = 1_000_000L
    private val run = WearActiveSession("test-run", WearQuickRunPreset.FREE_RUN.toRoutinePayload(), start)

    @Test fun `running snapshot labels cached distance and uses whole minutes`() {
        val snapshot = ambientRunSnapshot(WearUiState(active = run, metrics = WearMetrics(distanceKm = 5.24)), start + 1_859_999L)
        assertEquals("러닝 중", snapshot.status)
        assertEquals("5.24 km", snapshot.distance)
        assertEquals("누적 30분", snapshot.duration)
    }

    @Test fun `paused snapshot excludes paused duration`() {
        val paused = run.copy(paused = true, pausedAt = start + 600_000L, accumulatedPausedMillis = 60_000L)
        val snapshot = ambientRunSnapshot(WearUiState(active = paused), start + 3_600_000L)
        assertEquals("일시정지", snapshot.status)
        assertEquals("누적 9분", snapshot.duration)
        assertEquals("-- km", snapshot.distance)
    }

    @Test fun `completed session does not show previous metrics as an active run`() {
        val snapshot = ambientRunSnapshot(WearUiState(metrics = WearMetrics(distanceKm = 5.24)), start)
        assertEquals("오늘러닝", snapshot.status)
        assertNull(snapshot.distance)
        assertNull(snapshot.duration)
    }

    @Test fun `invalid sensor distance is not presented as a measurement`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { value ->
            assertEquals("-- km", ambientRunSnapshot(WearUiState(active = run, metrics = WearMetrics(distanceKm = value)), start).distance)
        }
    }
}
