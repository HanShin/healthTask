package com.hanshin.healthtask.running

import android.content.Context
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

enum class RunningPhase { IDLE, ACQUIRING_GPS, RUNNING, AUTO_PAUSED, PAUSED, COMPLETED }

data class RunningPoint(
    val latitude: Double,
    val longitude: Double,
    val elapsedMillis: Long,
)

data class RunningLap(
    val index: Int,
    val distanceMeters: Double,
    val durationMillis: Long,
    val totalElapsedMillis: Long,
) {
    val averagePaceMinPerKm: Double?
        get() = distanceMeters.takeIf { it >= 20.0 }
            ?.let { durationMillis / 60_000.0 / (it / 1_000.0) }

    val isComplete: Boolean
        get() = distanceMeters >= RunningMetrics.LAP_DISTANCE_METERS - 0.5
}

sealed interface RunningEvent {
    data class LapCompleted(val lap: RunningLap) : RunningEvent
    data object AutoPaused : RunningEvent
    data object AutoResumed : RunningEvent
}

data class RunningUiState(
    val sessionId: String? = null,
    val phase: RunningPhase = RunningPhase.IDLE,
    val plannedSlotId: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val elapsedMillis: Long = 0L,
    val distanceMeters: Double = 0.0,
    val currentPaceMinPerKm: Double? = null,
    val accuracyMeters: Float? = null,
    val gpsAvailable: Boolean = true,
    val route: List<RunningPoint> = emptyList(),
    val laps: List<RunningLap> = emptyList(),
    val autoPauseEnabled: Boolean = true,
) {
    val isActive: Boolean
        get() = phase == RunningPhase.ACQUIRING_GPS || phase == RunningPhase.RUNNING ||
            phase == RunningPhase.AUTO_PAUSED || phase == RunningPhase.PAUSED
    val isMoving: Boolean
        get() = phase == RunningPhase.ACQUIRING_GPS || phase == RunningPhase.RUNNING
    val shouldReceiveLocations: Boolean
        get() = phase == RunningPhase.ACQUIRING_GPS || phase == RunningPhase.RUNNING ||
            phase == RunningPhase.AUTO_PAUSED
    val averagePaceMinPerKm: Double?
        get() = distanceMeters.takeIf { it >= 20.0 }?.let { elapsedMillis / 60_000.0 / (it / 1_000.0) }
    val currentLapDistanceMeters: Double
        get() = (distanceMeters - laps.filter { it.isComplete }.sumOf { it.distanceMeters }).coerceAtLeast(0.0)
    val currentLapElapsedMillis: Long
        get() = (elapsedMillis - laps.lastOrNull { it.isComplete }?.totalElapsedMillis.orZero()).coerceAtLeast(0L)
}

data class CompletedRun(
    val sessionId: String,
    val plannedSlotId: String?,
    val startedAt: Long,
    val endedAt: Long,
    val elapsedMillis: Long,
    val distanceMeters: Double,
    val route: List<RunningPoint>,
    val laps: List<RunningLap>,
)

