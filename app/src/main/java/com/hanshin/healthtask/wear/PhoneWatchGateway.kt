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
import com.hanshin.healthtask.domain.RecordMode
import com.hanshin.healthtask.domain.latestCompletedSet
import com.hanshin.healthtask.shared.WearPaths
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRoutineExercise
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearRoutineSet
import kotlinx.coroutines.tasks.await

class PhoneWatchGateway(context: Context) {
    private val dataClient = Wearable.getDataClient(context)
    private val gson = Gson()

    suspend fun publishRoutine(
        routine: RoutineWithItems?,
        exercises: List<ExerciseEntity>,
        sessions: List<WorkoutSessionWithItems>,
        restTimerSeconds: Int,
    ) {
        if (routine == null) {
            dataClient.deleteDataItems("wear:${WearPaths.TODAY_ROUTINE}".toUri(), DataClient.FILTER_LITERAL).await()
            return
        }
        val exerciseMap = exercises.associateBy { it.id }
        val payload = WearRoutinePayload(
            routineId = routine.routine.id,
            title = routine.routine.name,
            restTimerSeconds = restTimerSeconds,
            exercises = routine.items.sortedBy { it.orderIndex }.map { plan ->
                val exercise = exerciseMap[plan.exerciseId]
                val recent = latestCompletedSet(sessions, plan.exerciseId)
                WearRoutineExercise(
                    id = plan.id,
                    exerciseId = plan.exerciseId,
                    name = exercise?.name ?: plan.exerciseId,
                    order = plan.orderIndex,
                    recordMode = if (plan.recordMode == RecordMode.CARDIO) WearRecordMode.CARDIO else WearRecordMode.SETS,
                    category = plan.category.name,
                    sets = if (plan.recordMode == RecordMode.SETS) {
                        (1..(plan.setCount ?: 3)).map { order ->
                            WearRoutineSet(
                                order = order,
                                reps = recent?.actualReps ?: plan.targetReps,
                                weightKg = recent?.actualWeightKg ?: plan.targetWeightKg,
                            )
                        }
                    } else emptyList(),
                    targetDurationMin = plan.targetDurationMin,
                    targetDistanceKm = plan.targetDistanceKm,
                )
            },
            updatedAt = System.currentTimeMillis(),
        )
        val request = PutDataMapRequest.create(WearPaths.TODAY_ROUTINE).apply {
            dataMap.putString(WearPaths.KEY_JSON, gson.toJson(payload))
            dataMap.putLong(WearPaths.KEY_UPDATED_AT, payload.updatedAt)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request).await()
    }
}
