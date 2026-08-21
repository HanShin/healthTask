package com.hanshin.healthtask.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("healthtask_preferences")

data class SyncPreferences(
    val enabled: Boolean = true,
    val lastSyncAt: Long? = null,
)

class AppPreferences(private val context: Context) {
    private val syncEnabled = booleanPreferencesKey("health_connect_sync_enabled")
    private val lastSyncAt = longPreferencesKey("health_connect_last_sync_at")

    val sync: Flow<SyncPreferences> = context.dataStore.data.map { values ->
        SyncPreferences(
            enabled = values[syncEnabled] ?: true,
            lastSyncAt = values[lastSyncAt],
        )
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { it[syncEnabled] = enabled }
    }

    suspend fun markSynced(at: Long = System.currentTimeMillis()) {
        context.dataStore.edit { it[lastSyncAt] = at }
    }
}
