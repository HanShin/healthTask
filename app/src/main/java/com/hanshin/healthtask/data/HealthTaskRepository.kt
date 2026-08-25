package com.hanshin.healthtask.data

import androidx.room.withTransaction
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.HealthMeasurementEntity
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.data.db.ProfileEntity
import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.RoutineEntity
import com.hanshin.healthtask.data.db.RoutineItemEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.TrainingPlanEntity
import com.hanshin.healthtask.data.db.TrainingPlanWithSlots
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.HealthMetricType
import com.hanshin.healthtask.domain.INBODY_PACKAGE
import com.hanshin.healthtask.domain.PlannedWorkoutType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.SyncStatus
import com.hanshin.healthtask.domain.TrainingGoalType
import com.hanshin.healthtask.domain.WorkoutSource
import com.hanshin.healthtask.domain.WorkoutStatus
import com.hanshin.healthtask.domain.latestCompletedSet
import com.hanshin.healthtask.domain.isRun
import com.hanshin.healthtask.domain.workoutStatus
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
import com.hanshin.healthtask.shared.TABATA_REST_SECONDS
import com.hanshin.healthtask.shared.TABATA_ROUNDS
import com.hanshin.healthtask.shared.TABATA_TOTAL_SECONDS
import com.hanshin.healthtask.shared.TABATA_WORK_SECONDS
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
    val trainingPlans: Flow<List<TrainingPlanWithSlots>> = dao.observeTrainingPlans()
    val sessions: Flow<List<WorkoutSessionWithItems>> = dao.observeSessions()
    val healthMeasurements: Flow<List<HealthMeasurementEntity>> = dao.observeHealthMeasurements()
    val workoutLinks = dao.observeWorkoutLinks()

    suspend fun initialize() {
        val existingExerciseIds = dao.getExercises().mapTo(mutableSetOf()) { it.id }
        val missingExercises = SeedData.exercises.filterNot { it.id in existingExerciseIds }
        if (missingExercises.isNotEmpty()) dao.upsertExercises(missingExercises)
        val profile = dao.getProfile()
        if (profile?.onboardingDone == true && dao.getTrainingPlans().none { it.plan.isActive }) {
            rebuildTrainingPlan(TrainingGoalType.BALANCED)
        }
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
            replaceActiveTrainingPlan(
                goalType = TrainingGoalType.BALANCED,
                weeklyGoal = workoutsPerWeek.coerceIn(1, 7),
                routines = dao.getRoutines(),
            )
        }
    }

    suspend fun saveRoutine(routineId: String?, name: String, exerciseIds: List<String>) {
        require(name.isNotBlank()) { "루틴 이름을 입력해 주세요." }
        require(exerciseIds.isNotEmpty()) { "운동을 한 개 이상 추가해 주세요." }
        val id = routineId ?: "routine-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val existing = routineId?.let { dao.getRoutine(it) }
        val exerciseMap = dao.getExercises().associateBy { it.id }
        val orderedExerciseIds = exerciseIds.filterNot { it == TABATA_EXERCISE_ID } +
            exerciseIds.filter { it == TABATA_EXERCISE_ID }
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
            dao.upsertRoutineItems(orderedExerciseIds.mapIndexed { index, exerciseId ->
                val exercise = requireNotNull(exerciseMap[exerciseId])
                val isTabata = exerciseId == TABATA_EXERCISE_ID
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
                    targetActivityLabel = when {
                        isTabata -> "${TABATA_WORK_SECONDS}초 운동 · ${TABATA_REST_SECONDS}초 휴식 × $TABATA_ROUNDS"
                        exercise.recordMode == RecordMode.CARDIO -> "유산소"
                        else -> null
                    },
                    targetDurationMin = when {
                        isTabata -> TABATA_TOTAL_SECONDS / 60.0
                        exercise.recordMode == RecordMode.CARDIO -> 20.0
                        else -> null
                    },
                )
            })
            fillUnassignedStrengthSlots(id, name.trim())
        }
    }

    suspend fun installTemplate(templateId: String) {
        val template = requireNotNull(SeedData.templates.find { it.routine.id == templateId })
        val now = System.currentTimeMillis()
        val id = "routine-${UUID.randomUUID()}"
        database.withTransaction {
            dao.upsertRoutine(template.routine.copy(id = id, createdAt = now, updatedAt = now))
            dao.upsertRoutineItems(template.items.map { it.copy(id = "plan-${UUID.randomUUID()}", routineId = id) })
            fillUnassignedStrengthSlots(id, template.routine.name)
        }
    }

    suspend fun deleteRoutine(id: String) = database.withTransaction {
        dao.detachRoutineFromPlanSlots(id)
        dao.deleteRoutineItems(id)
        dao.deleteRoutine(id)
    }

    suspend fun rebuildTrainingPlan(goalType: TrainingGoalType) {
        val weeklyGoal = dao.getProfile()?.workoutsPerWeek ?: 3
        database.withTransaction {
            replaceActiveTrainingPlan(goalType, weeklyGoal, dao.getRoutines())
        }
    }

    suspend fun startSession(routineId: String, planSlotId: String? = null): String {
        val routine = requireNotNull(dao.getRoutine(routineId)) { "루틴을 찾을 수 없습니다." }
        val planSlot = planSlotId?.let { requireNotNull(dao.getPlanSlot(it)) { "계획 운동을 찾을 수 없습니다." } }
        require(planSlot == null || planSlot.workoutType == PlannedWorkoutType.STRENGTH) {
            "근력 계획만 루틴으로 시작할 수 있습니다."
        }
        val exercises = dao.getExercises().associateBy { it.id }
        val sessionId = "session-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val previous = dao.getSessions()
        database.withTransaction {
            dao.upsertSession(
                WorkoutSessionEntity(
                    id = sessionId,
                    routineId = routineId,
                    planSlotId = planSlotId,
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
                    durationMin = plan.targetDurationMin.takeUnless { plan.exerciseId == TABATA_EXERCISE_ID },
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

    suspend fun savePhoneRun(
        startedAt: Long,
        endedAt: Long,
        elapsedMillis: Long,
        distanceMeters: Double,
        routePolyline: String?,
        lapData: String?,
        planSlotId: String? = null,
    ): WorkoutSessionEntity {
        require(endedAt >= startedAt) { "러닝 시간이 올바르지 않습니다." }
        require(elapsedMillis >= 1_000L) { "1초 이상 기록한 뒤 완료해 주세요." }
        val sessionId = "run-${UUID.randomUUID()}"
        val distanceKm = distanceMeters.coerceAtLeast(0.0) / 1_000.0
        val durationMinutes = elapsedMillis / 60_000.0
        val sessionDate = Instant.ofEpochMilli(startedAt)
            .atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val planSlot = planSlotId?.let { requireNotNull(dao.getPlanSlot(it)) { "계획 러닝을 찾을 수 없습니다." } }
        require(planSlot == null || planSlot.workoutType.isRun) { "러닝 계획만 GPS 기록과 연결할 수 있습니다." }
        val exerciseId = when (planSlot?.workoutType) {
            PlannedWorkoutType.QUALITY_RUN -> "tempo-run"
            PlannedWorkoutType.LONG_RUN -> "long-run"
            else -> "easy-run"
        }
        val session = WorkoutSessionEntity(
            id = sessionId,
            planSlotId = planSlotId,
            title = planSlot?.title ?: "러닝",
            sessionDate = sessionDate,
            status = WorkoutStatus.COMPLETED,
            source = WorkoutSource.LOCAL,
            startedAt = startedAt,
            endedAt = endedAt,
            memo = "휴대폰 GPS로 기록",
            distanceKm = distanceKm,
            routePolyline = routePolyline,
            lapData = lapData,
            activeDurationMillis = elapsedMillis,
            syncStatus = SyncStatus.PENDING,
        )
        database.withTransaction {
            dao.upsertSession(session)
            dao.upsertWorkoutItems(listOf(WorkoutItemEntity(
                id = "$sessionId-running",
                sessionId = sessionId,
                exerciseId = exerciseId,
                exerciseName = planSlot?.title ?: "러닝",
                orderIndex = 1,
                category = ExerciseCategory.CARDIO,
                recordMode = RecordMode.CARDIO,
                activityLabel = planSlot?.title ?: "GPS 러닝",
                distanceKm = distanceKm,
                durationMin = durationMinutes,
                avgPaceMinPerKm = distanceKm.takeIf { it > 0.02 }?.let { durationMinutes / it },
            )))
        }
        return session
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

    suspend fun saveInBodyScreenshotMetrics(date: LocalDate, values: Map<HealthMetricType, Double>) {
        val supportedTypes = setOf(
            HealthMetricType.SKELETAL_MUSCLE_KG,
            HealthMetricType.VISCERAL_FAT_LEVEL,
            HealthMetricType.INBODY_SCORE,
        )
        val accepted = values.filterKeys { it in supportedTypes }.filterValues { it > 0.0 }
        require(accepted.isNotEmpty()) { "저장할 인바디 수치가 없습니다." }
        val now = System.currentTimeMillis()
        val measuredAt = if (date == LocalDate.now()) {
            now
        } else {
            date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        dao.upsertHealthMeasurements(accepted.map { (type, value) ->
            HealthMeasurementEntity(
                id = "health-inbody-${date}-$type",
                recordDate = date.toString(),
                measuredAt = measuredAt,
                type = type,
                value = value,
                source = WorkoutSource.LOCAL,
                sourcePackage = INBODY_PACKAGE,
                updatedAt = now,
            )
        })
    }

    suspend fun updateProfileGoal(workoutsPerWeek: Int) {
        val current = dao.getProfile() ?: ProfileEntity(onboardingDone = true)
        database.withTransaction {
            val goal = workoutsPerWeek.coerceIn(1, 7)
            dao.upsertProfile(current.copy(
                workoutsPerWeek = goal,
                updatedAt = System.currentTimeMillis(),
            ))
            val goalType = dao.getTrainingPlans().firstOrNull { it.plan.isActive }?.plan?.goalType
                ?: TrainingGoalType.BALANCED
            replaceActiveTrainingPlan(goalType, goal, dao.getRoutines())
        }
    }

    suspend fun getSession(id: String) = dao.getSession(id)
    suspend fun getRoutine(id: String) = dao.getRoutine(id)
    suspend fun getPlanSlot(id: String) = dao.getPlanSlot(id)
    suspend fun getAllSessions() = dao.getSessions()
    suspend fun getLinks() = dao.getWorkoutLinks()

    private suspend fun fillUnassignedStrengthSlots(routineId: String, routineName: String) {
        val activePlan = dao.getTrainingPlans().firstOrNull { it.plan.isActive } ?: return
        val replacements = activePlan.slots.filter {
            it.workoutType == PlannedWorkoutType.STRENGTH && it.routineId == null
        }.map { it.copy(routineId = routineId, title = routineName) }
        if (replacements.isNotEmpty()) dao.upsertPlanSlots(replacements)
    }

    private suspend fun replaceActiveTrainingPlan(
        goalType: TrainingGoalType,
        weeklyGoal: Int,
        routines: List<RoutineWithItems>,
    ) {
        val now = System.currentTimeMillis()
        val planId = "training-plan-${UUID.randomUUID()}"
        val strengthRoutines = routines.filter { routine ->
            routine.routine.isActive && routine.items.any { it.recordMode == RecordMode.SETS }
        }
        val availableRoutines = strengthRoutines.filter { routine ->
            routine.items.none {
                it.recordMode == RecordMode.CARDIO && it.exerciseId != TABATA_EXERCISE_ID
            }
        }.ifEmpty { strengthRoutines }
        val pattern = when (goalType) {
            TrainingGoalType.BALANCED -> listOf(
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.EASY_RUN,
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.QUALITY_RUN,
                PlannedWorkoutType.LONG_RUN,
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.EASY_RUN,
            )
            TrainingGoalType.RUNNING -> listOf(
                PlannedWorkoutType.EASY_RUN,
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.QUALITY_RUN,
                PlannedWorkoutType.EASY_RUN,
                PlannedWorkoutType.LONG_RUN,
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.EASY_RUN,
            )
            TrainingGoalType.STRENGTH -> listOf(
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.EASY_RUN,
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.QUALITY_RUN,
                PlannedWorkoutType.STRENGTH,
                PlannedWorkoutType.EASY_RUN,
                PlannedWorkoutType.STRENGTH,
            )
        }.take(weeklyGoal.coerceIn(1, 7))
        val planName = when (goalType) {
            TrainingGoalType.BALANCED -> "균형형 주간 계획"
            TrainingGoalType.RUNNING -> "러닝 성장형 주간 계획"
            TrainingGoalType.STRENGTH -> "근력 성장형 주간 계획"
        }

        dao.deactivateTrainingPlans(now)
        dao.upsertTrainingPlan(TrainingPlanEntity(
            id = planId,
            name = planName,
            goalType = goalType,
            createdAt = now,
            updatedAt = now,
        ))
        var strengthIndex = 0
        dao.upsertPlanSlots(pattern.mapIndexed { index, workoutType ->
            val routine = if (workoutType == PlannedWorkoutType.STRENGTH && availableRoutines.isNotEmpty()) {
                availableRoutines[strengthIndex++ % availableRoutines.size]
            } else null
            val title = when (workoutType) {
                PlannedWorkoutType.STRENGTH -> routine?.routine?.name ?: "근력 루틴 필요"
                PlannedWorkoutType.EASY_RUN -> "이지런"
                PlannedWorkoutType.QUALITY_RUN -> "템포런"
                PlannedWorkoutType.LONG_RUN -> "롱런"
            }
            PlanSlotEntity(
                id = "plan-slot-${UUID.randomUUID()}",
                planId = planId,
                orderIndex = index + 1,
                workoutType = workoutType,
                routineId = routine?.routine?.id,
                title = title,
                preferredDayOfWeek = ((index * 7) / pattern.size.coerceAtLeast(1)) + 1,
                targetDurationMin = when (workoutType) {
                    PlannedWorkoutType.EASY_RUN -> 30.0
                    PlannedWorkoutType.QUALITY_RUN -> 35.0
                    PlannedWorkoutType.LONG_RUN -> 60.0
                    PlannedWorkoutType.STRENGTH -> null
                },
                targetDistanceKm = if (workoutType == PlannedWorkoutType.QUALITY_RUN) 5.0 else null,
                note = when (workoutType) {
                    PlannedWorkoutType.STRENGTH -> "세트와 중량을 기록해요."
                    PlannedWorkoutType.EASY_RUN -> "편안하게 대화할 수 있는 강도로 달려요."
                    PlannedWorkoutType.QUALITY_RUN -> "일정하게 빠른 리듬을 유지해요."
                    PlannedWorkoutType.LONG_RUN -> "지속 가능한 속도로 오래 달려요."
                },
                createdAt = now + index,
            )
        })
    }

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
                planSlotId = workout.planSlotId,
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
                routePolyline = workout.routePolyline,
                activeDurationMillis = workout.activeDurationMillis,
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
