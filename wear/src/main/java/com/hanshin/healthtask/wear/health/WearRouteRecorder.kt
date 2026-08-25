package com.hanshin.healthtask.wear.health

import com.hanshin.healthtask.shared.WearRoutePoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

internal class WearRouteRecorder(
    private val maxPoints: Int = MAX_ROUTE_POINTS,
) {
    private data class Sample(
        val latitude: Double,
        val longitude: Double,
        val elapsedMillis: Long,
        val accuracyMeters: Float,
    )

    private var previous: Sample? = null
    private var points: List<WearRoutePoint> = emptyList()

    fun reset() {
        previous = null
        points = emptyList()
    }

    fun breakSegment() {
        previous = null
    }

    /** Returns a new route only when the candidate becomes a recorded point. */
    fun record(
        latitude: Double,
        longitude: Double,
        elapsedMillis: Long,
        accuracyMeters: Float,
    ): List<WearRoutePoint>? {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || elapsedMillis < 0L) return null
        if (!accuracyMeters.isFinite() || accuracyMeters !in 0f..MAX_ROUTE_ACCURACY_METERS) return null

        val candidate = Sample(latitude, longitude, elapsedMillis, accuracyMeters)
        previous?.let { last ->
            val elapsedDelta = elapsedMillis - last.elapsedMillis
            if (elapsedDelta < LOCATION_INTERVAL_MILLIS) return null
            val distanceMeters = distanceMeters(last.latitude, last.longitude, latitude, longitude)
            val noiseFloor = max(
                LOCATION_MIN_DISTANCE_METERS.toDouble(),
                min(last.accuracyMeters, accuracyMeters) * GPS_NOISE_FLOOR_RATIO.toDouble(),
            )
            if (distanceMeters < noiseFloor) return null
            val speedMetersPerSecond = distanceMeters / (elapsedDelta / 1_000.0)
            if (!speedMetersPerSecond.isFinite() || speedMetersPerSecond > MAX_ROUTE_SPEED_METERS_PER_SECOND) return null
        }

        previous = candidate
        if (points.size >= maxPoints) points = points.filterIndexed { index, _ -> index % 2 == 0 }
        points = points + WearRoutePoint(latitude, longitude, elapsedMillis)
        return points
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val latitudeDelta = Math.toRadians(lat2 - lat1)
        val longitudeDelta = Math.toRadians(lon2 - lon1)
        val startLatitude = Math.toRadians(lat1)
        val endLatitude = Math.toRadians(lat2)
        val haversine = sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(startLatitude) * cos(endLatitude) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    private companion object {
        const val LOCATION_INTERVAL_MILLIS = 2_000L
        const val LOCATION_MIN_DISTANCE_METERS = 2f
        const val MAX_ROUTE_ACCURACY_METERS = 40f
        const val GPS_NOISE_FLOOR_RATIO = 0.22f
        const val MAX_ROUTE_SPEED_METERS_PER_SECOND = 12.5
        const val MAX_ROUTE_POINTS = 2_000
        const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
