package com.hanshin.healthtask.ui

import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.WorkoutSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthTrendsTest {
    @Test fun `series is chronological and keeps the most recent twelve measurements`() {
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

        assertEquals(12, series.size)
        assertEquals("2026-08-03", series.first().recordDate)
        assertEquals("2026-08-14", series.last().recordDate)
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
            measuredAt = date.takeLast(2).toLong(),
            type = type,
            value = value,
            source = WorkoutSource.LOCAL,
        )
}
