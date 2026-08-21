package com.hanshin.healthtask.wear.data

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.google.gson.Gson
import com.hanshin.healthtask.shared.WearPaths
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.wear.WatchApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class WearRoutineListenerService : WearableListenerService() {
    private val gson = Gson()

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val store = (application as WatchApplication).store
        runBlocking(Dispatchers.IO) {
            dataEvents.forEach { event ->
                if (event.dataItem.uri.path != WearPaths.TODAY_ROUTINE) return@forEach
                when (event.type) {
                    DataEvent.TYPE_CHANGED -> {
                        val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WearPaths.KEY_JSON)
                            ?: return@forEach
                        runCatching { gson.fromJson(json, WearRoutinePayload::class.java) }
                            .getOrNull()?.let { store.saveRoutine(it) }
                    }
                    DataEvent.TYPE_DELETED -> store.clearRoutine()
                }
            }
        }
    }
}
