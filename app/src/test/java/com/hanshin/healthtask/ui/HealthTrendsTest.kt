package com.hanshin.healthtask.ui

import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.WorkoutSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTrendsTest {
    @Test fun `card history retains multiple measurements on the same date and all sources`() {
        val a = metric("a", "2026-09-24", HealthMetricType.WEIGHT_KG, 70.1).copy(measuredAt = 10)
        val b = a.copy(id = "b", measuredAt = 20, value = 70.3)
        val c = a.copy(id = "c", measuredAt = 30, source = WorkoutSource.SAMSUNG_HEALTH)
        assertEquals(listOf(a, b, c), bodyHistorySeries(listOf(c, b, a), a.type, true))
    }

    @Test fun `recent history uses ten measurements not ten dates and all has no cap`() {
        val values = (1..25).map { metric("$it", "2026-09-24", HealthMetricType.WEIGHT_KG, 70.0).copy(measuredAt = it.toLong()) }
        assertEquals(values.takeLast(10), bodyHistorySeries(values.reversed(), HealthMetricType.WEIGHT_KG, false))
        assertEquals(values, bodyHistorySeries(values.reversed(), HealthMetricType.WEIGHT_KG, true))
    }

    @Test fun `card order starts with score and metabolism without exposing code or invalid data`() {
        val values = listOf(metric("w", "2026-09-24", HealthMetricType.WEIGHT_KG, 70.0),
            metric("b", "2026-09-24", HealthMetricType.BASAL_METABOLIC_RATE_KCAL, 1828.0),
            metric("s", "2026-09-24", HealthMetricType.INBODY_SCORE, 80.0),
            metric("c", "2026-09-24", HealthMetricType.UPPER_BALANCE_CODE, 0.0))
        assertEquals(listOf(HealthMetricType.INBODY_SCORE, HealthMetricType.BASAL_METABOLIC_RATE_KCAL, HealthMetricType.WEIGHT_KG),
            bodyHistoryTypes(bodyHistoryMeasurements(values)))
    }

    @Test fun `CSV callout retains original local seconds while manual input shows no fake time`() {
        val value = metric("csv", "2026-09-24", HealthMetricType.WEIGHT_KG, 70.2)
        val original = com.hanshin.healthtask.data.db.InBodyRecordEntity("csv", "2026-09-24T09:27:30", 0,
            "Asia/Seoul", "TEST", "test.csv", "{}", 0, 0)
        assertEquals("26.09.24 09:27:30", bodyHistoryDateLabel(value, original, java.time.ZoneId.of("America/New_York")))
        assertEquals("26.09.24", bodyHistoryDateLabel(value, null))
    }

    @Test fun `series is chronological and retains all measurements without a twelve point cap`() {
        val values = (1..14).map { day ->
            metric(
                id = "muscle-$day",
                date = "2026-08-${day.toString().padStart(2, '0')}",
                type = HealthMetricType.SKELETAL_MUSCLE_KG,
                value = 30.0 + day,
            )
        }.reversed() + metric(
            id = "score",
            date = "2026-08-14",
            type = HealthMetricType.INBODY_SCORE,
            value = 80.0,
        )

        val series = healthTrendSeries(values, HealthMetricType.SKELETAL_MUSCLE_KG)

        assertEquals(14, series.size)
        assertEquals("2026-08-01", series.first().recordDate)
        assertEquals("2026-08-14", series.last().recordDate)
    }

    @Test fun `calendar spacing follows elapsed days and a lone sample stays centered`() {
        val values = listOf("2026-08-01", "2026-08-02", "2026-08-11").mapIndexed { index, day ->
            metric("$index", day, HealthMetricType.WEIGHT_KG, 70.0)
        }
        assertEquals(listOf(0f, 0.1f, 1f), healthTrendXPositions(values))
        assertEquals(listOf(0.5f), healthTrendXPositions(values.take(1)))
        assertTrue(healthTrendXPositions(emptyList()).isEmpty())
    }

    @Test fun `periods are anchored to latest measurement and respect calendar month boundaries`() {
        val values = listOf("2026-08-24", "2026-08-25", "2026-09-24").mapIndexed { index, day ->
            metric("$index", day, HealthMetricType.WEIGHT_KG, 70.0)
        }
        val series = healthTrendSeries(values, HealthMetricType.WEIGHT_KG, period = HealthTrendPeriod.MONTH)
        assertEquals(listOf("2026-08-25", "2026-09-24"), series.map { it.recordDate })
        assertEquals(java.time.LocalDate.of(2026, 6, 25), HealthTrendPeriod.QUARTER.startDate(java.time.LocalDate.of(2026, 9, 24)))
        assertEquals(java.time.LocalDate.of(2026, 3, 25), HealthTrendPeriod.HALF_YEAR.startDate(java.time.LocalDate.of(2026, 9, 24)))
    }

    @Test fun `old metric is empty within recent window rather than silently using an older sample`() {
        val values = listOf(metric("old", "2026-06-01", HealthMetricType.SKELETAL_MUSCLE_KG, 30.0),
            metric("new", "2026-09-24", HealthMetricType.WEIGHT_KG, 70.0))
        assertTrue(healthTrendSeries(values, HealthMetricType.SKELETAL_MUSCLE_KG, period = HealthTrendPeriod.MONTH).isEmpty())
        assertEquals(1, healthTrendSeries(values, HealthMetricType.SKELETAL_MUSCLE_KG).size)
    }

    @Test fun `invalid dates nonfinite values and balance codes cannot appear in graphs`() {
        val values = listOf(
            metric("bad-date", "bad-date", HealthMetricType.WEIGHT_KG, 70.0).copy(measuredAt = 0),
            metric("nan", "2026-09-01", HealthMetricType.WEIGHT_KG, Double.NaN),
            metric("infinity", "2026-09-01", HealthMetricType.WEIGHT_KG, Double.POSITIVE_INFINITY),
            metric("negative", "2026-09-01", HealthMetricType.WEIGHT_KG, -1.0),
            metric("code", "2026-09-24", HealthMetricType.UPPER_BALANCE_CODE, 0.0),
            metric("valid", "2026-09-01", HealthMetricType.WEIGHT_KG, 70.0),
        )
        assertEquals(listOf(HealthMetricType.WEIGHT_KG), availableHealthTrendTypes(values))
        assertEquals(listOf("valid"), healthTrendSeries(values, HealthMetricType.WEIGHT_KG).map { it.id })
        assertEquals(java.time.LocalDate.of(2026, 9, 1), healthTrendReferenceDate(values))
    }

    @Test fun `ratios and real zero retain useful finite bounds without negative scales`() {
        val ratio = listOf(metric("r", "2026-09-24", HealthMetricType.ECW_RATIO, 0.371))
        val bounds = healthTrendBounds(ratio, HealthMetricType.ECW_RATIO)
        assertTrue(bounds.minimum < 0.371 && bounds.maximum > 0.371)
        assertTrue(bounds.range < 0.1)
        val zero = healthTrendBounds(listOf(metric("zero", "2026-09-24", HealthMetricType.BODY_FAT_MASS_KG, 0.0)), HealthMetricType.BODY_FAT_MASS_KG)
        assertEquals(0.0, zero.minimum, 0.0)
        assertTrue(zero.range > 0)
    }

    @Test fun `finite extreme source numbers cannot overflow chart bounds`() {
        val type = HealthMetricType.WEIGHT_KG
        val values = listOf(metric("extreme", "2026-09-24", type, Double.MAX_VALUE))
        val bounds = healthTrendBounds(values, type)
        assertTrue(bounds.minimum.isFinite() && bounds.maximum.isFinite() && bounds.range > 0)
    }

    @Test fun `point selection uses date position and handles empty data`() {
        assertEquals(1, nearestHealthTrendPoint(listOf(0f, 0.1f, 1f), 0.09f))
        assertEquals(2, nearestHealthTrendPoint(listOf(0f, 0.1f, 1f), 0.8f))
        assertEquals(0, nearestHealthTrendPoint(listOf(0.5f), -1f))
        assertEquals(null, nearestHealthTrendPoint(emptyList(), 0.5f))
    }

    @Test fun `deltas avoid binary rounding tails and preserve ratio precision`() {
        assertEquals("+0.7", healthTrendDelta(32.4, 33.1))
        assertEquals("-0.001", healthTrendDelta(0.372, 0.371))
        assertEquals("0", healthTrendDelta(70.2, 70.2))
    }

    @Test fun `same day representative prefers InBody and keeps the most recent measurement`() {
        val type = HealthMetricType.WEIGHT_KG
        val first = metric("first", "2026-09-24", type, 70.0).copy(measuredAt = 100, sourcePackage = com.hanshin.healthtask.domain.INBODY_PACKAGE)
        val last = first.copy(id = "last", value = 70.2, measuredAt = 200)
        val manual = first.copy(id = "manual", value = 80.0, measuredAt = 300, sourcePackage = null)
        assertEquals(listOf(last), effectiveHealthMeasurements(listOf(first, last, manual)))
    }

    @Test fun `bounds always contain values and preserve a useful visual span`() {
        val values = listOf(
            metric("a", "2026-08-01", HealthMetricType.VISCERAL_FAT_LEVEL, 8.0),
            metric("b", "2026-08-02", HealthMetricType.VISCERAL_FAT_LEVEL, 8.5),
        )

        val bounds = healthTrendBounds(values, HealthMetricType.VISCERAL_FAT_LEVEL)

        assertTrue(bounds.minimum <= 8.0)
        assertTrue(bounds.maximum >= 8.5)
        assertTrue(bounds.range >= 4.0)
    }

    @Test fun `available metric order prioritizes imported InBody values`() {
        val values = listOf(
            metric("weight", "2026-08-01", HealthMetricType.WEIGHT_KG, 80.0),
            metric("fat-mass", "2026-08-01", HealthMetricType.BODY_FAT_MASS_KG, 18.2),
            metric("score", "2026-08-01", HealthMetricType.INBODY_SCORE, 77.0),
            metric("muscle", "2026-08-01", HealthMetricType.SKELETAL_MUSCLE_KG, 38.1),
        )

        assertEquals(
            listOf(
                HealthMetricType.SKELETAL_MUSCLE_KG,
                HealthMetricType.BODY_FAT_MASS_KG,
                HealthMetricType.INBODY_SCORE,
                HealthMetricType.WEIGHT_KG,
            ),
            availableHealthTrendTypes(values),
        )
    }

    private fun metric(id: String, date: String, type: HealthMetricType, value: Double) =
        HealthMeasurementEntity(
            id = id,
            recordDate = date,
            measuredAt = date.takeLast(2).toLongOrNull() ?: 0,
            type = type,
            value = value,
            source = WorkoutSource.LOCAL,
        )
}
