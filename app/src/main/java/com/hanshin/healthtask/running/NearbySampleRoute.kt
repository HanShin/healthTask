package com.hanshin.healthtask.running

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Display-only synthetic loop. Never pass these points to the tracker or a workout repository. */
internal fun nearbySampleRoute(latitude: Double, longitude: Double): List<RunningPoint> {
    require(latitude.isFinite() && latitude in -84.0..84.0)
    require(longitude.isFinite() && longitude in -180.0..180.0)
    val metersPerDegree = 6_371_000.0 * PI / 180.0
    val longitudeScale = metersPerDegree * cos(Math.toRadians(latitude))
    val points = (0 until 64).map { index ->
        val angle = 2 * PI * index / 64
        val north = 140.0 * (1 - cos(angle))
        val east = 110.0 * sin(angle)
        RunningPoint(
            latitude + north / metersPerDegree,
            ((longitude + east / longitudeScale + 540.0) % 360.0) - 180.0,
            index * 5_000L,
        )
    }
    return points + points.first().copy(elapsedMillis = 320_000L)
}
