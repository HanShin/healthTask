package com.hanshin.healthtask.wear.health

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WearMetrics(
    val heartRateBpm: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val caloriesKcal: Double? = null,
    val distanceKm: Double? = null,
    val activeDurationMillis: Long = 0L,
    val tracking: Boolean = false,
    val paused: Boolean = false,
    val error: String? = null,
)

object WearMetricsRepository {
    private val mutableMetrics = MutableStateFlow(WearMetrics())
    val metrics: StateFlow<WearMetrics> = mutableMetrics.asStateFlow()

    fun reset() { mutableMetrics.value = WearMetrics(tracking = true) }
    fun update(transform: (WearMetrics) -> WearMetrics) { mutableMetrics.value = transform(mutableMetrics.value) }
}
