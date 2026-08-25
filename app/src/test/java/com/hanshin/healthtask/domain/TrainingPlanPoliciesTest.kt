package com.hanshin.healthtask.domain

import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingPlanPoliciesTest {
    private val today = LocalDate.now()
    private val startedAt = today.atTime(8, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val slots = listOf(
        slot("strength", 1, PlannedWorkoutType.STRENGTH),
        slot("easy", 2, PlannedWorkoutType.EASY_RUN),
        slot("long", 3, PlannedWorkoutType.LONG_RUN),
    )

    @Test fun `plan progress separates strength and running goals`() {
        val sessions = listOf(completedSession("session-1", "strength"), completedSession("session-2", "easy"))

        val progress = weeklyPlanProgress(slots, sessions, today)

        assertEquals(2, progress.completed)
        assertEquals(3, progress.goal)
        assertEquals(1, progress.strengthCompleted)
        assertEquals(1, progress.strengthGoal)
        assertEquals(1, progress.runningCompleted)
        assertEquals(2, progress.runningGoal)
    }

    @Test fun `next slot skips completed work and ignores last week`() {
        val completedThisWeek = completedSession("session-1", "strength")
        val completedLastWeek = completedSession("session-2", "easy")
            .copy(sessionDate = today.minusWeeks(1).toString())

        assertEquals("easy", nextPlanSlot(slots, listOf(completedThisWeek, completedLastWeek), today)?.id)
    }

    @Test fun `active and skipped sessions do not complete a plan slot`() {
        val active = completedSession("active", "strength").copy(status = WorkoutStatus.ACTIVE)
        val skipped = completedSession("skipped", "strength").copy(status = WorkoutStatus.SKIPPED)

        assertEquals("strength", nextPlanSlot(slots, listOf(active, skipped), today)?.id)
    }

    private fun slot(id: String, order: Int, type: PlannedWorkoutType) = PlanSlotEntity(
        id = id,
        planId = "plan",
        orderIndex = order,
        workoutType = type,
        title = id,
    )

    private fun completedSession(id: String, slotId: String) = WorkoutSessionEntity(
        id = id,
        planSlotId = slotId,
        title = id,
        sessionDate = today.toString(),
        status = WorkoutStatus.COMPLETED,
        source = WorkoutSource.LOCAL,
        startedAt = startedAt,
        endedAt = startedAt + 30 * 60_000,
    )
}
