package com.hanshin.healthtask.wear

import android.app.Application
import com.hanshin.healthtask.wear.data.WatchDataGateway
import com.hanshin.healthtask.wear.data.WatchStore
import com.hanshin.healthtask.wear.health.WearTelemetryRecorder
import java.io.File

class WatchApplication : Application() {
    lateinit var store: WatchStore
        private set
    lateinit var dataGateway: WatchDataGateway
        private set
    lateinit var sensorRecorder: WearTelemetryRecorder
        private set

    override fun onCreate() {
        super.onCreate()
        store = WatchStore(this)
        dataGateway = WatchDataGateway(this)
        sensorRecorder = WearTelemetryRecorder(File(filesDir, "run-sensors"))
    }
}
