package com.hanshin.healthtask.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearProtocolTest {
    private val routine = WearRoutinePayload(
        routineId = "routine",
        title = "루틴",
        exercises = emptyList(),
        updatedAt = 1L,
    )

    @Test fun `elapsed time excludes accumulated and current pause`() {
        val running = WearActiveSession(
            sessionId = "session",
            routine = routine,
            startedAt = 1_000L,
            accumulatedPausedMillis = 2_000L,
        )
        assertEquals(7_000L, running.elapsedMillis(10_000L))

        val paused = running.copy(paused = true, pausedAt = 8_000L)
        assertEquals(5_000L, paused.elapsedMillis(20_000L))
    }

    @Test fun `rest timer rounds up partial seconds and stops at zero`() {
        assertEquals(90, remainingRestSeconds(restEndsAt = 91_000L, now = 1_000L))
        assertEquals(1, remainingRestSeconds(restEndsAt = 91_000L, now = 90_001L))
        assertEquals(0, remainingRestSeconds(restEndsAt = 91_000L, now = 91_000L))
        assertEquals(0, remainingRestSeconds(restEndsAt = null, now = 1_000L))
    }

    @Test fun `plan slot survives from routine payload to completed workout`() {
        val plannedRoutine = routine.copy(planSlotId = "slot-1")
        val completed = WearCompletedWorkout(
            sessionId = "session",
            routineId = plannedRoutine.routineId,
            planSlotId = plannedRoutine.planSlotId,
            title = plannedRoutine.title,
            startedAt = 1L,
            endedAt = 2L,
            exercises = emptyList(),
        )

        assertEquals("slot-1", completed.planSlotId)
    }

    @Test fun `watch running metrics calculate current and average pace`() {
        assertEquals(5.0, WearRunningMetrics.currentPaceMinutesPerKm(1_000.0 / 300.0)!!, 0.001)
        assertEquals(5.0, WearRunningMetrics.averagePaceMinutesPerKm(2.0, 600_000L)!!, 0.001)
        assertNull(WearRunningMetrics.currentPaceMinutesPerKm(0.1))
        assertNull(WearRunningMetrics.averagePaceMinutesPerKm(0.0, 600_000L))
    }

    @Test fun `quick runs are standalone cardio routines`() {
        val free = WearQuickRunPreset.FREE_RUN.toRoutinePayload(updatedAt = 10L)
        val timed = WearQuickRunPreset.THIRTY_MINUTES.toRoutinePayload(updatedAt = 20L)
        val distance = WearQuickRunPreset.FIVE_KILOMETERS.toRoutinePayload(updatedAt = 30L)

        assertNull(free.planSlotId)
        assertEquals(WearRecordMode.CARDIO, free.exercises.single().recordMode)
        assertEquals(30.0, timed.exercises.single().targetDurationMin!!, 0.001)
        assertEquals(5.0, distance.exercises.single().targetDistanceKm!!, 0.001)
    }

    @Test fun `free workout uses strength sensors without GPS`() {
        val workout = freeWorkoutRoutinePayload(updatedAt = 40L)

        assertEquals(WearSensorMode.STRENGTH, workout.sensorMode)
        assertEquals(false, workout.usesGpsRunning)
        assertEquals(WearRecordMode.CARDIO, workout.exercises.single().recordMode)
        assertEquals("WEIGHT", workout.exercises.single().category)
        assertNull(workout.planSlotId)
    }

    @Test fun `start request validates identity payload and expiry`() {
        val request = WearStartWorkoutRequest(
            requestId = "start-session-1",
            sessionId = "session-1",
            routine = routine,
            requestedAt = 1_000L,
            expiresAt = 2_000L,
        )

        assertTrue(request.isValid(now = 1_999L))
        assertFalse(request.isValid(now = 2_000L))
        assertFalse(request.copy(requestId = "nested/id").isValid(now = 1_500L))
        assertFalse(request.copy(schemaVersion = 2).isValid(now = 1_500L))
        assertFalse(request.copy(expiresAt = request.requestedAt).isValid(now = 1_000L))
    }

    @Test fun `watch route codec is compact and ignores invalid points`() {
        val route = listOf(
            WearRoutePoint(37.566535, 126.977969, 0L),
            WearRoutePoint(37.567123, 126.979321, 5_000L),
        )

        val encoded = WearRouteCodec.encode(route)

        assertEquals("37.566535,126.977969,0;37.567123,126.979321,5000", encoded)
        assertEquals(route, WearRouteCodec.decode(encoded))
        assertEquals(emptyList<WearRoutePoint>(), WearRouteCodec.decode("91.0,127.0,0;broken"))
    }
}
