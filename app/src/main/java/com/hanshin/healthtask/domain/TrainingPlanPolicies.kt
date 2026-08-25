package com.hanshin.healthtask.domain

import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import java.time.LocalDate

data class WeeklyPlanProgress(
    val completed: Int,
    val goal: Int,
    val strengthCompleted: Int,
    val strengthGoal: Int,
    val runningCompleted: Int,
    val runningGoal: Int,
)

val PlannedWorkoutType.isRun: Boolean
    get() = this != PlannedWorkoutType.STRENGTH

fun completedPlanSlotIds(
    sessions: List<WorkoutSessionEntity>,
    referenceDate: LocalDate = LocalDate.now(),
): Set<String> = sessions.asSequence()
    .filter { session ->
        session.status != WorkoutStatus.ACTIVE && session.status != WorkoutStatus.SKIPPED &&
            LocalDate.parse(session.sessionDate).inSameSundayWeek(referenceDate)
    }
    .mapNotNull { it.planSlotId }
    .toSet()

fun weeklyPlanProgress(
    slots: List<PlanSlotEntity>,
    sessions: List<WorkoutSessionEntity>,
    referenceDate: LocalDate = LocalDate.now(),
): WeeklyPlanProgress {
    val completedIds = completedPlanSlotIds(sessions, referenceDate)
    val strength = slots.filter { it.workoutType == PlannedWorkoutType.STRENGTH }
    val running = slots.filter { it.workoutType.isRun }
    return WeeklyPlanProgress(
        completed = slots.count { it.id in completedIds },
        goal = slots.size,
        strengthCompleted = strength.count { it.id in completedIds },
        strengthGoal = strength.size,
        runningCompleted = running.count { it.id in completedIds },
        runningGoal = running.size,
    )
}

fun nextPlanSlot(
    slots: List<PlanSlotEntity>,
    sessions: List<WorkoutSessionEntity>,
    referenceDate: LocalDate = LocalDate.now(),
): PlanSlotEntity? {
    val completedIds = completedPlanSlotIds(sessions, referenceDate)
    return slots.sortedBy { it.orderIndex }.firstOrNull { it.id !in completedIds }
}
