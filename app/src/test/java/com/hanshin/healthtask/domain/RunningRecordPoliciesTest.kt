package com.hanshin.healthtask.domain

import com.hanshin.healthtask.data.db.*
import org.junit.Assert.*
import org.junit.Test

class RunningRecordPoliciesTest {
    private fun record(id: String = "local", title: String = "러닝", exercise: String = "easy-run",
        source: WorkoutSource = WorkoutSource.LOCAL, status: WorkoutStatus = WorkoutStatus.COMPLETED,
        category: ExerciseCategory = ExerciseCategory.CARDIO, mode: RecordMode = RecordMode.CARDIO,
    ) = WorkoutSessionWithItems(
        WorkoutSessionEntity(id = id, title = title, sessionDate = "2026-09-24", status = status,
            source = source, startedAt = 0L, endedAt = 1_800_000L),
        listOf(WorkoutItemWithSets(WorkoutItemEntity(id = "$id-item", sessionId = id, exerciseId = exercise,
            exerciseName = title, orderIndex = 1, category = category, recordMode = mode, distanceKm = 5.0), emptyList())),
    )

    @Test fun `walking strength mixed and active workouts are not running history`() {
        val run = record()
        assertTrue(run.isRunningRecord())
        assertTrue(record(title = "사용자 제목", exercise = "external-running").isRunningRecord())
        assertFalse(record(title = "걷기", exercise = "walking").isRunningRecord())
        assertFalse(record(title = "Running strength", category = ExerciseCategory.WEIGHT, mode = RecordMode.SETS).isRunningRecord())
        assertFalse(run.copy(items = run.items + record(category = ExerciseCategory.WEIGHT).items).isRunningRecord())
        assertTrue(visibleRunningSessions(listOf(record(status = WorkoutStatus.ACTIVE)), emptyList()).isEmpty())
    }

    @Test fun `linked external run reappears when its local counterpart is absent`() {
        val local = record()
        val external = record(id = "external", source = WorkoutSource.SAMSUNG_HEALTH)
        val link = SamsungWorkoutLinkEntity("link", "local", "external", 1.0)
        assertEquals(listOf(local), visibleRunningSessions(listOf(local, external), listOf(link)))
        assertEquals(listOf(external), visibleRunningSessions(listOf(external), listOf(link)))
    }

    @Test fun `session totals take precedence over duplicate item values and pauses`() {
        val run = record()
        assertEquals(5.0, run.runningDistanceKm(), 0.001)
        assertEquals(1_800_000L, run.runningDurationMillis())
        val paused = run.copy(session = run.session.copy(distanceKm = 4.5, activeDurationMillis = 1_500_000L))
        assertEquals(4.5, paused.runningDistanceKm(), 0.001)
        assertEquals(1_500_000L, paused.runningDurationMillis())
    }

    @Test fun `body values reject impossible percentages and nonfinite values`() {
        assertFalse(isValidBodyValue(HealthMetricType.WEIGHT_KG, Double.NaN))
        assertFalse(isValidBodyValue(HealthMetricType.WEIGHT_KG, Double.POSITIVE_INFINITY))
        assertFalse(isValidBodyValue(HealthMetricType.SKELETAL_MUSCLE_KG, 0.0))
        assertFalse(isValidBodyValue(HealthMetricType.BODY_FAT_PERCENT, 101.0))
        assertFalse(isValidBodyValue(HealthMetricType.INBODY_SCORE, 121.0))
        assertTrue(isValidBodyValue(HealthMetricType.BODY_FAT_PERCENT, 21.5))
    }
}
