package com.hanshin.healthtask.ui

import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.WorkoutSource
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthMergeTest {
    @Test fun `Samsung weight and body fat win while local-only metrics remain local`() {
        val date = "2026-08-21"
        val values = listOf(
            metric("local-weight", date, HealthMetricType.WEIGHT_KG, 80.0, WorkoutSource.LOCAL),
            metric("samsung-weight", date, HealthMetricType.WEIGHT_KG, 79.2, WorkoutSource.SAMSUNG_HEALTH),
            metric("local-muscle", date, HealthMetricType.SKELETAL_MUSCLE_KG, 35.0, WorkoutSource.LOCAL),
        )
        val result = effectiveHealthMeasurements(values).associateBy { it.type }
        assertEquals(79.2, result[HealthMetricType.WEIGHT_KG]!!.value, .001)
        assertEquals(WorkoutSource.SAMSUNG_HEALTH, result[HealthMetricType.WEIGHT_KG]!!.source)
        assertEquals(35.0, result[HealthMetricType.SKELETAL_MUSCLE_KG]!!.value, .001)
    }

    private fun metric(id: String, date: String, type: HealthMetricType, value: Double, source: WorkoutSource) =
        HealthMeasurementEntity(id, date, 1L, type, value, source)
}
