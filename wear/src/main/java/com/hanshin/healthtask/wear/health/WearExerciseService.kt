package com.hanshin.healthtask.wear.health

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WearExerciseService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private val callbackExecutor: Executor by lazy { ContextCompat.getMainExecutor(this) }
    private var callbackRegistered = false
    private var heartRateTotal = 0.0
    private var heartRateSamples = 0L

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
            WearMetricsRepository.update { current ->
                current.copy(
                    heartRateBpm = heartRate ?: current.heartRateBpm,
                    averageHeartRateBpm = averageHeartRate ?: current.averageHeartRateBpm,
                    caloriesKcal = calories ?: current.caloriesKcal,
                    distanceKm = distanceMeters?.div(1_000.0) ?: current.distanceKm,
                    activeDurationMillis = update.activeDurationCheckpoint?.activeDuration?.toMillis()
                        ?: current.activeDurationMillis,
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
                startForeground(NOTIFICATION_ID, notification())
                startTracking(intent.getBooleanExtra(EXTRA_CARDIO_ONLY, false))
            }
            ACTION_PAUSE -> scope.launch { runCatching { exerciseClient.pauseExerciseAsync().get() }
                .onSuccess { WearMetricsRepository.update { it.copy(paused = true) } }
                .onFailure(::reportError) }
            ACTION_RESUME -> scope.launch { runCatching { exerciseClient.resumeExerciseAsync().get() }
                .onSuccess { WearMetricsRepository.update { it.copy(paused = false) } }
                .onFailure(::reportError) }
            ACTION_STOP -> stopTracking()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTracking(cardioOnly: Boolean) {
        WearMetricsRepository.reset()
        heartRateTotal = 0.0
        heartRateSamples = 0L
        scope.launch {
            runCatching {
                val capabilities = exerciseClient.getCapabilitiesAsync().get()
                val preferredType = if (cardioOnly) ExerciseType.RUNNING else ExerciseType.STRENGTH_TRAINING
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
                if (cardioOnly) requested += DataType.DISTANCE_TOTAL
                exerciseClient.setUpdateCallback(callbackExecutor, callback)
                exerciseClient.startExerciseAsync(ExerciseConfig(
                    exerciseType = exerciseType,
                    dataTypes = requested.intersect(supported),
                    isAutoPauseAndResumeEnabled = false,
                    isGpsEnabled = cardioOnly,
                )).get()
            }.onFailure(::reportError)
        }
    }

    private fun stopTracking() {
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
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, WearMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fitness)
            .setContentTitle(getString(R.string.exercise_notification_title))
            .setContentText("워치에서 운동을 기록하고 있습니다$heartRate")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .build()
    }

    override fun onDestroy() {
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

        fun command(context: Context, action: String, cardioOnly: Boolean = false): Intent =
            Intent(context, WearExerciseService::class.java).apply {
                this.action = action
                putExtra(EXTRA_CARDIO_ONLY, cardioOnly)
            }
    }
}
