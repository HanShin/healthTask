package com.hanshin.healthtask.wear

import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.PlannedWorkoutType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.latestCompletedSet
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRoutineExercise
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearRoutineSet
import com.hanshin.healthtask.shared.WearSensorMode
import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
import com.hanshin.healthtask.shared.TABATA_REST_SECONDS
import com.hanshin.healthtask.shared.TABATA_ROUNDS
import com.hanshin.healthtask.shared.TABATA_WORK_SECONDS

fun buildWearRoutinePayload(
    routine: RoutineWithItems?,
    plannedSlot: PlanSlotEntity?,
    exercises: List<ExerciseEntity>,
    sessions: List<WorkoutSessionWithItems>,
    restTimerSeconds: Int,
    updatedAt: Long = System.currentTimeMillis(),
): WearRoutinePayload? {
    if (plannedSlot != null && plannedSlot.workoutType != PlannedWorkoutType.STRENGTH) {
        return plannedSlot.toWearRunningPayload(restTimerSeconds, updatedAt)
    }
    routine ?: return null
    val exerciseMap = exercises.associateBy { it.id }
    return WearRoutinePayload(
        routineId = routine.routine.id,
        planSlotId = plannedSlot?.id,
        title = routine.routine.name,
        restTimerSeconds = restTimerSeconds,
        exercises = routine.items.sortedBy { it.orderIndex }.map { plan ->
            val exercise = exerciseMap[plan.exerciseId]
            val recent = latestCompletedSet(sessions, plan.exerciseId)
            WearRoutineExercise(
                id = plan.id,
                exerciseId = plan.exerciseId,
                name = exercise?.name ?: plan.exerciseId,
                order = plan.orderIndex,
                recordMode = if (plan.recordMode == RecordMode.CARDIO) WearRecordMode.CARDIO else WearRecordMode.SETS,
                category = plan.category.name,
                sets = if (plan.recordMode == RecordMode.SETS) {
                    (1..(plan.setCount ?: 3)).map { order ->
                        WearRoutineSet(
                            order = order,
                            reps = recent?.actualReps ?: plan.targetReps,
                            weightKg = recent?.actualWeightKg ?: plan.targetWeightKg,
                        )
                    }
                } else emptyList(),
                targetDurationMin = plan.targetDurationMin,
                targetDistanceKm = plan.targetDistanceKm,
                intervalWorkSeconds = TABATA_WORK_SECONDS.takeIf { plan.exerciseId == TABATA_EXERCISE_ID },
                intervalRestSeconds = TABATA_REST_SECONDS.takeIf { plan.exerciseId == TABATA_EXERCISE_ID },
                intervalRounds = TABATA_ROUNDS.takeIf { plan.exerciseId == TABATA_EXERCISE_ID },
            )
        },
        updatedAt = updatedAt,
        sensorMode = WearSensorMode.STRENGTH,
    )
}

private fun PlanSlotEntity.toWearRunningPayload(
    restTimerSeconds: Int,
    updatedAt: Long,
): WearRoutinePayload = WearRoutinePayload(
    routineId = "planned-run-$id",
    planSlotId = id,
    title = title,
    exercises = listOf(
        WearRoutineExercise(
            id = "$id-exercise",
            exerciseId = when (workoutType) {
                PlannedWorkoutType.EASY_RUN -> "easy-run"
                PlannedWorkoutType.QUALITY_RUN -> "tempo-run"
                PlannedWorkoutType.LONG_RUN -> "long-run"
                PlannedWorkoutType.STRENGTH -> error("근력 슬롯은 러닝 페이로드로 변환할 수 없습니다.")
            },
            name = title,
            order = 1,
            recordMode = WearRecordMode.CARDIO,
            category = ExerciseCategory.CARDIO.name,
            targetDurationMin = targetDurationMin,
            targetDistanceKm = targetDistanceKm,
            targetPaceMinPerKm = targetPaceMinPerKm,
        ),
    ),
    updatedAt = updatedAt,
    restTimerSeconds = restTimerSeconds,
    sensorMode = WearSensorMode.RUNNING,
)
