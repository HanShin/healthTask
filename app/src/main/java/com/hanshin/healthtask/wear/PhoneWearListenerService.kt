package com.hanshin.healthtask.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.hanshin.healthtask.HealthTaskApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

class PhoneWearListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val importer = (application as HealthTaskApplication).watchWorkoutImporter
        runBlocking(Dispatchers.IO) {
            dataEvents.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED) importer.importItem(event.dataItem)
            }
        }
    }
}
