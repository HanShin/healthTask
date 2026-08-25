package com.hanshin.healthtask.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hanshin.healthtask.shared.DEFAULT_REST_TIMER_SECONDS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("healthtask_preferences")

data class SyncPreferences(
    val enabled: Boolean = true,
    val lastSyncAt: Long? = null,
)

data class RunningPreferences(
    val autoPauseEnabled: Boolean = true,
    val voiceGuidanceEnabled: Boolean = true,
)

class AppPreferences(private val context: Context) {
    private val syncEnabled = booleanPreferencesKey("health_connect_sync_enabled")
    private val lastSyncAt = longPreferencesKey("health_connect_last_sync_at")
    private val configuredRestTimerSeconds = intPreferencesKey("rest_timer_seconds")
    private val runningAutoPauseEnabled = booleanPreferencesKey("running_auto_pause_enabled")
    private val runningVoiceGuidanceEnabled = booleanPreferencesKey("running_voice_guidance_enabled")

    val sync: Flow<SyncPreferences> = context.dataStore.data.map { values ->
        SyncPreferences(
            enabled = values[syncEnabled] ?: true,
            lastSyncAt = values[lastSyncAt],
        )
    }

    val restTimerSeconds: Flow<Int> = context.dataStore.data.map { values ->
        (values[configuredRestTimerSeconds] ?: DEFAULT_REST_TIMER_SECONDS).coerceIn(15, 600)
    }

    val running: Flow<RunningPreferences> = context.dataStore.data.map { values ->
        RunningPreferences(
            autoPauseEnabled = values[runningAutoPauseEnabled] ?: true,
            voiceGuidanceEnabled = values[runningVoiceGuidanceEnabled] ?: true,
        )
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[syncEnabled] = enabled }
    }

    suspend fun markSynced(at: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[lastSyncAt] = at }
    }

    suspend fun setRestTimerSeconds(seconds: Int) {
        context.dataStore.edit { it[configuredRestTimerSeconds] = seconds.coerceIn(15, 600) }
    }

    suspend fun setRunningAutoPauseEnabled(enabled: Boolean) {
        context.dataStore.edit { it[runningAutoPauseEnabled] = enabled }
    }

    suspend fun setRunningVoiceGuidanceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[runningVoiceGuidanceEnabled] = enabled }
    }
}
