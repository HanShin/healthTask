package com.hanshin.healthtask.wear.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.hanshin.healthtask.wear.R
import com.hanshin.healthtask.wear.WatchApplication
import com.hanshin.healthtask.shared.RunSensorMetadata
import com.hanshin.healthtask.shared.RunSensorRecord
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.floor

class WearExerciseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val callbackExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    private var callbackRegistered = false
    private var heartRateTotal = 0.0
    private var heartRateSamples = 0L
    private var completedKilometers = 0
    private var lastLapActiveDurationMillis = 0L
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val locationListener = LocationListener(::recordLocation)
    private val routeRecorder = WearRouteRecorder()
    private var gpsRouteEnabled = false
    private var locationUpdatesActive = false
    private var routeStartedAtElapsedMillis = 0L
    private var routePausedAtElapsedMillis: Long? = null
    private var routeAccumulatedPausedMillis = 0L
    private var notificationActive = false
    private val workoutNotification by lazy { WearWorkoutNotification(this, NOTIFICATION_ID, CHANNEL_ID) }
    private val sensorRecorder get() = (application as WatchApplication).sensorRecorder
    @Volatile private var sensorSessionId: String? = null
    private var bootEpochMillis = 0L
    private var lastExerciseState: String? = null
    private var exerciseEnded = CompletableDeferred<Unit>()
    private var stopping = false
    private var startJob: Job? = null

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() { callbackRegistered = true }

        override fun onRegistrationFailed(throwable: Throwable) {
            WearMetricsRepository.update { it.copy(error = throwable.message ?: "센서 연결 실패") }
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            recordSensors(update.sensorRecords(bootEpochMillis))
            val exerciseState = update.exerciseStateInfo.state
            if (exerciseState.name != lastExerciseState) {
                lastExerciseState = exerciseState.name
                recordEvent("exerciseStateObserved", exerciseState.name)
            }
            val metrics = update.latestMetrics
            val heartRate = metrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.takeIf { it.isFinite() && it > 0 }
            metrics.getData(DataType.HEART_RATE_BPM).filter { it.value.isFinite() && it.value > 0 }.forEach { point ->
                heartRateTotal += point.value
                heartRateSamples++
            }
            val averageHeartRate = metrics.getData(DataType.HEART_RATE_BPM_STATS)?.average?.takeIf { it.isFinite() && it > 0 }
                ?: heartRateTotal.takeIf { heartRateSamples > 0 }?.div(heartRateSamples)
            val calories = metrics.getData(DataType.CALORIES_TOTAL)?.total
            val distanceMeters = metrics.getData(DataType.DISTANCE_TOTAL)?.total
            val speedMetersPerSecond = metrics.getData(DataType.SPEED).lastOrNull()?.value
            val activeDurationMillis = update.activeDurationCheckpoint?.activeDuration?.toMillis()
            var lastLapDurationMillis: Long? = null
            val newCompletedKilometers = distanceMeters?.let { floor(it / 1_000.0).toInt() } ?: completedKilometers
            if (newCompletedKilometers > completedKilometers) {
                activeDurationMillis?.let { activeDuration ->
                    lastLapDurationMillis = (activeDuration - lastLapActiveDurationMillis).coerceAtLeast(0L)
                    lastLapActiveDurationMillis = activeDuration
                }
                completedKilometers = newCompletedKilometers
                signalLapComplete()
                if (!stopping) scope.launch {
                    runCatching { exerciseClient.markLapAsync().get(5, TimeUnit.SECONDS) }
                        .onFailure { recordEvent("quality", "LAP_MARK_FAILED") }
                }
            }
            WearMetricsRepository.update { current ->
                current.copy(
                    heartRateBpm = heartRate ?: current.heartRateBpm,
                    averageHeartRateBpm = averageHeartRate ?: current.averageHeartRateBpm,
                    caloriesKcal = calories ?: current.caloriesKcal,
                    distanceKm = distanceMeters?.div(1_000.0) ?: current.distanceKm,
                    speedMetersPerSecond = speedMetersPerSecond ?: current.speedMetersPerSecond,
                    cadenceStepsPerMinute = metrics.getData(DataType.STEPS_PER_MINUTE).lastOrNull()?.value?.toDouble()
                        ?: current.cadenceStepsPerMinute,
                    stepsTotal = metrics.getData(DataType.STEPS_TOTAL)?.total ?: current.stepsTotal,
                    elevationGainMeters = metrics.getData(DataType.ELEVATION_GAIN_TOTAL)?.total ?: current.elevationGainMeters,
                    activeDurationMillis = activeDurationMillis ?: current.activeDurationMillis,
                    completedKilometers = completedKilometers,
                    lastLapDurationMillis = lastLapDurationMillis ?: current.lastLapDurationMillis,
                )
            }
            updateNotification()
            if (exerciseState.isEnded) exerciseEnded.complete(Unit)
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
            recordSensors(lapSummary.sensorRecords())
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            recordEvent(requestedRunSensors[dataType] ?: dataType.name, availability.toString())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gpsRunning = intent.getBooleanExtra(EXTRA_CARDIO_ONLY, false)
                if (!gpsRunning) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startInForeground(gpsRunning)
                startTracking(gpsRunning)
            }
            ACTION_PAUSE -> {
                recordEvent("userAction", "PAUSE_REQUESTED")
                pauseRouteClock()
                stopLocationUpdates()
                WearMetricsRepository.update { it.copy(paused = true, speedMetersPerSecond = null) }
                updateNotification()
                scope.launch {
                    runCatching { exerciseClient.pauseExerciseAsync().get() }
                        .onFailure(::reportError)
                }
            }
            ACTION_RESUME -> {
                recordEvent("userAction", "RESUME_REQUESTED")
                resumeRouteClock()
                if (gpsRouteEnabled) runCatching { startLocationUpdates() }.onFailure(::reportError)
                WearMetricsRepository.update { it.copy(paused = false) }
                updateNotification()
                scope.launch {
                    runCatching { exerciseClient.resumeExerciseAsync().get() }
                        .onFailure(::reportError)
                }
            }
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(gpsRunning: Boolean) {
        @Suppress("DEPRECATION")
        val serviceType = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or
                    if (gpsRunning) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
            gpsRunning -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, workoutNotification.build(WearMetrics(tracking = true)), serviceType)
        notificationActive = true
    }

    private fun startTracking(gpsRunning: Boolean) {
        WearMetricsRepository.reset()
        heartRateTotal = 0.0
        heartRateSamples = 0L
        completedKilometers = 0
        lastLapActiveDurationMillis = 0L
        stopping = false
        exerciseEnded = CompletableDeferred()
        lastExerciseState = null
        gpsRouteEnabled = gpsRunning
        resetRouteClock()
        startJob = scope.launch {
            runCatching {
                if (gpsRunning && !getSystemService(LocationManager::class.java)
                        .isProviderEnabled(LocationManager.GPS_PROVIDER)
                ) {
                    error("워치 위치 서비스를 켜 주세요.")
                }
                val capabilities = exerciseClient.getCapabilitiesAsync().get(10, TimeUnit.SECONDS)
                val preferredType = ExerciseType.RUNNING
                val exerciseType = when {
                    preferredType in capabilities.supportedExerciseTypes -> preferredType
                    ExerciseType.WORKOUT in capabilities.supportedExerciseTypes -> ExerciseType.WORKOUT
                    else -> capabilities.supportedExerciseTypes.firstOrNull()
                        ?: error("지원되는 운동 센서 유형이 없습니다.")
                }
                val supported = capabilities.getExerciseTypeCapabilities(exerciseType).supportedDataTypes
                val requested = requestedRunSensors.keys
                val active = (application as WatchApplication).store.activeSession.first()
                    ?: error("진행 중인 러닝 정보를 찾을 수 없습니다.")
                val anchorElapsed = SystemClock.elapsedRealtime()
                val anchorEpoch = System.currentTimeMillis()
                bootEpochMillis = anchorEpoch - anchorElapsed
                sensorRecorder.begin(RunSensorMetadata(
                    sessionId = active.sessionId, startedAtEpochMillis = active.startedAt,
                    clockAnchorEpochMillis = anchorEpoch, clockAnchorElapsedRealtimeMillis = anchorElapsed,
                    timeZoneId = ZoneId.systemDefault().id, manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL, sdkInt = Build.VERSION.SDK_INT,
                    requestedMetrics = requestedRunSensors.values.toList(),
                    supportedMetrics = requestedRunSensors.filterKeys { it in supported }.values.toList(),
                    unsupportedMetrics = requestedRunSensors.filterKeys { it !in supported }.values.toList(),
                ))
                sensorSessionId = active.sessionId
                recordEvent("recorder", "STARTED")
                if (stopping) return@launch
                if (gpsRunning) withContext(Dispatchers.Main) { startLocationUpdates() }
                exerciseClient.setUpdateCallback(callbackExecutor, callback)
                exerciseClient.startExerciseAsync(ExerciseConfig(
                    exerciseType = exerciseType,
                    dataTypes = requested.intersect(supported),
                    isAutoPauseAndResumeEnabled = false,
                    isGpsEnabled = gpsRunning,
                )).get(10, TimeUnit.SECONDS)
            }.onFailure(::reportError)
        }
    }

    private fun stopTracking() {
        if (stopping) return
        stopping = true
        recordEvent("userAction", "FINISH_REQUESTED")
        // Ignore a late sensor callback so it cannot recreate the completed run's notification.
        notificationActive = false
        stopLocationUpdates()
        gpsRouteEnabled = false
        scope.launch {
            startJob?.join()
            withContext(Dispatchers.Main) { stopLocationUpdates() }
            // Preserve the final partial lap too. Lap distances are observed intervals, not synthetic exact km.
            runCatching { exerciseClient.markLapAsync().get(5, TimeUnit.SECONDS) }
                .onFailure { recordEvent("quality", "FINAL_LAP_UNAVAILABLE") }
            // Keep the callback until the final batched sensor update has been persisted.
            runCatching { exerciseClient.endExerciseAsync().get(10, TimeUnit.SECONDS) }
                .onFailure { recordEvent("quality", "END_REQUEST_FAILED") }
            if (withTimeoutOrNull(10_000L) { exerciseEnded.await(); true } != true) {
                recordEvent("quality", "FINAL_SENSOR_UPDATE_TIMEOUT")
            }
            runCatching { exerciseClient.clearUpdateCallbackAsync(callback).get(5, TimeUnit.SECONDS) }
            withContext(Dispatchers.Main) { sensorSessionId = null }
            WearMetricsRepository.update { it.copy(tracking = false, paused = false) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun reportError(error: Throwable) {
        WearMetricsRepository.update { it.copy(error = error.cause?.message ?: error.message ?: "운동 센서 오류") }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (locationUpdatesActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            error("러닝 경로 기록에는 정확한 위치 권한이 필요합니다.")
        }
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            LOCATION_INTERVAL_MILLIS,
            LOCATION_MIN_DISTANCE_METERS,
            callbackExecutor,
            locationListener,
        )
        locationUpdatesActive = true
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        locationManager.removeUpdates(locationListener)
        locationUpdatesActive = false
    }

    private fun recordLocation(location: android.location.Location) {
        val metrics = WearMetricsRepository.metrics.value
        if (!locationUpdatesActive || !metrics.tracking || metrics.paused) return
        if (!location.hasAccuracy()) return
        val ageNanos = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        if (location.elapsedRealtimeNanos <= 0L || ageNanos < 0L) return
        if (location.elapsedRealtimeNanos > 0L && ageNanos > MAX_LOCATION_AGE_NANOS) return
        recordSensors(listOf(RunSensorRecord(
            kind = "location", timestampEpochMillis = bootEpochMillis + location.elapsedRealtimeNanos / 1_000_000,
            elapsedRealtimeMillis = location.elapsedRealtimeNanos / 1_000_000,
            activeElapsedMillis = routeElapsedMillis(location.elapsedRealtimeNanos / 1_000_000), latitude = location.latitude, longitude = location.longitude,
            altitudeMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() },
            horizontalAccuracyMeters = location.accuracy.toDouble(),
            verticalAccuracyMeters = location.verticalAccuracyMeters.toDouble().takeIf { location.hasVerticalAccuracy() },
            speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
            bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() },
            accuracy = if (location.accuracy > 40f) "LOW_ACCURACY" else "GPS_FIX",
        )))
        routeRecorder.record(
            latitude = location.latitude,
            longitude = location.longitude,
            elapsedMillis = routeElapsedMillis(),
            accuracyMeters = location.accuracy,
        )?.let { route ->
            WearMetricsRepository.update { current -> current.copy(route = route) }
        }
    }

    private fun resetRouteClock() {
        routeStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        routePausedAtElapsedMillis = null
        routeAccumulatedPausedMillis = 0L
        routeRecorder.reset()
    }

    private fun recordSensors(records: List<RunSensorRecord>) {
        sensorSessionId?.let { sensorRecorder.append(it, records) }
    }

    private fun recordEvent(metric: String, state: String,
        timestamp: Long = System.currentTimeMillis(), elapsed: Long = SystemClock.elapsedRealtime()) {
        recordSensors(listOf(RunSensorRecord("event", timestamp, elapsed, metric = metric, state = state)))
    }

    private fun pauseRouteClock() {
        if (routePausedAtElapsedMillis == null) routePausedAtElapsedMillis = SystemClock.elapsedRealtime()
    }

    private fun resumeRouteClock() {
        val now = SystemClock.elapsedRealtime()
        routePausedAtElapsedMillis?.let { pausedAt ->
            routeAccumulatedPausedMillis += (now - pausedAt).coerceAtLeast(0L)
        }
        routePausedAtElapsedMillis = null
        routeRecorder.breakSegment()
    }

    private fun routeElapsedMillis(now: Long = SystemClock.elapsedRealtime()): Long =
        ((routePausedAtElapsedMillis ?: now) - routeStartedAtElapsedMillis - routeAccumulatedPausedMillis)
            .coerceAtLeast(0L)

    private fun signalLapComplete() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 180L, 100L, 180L), -1),
        )
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.exercise_notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun updateNotification() {
        if (!notificationActive) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification = workoutNotification.build(WearMetricsRepository.metrics.value)

    override fun onDestroy() {
        notificationActive = false
        stopLocationUpdates()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.hanshin.healthtask.wear.START"
        const val ACTION_PAUSE = "com.hanshin.healthtask.wear.PAUSE"
        const val ACTION_RESUME = "com.hanshin.healthtask.wear.RESUME"
        const val ACTION_STOP = "com.hanshin.healthtask.wear.STOP"
        const val EXTRA_CARDIO_ONLY = "cardio_only"
        private const val CHANNEL_ID = "active_workout"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MILLIS = 2_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 2f
        private const val MAX_LOCATION_AGE_NANOS = 30_000_000_000L

        fun command(context: Context, action: String, cardioOnly: Boolean = false): Intent =
            Intent(context, WearExerciseService::class.java).apply {
                this.action = action
                putExtra(EXTRA_CARDIO_ONLY, cardioOnly)
            }

    }
}
