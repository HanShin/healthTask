package com.hanshin.healthtask.shared

import com.google.gson.Gson
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Sensor timestamps retain the device monotonic clock as well as its anchored UTC clock. */
data class RunSensorRecord(
    val kind: String,
    val timestampEpochMillis: Long,
    val elapsedRealtimeMillis: Long? = null,
    val activeElapsedMillis: Long? = null,
    val metric: String? = null,
    val value: Double? = null,
    val accuracy: String? = null,
    val state: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitudeMeters: Double? = null,
    val horizontalAccuracyMeters: Double? = null,
    val verticalAccuracyMeters: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val bearingDegrees: Double? = null,
    val intervalStartEpochMillis: Long? = null,
    val lapIndex: Int? = null,
)

data class RunSensorMetadata(
    val sessionId: String,
    val startedAtEpochMillis: Long,
    val clockAnchorEpochMillis: Long,
    val clockAnchorElapsedRealtimeMillis: Long,
    val timeZoneId: String,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val requestedMetrics: List<String>,
    val supportedMetrics: List<String>,
    val unsupportedMetrics: List<String>,
    val source: String = "wear_health_services_and_android_gps",
)

data class RunTelemetry(
    val schemaVersion: Int = 1,
    val metadata: RunSensorMetadata,
    val records: List<RunSensorRecord>,
)

data class HeartRateBandDuration(val lowerInclusiveBpm: Int, val upperExclusiveBpm: Int?, val durationMillis: Long)

data class RunSensorSummary(
    val heartRateSampleCount: Int,
    val minHeartRateBpm: Double?,
    val maxHeartRateBpm: Double?,
    val sampleMeanHeartRateBpm: Double?,
    val cadenceSampleCount: Int,
    val sampleMeanCadenceStepsPerMinute: Double?,
    val maxCadenceStepsPerMinute: Double?,
    val stepsTotal: Double?,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val minAltitudeMeters: Double?,
    val maxAltitudeMeters: Double?,
    val locationSampleCount: Int,
    val heartRateBands: List<HeartRateBandDuration>,
    val heartRateBandCoveredMillis: Long,
    val unsupportedMetrics: List<String>,
    val collectionWarnings: List<String>? = null,
)

object RunTelemetryCodec {
    private val gson = Gson()
    fun encode(value: RunTelemetry): String = gson.toJson(value)
    fun decode(raw: String): RunTelemetry = gson.fromJson(raw, RunTelemetry::class.java).also {
        require(it.schemaVersion == 1 && it.metadata.sessionId.isNotBlank()) { "지원하지 않는 센서 기록입니다." }
        require(it.records.all { point -> point.timestampEpochMillis >= 0L &&
            listOfNotNull(point.value, point.latitude, point.longitude, point.altitudeMeters,
                point.horizontalAccuracyMeters, point.verticalAccuracyMeters, point.speedMetersPerSecond,
                point.bearingDegrees).all(Double::isFinite) }) { "올바르지 않은 센서 기록입니다." }
    }
    fun encodeSummary(value: RunSensorSummary): String = gson.toJson(value)
    fun decodeSummary(raw: String?): RunSensorSummary? = raw?.let {
        runCatching { gson.fromJson(it, RunSensorSummary::class.java) }.getOrNull()
    }
    fun compress(raw: String): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        output.toByteArray()
    }
    fun decompress(input: InputStream): String = GZIPInputStream(input).use { gzip ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = gzip.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= 64L * 1024 * 1024) { "센서 기록 파일이 너무 큽니다." }
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
}

/** No interpolated samples. Absolute BPM bands are descriptive, not personalized training zones. */
fun RunTelemetry.summarize(): RunSensorSummary {
    val samples = records.filter { it.kind == "sample" && it.value?.isFinite() == true }
        .distinctBy { Triple(it.metric, it.timestampEpochMillis, it.elapsedRealtimeMillis) }
    fun values(metric: String) = samples.filter { it.metric == metric }.mapNotNull { it.value }
    fun latest(metric: String) = samples.filter { it.metric == metric }.maxByOrNull { it.timestampEpochMillis }?.value
    val heart = samples.filter { it.metric == "heartRateBpm" && it.value!! > 0.0 }
        .sortedBy { it.elapsedRealtimeMillis ?: it.timestampEpochMillis }
    val cadence = values("cadenceStepsPerMinute").filter { it >= 0.0 }
    val altitude = values("altitudeMeters").ifEmpty {
        records.filter { it.kind == "location" }.mapNotNull { it.altitudeMeters }
    }
    val bands = longArrayOf(0, 0, 0, 0, 0, 0)
    heart.zipWithNext().forEach { (a, b) ->
        val delta = (b.elapsedRealtimeMillis ?: b.timestampEpochMillis) - (a.elapsedRealtimeMillis ?: a.timestampEpochMillis)
        val activeDelta = if (a.activeElapsedMillis != null && b.activeElapsedMillis != null)
            b.activeElapsedMillis - a.activeElapsedMillis else null
        // Exclude pauses, unanchored samples, long sensor gaps and the uncovered final sample.
        if (delta in 1..5_000 && activeDelta != null && activeDelta in 1..delta) {
            val index = when { a.value!! < 100 -> 0; a.value < 120 -> 1; a.value < 140 -> 2
                a.value < 160 -> 3; a.value < 180 -> 4; else -> 5 }
            bands[index] += activeDelta
        }
    }
    val hr = heart.map { it.value!! }
    val lower = listOf(0, 100, 120, 140, 160, 180)
    return RunSensorSummary(hr.size, hr.minOrNull(), hr.maxOrNull(), hr.takeIf { it.isNotEmpty() }?.average(),
        cadence.size, cadence.takeIf { it.isNotEmpty() }?.average(), cadence.maxOrNull(), latest("stepsTotal"),
        latest("elevationGainMeters"), latest("elevationLossMeters"), altitude.minOrNull(), altitude.maxOrNull(),
        records.count { it.kind == "location" }, lower.mapIndexed { index, start ->
            HeartRateBandDuration(start, lower.getOrNull(index + 1), bands[index])
        }, bands.sum(), metadata.unsupportedMetrics,
        records.filter { it.kind == "event" && it.metric == "quality" }.mapNotNull { it.state }.distinct())
}