class RunningTracker(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var autoPauseEnabled = true
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(restore())
    val state: kotlinx.coroutines.flow.StateFlow<RunningUiState> = mutableState
    private val mutableEvents = MutableSharedFlow<RunningEvent>(extraBufferCapacity = 8)
    val events = mutableEvents.asSharedFlow()

    private var activeSince = preferences.longOrNull(KEY_ACTIVE_SINCE)
    private var accumulatedElapsed = preferences.getLong(KEY_ACCUMULATED, mutableState.value.elapsedMillis)
    private var lastSample: GpsSample? = null
    private var lastAcceptedWallTime: Long? = null
    private var stationarySinceWallTime: Long? = null

    init {
        scope.launch {
            while (true) {
                delay(1_000L)
                tick()
            }
        }
    }

    fun start(
        sessionId: String,
        plannedSlotId: String? = null,
        now: Long = System.currentTimeMillis(),
    ) {
        if (mutableState.value.isActive) return
        require(sessionId.isNotBlank()) { "러닝 세션 ID가 필요합니다." }
        activeSince = now
        accumulatedElapsed = 0L
        lastSample = null
        lastAcceptedWallTime = null
        stationarySinceWallTime = null
        update(
            RunningUiState(
                sessionId = sessionId,
                phase = RunningPhase.ACQUIRING_GPS,
                plannedSlotId = plannedSlotId,
                startedAt = now,
                autoPauseEnabled = autoPauseEnabled,
            )
        )
    }

    fun pause(now: Long = System.currentTimeMillis()) {
        if (!mutableState.value.isMoving && mutableState.value.phase != RunningPhase.AUTO_PAUSED) return
        accumulatedElapsed = elapsedAt(now)
        activeSince = null
        lastSample = null
        stationarySinceWallTime = null
        update(mutableState.value.copy(
            phase = RunningPhase.PAUSED,
            elapsedMillis = accumulatedElapsed,
            currentPaceMinPerKm = null,
        ))
    }

    fun resume(now: Long = System.currentTimeMillis()) {
        val previousPhase = mutableState.value.phase
        if (previousPhase != RunningPhase.PAUSED && previousPhase != RunningPhase.AUTO_PAUSED) return
        activeSince = now
        lastSample = null
        stationarySinceWallTime = null
        update(mutableState.value.copy(
            phase = if (mutableState.value.route.isEmpty()) RunningPhase.ACQUIRING_GPS else RunningPhase.RUNNING,
            currentPaceMinPerKm = null,
        ))
        if (previousPhase == RunningPhase.AUTO_PAUSED) mutableEvents.tryEmit(RunningEvent.AutoResumed)
    }

    fun finish(now: Long = System.currentTimeMillis()): CompletedRun? {
        val current = mutableState.value
        val sessionId = current.sessionId ?: return null
        val startedAt = current.startedAt ?: return null
        if (!current.isActive) return null
        accumulatedElapsed = elapsedAt(now)
        activeSince = null
        lastSample = null
        stationarySinceWallTime = null
        val laps = RunningMetrics.withFinalPartialLap(current.laps, current.distanceMeters, accumulatedElapsed)
        val completed = current.copy(
            phase = RunningPhase.COMPLETED,
            endedAt = now,
            elapsedMillis = accumulatedElapsed,
            currentPaceMinPerKm = null,
            laps = laps,
        )
        update(completed)
        return CompletedRun(
            sessionId = sessionId,
            plannedSlotId = completed.plannedSlotId,
            startedAt = startedAt,
            endedAt = now,
            elapsedMillis = accumulatedElapsed,
            distanceMeters = completed.distanceMeters,
            route = completed.route,
            laps = laps,
        )
    }

    fun reset() {
        activeSince = null
        accumulatedElapsed = 0L
        lastSample = null
        lastAcceptedWallTime = null
        stationarySinceWallTime = null
        preferences.edit().clear().apply()
        mutableState.value = RunningUiState()
    }

    fun setGpsAvailable(available: Boolean) {
        update(mutableState.value.copy(gpsAvailable = available))
    }

    fun setAutoPauseEnabled(enabled: Boolean, now: Long = System.currentTimeMillis()) {
        autoPauseEnabled = enabled
        if (!enabled && mutableState.value.phase == RunningPhase.AUTO_PAUSED) {
            resume(now)
        } else {
            update(mutableState.value.copy(autoPauseEnabled = enabled))
        }
    }

    fun onLocation(sample: GpsSample, now: Long = System.currentTimeMillis()) {
        val current = mutableState.value
        if (!current.shouldReceiveLocations) return
        if (sample.accuracyMeters !in 0f..RunningMetrics.MAX_ACCURACY_METERS) {
            update(current.copy(accuracyMeters = sample.accuracyMeters))
            return
        }

        val previous = lastSample
        if (previous == null) {
            lastSample = sample
            lastAcceptedWallTime = now
            if (current.phase == RunningPhase.AUTO_PAUSED) {
                update(current.copy(accuracyMeters = sample.accuracyMeters, gpsAvailable = true))
            } else {
                update(current.copy(
                    phase = RunningPhase.RUNNING,
                    accuracyMeters = sample.accuracyMeters,
                    gpsAvailable = true,
                    route = current.route + RunningPoint(sample.latitude, sample.longitude, elapsedAt(now)),
                ))
            }
            return
        }

        val segment = RunningMetrics.segment(previous, sample)
        if (segment == null) {
            val rawDistance = RunningMetrics.distanceMeters(
                previous.latitude,
                previous.longitude,
                sample.latitude,
                sample.longitude,
            )
            if (rawDistance > 100.0 || sample.timestampMillis - previous.timestampMillis > 30_000L) {
                lastSample = sample
            }
            update(current.copy(accuracyMeters = sample.accuracyMeters, gpsAvailable = true))
            return
        }
        if (segment.distanceMeters == 0.0) {
            if (current.phase == RunningPhase.RUNNING && autoPauseEnabled && stationarySinceWallTime == null) {
                stationarySinceWallTime = now
            }
            if (current.phase == RunningPhase.AUTO_PAUSED) lastSample = sample
            update(current.copy(accuracyMeters = sample.accuracyMeters, gpsAvailable = true))
            return
        }

        val measuredSpeed = maxOf(sample.speedMetersPerSecond?.toDouble() ?: 0.0, segment.speedMetersPerSecond)
        if (current.phase == RunningPhase.AUTO_PAUSED) {
            lastSample = sample
            lastAcceptedWallTime = now
            if (measuredSpeed >= RunningMetrics.AUTO_RESUME_SPEED_METERS_PER_SECOND) autoResume(sample, now)
            else update(current.copy(accuracyMeters = sample.accuracyMeters, gpsAvailable = true))
            return
        }
        if (autoPauseEnabled && measuredSpeed < RunningMetrics.AUTO_PAUSE_SPEED_METERS_PER_SECOND) {
            if (stationarySinceWallTime == null) stationarySinceWallTime = now
        } else {
            stationarySinceWallTime = null
        }

        val instantPace = RunningMetrics.paceMinutesPerKm(measuredSpeed)
        val smoothedPace = instantPace?.let { pace ->
            current.currentPaceMinPerKm?.let { previousPace -> previousPace * 0.7 + pace * 0.3 } ?: pace
        }
        lastSample = sample
        lastAcceptedWallTime = now
        val previousElapsed = current.route.lastOrNull()?.elapsedMillis ?: current.elapsedMillis
        val newElapsed = elapsedAt(now)
        val newDistance = current.distanceMeters + segment.distanceMeters
        val newLaps = RunningMetrics.completedLaps(
            existing = current.laps,
            previousDistanceMeters = current.distanceMeters,
            currentDistanceMeters = newDistance,
            previousElapsedMillis = previousElapsed,
            currentElapsedMillis = newElapsed,
        )
        newLaps.drop(current.laps.size).forEach { mutableEvents.tryEmit(RunningEvent.LapCompleted(it)) }
        update(current.copy(
            phase = RunningPhase.RUNNING,
            elapsedMillis = newElapsed,
            distanceMeters = newDistance,
            currentPaceMinPerKm = smoothedPace,
            accuracyMeters = sample.accuracyMeters,
            gpsAvailable = true,
            route = (current.route + RunningPoint(sample.latitude, sample.longitude, newElapsed)).takeLast(MAX_ROUTE_POINTS),
            laps = newLaps,
        ))
    }

    private fun tick(now: Long = System.currentTimeMillis()) {
        val current = mutableState.value
        if (!current.isMoving) return
        if (current.phase == RunningPhase.RUNNING && autoPauseEnabled &&
            stationarySinceWallTime?.let { now - it >= RunningMetrics.AUTO_PAUSE_DELAY_MILLIS } == true
        ) {
            autoPause(now)
            return
        }
        val stalePace = lastAcceptedWallTime?.let { now - it > 10_000L } != false
        update(current.copy(
            elapsedMillis = elapsedAt(now),
            currentPaceMinPerKm = if (stalePace) null else current.currentPaceMinPerKm,
        ))
    }

    private fun elapsedAt(now: Long): Long =
        accumulatedElapsed + (activeSince?.let { (now - it).coerceAtLeast(0L) } ?: 0L)

    private fun autoPause(now: Long) {
        if (mutableState.value.phase != RunningPhase.RUNNING) return
        accumulatedElapsed = elapsedAt(now)
        activeSince = null
        lastSample = null
        stationarySinceWallTime = null
        update(mutableState.value.copy(
            phase = RunningPhase.AUTO_PAUSED,
            elapsedMillis = accumulatedElapsed,
            currentPaceMinPerKm = null,
        ))
        mutableEvents.tryEmit(RunningEvent.AutoPaused)
    }

    private fun autoResume(sample: GpsSample, now: Long) {
        if (mutableState.value.phase != RunningPhase.AUTO_PAUSED) return
        activeSince = now
        stationarySinceWallTime = null
        update(mutableState.value.copy(
            phase = RunningPhase.RUNNING,
            accuracyMeters = sample.accuracyMeters,
            gpsAvailable = true,
            route = (mutableState.value.route + RunningPoint(sample.latitude, sample.longitude, accumulatedElapsed))
                .takeLast(MAX_ROUTE_POINTS),
        ))
        mutableEvents.tryEmit(RunningEvent.AutoResumed)
    }

    private fun update(value: RunningUiState) {
        mutableState.value = value
        preferences.edit()
            .putString(KEY_SESSION_ID, value.sessionId)
            .putString(KEY_PHASE, value.phase.name)
            .putString(KEY_PLANNED_SLOT_ID, value.plannedSlotId)
            .putLong(KEY_STARTED_AT, value.startedAt ?: -1L)
            .putLong(KEY_ENDED_AT, value.endedAt ?: -1L)
            .putLong(KEY_ACTIVE_SINCE, activeSince ?: -1L)
            .putLong(KEY_ACCUMULATED, accumulatedElapsed)
            .putLong(KEY_DISTANCE_BITS, value.distanceMeters.toBits())
            .putString(KEY_ROUTE, RunningRouteCodec.encode(value.route))
            .putString(KEY_LAPS, RunningLapCodec.encode(value.laps))
            .apply()
    }

    private fun restore(): RunningUiState {
        val phase = runCatching {
            RunningPhase.valueOf(preferences.getString(KEY_PHASE, RunningPhase.IDLE.name)!!)
        }.getOrDefault(RunningPhase.IDLE)
        if (phase == RunningPhase.IDLE) return RunningUiState()
        val route = RunningRouteCodec.decode(preferences.getString(KEY_ROUTE, null))
        val laps = RunningLapCodec.decode(preferences.getString(KEY_LAPS, null))
        val accumulated = preferences.getLong(KEY_ACCUMULATED, 0L)
        val restoredActiveSince = preferences.longOrNull(KEY_ACTIVE_SINCE)
        val elapsed = accumulated + if (phase == RunningPhase.RUNNING || phase == RunningPhase.ACQUIRING_GPS) {
            restoredActiveSince?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) } ?: 0L
        } else 0L
        return RunningUiState(
            sessionId = preferences.getString(KEY_SESSION_ID, null) ?: "run-${UUID.randomUUID()}",
            phase = phase,
            plannedSlotId = preferences.getString(KEY_PLANNED_SLOT_ID, null),
            startedAt = preferences.longOrNull(KEY_STARTED_AT),
            endedAt = preferences.longOrNull(KEY_ENDED_AT),
            elapsedMillis = elapsed,
            distanceMeters = Double.fromBits(preferences.getLong(KEY_DISTANCE_BITS, 0L)),
            route = route,
            laps = laps,
            autoPauseEnabled = autoPauseEnabled,
        )
    }

    private fun android.content.SharedPreferences.longOrNull(key: String): Long? =
        getLong(key, -1L).takeIf { it >= 0L }

    private companion object {
        const val PREFERENCES = "running_tracker"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_PHASE = "phase"
        const val KEY_PLANNED_SLOT_ID = "planned_slot_id"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_ENDED_AT = "ended_at"
        const val KEY_ACTIVE_SINCE = "active_since"
        const val KEY_ACCUMULATED = "accumulated"
        const val KEY_DISTANCE_BITS = "distance_bits"
        const val KEY_ROUTE = "route"
        const val KEY_LAPS = "laps"
        const val MAX_ROUTE_POINTS = 4_000
    }
}

