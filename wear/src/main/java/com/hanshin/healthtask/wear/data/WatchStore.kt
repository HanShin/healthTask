package com.hanshin.healthtask.wear.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearRoutinePayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.watchDataStore by preferencesDataStore(name = "watch_workout_state")

class WatchStore(private val context: Context) {
    private val gson = Gson()

    val routine: Flow<WearRoutinePayload?> = context.watchDataStore.data.map { values ->
        values[ROUTINE]?.let { json -> runCatching { gson.fromJson(json, WearRoutinePayload::class.java) }.getOrNull() }
    }
    val activeSession: Flow<WearActiveSession?> = context.watchDataStore.data.map { values ->
        values[ACTIVE]?.let { json -> runCatching { gson.fromJson(json, WearActiveSession::class.java) }.getOrNull() }
    }

    suspend fun saveRoutine(value: WearRoutinePayload) {
        context.watchDataStore.edit { it[ROUTINE] = gson.toJson(value) }
    }

    suspend fun clearRoutine() {
        context.watchDataStore.edit { it.remove(ROUTINE) }
    }

    suspend fun saveActiveSession(value: WearActiveSession) {
        context.watchDataStore.edit { it[ACTIVE] = gson.toJson(value) }
    }

    suspend fun clearActiveSession() {
        context.watchDataStore.edit { it.remove(ACTIVE) }
    }

    private companion object {
        val ROUTINE = stringPreferencesKey("today_routine_json")
        val ACTIVE = stringPreferencesKey("active_session_json")
    }
}
