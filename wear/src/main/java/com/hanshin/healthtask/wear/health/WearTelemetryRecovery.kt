package com.hanshin.healthtask.wear.health

import com.hanshin.healthtask.shared.RunTelemetry
import com.hanshin.healthtask.shared.WearRoutePoint
import com.hanshin.healthtask.shared.summarize

/** Rebuild summary after process recreation from the durable journal, not an empty UI cache. */
internal fun WearMetrics.withTelemetryFallback(telemetry: RunTelemetry?): WearMetrics {
    telemetry ?: return this
    fun last(metric: String) = telemetry.records.filter { it.kind in setOf("sample", "checkpoint") && it.metric == metric && it.value?.isFinite() == true }
        .maxByOrNull { it.timestampEpochMillis }?.value
    val summary = telemetry.summarize()
    return copy(
        averageHeartRateBpm = averageHeartRateBpm ?: summary.sampleMeanHeartRateBpm,
        distanceKm = distanceKm ?: last("distanceMeters")?.div(1_000.0),
        caloriesKcal = caloriesKcal ?: last("caloriesKcal"),
        stepsTotal = stepsTotal ?: last("stepsTotal")?.toLong(),
        activeDurationMillis = activeDurationMillis.takeIf { it > 0 }
            ?: last("activeDurationMillis")?.toLong() ?: 0L,
        route = route.ifEmpty {
            telemetry.records.filter { it.kind == "location" }.mapNotNull { point ->
                val lat = point.latitude ?: return@mapNotNull null
                val lon = point.longitude ?: return@mapNotNull null
                val active = point.activeElapsedMillis ?: return@mapNotNull null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || active < 0 ||
                    (point.horizontalAccuracyMeters ?: Double.MAX_VALUE) > 40) null
                else WearRoutePoint(lat, lon, active)
            }
        },
    )
}
