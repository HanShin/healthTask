package com.hanshin.healthtask.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

data class InBodyGraphPreferences(
    val showAll: Boolean = false,
    val hiddenMetrics: Set<String> = emptySet(),
)

class AppPreferences(private val context: Context) {
    private val syncEnabled = booleanPreferencesKey("health_connect_sync_enabled")
    private val lastSyncAt = longPreferencesKey("health_connect_last_sync_at")
    private val runningAutoPauseEnabled = booleanPreferencesKey("running_auto_pause_enabled")
    private val runningVoiceGuidanceEnabled = booleanPreferencesKey("running_voice_guidance_enabled")
    private val inBodyShowAll = booleanPreferencesKey("inbody_graph_show_all")
    private val inBodyHiddenMetrics = stringSetPreferencesKey("inbody_graph_hidden_metrics")

    val inBodyGraphs: Flow<InBodyGraphPreferences> = context.dataStore.data.map { values ->
        InBodyGraphPreferences(values[inBodyShowAll] ?: false, values[inBodyHiddenMetrics] ?: emptySet())
    }

    suspend fun setInBodyGraphs(value: InBodyGraphPreferences) {
        context.dataStore.edit {
            it[inBodyShowAll] = value.showAll
            it[inBodyHiddenMetrics] = value.hiddenMetrics
        }
    }

    val sync: Flow<SyncPreferences> = context.dataStore.data.map { values ->
        SyncPreferences(
            enabled = values[syncEnabled] ?: true,
            lastSyncAt = values[lastSyncAt],
        )
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

    suspend fun setRunningAutoPauseEnabled(enabled: Boolean) {
        context.dataStore.edit { it[runningAutoPauseEnabled] = enabled }
    }

    suspend fun setRunningVoiceGuidanceEnabled(enabled: Boolean) {
        context.dataStore.edit { it[runningVoiceGuidanceEnabled] = enabled }
    }
}
