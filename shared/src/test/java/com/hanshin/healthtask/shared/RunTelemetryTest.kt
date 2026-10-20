package com.hanshin.healthtask.shared

import org.junit.Assert.*
import org.junit.Test

class RunTelemetryTest {
    private val metadata = RunSensorMetadata("run", 1_000, 1_000, 0, "Asia/Seoul", "test", "test", 36,
        listOf("heartRateBpm", "cadenceStepsPerMinute"), listOf("heartRateBpm"), listOf("cadenceStepsPerMinute"))
    private fun hr(t: Long, value: Double, active: Long? = t) = RunSensorRecord("sample", 1_000 + t,
        elapsedRealtimeMillis = t, activeElapsedMillis = active, metric = "heartRateBpm", value = value)

    @Test fun summaryUsesEverySampleAndDoesNotFillPausesGapsOrLastSample() {
        val telemetry = RunTelemetry(metadata = metadata, records = listOf(
            hr(0, 110.0), hr(1_000, 130.0), hr(2_000, 150.0), hr(10_000, 190.0),
            hr(11_000, 180.0, 10_000), hr(12_000, 160.0, 10_000), hr(13_000, 140.0, 11_000),
        ))
        val summary = telemetry.summarize()
        assertEquals(7, summary.heartRateSampleCount)
        assertEquals(110.0, summary.minHeartRateBpm!!, 0.0)
        assertEquals(190.0, summary.maxHeartRateBpm!!, 0.0)
        assertEquals(3_000L, summary.heartRateBandCoveredMillis)
        assertEquals(1_000L, summary.heartRateBands[1].durationMillis)
        assertEquals(1_000L, summary.heartRateBands[2].durationMillis)
        assertEquals(1_000L, summary.heartRateBands[4].durationMillis)
    }

    @Test fun missingMeasurementsRemainUnknownAndZeroStepCountIsKnown() {
        val summary = RunTelemetry(metadata = metadata, records = listOf(
            RunSensorRecord("sample", 1_000, metric = "stepsTotal", value = 0.0),
        )).summarize()
        assertNull(summary.minHeartRateBpm)
        assertNull(summary.sampleMeanCadenceStepsPerMinute)
        assertNull(summary.elevationGainMeters)
        assertEquals(0.0, summary.stepsTotal!!, 0.0)
        assertEquals(listOf("cadenceStepsPerMinute"), summary.unsupportedMetrics)
        assertEquals(0L, summary.heartRateBandCoveredMillis)
    }

    @Test fun duplicatesDoNotSkewSummaryAndUnanchoredHeartRateDoesNotInventZoneDuration() {
        val point = hr(1_000, 150.0, null)
        val summary = RunTelemetry(metadata = metadata, records = listOf(point, point, hr(2_000, 160.0, null))).summarize()
        assertEquals(2, summary.heartRateSampleCount)
        assertEquals(155.0, summary.sampleMeanHeartRateBpm!!, 0.0)
        assertEquals(0L, summary.heartRateBandCoveredMillis)
    }

    @Test fun cumulativeTotalsUseLastSampleNotSumAndCadenceUsesAllSamples() {
        val records = listOf(
            RunSensorRecord("sample", 1_000, metric = "stepsTotal", value = 100.0),
            RunSensorRecord("sample", 2_000, metric = "stepsTotal", value = 120.0),
            RunSensorRecord("sample", 1_000, metric = "cadenceStepsPerMinute", value = 160.0),
            RunSensorRecord("sample", 2_000, metric = "cadenceStepsPerMinute", value = 180.0),
            RunSensorRecord("sample", 1_000, metric = "elevationGainMeters", value = 12.0),
            RunSensorRecord("sample", 2_000, metric = "elevationGainMeters", value = 16.0),
        )
        val summary = RunTelemetry(metadata = metadata, records = records).summarize()
        assertEquals(120.0, summary.stepsTotal!!, 0.0)
        assertEquals(170.0, summary.sampleMeanCadenceStepsPerMinute!!, 0.0)
        assertEquals(16.0, summary.elevationGainMeters!!, 0.0)
    }

    @Test fun largeCompressedPayloadRoundTripsWithoutDroppingSamples() {
        val telemetry = RunTelemetry(metadata = metadata, records = (0..10_000).map { hr(it * 1_000L, 130.0 + it % 50) })
        val json = RunTelemetryCodec.encode(telemetry)
        assertTrue(json.length > 100_000)
        val restored = RunTelemetryCodec.decode(RunTelemetryCodec.decompress(RunTelemetryCodec.compress(json).inputStream()))
        assertEquals(telemetry, restored)
    }

    @Test(expected = IllegalArgumentException::class) fun unsupportedSensorSchemaIsNotSilentlyAccepted() {
        RunTelemetryCodec.decode(RunTelemetryCodec.encode(RunTelemetry(schemaVersion = 99, metadata = metadata, records = emptyList())))
    }
}
