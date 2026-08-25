package com.hanshin.healthtask.shared

import java.util.Locale

data class WearRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val elapsedMillis: Long,
)

/** Compact route format shared with the phone's existing running history. */
object WearRouteCodec {
    fun encode(points: List<WearRoutePoint>): String = points.joinToString(";") { point ->
        String.format(
            Locale.US,
            "%.6f,%.6f,%d",
            point.latitude,
            point.longitude,
            point.elapsedMillis,
        )
    }

    fun decode(value: String?): List<WearRoutePoint> = value.orEmpty()
        .split(';')
        .mapNotNull { encoded ->
            val values = encoded.split(',')
            if (values.size != 3) return@mapNotNull null
            val latitude = values[0].toDoubleOrNull() ?: return@mapNotNull null
            val longitude = values[1].toDoubleOrNull() ?: return@mapNotNull null
            val elapsedMillis = values[2].toLongOrNull() ?: return@mapNotNull null
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || elapsedMillis < 0L) {
                return@mapNotNull null
            }
            WearRoutePoint(latitude, longitude, elapsedMillis)
        }
}
