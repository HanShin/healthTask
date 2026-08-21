package com.hanshin.healthtask.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

class PhoneWearListenerService : WearableListenerService() {
    private val gson = Gson()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val app = application as HealthTaskApplication
        runBlocking(Dispatchers.IO) {
            dataEvents.forEach { event ->
                val item = event.dataItem
                if (event.type != DataEvent.TYPE_CHANGED ||
                    !item.uri.path.orEmpty().startsWith(WearPaths.COMPLETED_WORKOUT_PREFIX)
                ) return@forEach
                val json = DataMapItem.fromDataItem(item).dataMap.getString(WearPaths.KEY_JSON)
                    ?: return@forEach
                runCatching {
                    val workout = gson.fromJson(json, WearCompletedWorkout::class.java)
                    app.repository.importWearWorkout(workout)
                    Wearable.getDataClient(this@PhoneWearListenerService)
                        .deleteDataItems(item.uri)
                        .await()
                }
            }
        }
    }
}
