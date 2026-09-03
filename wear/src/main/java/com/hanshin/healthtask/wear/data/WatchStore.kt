package com.hanshin.healthtask.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.watchDataStore by preferencesDataStore(name = "watch_workout_state")

class WatchStore private constructor(
    private val dataStore: DataStore<Preferences>,
    private val gson: Gson,
) {
    constructor(context: Context) : this(context.watchDataStore, Gson())

    internal constructor(dataStore: DataStore<Preferences>) : this(dataStore, Gson())

    val routine: Flow<WearRoutinePayload?> = dataStore.data.map { values ->
        values[ROUTINE]?.let { json -> runCatching { gson.fromJson(json, WearRoutinePayload::class.java) }.getOrNull() }
    }
    val activeSession: Flow<WearActiveSession?> = dataStore.data.map { values ->
        values[ACTIVE]?.let { json -> runCatching { gson.fromJson(json, WearActiveSession::class.java) }.getOrNull() }
    }
    val pendingStartRequest: Flow<WearStartWorkoutRequest?> = dataStore.data.map { values ->
        values[PENDING_START_REQUEST]
            ?.let { json -> runCatching { gson.fromJson(json, WearStartWorkoutRequest::class.java) }.getOrNull() }
            ?.takeIf { it.isValid() }
    }

    suspend fun saveRoutine(value: WearRoutinePayload) {
        dataStore.edit { it[ROUTINE] = gson.toJson(value) }
    }

    suspend fun clearRoutine() {
        dataStore.edit { it.remove(ROUTINE) }
    }

    suspend fun saveActiveSession(value: WearActiveSession) {
        dataStore.edit { it[ACTIVE] = gson.toJson(value) }
    }

    suspend fun clearActiveSession() {
        dataStore.edit { it.remove(ACTIVE) }
    }

    suspend fun savePendingStartRequest(
        value: WearStartWorkoutRequest,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!value.isValid(now)) return
        dataStore.edit { values ->
            val current = values[PENDING_START_REQUEST]
                ?.let { json -> runCatching { gson.fromJson(json, WearStartWorkoutRequest::class.java) }.getOrNull() }
            if (current == null || !current.isValid(now) || value.requestedAt >= current.requestedAt) {
                values[PENDING_START_REQUEST] = gson.toJson(value)
            }
        }
    }

    suspend fun clearPendingStartRequest(requestId: String? = null) {
        dataStore.edit { values ->
            val storedRequestId = values[PENDING_START_REQUEST]
                ?.let { json -> runCatching { gson.fromJson(json, WearStartWorkoutRequest::class.java) }.getOrNull() }
                ?.requestId
            if (requestId == null || requestId == storedRequestId) {
                values.remove(PENDING_START_REQUEST)
            }
        }
    }

    private companion object {
        val ROUTINE = stringPreferencesKey("today_routine_json")
        val ACTIVE = stringPreferencesKey("active_session_json")
        val PENDING_START_REQUEST = stringPreferencesKey("pending_start_request_json")
    }
}
