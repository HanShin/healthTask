package com.hanshin.healthtask.wear

import android.app.Application
import com.hanshin.healthtask.wear.data.WatchDataGateway
import com.hanshin.healthtask.wear.data.WatchStore

class WatchApplication : Application() {
    lateinit var store: WatchStore
        private set
    lateinit var dataGateway: WatchDataGateway
        private set

    override fun onCreate() {
        super.onCreate()
        store = WatchStore(this)
        dataGateway = WatchDataGateway(this)
    }
}
