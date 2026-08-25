package com.hanshin.healthtask.wear.health

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import androidx.core.app.NotificationCompat
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
import com.hanshin.healthtask.wear.WearMainActivity
import com.hanshin.healthtask.shared.WearRunningMetrics
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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

    private val callback = object : ExerciseUpdateCallback {
        override fun onRegistered() { callbackRegistered = true }

        override fun onRegistrationFailed(throwable: Throwable) {
            WearMetricsRepository.update { it.copy(error = throwable.message ?: "센서 연결 실패") }
        }

        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val metrics = update.latestMetrics
            val heartRate = metrics.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
            if (heartRate != null) {
                heartRateTotal += heartRate
                heartRateSamples++
            }
            val averageHeartRate = metrics.getData(DataType.HEART_RATE_BPM_STATS)?.average
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
            }
            WearMetricsRepository.update { current ->
                current.copy(
                    heartRateBpm = heartRate ?: current.heartRateBpm,
                    averageHeartRateBpm = averageHeartRate ?: current.averageHeartRateBpm,
                    caloriesKcal = calories ?: current.caloriesKcal,
                    distanceKm = distanceMeters?.div(1_000.0) ?: current.distanceKm,
                    speedMetersPerSecond = speedMetersPerSecond ?: current.speedMetersPerSecond,
                    activeDurationMillis = activeDurationMillis ?: current.activeDurationMillis,
                    completedKilometers = completedKilometers,
                    lastLapDurationMillis = lastLapDurationMillis ?: current.lastLapDurationMillis,
                    error = null,
                )
            }
            updateNotification()
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gpsRunning = intent.getBooleanExtra(EXTRA_CARDIO_ONLY, false)
                startInForeground(gpsRunning)
                startTracking(gpsRunning)
            }
            ACTION_PAUSE -> {
                pauseRouteClock()
                stopLocationUpdates()
                WearMetricsRepository.update { it.copy(paused = true, speedMetersPerSecond = null) }
                scope.launch {
                    runCatching { exerciseClient.pauseExerciseAsync().get() }
                        .onFailure(::reportError)
                }
            }
            ACTION_RESUME -> {
                resumeRouteClock()
                if (gpsRouteEnabled) runCatching { startLocationUpdates() }.onFailure(::reportError)
                WearMetricsRepository.update { it.copy(paused = false) }
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
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), serviceType)
    }

    private fun startTracking(gpsRunning: Boolean) {
        WearMetricsRepository.reset()
        heartRateTotal = 0.0
        heartRateSamples = 0L
        completedKilometers = 0
        lastLapActiveDurationMillis = 0L
        gpsRouteEnabled = gpsRunning
        resetRouteClock()
        scope.launch {
            runCatching {
                if (gpsRunning && !getSystemService(LocationManager::class.java)
                        .isProviderEnabled(LocationManager.GPS_PROVIDER)
                ) {
                    error("워치 위치 서비스를 켜 주세요.")
                }
                if (gpsRunning) startLocationUpdates()
                val capabilities = exerciseClient.getCapabilitiesAsync().get()
                val preferredType = if (gpsRunning) ExerciseType.RUNNING else ExerciseType.STRENGTH_TRAINING
                val exerciseType = when {
                    preferredType in capabilities.supportedExerciseTypes -> preferredType
                    ExerciseType.WORKOUT in capabilities.supportedExerciseTypes -> ExerciseType.WORKOUT
                    else -> capabilities.supportedExerciseTypes.firstOrNull()
                        ?: error("지원되는 운동 센서 유형이 없습니다.")
                }
                val supported = capabilities.getExerciseTypeCapabilities(exerciseType).supportedDataTypes
                val requested = mutableSetOf<DataType<*, *>>(
                    DataType.HEART_RATE_BPM,
                    DataType.HEART_RATE_BPM_STATS,
                    DataType.CALORIES_TOTAL,
                )
                if (gpsRunning) requested += DataType.DISTANCE_TOTAL
                if (gpsRunning) requested += DataType.SPEED
                exerciseClient.setUpdateCallback(callbackExecutor, callback)
                exerciseClient.startExerciseAsync(ExerciseConfig(
                    exerciseType = exerciseType,
                    dataTypes = requested.intersect(supported),
                    isAutoPauseAndResumeEnabled = false,
                    isGpsEnabled = gpsRunning,
                )).get()
            }.onFailure(::reportError)
        }
    }

    private fun stopTracking() {
        stopLocationUpdates()
        gpsRouteEnabled = false
        scope.launch {
            runCatching { exerciseClient.endExerciseAsync().get() }
            if (callbackRegistered) runCatching { exerciseClient.clearUpdateCallbackAsync(callback).get() }
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
        if (location.elapsedRealtimeNanos > 0L && ageNanos > MAX_LOCATION_AGE_NANOS) return
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val metrics = WearMetricsRepository.metrics.value
        val heartRate = metrics.heartRateBpm?.toInt()?.let { " · 심박 $it" }.orEmpty()
        val running = metrics.distanceKm?.let { distance ->
            val pace = WearRunningMetrics.currentPaceMinutesPerKm(metrics.speedMetersPerSecond)
            "%.2fkm · %s/km%s".format(distance, formatPace(pace), heartRate)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fitness)
            .setContentTitle(getString(R.string.exercise_notification_title))
            .setContentText(running ?: "워치에서 운동을 기록하고 있습니다$heartRate")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
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

        private fun formatPace(paceMinutesPerKm: Double?): String {
            val totalSeconds = paceMinutesPerKm?.takeIf { it.isFinite() && it in 0.0..60.0 }
                ?.times(60.0)?.toInt() ?: return "--'--\""
            return "%d'%02d\"".format(totalSeconds / 60, totalSeconds % 60)
        }
    }
}
