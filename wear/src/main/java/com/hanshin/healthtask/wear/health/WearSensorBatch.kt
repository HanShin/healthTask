package com.hanshin.healthtask.wear.health

import androidx.health.services.client.data.AggregateDataType
import androidx.health.services.client.data.CumulativeDataPoint
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.SampleDataPoint
import com.hanshin.healthtask.shared.RunSensorRecord

internal val requestedRunSensors: Map<DataType<*, *>, String> = linkedMapOf(
    DataType.HEART_RATE_BPM to "heartRateBpm",
    DataType.HEART_RATE_BPM_STATS to "heartRateStatistics",
    DataType.SPEED to "speedMetersPerSecond",
    DataType.DISTANCE_TOTAL to "distanceMeters",
    DataType.CALORIES_TOTAL to "caloriesKcal",
    DataType.STEPS_TOTAL to "stepsTotal",
    DataType.STEPS_PER_MINUTE to "cadenceStepsPerMinute",
    DataType.ABSOLUTE_ELEVATION to "altitudeMeters",
    DataType.ELEVATION_GAIN_TOTAL to "elevationGainMeters",
    DataType.ELEVATION_LOSS_TOTAL to "elevationLossMeters",
)

/** Process EVERY point in a batched update, using sample time, never callback arrival time. */
internal fun ExerciseUpdate.sensorRecords(bootEpochMillis: Long): List<RunSensorRecord> = buildList {
    activeDurationCheckpoint?.let { checkpoint ->
        add(RunSensorRecord("checkpoint", checkpoint.time.toEpochMilli(),
            activeElapsedMillis = checkpoint.activeDuration.toMillis(), metric = "activeDurationMillis",
            value = checkpoint.activeDuration.toMillis().toDouble()))
    }
    fun <T : Number> samples(type: DeltaDataType<T, SampleDataPoint<T>>, metric: String) {
        latestMetrics.getData(type).forEach { point ->
            val value = point.value.toDouble()
            if (value.isFinite()) add(RunSensorRecord(
                kind = "sample", timestampEpochMillis = bootEpochMillis + point.timeDurationFromBoot.toMillis(),
                elapsedRealtimeMillis = point.timeDurationFromBoot.toMillis(),
                activeElapsedMillis = runCatching { getActiveDurationAtDataPoint(point).toMillis() }.getOrNull(),
                metric = metric, value = value, accuracy = point.accuracy?.toString(),
            ))
        }
    }
    fun <T : Number> cumulative(type: AggregateDataType<T, CumulativeDataPoint<T>>, metric: String) {
        latestMetrics.getData(type)?.let { point ->
            if (point.total.toDouble().isFinite()) add(RunSensorRecord(
                kind = "sample", timestampEpochMillis = point.end.toEpochMilli(),
                metric = metric, value = point.total.toDouble(),
                activeElapsedMillis = activeDurationCheckpoint?.activeDuration?.toMillis(),
            ))
        }
    }
    samples(DataType.HEART_RATE_BPM, "heartRateBpm")
    samples(DataType.SPEED, "speedMetersPerSecond")
    samples(DataType.STEPS_PER_MINUTE, "cadenceStepsPerMinute")
    samples(DataType.ABSOLUTE_ELEVATION, "altitudeMeters")
    cumulative(DataType.DISTANCE_TOTAL, "distanceMeters")
    cumulative(DataType.CALORIES_TOTAL, "caloriesKcal")
    cumulative(DataType.STEPS_TOTAL, "stepsTotal")
    cumulative(DataType.ELEVATION_GAIN_TOTAL, "elevationGainMeters")
    cumulative(DataType.ELEVATION_LOSS_TOTAL, "elevationLossMeters")
}

internal fun ExerciseLapSummary.sensorRecords(): List<RunSensorRecord> = buildList {
    fun metric(name: String, value: Double?) {
        if (value?.isFinite() == true) add(RunSensorRecord("lapSummary", endTime.toEpochMilli(),
            metric = name, value = value, intervalStartEpochMillis = startTime.toEpochMilli(), lapIndex = lapCount))
    }
    metric("activeDurationMillis", activeDuration.toMillis().toDouble())
    metric("distanceMeters", lapMetrics.getData(DataType.DISTANCE_TOTAL)?.total)
    metric("caloriesKcal", lapMetrics.getData(DataType.CALORIES_TOTAL)?.total)
    metric("stepsTotal", lapMetrics.getData(DataType.STEPS_TOTAL)?.total?.toDouble())
    metric("averageHeartRateBpm", lapMetrics.getData(DataType.HEART_RATE_BPM_STATS)?.average)
    metric("minHeartRateBpm", lapMetrics.getData(DataType.HEART_RATE_BPM_STATS)?.min)
    metric("maxHeartRateBpm", lapMetrics.getData(DataType.HEART_RATE_BPM_STATS)?.max)
}
