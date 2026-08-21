package com.hanshin.healthtask.domain

import com.hanshin.healthtask.data.db.SamsungWorkoutLinkEntity
import com.hanshin.healthtask.data.db.SetRecordEntity
import com.hanshin.healthtask.data.db.WorkoutItemEntity
import com.hanshin.healthtask.data.db.WorkoutItemWithSets
import com.hanshin.healthtask.data.db.WorkoutSessionEntity
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutPoliciesTest {
    private val today = LocalDate.now()
    private val start = today.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `ten minute Samsung workout counts but nine minute workout does not`() {
        val short = session("short", WorkoutSource.SAMSUNG_HEALTH, start, start + 9 * 60_000)
        val enough = session("enough", WorkoutSource.SAMSUNG_HEALTH, start, start + 10 * 60_000)
        assertEquals(1, weeklyGoalCount(listOf(short, enough), emptyList(), today))
    }

    @Test fun `linked local and Samsung workouts count once`() {
        val local = session("local", WorkoutSource.LOCAL, start, start + 30 * 60_000, "routine-a")
        val external = session("external", WorkoutSource.SAMSUNG_HEALTH, start, start + 31 * 60_000)
        val link = SamsungWorkoutLinkEntity("link", local.id, external.id, .96)
        assertEquals(1, weeklyGoalCount(listOf(local, external), listOf(link), today))
    }

    @Test fun `Samsung workout never advances routine order`() {
        val samsung = session("external", WorkoutSource.SAMSUNG_HEALTH, start, start + 40 * 60_000)
        assertEquals(0, nextRoutineIndex(listOf(samsung), 3))
        val local = session("local", WorkoutSource.LOCAL, start, start + 40 * 60_000, "routine-a")
        assertEquals(1, nextRoutineIndex(listOf(samsung, local), 3))
    }

    @Test fun `routine order restarts on Sunday week boundary`() {
        val lastWeekDate = today.minusWeeks(1)
        val old = session("old", WorkoutSource.LOCAL, start, start + 40 * 60_000, "routine")
            .copy(sessionDate = lastWeekDate.toString())
        assertEquals(0, nextRoutineIndex(listOf(old), 3, today))
    }

    @Test fun `automatic link requires seventy percent of shorter duration and best candidate wins`() {
        val samsung = session("samsung", WorkoutSource.SAMSUNG_HEALTH, start, start + 30 * 60_000)
        val weak = session("weak", WorkoutSource.LOCAL, start - 20 * 60_000, start + 5 * 60_000, "routine")
        val strong = session("strong", WorkoutSource.LOCAL, start + 2 * 60_000, start + 28 * 60_000, "routine")
        val result = bestAutomaticLink(listOf(weak, strong), samsung) { ExerciseCategory.WEIGHT }
        assertNotNull(result)
        assertEquals("strong", result!!.first.id)
        assertEquals(1.0, result.second, .001)
        assertNull(bestAutomaticLink(listOf(weak), samsung) { ExerciseCategory.WEIGHT })
    }

    @Test fun `session status and recent completed set are calculated from detail`() {
        val session = session("local", WorkoutSource.LOCAL, start, start + 20 * 60_000, "routine")
        val item = WorkoutItemEntity("item", session.id, "squat", "스쿼트", 1, ExerciseCategory.WEIGHT, RecordMode.SETS)
        val completed = SetRecordEntity("set", item.id, 1, actualReps = 8, actualWeightKg = 60.0, completed = true)
        val fullItem = WorkoutItemWithSets(item, listOf(completed))
        val full = WorkoutSessionWithItems(session, listOf(fullItem))
        assertEquals(WorkoutStatus.COMPLETED, workoutStatus(listOf(fullItem)))
        assertEquals(completed, latestCompletedSet(listOf(full), "squat"))
    }

    private fun session(
        id: String,
        source: WorkoutSource,
        startedAt: Long,
        endedAt: Long,
        routineId: String? = null,
    ) = WorkoutSessionEntity(
        id = id,
        routineId = routineId,
        title = if (source == WorkoutSource.SAMSUNG_HEALTH) "근력 운동" else "루틴",
        sessionDate = today.toString(),
        status = WorkoutStatus.COMPLETED,
        source = source,
        startedAt = startedAt,
        endedAt = endedAt,
    )
}
