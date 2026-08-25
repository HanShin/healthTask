package com.hanshin.healthtask

import android.app.Application
import com.hanshin.healthtask.data.AppPreferences
import com.hanshin.healthtask.data.BackupCodec
import com.hanshin.healthtask.data.HealthTaskRepository
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.health.AndroidHealthConnectGateway
import com.hanshin.healthtask.health.HealthConnectGateway
import com.hanshin.healthtask.health.HealthSyncManager
import com.hanshin.healthtask.running.RunningTracker
import com.hanshin.healthtask.wear.PhoneWatchGateway

class HealthTaskApplication : Application() {
    lateinit var database: HealthTaskDatabase
        private set
    lateinit var repository: HealthTaskRepository
        private set
    lateinit var preferences: AppPreferences
        private set
    lateinit var healthConnect: HealthConnectGateway
        private set
    lateinit var syncManager: HealthSyncManager
        private set
    lateinit var backupCodec: BackupCodec
        private set
    lateinit var phoneWatchGateway: PhoneWatchGateway
        private set
    lateinit var runningTracker: RunningTracker
        private set

    override fun onCreate() {
        super.onCreate()
        database = HealthTaskDatabase.create(this)
        repository = HealthTaskRepository(database)
        preferences = AppPreferences(this)
        healthConnect = AndroidHealthConnectGateway(this)
        syncManager = HealthSyncManager(database, healthConnect, preferences)
        backupCodec = BackupCodec(database)
        phoneWatchGateway = PhoneWatchGateway(this)
        runningTracker = RunningTracker(this)
    }
}
