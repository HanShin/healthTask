package com.hanshin.healthtask.wear

import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearWorkoutStartPolicyTest {
    private val now = 10_000L
    private val routine = WearRoutinePayload(
        routineId = "routine",
        title = "루틴",
        exercises = emptyList(),
        updatedAt = now,
    )

    @Test fun `local start is blocked while a live phone request has priority`() {
        val local = PendingWorkoutStart(routine)

        assertTrue(isWorkoutStartCurrent(local, active = null, pendingRequest = null, now = now))
        assertFalse(isWorkoutStartCurrent(local, active = null, pendingRequest = request("remote"), now = now))
    }

    @Test fun `permission result only accepts the same live remote request`() {
        val current = request("current")
        val candidate = PendingWorkoutStart(
            routine = current.routine,
            sessionId = current.sessionId,
            requestId = current.requestId,
        )

        assertTrue(isWorkoutStartCurrent(candidate, active = null, pendingRequest = current, now = now))
        assertFalse(isWorkoutStartCurrent(candidate, active = null, pendingRequest = request("replacement"), now = now))
        assertFalse(
            isWorkoutStartCurrent(
                candidate,
                active = null,
                pendingRequest = current.copy(expiresAt = now),
                now = now,
            ),
        )
    }

    @Test fun `no new workout can replace an active session`() {
        val active = WearActiveSession(
            sessionId = "active",
            routine = routine,
            startedAt = now - 1_000L,
        )

        assertFalse(isWorkoutStartCurrent(PendingWorkoutStart(routine), active, null, now))
    }

    @Test fun `expiry delay clamps requests that already reached ttl`() {
        assertEquals(2_000L, startRequestExpiryDelayMillis(request("future"), now))
        assertEquals(0L, startRequestExpiryDelayMillis(request("expired").copy(expiresAt = now - 1), now))
    }

    private fun request(id: String) = WearStartWorkoutRequest(
        requestId = id,
        sessionId = "session-$id",
        routine = routine,
        requestedAt = now - 1_000L,
        expiresAt = now + 2_000L,
    )
}
