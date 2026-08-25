package com.hanshin.healthtask.wear

import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.RoutineEntity
import com.hanshin.healthtask.data.db.RoutineItemEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.domain.ExerciseCategory
import com.hanshin.healthtask.domain.PlannedWorkoutType
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.shared.TABATA_EXERCISE_ID
import com.hanshin.healthtask.shared.TABATA_REST_SECONDS
import com.hanshin.healthtask.shared.TABATA_ROUNDS
import com.hanshin.healthtask.shared.TABATA_WORK_SECONDS
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearSensorMode
import com.hanshin.healthtask.shared.usesGpsRunning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearRoutineMapperTest {
    @Test
    fun `tabata finisher keeps interval settings in strength routine`() {
        val routine = RoutineWithItems(
            routine = RoutineEntity(id = "routine", name = "근력 + 타바타"),
            items = listOf(
                RoutineItemEntity(
                    id = "tabata-item",
                    routineId = "routine",
                    exerciseId = TABATA_EXERCISE_ID,
                    orderIndex = 1,
                    category = ExerciseCategory.CARDIO,
                    recordMode = RecordMode.CARDIO,
                    targetDurationMin = 4.0,
                ),
            ),
        )
        val exercise = ExerciseEntity(
            id = TABATA_EXERCISE_ID,
            name = "타바타 피니셔",
            category = ExerciseCategory.CARDIO,
            recordMode = RecordMode.CARDIO,
        )

        val payload = requireNotNull(buildWearRoutinePayload(
            routine = routine,
            plannedSlot = null,
            exercises = listOf(exercise),
            sessions = emptyList(),
            restTimerSeconds = 90,
        ))
        val tabata = payload.exercises.single()

        assertEquals(WearSensorMode.STRENGTH, payload.sensorMode)
        assertEquals(false, payload.usesGpsRunning)
        assertEquals(TABATA_WORK_SECONDS, tabata.intervalWorkSeconds)
        assertEquals(TABATA_REST_SECONDS, tabata.intervalRestSeconds)
        assertEquals(TABATA_ROUNDS, tabata.intervalRounds)
    }

    @Test
    fun `planned run becomes a cardio watch routine and keeps plan slot`() {
        val slot = PlanSlotEntity(
            id = "slot-run",
            planId = "plan",
            orderIndex = 2,
            workoutType = PlannedWorkoutType.QUALITY_RUN,
            title = "템포런",
            targetDurationMin = 35.0,
            targetDistanceKm = 5.0,
            targetPaceMinPerKm = 5.2,
        )

        val payload = requireNotNull(buildWearRoutinePayload(
            routine = null,
            plannedSlot = slot,
            exercises = emptyList(),
            sessions = emptyList(),
            restTimerSeconds = 90,
            updatedAt = 123L,
        ))

        assertEquals("slot-run", payload.planSlotId)
        assertEquals("planned-run-slot-run", payload.routineId)
        assertEquals("템포런", payload.title)
        assertEquals(WearRecordMode.CARDIO, payload.exercises.single().recordMode)
        assertEquals("tempo-run", payload.exercises.single().exerciseId)
        assertEquals(35.0, payload.exercises.single().targetDurationMin!!, 0.001)
        assertEquals(5.0, payload.exercises.single().targetDistanceKm!!, 0.001)
        assertEquals(5.2, payload.exercises.single().targetPaceMinPerKm!!, 0.001)
        assertEquals(WearSensorMode.RUNNING, payload.sensorMode)
    }

    @Test
    fun `nothing is published when neither routine nor running slot exists`() {
        assertNull(buildWearRoutinePayload(
            routine = null,
            plannedSlot = null,
            exercises = emptyList(),
            sessions = emptyList(),
            restTimerSeconds = 90,
        ))
    }
}
