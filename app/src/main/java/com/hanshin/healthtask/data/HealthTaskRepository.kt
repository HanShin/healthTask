package com.hanshin.healthtask.data

import androidx.room.withTransaction
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.ProfileEntity
import com.hanshin.healthtask.data.db.RoutineEntity
import com.hanshin.healthtask.data.db.RoutineItemEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus
import com.hanshin.healthtask.domain.latestCompletedSet
import com.hanshin.healthtask.domain.workoutStatus
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearRecordMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class HealthTaskRepository(private val database: HealthTaskDatabase) {
    val dao = database.dao()
    val profile: Flow<ProfileEntity?> = dao.observeProfile()
    val exercises: Flow<List<ExerciseEntity>> = dao.observeExercises()
    val routines: Flow<List<RoutineWithItems>> = dao.observeRoutines()
    val sessions: Flow<List<WorkoutSessionWithItems>> = dao.observeSessions()
    val healthMeasurements: Flow<List<HealthMeasurementEntity>> = dao.observeHealthMeasurements()
    val workoutLinks = dao.observeWorkoutLinks()

    suspend fun initialize() {
        if (dao.getExercises().isEmpty()) dao.upsertExercises(SeedData.exercises)
    }

    suspend fun finishOnboarding(workoutsPerWeek: Int, installTemplates: Boolean) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            dao.upsertProfile(ProfileEntity(workoutsPerWeek = workoutsPerWeek.coerceIn(1, 7), onboardingDone = true, createdAt = now, updatedAt = now))
            if (installTemplates && dao.getRoutines().isEmpty()) {
                SeedData.templates.take(workoutsPerWeek.coerceAtMost(SeedData.templates.size)).forEachIndexed { index, template ->
                    val id = "routine-${UUID.randomUUID()}"
                    dao.upsertRoutine(template.routine.copy(id = id, createdAt = now + index, updatedAt = now + index))
                    dao.upsertRoutineItems(template.items.map { it.copy(id = "plan-${UUID.randomUUID()}", routineId = id) })
                }
            }
        }
    }

    suspend fun saveRoutine(routineId: String?, name: String, exerciseIds: List<String>) {
        require(name.isNotBlank()) { "루틴 이름을 입력해 주세요." }
        require(exerciseIds.isNotEmpty()) { "운동을 한 개 이상 추가해 주세요." }
        val id = routineId ?: "routine-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val existing = routineId?.let { dao.getRoutine(it) }
        val exerciseMap = dao.getExercises().associateBy { it.id }
        database.withTransaction {
            dao.upsertRoutine(
                RoutineEntity(
                    id = id,
                    name = name.trim(),
                    source = existing?.routine?.source ?: "manual",
                    isActive = existing?.routine?.isActive ?: true,
                    createdAt = existing?.routine?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            dao.deleteRoutineItems(id)
            dao.upsertRoutineItems(exerciseIds.mapIndexed { index, exerciseId ->
                val exercise = requireNotNull(exerciseMap[exerciseId])
                RoutineItemEntity(
                    id = "plan-${UUID.randomUUID()}",
                    routineId = id,
                    exerciseId = exerciseId,
                    orderIndex = index + 1,
                    category = exercise.category,
                    recordMode = exercise.recordMode,
                    setCount = if (exercise.recordMode == RecordMode.SETS) 3 else null,
                    targetReps = if (exercise.recordMode == RecordMode.SETS) 10 else null,
                    restSeconds = if (exercise.recordMode == RecordMode.SETS) 60 else null,
                    targetWeightKg = if (exercise.category == ExerciseCategory.WEIGHT) 10.0 else null,
                    targetActivityLabel = if (exercise.recordMode == RecordMode.CARDIO) "유산소" else null,
                    targetDurationMin = if (exercise.recordMode == RecordMode.CARDIO) 20.0 else null,
                )
            })
        }
    }

    suspend fun installTemplate(templateId: String) {
        val template = requireNotNull(SeedData.templates.find { it.routine.id == templateId })
        val now = System.currentTimeMillis()
        val id = "routine-${UUID.randomUUID()}"
        database.withTransaction {
            dao.upsertRoutine(template.routine.copy(id = id, createdAt = now, updatedAt = now))
            dao.upsertRoutineItems(template.items.map { it.copy(id = "plan-${UUID.randomUUID()}", routineId = id) })
        }
    }

    suspend fun deleteRoutine(id: String) = database.withTransaction {
        dao.deleteRoutineItems(id)
        dao.deleteRoutine(id)
    }

    suspend fun startSession(routineId: String): String {
        val routine = requireNotNull(dao.getRoutine(routineId)) { "루틴을 찾을 수 없습니다." }
        val exercises = dao.getExercises().associateBy { it.id }
        val sessionId = "session-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val previous = dao.getSessions()
        database.withTransaction {
            dao.upsertSession(
                WorkoutSessionEntity(
                    id = sessionId,
                    routineId = routineId,
                    title = routine.routine.name,
                    sessionDate = LocalDate.now().toString(),
                    status = WorkoutStatus.ACTIVE,
                    source = WorkoutSource.LOCAL,
                    startedAt = now,
                    syncStatus = SyncStatus.PENDING,
                )
            )
            routine.items.sortedBy { it.orderIndex }.forEach { plan ->
                val exercise = exercises[plan.exerciseId] ?: return@forEach
                val itemId = "item-${UUID.randomUUID()}"
                dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
                    id = itemId,
                    sessionId = sessionId,
                    exerciseId = plan.exerciseId,
                    exerciseName = exercise.name,
                    orderIndex = plan.orderIndex,
                    category = plan.category,
                    recordMode = plan.recordMode,
                    activityLabel = plan.targetActivityLabel,
                    distanceKm = plan.targetDistanceKm,
                    durationMin = plan.targetDurationMin,
                    avgPaceMinPerKm = plan.targetPaceMinPerKm,
                    note = plan.note,
                )))
                if (plan.recordMode == RecordMode.SETS) {
                    val carry = latestCompletedSet(previous, plan.exerciseId)
                    dao.upsertSetRecords((1..(plan.setCount ?: 3)).map { order ->
                        SetRecordEntity(
                            id = "set-${UUID.randomUUID()}",
                            workoutItemId = itemId,
                            orderIndex = order,
                            plannedReps = plan.targetReps,
                            actualReps = carry?.actualReps ?: plan.targetReps,
                            plannedWeightKg = plan.targetWeightKg,
                            actualWeightKg = carry?.actualWeightKg ?: plan.targetWeightKg,
                        )
                    })
                }
            }
        }
        return sessionId
    }

    suspend fun updateSet(set: SetRecordEntity) = dao.upsertSetRecords(listOf(set))
    suspend fun updateWorkoutItem(item: WorkoutItemEntity) = dao.upsertWorkoutItems(listOf(item))

    suspend fun finishSession(sessionId: String): WorkoutSessionEntity {
        val full = requireNotNull(dao.getSession(sessionId))
        val status = workoutStatus(full.items)
        val updated = full.session.copy(
            status = status,
            endedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING,
            updatedAt = System.currentTimeMillis(),
        )
        dao.updateSession(updated)
        return updated
    }

    suspend fun deleteSession(id: String) = database.withTransaction {
        dao.deleteSetsForSession(id)
        dao.deleteItemsForSession(id)
        dao.deleteSession(id)
    }

    suspend fun saveManualHealthMetric(date: LocalDate, type: HealthMetricType, value: Double) {
        require(value > 0.0) { "0보다 큰 값을 입력해 주세요." }
        val now = System.currentTimeMillis()
        dao.upsertHealthMeasurements(listOf(HealthMeasurementEntity(
            id = "health-local-${date}-$type",
            recordDate = date.toString(),
            measuredAt = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            type = type,
            value = value,
            source = WorkoutSource.LOCAL,
            updatedAt = now,
        )))
    }

    suspend fun updateProfileGoal(workoutsPerWeek: Int) {
        val current = dao.getProfile() ?: ProfileEntity(onboardingDone = true)
        dao.upsertProfile(current.copy(
            workoutsPerWeek = workoutsPerWeek.coerceIn(1, 7),
            updatedAt = System.currentTimeMillis(),
        ))
    }

    suspend fun getSession(id: String) = dao.getSession(id)
    suspend fun getRoutine(id: String) = dao.getRoutine(id)
    suspend fun getAllSessions() = dao.getSessions()
    suspend fun getLinks() = dao.getWorkoutLinks()

    /** Imports a completed watch workout exactly once, even if the Data Layer retries delivery. */
    suspend fun importWearWorkout(workout: WearCompletedWorkout): Boolean {
        if (dao.getSession(workout.sessionId) != null) return false
        require(workout.endedAt >= workout.startedAt) { "워치 운동 시간이 올바르지 않습니다." }
        val sessionDate = Instant.ofEpochMilli(workout.startedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val completedItems = workout.exercises.count { exercise ->
            when (exercise.recordMode) {
                WearRecordMode.SETS -> exercise.sets.isNotEmpty() && exercise.sets.all { it.completed }
                WearRecordMode.CARDIO -> (exercise.durationMin ?: 0.0) > 0.0
            }
        }
        val status = when {
            completedItems == 0 -> WorkoutStatus.SKIPPED
            completedItems == workout.exercises.size -> WorkoutStatus.COMPLETED
            else -> WorkoutStatus.PARTIAL
        }
        var imported = false
        database.withTransaction {
            if (dao.getSession(workout.sessionId) != null) return@withTransaction
            imported = true
            dao.upsertSession(WorkoutSessionEntity(
                id = workout.sessionId,
                routineId = workout.routineId,
                title = workout.title,
                sessionDate = sessionDate,
                status = status,
                source = WorkoutSource.LOCAL,
                startedAt = workout.startedAt,
                endedAt = workout.endedAt,
                memo = "Galaxy Watch에서 기록",
                distanceKm = workout.distanceKm,
                caloriesKcal = workout.caloriesKcal,
                averageHeartRateBpm = workout.averageHeartRateBpm,
                syncStatus = SyncStatus.PENDING,
            ))
            workout.exercises.sortedBy { it.order }.forEach { exercise ->
                val itemId = "${workout.sessionId}-item-${exercise.order}"
                val recordMode = if (exercise.recordMode == WearRecordMode.CARDIO) RecordMode.CARDIO else RecordMode.SETS
                val category = runCatching { ExerciseCategory.valueOf(exercise.category) }
                    .getOrDefault(if (recordMode == RecordMode.CARDIO) ExerciseCategory.CARDIO else ExerciseCategory.WEIGHT)
                dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
                    id = itemId,
                    sessionId = workout.sessionId,
                    exerciseId = exercise.exerciseId,
                    exerciseName = exercise.name,
                    orderIndex = exercise.order,
                    category = category,
                    recordMode = recordMode,
                    distanceKm = exercise.distanceKm,
                    durationMin = exercise.durationMin,
                    avgPaceMinPerKm = if ((exercise.distanceKm ?: 0.0) > 0.0) {
                        exercise.durationMin?.div(exercise.distanceKm!!)
                    } else null,
                )))
                if (recordMode == RecordMode.SETS) {
                    dao.upsertSetRecords(exercise.sets.map { set ->
                        SetRecordEntity(
                            id = "$itemId-set-${set.order}",
                            workoutItemId = itemId,
                            orderIndex = set.order,
                            plannedReps = set.reps,
                            actualReps = set.reps,
                            plannedWeightKg = set.weightKg,
                            actualWeightKg = set.weightKg,
                            completed = set.completed,
                        )
                    })
                }
            }
        }
        return imported
    }
}
