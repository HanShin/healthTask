package com.hanshin.healthtask.wear.health

import androidx.health.services.client.data.*
import java.time.Duration
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class WearSensorBatchTest {
    @Test fun batchedHeartRateSpeedCadenceAndAltitudeKeepEveryOriginalTimestamp() {
        val points = listOf(
            SampleDataPoint(DataType.HEART_RATE_BPM, 140.0, Duration.ofSeconds(101)),
            SampleDataPoint(DataType.HEART_RATE_BPM, 150.0, Duration.ofSeconds(102)),
            SampleDataPoint(DataType.HEART_RATE_BPM, 160.0, Duration.ofSeconds(103)),
            SampleDataPoint(DataType.SPEED, 3.0, Duration.ofSeconds(101)),
            SampleDataPoint(DataType.SPEED, 3.5, Duration.ofSeconds(102)),
            SampleDataPoint(DataType.STEPS_PER_MINUTE, 174L, Duration.ofSeconds(102)),
            SampleDataPoint(DataType.ABSOLUTE_ELEVATION, 35.0, Duration.ofSeconds(102)),
            CumulativeDataPoint(DataType.STEPS_TOTAL, 20L, Instant.ofEpochMilli(101_000), Instant.ofEpochMilli(104_000)),
        )
        val update = update(points, ExerciseState.ACTIVE)
        val records = update.sensorRecords(1_000)
        assertEquals(8, records.count { it.kind == "sample" })
        assertEquals(listOf(102_000L, 103_000L, 104_000L), records.filter { it.metric == "heartRateBpm" }.map { it.timestampEpochMillis })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), records.filter { it.metric == "heartRateBpm" }.map { it.activeElapsedMillis })
        assertEquals(174.0, records.single { it.metric == "cadenceStepsPerMinute" }.value!!, 0.0)
        assertEquals(20.0, records.single { it.metric == "stepsTotal" }.value!!, 0.0)
    }

    @Test fun unsupportedMetricsAreNotFabricatedAndNonfiniteSamplesAreRejected() {
        val update = update(listOf(SampleDataPoint(DataType.SPEED, Double.NaN, Duration.ofSeconds(101))), ExerciseState.ACTIVE)
        assertTrue(update.sensorRecords(1_000).none { it.kind == "sample" })
        assertTrue(DataType.ELEVATION_GAIN_TOTAL in requestedRunSensors)
        assertTrue(DataType.STEPS_PER_MINUTE in requestedRunSensors)
    }

    @Test fun partialLapKeepsItsActualIntervalDistanceAndDuration() {
        val summary = ExerciseLapSummary(2, Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(91_000),
            Duration.ofSeconds(80), DataPointContainer(listOf(
                CumulativeDataPoint(DataType.DISTANCE_TOTAL, 250.0, Instant.ofEpochMilli(1_000), Instant.ofEpochMilli(91_000)),
            )))
        val records = summary.sensorRecords()
        assertEquals(2, records.size)
        assertTrue(records.all { it.kind == "lapSummary" && it.lapIndex == 2 && it.intervalStartEpochMillis == 1_000L })
        assertEquals(250.0, records.single { it.metric == "distanceMeters" }.value!!, 0.0)
        assertEquals(80_000.0, records.single { it.metric == "activeDurationMillis" }.value!!, 0.0)
    }

    // AndroidX exposes updates via callbacks only; construct a synthetic batch solely in tests.
    @Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")
    private fun update(points: List<DataPoint<*>>, state: ExerciseState): ExerciseUpdate = ExerciseUpdate(
        latestMetrics = DataPointContainer(points), latestAchievedGoals = emptySet(), latestMilestoneMarkerSummaries = emptySet(),
        exerciseStateInfo = ExerciseStateInfo(state, 0),
        activeDurationCheckpoint = ExerciseUpdate.ActiveDurationCheckpoint(Instant.ofEpochMilli(104_000), Duration.ofSeconds(3)),
        startTime = Instant.ofEpochMilli(101_000),
        activeDurationLegacy = Duration.ofSeconds(3),
        updateDurationFromBoot = Duration.ofSeconds(103),
    )
}
