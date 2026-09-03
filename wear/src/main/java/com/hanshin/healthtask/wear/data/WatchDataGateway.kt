package com.hanshin.healthtask.wear.data

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearPaths
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import kotlinx.coroutines.tasks.await

class WatchDataGateway(context: Context) {
    private val dataClient = Wearable.getDataClient(context)
    private val gson = Gson()

    /** DataItems remain queued while the phone is disconnected and are removed after phone import. */
    suspend fun sendCompletedWorkout(workout: WearCompletedWorkout) {
        val path = WearPaths.COMPLETED_WORKOUT_PREFIX + workout.sessionId
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(WearPaths.KEY_JSON, gson.toJson(workout))
            dataMap.putLong(WearPaths.KEY_UPDATED_AT, workout.endedAt)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    suspend fun readLatestRoutine(): WearRoutinePayload? {
        val items = dataClient.getDataItems(
            "wear://*${WearPaths.TODAY_ROUTINE}".toUri(),
            DataClient.FILTER_LITERAL,
        ).await()
        return try {
            items.firstOrNull()?.let { item ->
                DataMapItem.fromDataItem(item).dataMap.getString(WearPaths.KEY_JSON)
                    ?.let { gson.fromJson(it, WearRoutinePayload::class.java) }
            }
        } finally {
            items.release()
        }
    }

    suspend fun readLatestStartRequest(now: Long = System.currentTimeMillis()): WearStartWorkoutRequest? {
        val items = dataClient.getDataItems(
            "wear://*${WearPaths.START_WORKOUT_PREFIX}".toUri(),
            DataClient.FILTER_PREFIX,
        ).await()
        return try {
            items.asSequence()
                .mapNotNull { item ->
                    val json = runCatching {
                        DataMapItem.fromDataItem(item).dataMap.getString(WearPaths.KEY_JSON)
                    }.getOrNull()
                    decodeStartWorkoutRequest(json, item.uri.path, now, gson)
                }
                .maxWithOrNull(compareBy<WearStartWorkoutRequest> { it.requestedAt }.thenBy { it.requestId })
        } finally {
            items.release()
        }
    }

    suspend fun consumeStartRequest(requestId: String) {
        require(requestId.isNotBlank() && '/' !in requestId) { "Invalid workout start request ID." }
        dataClient.deleteDataItems(
            "wear://*${WearPaths.START_WORKOUT_PREFIX}$requestId".toUri(),
            DataClient.FILTER_LITERAL,
        ).await()
    }
}
