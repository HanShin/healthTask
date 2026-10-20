package com.hanshin.healthtask

import android.app.Application
import com.hanshin.healthtask.cloud.CloudSync
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.hanshin.healthtask.data.AppPreferences
import com.hanshin.healthtask.data.BackupCodec
import com.hanshin.healthtask.data.HealthTaskRepository
import com.hanshin.healthtask.data.db.HealthTaskDatabase
import com.hanshin.healthtask.health.AndroidHealthConnectGateway
import com.hanshin.healthtask.health.HealthConnectGateway
import com.hanshin.healthtask.health.HealthSyncManager
import com.hanshin.healthtask.running.RunningTracker
import com.hanshin.healthtask.wear.PhoneWatchGateway
import com.hanshin.healthtask.wear.WatchWorkoutImporter

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
    lateinit var watchWorkoutImporter: WatchWorkoutImporter
        private set

    lateinit var cloudSync: CloudSync
        private set
    private val cloudScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        database = HealthTaskDatabase.create(this)
        repository = HealthTaskRepository(database)
        preferences = AppPreferences(this)
        healthConnect = AndroidHealthConnectGateway(this)
        syncManager = HealthSyncManager(database, healthConnect, preferences) { CloudSync.enqueue(this) }
        backupCodec = BackupCodec(database)
        phoneWatchGateway = PhoneWatchGateway(this)
        runningTracker = RunningTracker(this)
        watchWorkoutImporter = WatchWorkoutImporter(this)
        cloudSync = CloudSync(this)
        CloudSync.schedule(this)
        cloudScope.launch {
            database.invalidationTracker.createFlow("workout_sessions", "workout_items", "set_records", "health_measurements", "inbody_records", "samsung_workout_links", "run_telemetry_chunks", emitInitialState = false)
                .debounce(2_000).collect { CloudSync.enqueue(this@HealthTaskApplication) }
        }
    }
}
