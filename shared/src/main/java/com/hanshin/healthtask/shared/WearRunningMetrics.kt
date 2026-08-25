package com.hanshin.healthtask.shared

object WearRunningMetrics {
    private const val MIN_RUNNING_SPEED_METERS_PER_SECOND = 0.45
    private const val MAX_RUNNING_SPEED_METERS_PER_SECOND = 12.5

    fun currentPaceMinutesPerKm(speedMetersPerSecond: Double?): Double? =
        speedMetersPerSecond
            ?.takeIf { it.isFinite() && it in MIN_RUNNING_SPEED_METERS_PER_SECOND..MAX_RUNNING_SPEED_METERS_PER_SECOND }
            ?.let { 1_000.0 / it / 60.0 }

    fun averagePaceMinutesPerKm(distanceKm: Double?, activeDurationMillis: Long): Double? {
        val distance = distanceKm?.takeIf { it.isFinite() && it > 0.01 } ?: return null
        if (activeDurationMillis <= 0L) return null
        val pace = (activeDurationMillis / 60_000.0) / distance
        return pace.takeIf { it.isFinite() && it in 0.0..60.0 }
    }
}
