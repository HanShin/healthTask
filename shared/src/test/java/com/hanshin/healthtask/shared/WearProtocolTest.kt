package com.hanshin.healthtask.shared

import org.junit.Assert.assertEquals
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
}