private fun Long?.orZero(): Long = this ?: 0L

object RunningRouteCodec {
    fun encode(points: List<RunningPoint>): String = points.joinToString(";") { point ->
        String.format(Locale.US, "%.6f,%.6f,%d", point.latitude, point.longitude, point.elapsedMillis)
    }

    fun decode(value: String?): List<RunningPoint> = value.orEmpty()
        .split(';')
        .mapNotNull { encoded ->
            val values = encoded.split(',')
            if (values.size != 3) return@mapNotNull null
            RunningPoint(
                latitude = values[0].toDoubleOrNull() ?: return@mapNotNull null,
                longitude = values[1].toDoubleOrNull() ?: return@mapNotNull null,
                elapsedMillis = values[2].toLongOrNull() ?: return@mapNotNull null,
            )
        }
}

object RunningLapCodec {
    fun encode(laps: List<RunningLap>): String = laps.joinToString(";") { lap ->
        String.format(
            Locale.US,
            "%d,%.1f,%d,%d",
            lap.index,
            lap.distanceMeters,
            lap.durationMillis,
            lap.totalElapsedMillis,
        )
    }

    fun decode(value: String?): List<RunningLap> = value.orEmpty()
        .split(';')
        .mapNotNull { encoded ->
            val values = encoded.split(',')
            if (values.size != 4) return@mapNotNull null
            RunningLap(
                index = values[0].toIntOrNull() ?: return@mapNotNull null,
                distanceMeters = values[1].toDoubleOrNull() ?: return@mapNotNull null,
                durationMillis = values[2].toLongOrNull() ?: return@mapNotNull null,
                totalElapsedMillis = values[3].toLongOrNull() ?: return@mapNotNull null,
            )
        }
}
