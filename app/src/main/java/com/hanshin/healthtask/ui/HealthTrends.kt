package com.hanshin.healthtask.ui

import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.domain.HealthMetricType
import kotlin.math.max

internal val healthTrendMetricTypes = listOf(
    HealthMetricType.SKELETAL_MUSCLE_KG,
    HealthMetricType.BODY_FAT_MASS_KG,
    HealthMetricType.VISCERAL_FAT_LEVEL,
    HealthMetricType.INBODY_SCORE,
    HealthMetricType.WEIGHT_KG,
    HealthMetricType.BODY_FAT_PERCENT,
)

internal data class HealthTrendBounds(val minimum: Double, val maximum: Double) {
    val range: Double get() = maximum - minimum
}

internal fun availableHealthTrendTypes(values: List<HealthMeasurementEntity>): List<HealthMetricType> =
    healthTrendMetricTypes.filter { type -> values.any { it.type == type } }

internal fun healthTrendSeries(
    values: List<HealthMeasurementEntity>,
    type: HealthMetricType,
    maximumPoints: Int = 12,
): List<HealthMeasurementEntity> = values.asSequence()
    .filter { it.type == type }
    .sortedWith(compareBy<HealthMeasurementEntity> { it.recordDate }.thenBy { it.measuredAt })
    .toList()
    .takeLast(maximumPoints.coerceAtLeast(1))

internal fun healthTrendBounds(
    values: List<HealthMeasurementEntity>,
    type: HealthMetricType,
): HealthTrendBounds {
    if (values.isEmpty()) return HealthTrendBounds(0.0, 1.0)
    val minimum = values.minOf { it.value }
    val maximum = values.maxOf { it.value }
    val minimumSpan = when (type) {
        HealthMetricType.VISCERAL_FAT_LEVEL -> 4.0
        HealthMetricType.INBODY_SCORE -> 10.0
        HealthMetricType.BODY_FAT_PERCENT -> 5.0
        else -> 2.0
    }
    val span = max((maximum - minimum) * 1.3, minimumSpan)
    val center = (minimum + maximum) / 2.0
    return HealthTrendBounds(center - span / 2.0, center + span / 2.0)
}
