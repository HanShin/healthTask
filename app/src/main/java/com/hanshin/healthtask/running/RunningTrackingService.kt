package com.hanshin.healthtask.running

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.speech.tts.TextToSpeech
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.hanshin.healthtask.HealthTaskApplication
import com.hanshin.healthtask.MainActivity
import com.hanshin.healthtask.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class RunningTrackingService : Service(), LocationListener {
    private lateinit var tracker: RunningTracker
    private lateinit var locationManager: LocationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var receivingLocations = false
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var voiceGuidanceEnabled = true

    override fun onCreate() {
        super.onCreate()
        tracker = (application as HealthTaskApplication).runningTracker
        locationManager = getSystemService(LocationManager::class.java)
        createNotificationChannel()
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val language = textToSpeech?.setLanguage(Locale.KOREAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
                textToSpeechReady = language != TextToSpeech.LANG_MISSING_DATA && language != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
        scope.launch {
            tracker.state
                .map { ServiceSnapshot(it.phase, it.elapsedMillis / 1_000L, (it.distanceMeters / 10.0).toInt()) }
                .distinctUntilChanged()
                .collect { snapshot ->
                    val state = tracker.state.value
                    if (state.isActive) notificationManager().notify(NOTIFICATION_ID, notification(state))
                    if (snapshot.phase == RunningPhase.PAUSED) stopTracking() else if (state.shouldReceiveLocations) startTracking()
                }
        }
        scope.launch {
            tracker.events.collect(::announce)
        }
        scope.launch {
            (application as HealthTaskApplication).preferences.running.collectLatest { preferences ->
                voiceGuidanceEnabled = preferences.voiceGuidanceEnabled
                tracker.setAutoPauseEnabled(preferences.autoPauseEnabled)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionId ->
                tracker.start(sessionId, intent.getStringExtra(EXTRA_PLAN_SLOT_ID))
            }
            ACTION_PAUSE -> tracker.pause()
            ACTION_RESUME -> tracker.resume()
            ACTION_STOP -> {
                stopTracking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val state = tracker.state.value
        if (!state.isActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        if (state.shouldReceiveLocations) startTracking() else stopTracking()
        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        tracker.onLocation(
            GpsSample(
                latitude = location.latitude,
                longitude = location.longitude,
                timestampMillis = location.elapsedRealtimeNanos / 1_000_000L,
                accuracyMeters = location.accuracy,
                speedMetersPerSecond = location.speed.takeIf { location.hasSpeed() },
            )
        )
    }

    override fun onProviderEnabled(provider: String) = refreshGpsAvailability()
    override fun onProviderDisabled(provider: String) = refreshGpsAvailability()
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTracking()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        scope.cancel()
        super.onDestroy()
    }

    private fun startTracking() {
        if (receivingLocations) return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            tracker.setGpsAvailable(false)
            return
        }
        val providers = buildList {
            if (LocationManager.GPS_PROVIDER in locationManager.allProviders) add(LocationManager.GPS_PROVIDER)
            if (LocationManager.NETWORK_PROVIDER in locationManager.allProviders) add(LocationManager.NETWORK_PROVIDER)
        }
        refreshGpsAvailability()
        providers.forEach { provider ->
            locationManager.requestLocationUpdates(provider, 1_000L, 0f, this, Looper.getMainLooper())
        }
        receivingLocations = providers.isNotEmpty()
    }

    private fun refreshGpsAvailability() {
        val available = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)
        tracker.setGpsAvailable(available)
    }

    private fun stopTracking() {
        if (receivingLocations) locationManager.removeUpdates(this)
        receivingLocations = false
    }

    private fun createNotificationChannel() {
        notificationManager().createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "러닝 기록", NotificationManager.IMPORTANCE_LOW).apply {
                description = "진행 중인 GPS 러닝 기록을 표시합니다."
                setShowBadge(false)
            }
        )
    }

    private fun notification(state: RunningUiState): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val paused = state.phase == RunningPhase.PAUSED || state.phase == RunningPhase.AUTO_PAUSED
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleLabel = if (paused) "재개" else "일시정지"
        val toggle = PendingIntent.getService(
            this,
            2,
            Intent(this, RunningTrackingService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(when (state.phase) {
                RunningPhase.AUTO_PAUSED -> "러닝 자동 일시정지"
                RunningPhase.PAUSED -> "러닝 일시정지"
                else -> "러닝 기록 중"
            })
            .setContentText("${formatDuration(state.elapsedMillis)} · ${String.format(java.util.Locale.KOREA, "%.2f km", state.distanceMeters / 1_000.0)}")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .addAction(0, toggleLabel, toggle)
            .build()
    }

    private fun announce(event: RunningEvent) {
        if (!voiceGuidanceEnabled || !textToSpeechReady) return
        val message = when (event) {
            is RunningEvent.LapCompleted -> {
                val pace = event.lap.averagePaceMinPerKm ?: return
                val minutes = pace.toInt()
                val seconds = ((pace - minutes) * 60).toInt().coerceIn(0, 59)
                "${event.lap.index}킬로미터. 구간 페이스 ${minutes}분 ${seconds}초."
            }
            RunningEvent.AutoPaused -> "움직임이 없어 자동으로 일시정지합니다."
            RunningEvent.AutoResumed -> "러닝 기록을 다시 시작합니다."
        }
        textToSpeech?.speak(message, TextToSpeech.QUEUE_ADD, null, "running-${System.nanoTime()}")
    }

    private fun notificationManager(): NotificationManager = getSystemService(NotificationManager::class.java)

    companion object {
        private const val CHANNEL_ID = "running_tracking"
        private const val NOTIFICATION_ID = 4101
        private const val ACTION_START = "com.hanshin.healthtask.running.START"
        private const val ACTION_PAUSE = "com.hanshin.healthtask.running.PAUSE"
        private const val ACTION_RESUME = "com.hanshin.healthtask.running.RESUME"
        private const val ACTION_STOP = "com.hanshin.healthtask.running.STOP"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_PLAN_SLOT_ID = "plan_slot_id"

        fun start(
            context: Context,
            sessionId: String,
            planSlotId: String? = null,
        ) = ContextCompat.startForegroundService(
            context,
            Intent(context, RunningTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_PLAN_SLOT_ID, planSlotId),
        )

        fun pause(context: Context) = context.startService(
            Intent(context, RunningTrackingService::class.java).setAction(ACTION_PAUSE),
        )

        fun resume(context: Context) = context.startService(
            Intent(context, RunningTrackingService::class.java).setAction(ACTION_RESUME),
        )

        fun stop(context: Context) = context.startService(
            Intent(context, RunningTrackingService::class.java).setAction(ACTION_STOP),
        )
    }

    private data class ServiceSnapshot(
        val phase: RunningPhase,
        val elapsedSeconds: Long,
        val distanceDecameters: Int,
    )
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1_000L
    return "%02d:%02d:%02d".format(totalSeconds / 3_600L, (totalSeconds / 60L) % 60L, totalSeconds % 60L)
}
