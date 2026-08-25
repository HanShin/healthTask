package com.hanshin.healthtask.wear

import android.content.Context
import androidx.core.net.toUri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.shared.WearPaths
import kotlinx.coroutines.tasks.await

class PhoneWatchGateway(context: Context) {
    private val dataClient = Wearable.getDataClient(context)
    private val gson = Gson()

    suspend fun publishRoutine(
        routine: RoutineWithItems?,
        plannedSlot: PlanSlotEntity?,
        exercises: List<ExerciseEntity>,
        sessions: List<WorkoutSessionWithItems>,
        restTimerSeconds: Int,
    ) {
        val payload = buildWearRoutinePayload(
            routine = routine,
            plannedSlot = plannedSlot,
            exercises = exercises,
            sessions = sessions,
            restTimerSeconds = restTimerSeconds,
        )
        if (payload == null) {
            dataClient.deleteDataItems("wear:${WearPaths.TODAY_ROUTINE}".toUri(), DataClient.FILTER_LITERAL).await()
            return
        }
        val request = PutDataMapRequest.create(WearPaths.TODAY_ROUTINE).apply {
            dataMap.putString(WearPaths.KEY_JSON, gson.toJson(payload))
            dataMap.putLong(WearPaths.KEY_UPDATED_AT, payload.updatedAt)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }
}
