package com.hanshin.healthtask.running

import com.hanshin.healthtask.shared.WearRouteCodec
import com.hanshin.healthtask.shared.WearRoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningMetricsTest {
    @Test
    fun `normal running segment contributes distance and speed`() {
        val start = sample(longitude = 127.0, timestamp = 1_000L)
        val end = sample(longitude = 127.0009, timestamp = 31_000L)

        val segment = requireNotNull(RunningMetrics.segment(start, end))

        assertTrue(segment.distanceMeters in 75.0..85.0)
        assertTrue(segment.speedMetersPerSecond in 2.4..3.0)
    }

    @Test
    fun `small movement inside accuracy noise is ignored`() {
        val start = sample(longitude = 127.0, timestamp = 1_000L, accuracy = 12f)
        val jitter = sample(longitude = 127.00001, timestamp = 2_000L, accuracy = 12f)

        assertEquals(0.0, requireNotNull(RunningMetrics.segment(start, jitter)).distanceMeters, 0.0)
    }

    @Test
    fun `inaccurate and teleporting samples are rejected`() {
        val start = sample(longitude = 127.0, timestamp = 1_000L)
        val inaccurate = sample(longitude = 127.0001, timestamp = 2_000L, accuracy = 80f)
        val teleport = sample(longitude = 127.02, timestamp = 2_000L)

        assertNull(RunningMetrics.segment(start, inaccurate))
        assertNull(RunningMetrics.segment(start, teleport))
    }

    @Test
    fun `pace is converted to minutes per kilometer`() {
        assertEquals(5.0, RunningMetrics.paceMinutesPerKm(1_000.0 / 300.0)!!, 0.001)
        assertNull(RunningMetrics.paceMinutesPerKm(0.1))
    }

    @Test
    fun `one kilometer crossing interpolates lap completion time`() {
        val laps = RunningMetrics.completedLaps(
            existing = emptyList(),
            previousDistanceMeters = 950.0,
            currentDistanceMeters = 1_050.0,
            previousElapsedMillis = 280_000L,
            currentElapsedMillis = 310_000L,
        )

        assertEquals(1, laps.size)
        assertEquals(1_000.0, laps.single().distanceMeters, 0.0)
        assertEquals(295_000L, laps.single().durationMillis)
        assertEquals(295_000L, laps.single().totalElapsedMillis)
    }

    @Test
    fun `final incomplete lap is retained with its own pace`() {
        val first = RunningLap(1, 1_000.0, 300_000L, 300_000L)

        val laps = RunningMetrics.withFinalPartialLap(listOf(first), 1_400.0, 420_000L)

        assertEquals(2, laps.size)
        assertEquals(400.0, laps.last().distanceMeters, 0.0)
        assertEquals(120_000L, laps.last().durationMillis)
        assertEquals(5.0, laps.last().averagePaceMinPerKm!!, 0.001)
    }

    @Test
    fun `lap codec preserves saved splits`() {
        val original = listOf(
            RunningLap(1, 1_000.0, 302_000L, 302_000L),
            RunningLap(2, 450.5, 140_000L, 442_000L),
        )

        assertEquals(original, RunningLapCodec.decode(RunningLapCodec.encode(original)))
    }

    @Test
    fun `phone route preview decodes route sent by watch`() {
        val encoded = WearRouteCodec.encode(listOf(
            WearRoutePoint(37.566535, 126.977969, 0L),
            WearRoutePoint(37.567123, 126.979321, 5_000L),
        ))

        assertEquals(
            listOf(
                RunningPoint(37.566535, 126.977969, 0L),
                RunningPoint(37.567123, 126.979321, 5_000L),
            ),
            RunningRouteCodec.decode(encoded),
        )
    }

    private fun sample(
        longitude: Double,
        timestamp: Long,
        accuracy: Float = 5f,
    ) = GpsSample(
        latitude = 37.5,
        longitude = longitude,
        timestampMillis = timestamp,
        accuracyMeters = accuracy,
    )
}
