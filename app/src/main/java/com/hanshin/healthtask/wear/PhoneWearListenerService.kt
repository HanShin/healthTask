package com.hanshin.healthtask.wear

import android.net.Uri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.running.CompletedRun
import com.hanshin.healthtask.running.RunningLapCodec
import com.hanshin.healthtask.running.RunningRouteCodec
import com.hanshin.healthtask.running.RunningTrackingService
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

class PhoneWearListenerService : WearableListenerService() {
    private val gson = Gson()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val app = application as HealthTaskApplication
        val pending = dataEvents.mapNotNull { event ->
            val item = event.dataItem
            if (event.type != DataEvent.TYPE_CHANGED ||
                !item.uri.path.orEmpty().startsWith(WearPaths.COMPLETED_WORKOUT_PREFIX)
            ) return@mapNotNull null
            val json = runCatching {
                DataMapItem.fromDataItem(item).dataMap.getString(WearPaths.KEY_JSON)
            }.getOrNull() ?: return@mapNotNull null
            runCatching {
                val workout = gson.fromJson(json, WearCompletedWorkout::class.java)
                val activePhoneRun = app.runningTracker.state.value
                val completedPhoneRun = if (
                    activePhoneRun.isActive && activePhoneRun.sessionId == workout.sessionId
                ) {
                    app.runningTracker.finish().also {
                        runCatching { RunningTrackingService.stop(this@PhoneWearListenerService) }
                    }
                } else null
                PendingCompletedWorkout(item.uri, workout, completedPhoneRun)
            }.getOrNull()
        }
        runBlocking(Dispatchers.IO) {
            pending.forEach { item ->
                runCatching {
                    item.completedPhoneRun?.let { completed ->
                        if (completed.elapsedMillis >= 1_000L) {
                            app.repository.savePhoneRun(
                                sessionId = completed.sessionId,
                                startedAt = completed.startedAt,
                                endedAt = completed.endedAt,
                                elapsedMillis = completed.elapsedMillis,
                                distanceMeters = completed.distanceMeters,
                                routePolyline = RunningRouteCodec.encode(completed.route).takeIf { it.isNotBlank() },
                                lapData = RunningLapCodec.encode(completed.laps).takeIf { it.isNotBlank() },
                                planSlotId = completed.plannedSlotId,
                            )
                        }
                    }
                    // Always apply the watch payload after the phone snapshot. The repository
                    // merges sensor metrics into phone runs and de-duplicates strength results.
                    app.repository.importWearWorkout(item.workout)
                    Wearable.getDataClient(this@PhoneWearListenerService)
                        .deleteDataItems(item.uri)
                        .await()
                }
            }
        }
    }
}

private data class PendingCompletedWorkout(
    val uri: Uri,
    val workout: WearCompletedWorkout,
    val completedPhoneRun: CompletedRun?,
)
