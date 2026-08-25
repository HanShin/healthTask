package com.hanshin.healthtask.running

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class GpsSample(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float,
    val speedMetersPerSecond: Float? = null,
)

data class RunningSegment(
    val distanceMeters: Double,
    val speedMetersPerSecond: Double,
)

object RunningMetrics {
    const val MAX_ACCURACY_METERS = 40f
    const val MAX_RUNNING_SPEED_METERS_PER_SECOND = 12.5
    const val LAP_DISTANCE_METERS = 1_000.0
    const val AUTO_PAUSE_SPEED_METERS_PER_SECOND = 0.7
    const val AUTO_RESUME_SPEED_METERS_PER_SECOND = 1.0
    const val AUTO_PAUSE_DELAY_MILLIS = 8_000L

    /**
     * Returns a valid movement segment, 0 m for GPS jitter, or null for an
     * unusable/teleporting point.
     */
    fun segment(previous: GpsSample, current: GpsSample): RunningSegment? {
        if (!isUsable(previous) || !isUsable(current)) return null
        val elapsedSeconds = (current.timestampMillis - previous.timestampMillis) / 1_000.0
        if (elapsedSeconds <= 0.0) return null
        val distance = distanceMeters(previous.latitude, previous.longitude, current.latitude, current.longitude)
        val noiseFloor = max(1.5, min(previous.accuracyMeters, current.accuracyMeters) * 0.22)
        if (distance < noiseFloor) return RunningSegment(0.0, 0.0)
        val speed = distance / elapsedSeconds
        if (speed > MAX_RUNNING_SPEED_METERS_PER_SECOND) return null
        return RunningSegment(distance, speed)
    }

    fun paceMinutesPerKm(speedMetersPerSecond: Double): Double? =
        speedMetersPerSecond.takeIf { it in 0.45..MAX_RUNNING_SPEED_METERS_PER_SECOND }
            ?.let { 1_000.0 / it / 60.0 }

    fun completedLaps(
        existing: List<RunningLap>,
        previousDistanceMeters: Double,
        currentDistanceMeters: Double,
        previousElapsedMillis: Long,
        currentElapsedMillis: Long,
    ): List<RunningLap> {
        if (currentDistanceMeters <= previousDistanceMeters || currentElapsedMillis < previousElapsedMillis) return existing
        val result = existing.toMutableList()
        var targetDistance = (result.size + 1) * LAP_DISTANCE_METERS
        while (targetDistance <= currentDistanceMeters) {
            val fraction = ((targetDistance - previousDistanceMeters) /
                (currentDistanceMeters - previousDistanceMeters)).coerceIn(0.0, 1.0)
            val lapCompletedAt = previousElapsedMillis +
                ((currentElapsedMillis - previousElapsedMillis) * fraction).toLong()
            val previousLapCompletedAt = result.lastOrNull()?.totalElapsedMillis ?: 0L
            result += RunningLap(
                index = result.size + 1,
                distanceMeters = LAP_DISTANCE_METERS,
                durationMillis = (lapCompletedAt - previousLapCompletedAt).coerceAtLeast(0L),
                totalElapsedMillis = lapCompletedAt,
            )
            targetDistance = (result.size + 1) * LAP_DISTANCE_METERS
        }
        return result
    }

    fun withFinalPartialLap(
        completed: List<RunningLap>,
        totalDistanceMeters: Double,
        totalElapsedMillis: Long,
    ): List<RunningLap> {
        val completedDistance = completed.sumOf { it.distanceMeters }
        val remainingDistance = (totalDistanceMeters - completedDistance).coerceAtLeast(0.0)
        if (remainingDistance < 1.0) return completed
        val previousElapsed = completed.lastOrNull()?.totalElapsedMillis ?: 0L
        return completed + RunningLap(
            index = completed.size + 1,
            distanceMeters = remainingDistance,
            durationMillis = (totalElapsedMillis - previousElapsed).coerceAtLeast(0L),
            totalElapsedMillis = totalElapsedMillis,
        )
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val latitudeDelta = Math.toRadians(lat2 - lat1)
        val longitudeDelta = Math.toRadians(lon2 - lon1)
        val startLatitude = Math.toRadians(lat1)
        val endLatitude = Math.toRadians(lat2)
        val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(startLatitude) * cos(endLatitude) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * earthRadiusMeters * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private fun isUsable(sample: GpsSample): Boolean =
        sample.latitude in -90.0..90.0 &&
            sample.longitude in -180.0..180.0 &&
            sample.accuracyMeters.isFinite() &&
            sample.accuracyMeters in 0f..MAX_ACCURACY_METERS
}
