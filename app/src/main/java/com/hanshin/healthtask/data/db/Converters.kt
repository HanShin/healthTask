package com.hanshin.healthtask.data.db

import androidx.room.TypeConverter
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus

class Converters {
    @TypeConverter fun workoutSource(value: WorkoutSource): String = value.name
    @TypeConverter fun workoutSource(value: String): WorkoutSource = WorkoutSource.valueOf(value)
    @TypeConverter fun syncStatus(value: SyncStatus): String = value.name
    @TypeConverter fun syncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
    @TypeConverter fun category(value: ExerciseCategory): String = value.name
    @TypeConverter fun category(value: String): ExerciseCategory = ExerciseCategory.valueOf(value)
    @TypeConverter fun recordMode(value: RecordMode): String = value.name
    @TypeConverter fun recordMode(value: String): RecordMode = RecordMode.valueOf(value)
    @TypeConverter fun workoutStatus(value: WorkoutStatus): String = value.name
    @TypeConverter fun workoutStatus(value: String): WorkoutStatus = WorkoutStatus.valueOf(value)
    @TypeConverter fun healthMetric(value: HealthMetricType): String = value.name
    @TypeConverter fun healthMetric(value: String): HealthMetricType = HealthMetricType.valueOf(value)
}
