package com.hanshin.healthtask.ui

import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.InBodyRecordEntity
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.isExternal
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

internal enum class HealthTrendPeriod(val label: String, val months: Long?) {
    MONTH("1개월", 1), QUARTER("3개월", 3), HALF_YEAR("6개월", 6), ALL("전체", null);
    fun startDate(end: LocalDate): LocalDate? = months?.let { end.minusMonths(it).plusDays(1) }
}

internal fun validTrendMeasurement(value: HealthMeasurementEntity): Boolean =
    !value.type.isCode && value.value.isFinite() && value.value >= 0 &&
        runCatching { LocalDate.parse(value.recordDate) }.isSuccess

internal fun healthTrendReferenceDate(values: List<HealthMeasurementEntity>): LocalDate? =
    values.filter(::validTrendMeasurement).maxOfOrNull { LocalDate.parse(it.recordDate) }

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
    (healthTrendMetricTypes + HealthMetricType.entries.filterNot { it.isCode }).distinct()
        .filter { type -> values.any { it.type == type && validTrendMeasurement(it) } }

internal fun healthTrendSeries(
    values: List<HealthMeasurementEntity>,
    type: HealthMetricType,
    maximumPoints: Int = Int.MAX_VALUE,
    period: HealthTrendPeriod = HealthTrendPeriod.ALL,
    referenceDate: LocalDate? = healthTrendReferenceDate(values),
): List<HealthMeasurementEntity> = values.asSequence()
    .filter { it.type == type && validTrendMeasurement(it) }
    .filter {
        val date = LocalDate.parse(it.recordDate)
        val start = referenceDate?.let(period::startDate)
        (referenceDate == null || date <= referenceDate) && (start == null || date >= start)
    }
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
    val minimumSpan = if (type.unit == "ratio") 0.04 else when (type) {
        HealthMetricType.VISCERAL_FAT_LEVEL -> 4.0
        HealthMetricType.INBODY_SCORE -> 10.0
        HealthMetricType.BODY_FAT_PERCENT -> 5.0
        else -> 2.0
    }
    val span = max((maximum - minimum) * 1.3, max(minimumSpan, maximum * 0.005)).coerceAtMost(Double.MAX_VALUE)
    val center = minimum / 2.0 + maximum / 2.0
    return HealthTrendBounds((center - span / 2.0).coerceAtLeast(0.0), (center + span / 2.0).coerceAtMost(Double.MAX_VALUE))
}

/** Use elapsed calendar days, not record indices. A lone measurement is centered. */
internal fun healthTrendXPositions(values: List<HealthMeasurementEntity>): List<Float> {
    if (values.isEmpty()) return emptyList()
    val days = values.map { LocalDate.parse(it.recordDate).toEpochDay() }
    val minimum = days.min()
    val span = days.max() - minimum
    return days.map { if (span == 0L) 0.5f else ((it - minimum).toDouble() / span).toFloat() }
}

internal fun nearestHealthTrendPoint(positions: List<Float>, fraction: Float): Int? =
    positions.indices.minByOrNull { abs(positions[it] - fraction.coerceIn(0f, 1f)) }

internal fun healthTrendDelta(first: Double, last: Double): String {
    val difference = BigDecimal.valueOf(last).subtract(BigDecimal.valueOf(first))
    return (if (difference.signum() > 0) "+" else "") + difference.stripTrailingZeros().toPlainString()
}

internal const val RECENT_BODY_MEASUREMENTS = 10

/** The card history intentionally retains every measurement, including repeated dates. */
internal fun bodyHistoryMeasurements(values: List<HealthMeasurementEntity>): List<HealthMeasurementEntity> =
    values.filter { validTrendMeasurement(it) &&
        (it.type != HealthMetricType.BODY_WATER_L || it.externalRecordId?.startsWith("inbody-csv-") == true) }
        .sortedWith(compareBy<HealthMeasurementEntity> { it.recordDate }.thenBy { it.measuredAt }.thenBy { it.id })

internal fun bodyHistoryTypes(values: List<HealthMeasurementEntity>): List<HealthMetricType> =
    (listOf(HealthMetricType.INBODY_SCORE, HealthMetricType.BASAL_METABOLIC_RATE_KCAL,
        HealthMetricType.WEIGHT_KG, HealthMetricType.SKELETAL_MUSCLE_KG, HealthMetricType.BODY_FAT_PERCENT) +
        availableHealthTrendTypes(values)).distinct().filter { type -> values.any { it.type == type } }

internal fun bodyHistorySeries(values: List<HealthMeasurementEntity>, type: HealthMetricType, showAll: Boolean) =
    bodyHistoryMeasurements(values).filter { it.type == type }.let { if (showAll) it else it.takeLast(RECENT_BODY_MEASUREMENTS) }

internal fun bodyHistoryDateLabel(value: HealthMeasurementEntity, original: InBodyRecordEntity?, zone: ZoneId = ZoneId.systemDefault()): String {
    // CSV preserves its original local time even when the phone changes time zones.
    val csvTime = original?.let { runCatching { LocalDateTime.parse(it.measuredLocal) }.getOrNull() }
    if (csvTime != null) return csvTime.format(DateTimeFormatter.ofPattern("yy.MM.dd HH:mm:ss"))
    // Manual/capture input has only a date; its stored timestamp is not a measurement time.
    val externalTime = if (value.source.isExternal()) Instant.ofEpochMilli(value.measuredAt).atZone(zone) else null
    return if (externalTime?.toLocalDate()?.toString() == value.recordDate)
        externalTime.format(DateTimeFormatter.ofPattern("yy.MM.dd HH:mm:ss"))
    else LocalDate.parse(value.recordDate).format(DateTimeFormatter.ofPattern("yy.MM.dd"))
}
