package com.hanshin.healthtask.wear.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.gson.Gson
import com.hanshin.healthtask.shared.WearPaths
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchStartRequestTest {
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val storeFile = File.createTempFile("watch-start-request", ".preferences_pb").apply { delete() }
    private val dataStore = PreferenceDataStoreFactory.create(scope = storeScope) { storeFile }
    private val store = WatchStore(dataStore)
    private val gson = Gson()
    private val now = System.currentTimeMillis()

    @After fun tearDown() {
        storeScope.cancel()
        storeFile.delete()
    }

    @Test fun `store ignores expired and older requests and clears only matching request`() = runBlocking {
        val current = request(
            requestId = "current",
            requestedAt = now + 2,
            expiresAt = now + 60_000,
        )
        val older = request(
            requestId = "older",
            requestedAt = now + 1,
            expiresAt = now + 60_000,
        )
        val expired = request(
            requestId = "expired",
            requestedAt = now + 3,
            expiresAt = now - 1,
        )

        store.savePendingStartRequest(current, now)
        store.savePendingStartRequest(older, now)
        store.savePendingStartRequest(expired, now)
        assertEquals(current, store.pendingStartRequest.first())

        store.clearPendingStartRequest("older")
        assertEquals(current, store.pendingStartRequest.first())
        store.clearPendingStartRequest("current")
        assertNull(store.pendingStartRequest.first())
    }

    @Test fun `listener decoder requires matching flat path and unexpired request`() {
        val request = request(
            requestId = "request-1",
            requestedAt = now,
            expiresAt = now + 60_000,
        )
        val json = gson.toJson(request)

        assertEquals(
            request,
            decodeStartWorkoutRequest(
                json = json,
                path = WearPaths.START_WORKOUT_PREFIX + request.requestId,
                now = now,
                gson = gson,
            ),
        )
        assertNull(
            decodeStartWorkoutRequest(
                json = json,
                path = WearPaths.START_WORKOUT_PREFIX + "different",
                now = now,
                gson = gson,
            ),
        )
        assertNull(
            decodeStartWorkoutRequest(
                json = json,
                path = WearPaths.START_WORKOUT_PREFIX + request.requestId + "/nested",
                now = now,
                gson = gson,
            ),
        )
        assertNull(
            decodeStartWorkoutRequest(
                json = json,
                path = WearPaths.START_WORKOUT_PREFIX + request.requestId,
                now = request.expiresAt + 1,
                gson = gson,
            ),
        )
    }

    private fun request(
        requestId: String,
        requestedAt: Long,
        expiresAt: Long,
    ) = WearStartWorkoutRequest(
        requestId = requestId,
        sessionId = "session-$requestId",
        routine = WearRoutinePayload(
            routineId = "routine",
            title = "루틴",
            exercises = emptyList(),
            updatedAt = requestedAt,
        ),
        requestedAt = requestedAt,
        expiresAt = expiresAt,
    )
}
