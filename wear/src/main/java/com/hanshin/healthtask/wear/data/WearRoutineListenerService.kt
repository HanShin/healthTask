package com.hanshin.healthtask.wear.data

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.hanshin.healthtask.shared.WearPaths
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import com.hanshin.healthtask.wear.WatchApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class WearRoutineListenerService : WearableListenerService() {
    private val gson = Gson()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = (application as WatchApplication).store
        runBlocking(Dispatchers.IO) {
            dataEvents.forEach { event ->
                val path = event.dataItem.uri.path.orEmpty()
                when {
                    path == WearPaths.TODAY_ROUTINE -> when (event.type) {
                        DataEvent.TYPE_CHANGED -> {
                            val json = runCatching {
                                DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WearPaths.KEY_JSON)
                            }.getOrNull() ?: return@forEach
                            runCatching { gson.fromJson(json, WearRoutinePayload::class.java) }
                                .getOrNull()?.let { store.saveRoutine(it) }
                        }
                        DataEvent.TYPE_DELETED -> store.clearRoutine()
                    }

                    path.startsWith(WearPaths.START_WORKOUT_PREFIX) -> {
                        val requestId = path.removePrefix(WearPaths.START_WORKOUT_PREFIX)
                            .takeIf { it.isNotBlank() && '/' !in it }
                            ?: return@forEach
                        when (event.type) {
                            DataEvent.TYPE_CHANGED -> {
                                val json = runCatching {
                                    DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WearPaths.KEY_JSON)
                                }.getOrNull()
                                decodeStartWorkoutRequest(json, path)?.let { request ->
                                    store.savePendingStartRequest(request)
                                }
                            }
                            DataEvent.TYPE_DELETED -> store.clearPendingStartRequest(requestId)
                        }
                    }
                }
            }
        }
    }
}

internal fun decodeStartWorkoutRequest(
    json: String?,
    path: String?,
    now: Long = System.currentTimeMillis(),
    gson: Gson = Gson(),
): WearStartWorkoutRequest? {
    val requestId = path
        ?.takeIf { it.startsWith(WearPaths.START_WORKOUT_PREFIX) }
        ?.removePrefix(WearPaths.START_WORKOUT_PREFIX)
        ?.takeIf { it.isNotBlank() && '/' !in it }
        ?: return null
    val request = json
        ?.let { runCatching { gson.fromJson(it, WearStartWorkoutRequest::class.java) }.getOrNull() }
        ?: return null
    return request.takeIf { it.requestId == requestId && it.isValid(now) }
}
