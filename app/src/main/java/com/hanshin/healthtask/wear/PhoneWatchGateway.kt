package com.hanshin.healthtask.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.gson.Gson
import com.hanshin.healthtask.data.db.ExerciseEntity
import com.hanshin.healthtask.data.db.RoutineWithItems
import com.hanshin.healthtask.data.db.WorkoutSessionWithItems
import com.hanshin.healthtask.data.db.PlanSlotEntity
import com.hanshin.healthtask.shared.WearPaths
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class PhoneWatchGateway(context: Context) {
    private val dataClient = Wearable.getDataClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val remoteActivityHelper = RemoteActivityHelper(
        context,
        ContextCompat.getMainExecutor(context),
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
            dataClient.deleteDataItems("wear://*${WearPaths.TODAY_ROUTINE}".toUri(), DataClient.FILTER_LITERAL).await()
            return
        }
        val request = PutDataMapRequest.create(WearPaths.TODAY_ROUTINE).apply {
            dataMap.putString(WearPaths.KEY_JSON, gson.toJson(payload))
            dataMap.putLong(WearPaths.KEY_UPDATED_AT, payload.updatedAt)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }

    suspend fun requestWorkoutStart(request: WearStartWorkoutRequest): Boolean = runCatching {
        require(request.isValid()) { "운동 시작 요청이 올바르지 않거나 만료됐습니다." }
        pruneStartRequests()
        val path = WearPaths.START_WORKOUT_PREFIX + request.requestId
        val dataRequest = PutDataMapRequest.create(path).apply {
            dataMap.putString(WearPaths.KEY_JSON, gson.toJson(request))
            dataMap.putLong(WearPaths.KEY_UPDATED_AT, request.requestedAt)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(dataRequest).await()
        scheduleExpiryCleanup(path, request.expiresAt)

        val nodes = nodeClient.connectedNodes.await()
        val target = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: error("연결된 워치를 찾을 수 없습니다.")
        val remoteIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.Builder()
                .scheme("healthtask")
                .authority("workout")
                .appendPath("start")
                .appendPath(request.requestId)
                .build(),
        ).addCategory(Intent.CATEGORY_BROWSABLE)
        withContext(Dispatchers.IO) {
            remoteActivityHelper.startRemoteActivity(remoteIntent, target.id)
                .get(10, TimeUnit.SECONDS)
        }
        true
    }.getOrDefault(false)

    private suspend fun pruneStartRequests() {
        val items = dataClient.getDataItems(
            "wear://*${WearPaths.START_WORKOUT_PREFIX}".toUri(),
            DataClient.FILTER_PREFIX,
        ).await()
        try {
            items.forEach { item -> dataClient.deleteDataItems(item.uri, DataClient.FILTER_LITERAL).await() }
        } finally {
            items.release()
        }
    }

    private fun scheduleExpiryCleanup(path: String, expiresAt: Long) {
        scope.launch {
            delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
            runCatching {
                dataClient.deleteDataItems("wear://*$path".toUri(), DataClient.FILTER_LITERAL).await()
            }
        }
    }
}
