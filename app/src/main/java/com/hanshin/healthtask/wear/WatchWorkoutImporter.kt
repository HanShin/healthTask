package com.hanshin.healthtask.wear

import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.running.RunningLapCodec
import com.hanshin.healthtask.running.RunningRouteCodec
import com.hanshin.healthtask.running.RunningTrackingService
import com.hanshin.healthtask.shared.RunTelemetryCodec
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class WatchWorkoutImporter(private val app: HealthTaskApplication) {
    private val dataClient = Wearable.getDataClient(app)
    private val gson = Gson()
    private val mutex = Mutex()

    /** Retry durable pending items on app open, including items received by an older phone build. */
    suspend fun importPending() = withContext(Dispatchers.IO) {
        val items = dataClient.getDataItems("wear://*${WearPaths.COMPLETED_WORKOUT_PREFIX}".toUri(),
            DataClient.FILTER_PREFIX).await()
        try { items.forEach { importItem(it) } } finally { items.release() }
    }

    suspend fun importItem(item: DataItem) = mutex.withLock {
        if (!item.uri.path.orEmpty().startsWith(WearPaths.COMPLETED_WORKOUT_PREFIX)) return@withLock
        runCatching {
            val map = DataMapItem.fromDataItem(item).dataMap
            val asset = map.getAsset(WearPaths.KEY_WORKOUT_ASSET)
            val json = if (asset != null) {
                val response = dataClient.getFdForAsset(asset).await()
                try { response.inputStream.use { RunTelemetryCodec.decompress(it) } }
                finally { response.release() }
            } else map.getString(WearPaths.KEY_JSON) ?: return@withLock
            val workout = gson.fromJson(json, WearCompletedWorkout::class.java)
            require(item.uri.path == WearPaths.COMPLETED_WORKOUT_PREFIX + workout.sessionId)
            require(workout.schemaVersion in 1..5 && workout.endedAt >= workout.startedAt)
            workout.telemetry?.let { require(it.metadata.sessionId == workout.sessionId) }
            withContext(Dispatchers.Main) {
                val active = app.runningTracker.state.value
                if (active.isActive && active.sessionId == workout.sessionId) {
                    app.runningTracker.finish().also { runCatching { RunningTrackingService.stop(app) } }
                } else null
            }?.let { completed ->
                if (completed.elapsedMillis >= 1_000L) app.repository.savePhoneRun(
                    sessionId = completed.sessionId, startedAt = completed.startedAt, endedAt = completed.endedAt,
                    elapsedMillis = completed.elapsedMillis, distanceMeters = completed.distanceMeters,
                    routePolyline = RunningRouteCodec.encode(completed.route).takeIf { it.isNotBlank() },
                    lapData = RunningLapCodec.encode(completed.laps).takeIf { it.isNotBlank() },
                    planSlotId = completed.plannedSlotId,
                )
            }
            app.repository.importWearWorkout(workout)
            // Acknowledge only after both summary and complete telemetry commit successfully.
            dataClient.deleteDataItems(item.uri, DataClient.FILTER_LITERAL).await()
        }.onFailure {
            // Never log health payloads or exception messages containing raw JSON.
            Log.w("WatchWorkoutImport", "Pending watch record retained: ${it.javaClass.simpleName}")
        }
        Unit
    }
}
