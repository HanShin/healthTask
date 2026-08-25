package com.hanshin.healthtask.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.PlannedWorkoutType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.TrainingGoalType
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: String = "local-profile",
    val workoutsPerWeek: Int = 3,
    val onboardingDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "exercises", indices = [Index("name"), Index("category")])
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: ExerciseCategory,
    val recordMode: RecordMode,
    val muscleGroup: String? = null,
    val equipment: String? = null,
    val guideHeadline: String? = null,
    val guideCues: String? = null,
    val guideWarning: String? = null,
    val guideVideoUrl: String? = null,
    val guideAssetPath: String? = null,
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "routines", indices = [Index("isActive"), Index("createdAt")])
data class RoutineEntity(
    @PrimaryKey val id: String,
    val name: String,
    val source: String = "manual",
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "routine_items", indices = [Index("routineId"), Index("exerciseId")])
data class RoutineItemEntity(
    @PrimaryKey val id: String,
    val routineId: String,
    val exerciseId: String,
    val orderIndex: Int,
    val category: ExerciseCategory,
    val recordMode: RecordMode,
    val setCount: Int? = null,
    val targetReps: Int? = null,
    val restSeconds: Int? = null,
    val targetWeightKg: Double? = null,
    val targetActivityLabel: String? = null,
    val targetDistanceKm: Double? = null,
    val targetDurationMin: Double? = null,
    val targetPaceMinPerKm: Double? = null,
    val note: String? = null,
)

@Entity(tableName = "training_plans", indices = [Index("isActive")])
data class TrainingPlanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val goalType: TrainingGoalType,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "plan_slots", indices = [Index("planId"), Index("routineId")])
data class PlanSlotEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val orderIndex: Int,
    val workoutType: PlannedWorkoutType,
    val routineId: String? = null,
    val title: String,
    val preferredDayOfWeek: Int? = null,
    val targetDurationMin: Double? = null,
    val targetDistanceKm: Double? = null,
    val targetPaceMinPerKm: Double? = null,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "workout_sessions",
    indices = [Index("sessionDate"), Index("routineId"), Index("planSlotId"), Index("source"), Index(value = ["healthConnectRecordId"], unique = true)]
)
data class WorkoutSessionEntity(
    @PrimaryKey val id: String,
    val routineId: String? = null,
    val planSlotId: String? = null,
    val title: String,
    val sessionDate: String,
    val status: WorkoutStatus,
    val source: WorkoutSource,
    val startedAt: Long,
    val endedAt: Long? = null,
    val memo: String? = null,
    val healthConnectRecordId: String? = null,
    val sourcePackage: String? = null,
    val distanceKm: Double? = null,
    val caloriesKcal: Double? = null,
    val averageHeartRateBpm: Double? = null,
    val routePolyline: String? = null,
    val lapData: String? = null,
    val activeDurationMillis: Long? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING,
    val syncError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "workout_items", indices = [Index("sessionId"), Index("exerciseId")])
data class WorkoutItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val exerciseId: String,
    val exerciseName: String,
    val orderIndex: Int,
    val category: ExerciseCategory,
    val recordMode: RecordMode,
    val activityLabel: String? = null,
    val distanceKm: Double? = null,
    val durationMin: Double? = null,
    val avgPaceMinPerKm: Double? = null,
    val note: String? = null,
)

@Entity(tableName = "set_records", indices = [Index("workoutItemId")])
data class SetRecordEntity(
    @PrimaryKey val id: String,
    val workoutItemId: String,
    val orderIndex: Int,
    val plannedReps: Int? = null,
    val actualReps: Int? = null,
    val plannedWeightKg: Double? = null,
    val actualWeightKg: Double? = null,
    val completed: Boolean = false,
)

@Entity(
    tableName = "health_measurements",
    indices = [Index("recordDate"), Index("type"), Index(value = ["externalRecordId", "type"], unique = true)]
)
data class HealthMeasurementEntity(
    @PrimaryKey val id: String,
    val recordDate: String,
    val measuredAt: Long,
    val type: HealthMetricType,
    val value: Double,
    val source: WorkoutSource,
    val externalRecordId: String? = null,
    val sourcePackage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "samsung_workout_links",
    indices = [Index(value = ["localSessionId"], unique = true), Index(value = ["samsungSessionId"], unique = true)]
)
data class SamsungWorkoutLinkEntity(
    @PrimaryKey val id: String,
    val localSessionId: String,
    val samsungSessionId: String,
    val overlapRatio: Double,
    val linkedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class RoutineWithItems(
    @Embedded val routine: RoutineEntity,
    @Relation(parentColumn = "id", entityColumn = "routineId") val items: List<RoutineItemEntity>,
)

data class TrainingPlanWithSlots(
    @Embedded val plan: TrainingPlanEntity,
    @Relation(parentColumn = "id", entityColumn = "planId") val slots: List<PlanSlotEntity>,
)

data class WorkoutItemWithSets(
    @Embedded val item: WorkoutItemEntity,
    @Relation(parentColumn = "id", entityColumn = "workoutItemId") val sets: List<SetRecordEntity>,
)

data class WorkoutSessionWithItems(
    @Embedded val session: WorkoutSessionEntity,
    @Relation(entity = WorkoutItemEntity::class, parentColumn = "id", entityColumn = "sessionId")
    val items: List<WorkoutItemWithSets>,
)
