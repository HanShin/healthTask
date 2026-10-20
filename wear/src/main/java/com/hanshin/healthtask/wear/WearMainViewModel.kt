package com.hanshin.healthtask.wear

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearRouteCodec
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import com.hanshin.healthtask.shared.elapsedMillis
import com.hanshin.healthtask.shared.usesGpsRunning
import com.hanshin.healthtask.wear.health.WearExerciseService
import com.hanshin.healthtask.wear.health.WearMetrics
import com.hanshin.healthtask.wear.health.WearMetricsRepository
import com.hanshin.healthtask.wear.health.withTelemetryFallback
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class WearUiState(
    val routine: WearRoutinePayload? = null,
    val pendingStartRequest: WearStartWorkoutRequest? = null,
    val active: WearActiveSession? = null,
    val metrics: WearMetrics = WearMetrics(),
    val message: String? = null,
) {
    val currentExercise get() = active?.exercises?.getOrNull(active.currentExerciseIndex)
}

internal data class PendingWorkoutStart(
    val routine: WearRoutinePayload,
    val sessionId: String? = null,
    val requestId: String? = null,
)

class WearMainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchApplication
    private val message = MutableStateFlow<String?>(null)
    private val _pendingPermissionStart = MutableStateFlow<PendingWorkoutStart?>(null)
    internal val pendingPermissionStart = _pendingPermissionStart.asStateFlow()
    private var workoutStartInProgress = false
    private var workoutFinishInProgress = false

    val state: StateFlow<WearUiState> = combine(
        app.store.routine,
        app.store.pendingStartRequest,
        app.store.activeSession,
        WearMetricsRepository.metrics,
        message,
    ) { routine, pendingStartRequest, active, metrics, currentMessage ->
        WearUiState(
            routine = routine?.takeIf { it.usesGpsRunning },
            pendingStartRequest = pendingStartRequest?.takeIf { it.routine.usesGpsRunning },
            active = active,
            metrics = metrics,
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WearUiState())

    init {
        viewModelScope.launch {
            runCatching { app.dataGateway.readLatestRoutine() }
                .getOrNull()?.let { app.store.saveRoutine(it) }
        }
        refreshStartRequest()
        viewModelScope.launch {
            app.store.pendingStartRequest.collectLatest { request ->
                request ?: return@collectLatest
                delay(startRequestExpiryDelayMillis(request, System.currentTimeMillis()))
                viewModelScope.launch {
                    app.store.clearPendingStartRequest(request.requestId)
                    runCatching { app.dataGateway.consumeStartRequest(request.requestId) }
                }
            }
        }
    }

    fun refreshStartRequest(expectedRequestId: String? = null) = viewModelScope.launch {
        val attempts = if (expectedRequestId == null) 1 else 4
        repeat(attempts) { attempt ->
            val request = runCatching { app.dataGateway.readLatestStartRequest() }.getOrNull()
            if (request != null && (expectedRequestId == null || request.requestId == expectedRequestId)) {
                app.store.savePendingStartRequest(request)
                return@launch
            }
            if (attempt < attempts - 1) delay(250L)
        }
    }

    internal fun deferWorkoutStartForPermissions(start: PendingWorkoutStart): Boolean {
        if (_pendingPermissionStart.value != null) return false
        _pendingPermissionStart.value = start
        return true
    }

    fun completePendingPermissionStart(trackSensors: Boolean) {
        val start = _pendingPermissionStart.value ?: return
        _pendingPermissionStart.value = null
        startWorkout(
            routine = start.routine,
            trackSensors = trackSensors,
            sessionId = start.sessionId,
            requestId = start.requestId,
        )
    }

    fun startWorkout(
        routine: WearRoutinePayload,
        trackSensors: Boolean,
        sessionId: String? = null,
        requestId: String? = null,
    ) {
        if (!routine.usesGpsRunning) {
            message.value = "이제 러닝만 기록할 수 있습니다."
            return
        }
        if (workoutStartInProgress) return
        workoutStartInProgress = true
        viewModelScope.launch {
            try {
                val candidate = PendingWorkoutStart(routine, sessionId, requestId)
                val currentState = state.value
                if (!isWorkoutStartCurrent(candidate, currentState.active, currentState.pendingStartRequest)) {
                    if (currentState.active != null) {
                        message.value = "이미 진행 중인 운동이 있습니다."
                    } else if (requestId != null) {
                        message.value = "운동 시작 요청이 만료되었거나 변경됐습니다."
                        currentState.pendingStartRequest
                            ?.takeIf { it.requestId == requestId && !it.isValid() }
                            ?.let {
                                consumeAndClearStartRequest(it.requestId)
                            }
                    }
                    return@launch
                }
                val usesGpsRunning = routine.usesGpsRunning
                if (usesGpsRunning && !trackSensors) {
                    message.value = "러닝 기록에는 위치·활동·심박 권한이 필요합니다."
                    return@launch
                }
                val active = WearActiveSession(
                    sessionId = sessionId?.takeIf { it.isNotBlank() } ?: "wear-session-${UUID.randomUUID()}",
                    routine = routine,
                    startedAt = System.currentTimeMillis(),
                    exercises = routine.exercises.map { exercise ->
                        exercise.copy(
                            sets = exercise.sets.map { it.copy(completed = false) },
                            durationMin = null,
                            distanceKm = null,
                        )
                    },
                )
                app.store.saveActiveSession(active)
                if (trackSensors) {
                    val serviceStarted = runCatching {
                        ContextCompat.startForegroundService(
                            app,
                            WearExerciseService.command(app, WearExerciseService.ACTION_START, usesGpsRunning),
                        )
                    }.getOrNull() != null
                    if (!serviceStarted) {
                        app.store.clearActiveSession()
                        message.value = "운동 센서 서비스를 시작하지 못했습니다. 다시 시도해 주세요."
                        return@launch
                    }
                } else {
                    message.value = "센서 권한 없이 운동 기록을 시작했습니다."
                }
                requestId?.let { consumeAndClearStartRequest(it) }
            } finally {
                workoutStartInProgress = false
            }
        }
    }

    fun declineStartRequest(requestId: String) = viewModelScope.launch {
        val pendingRequest = state.value.pendingStartRequest
        if (pendingRequest?.requestId != requestId) return@launch
        consumeAndClearStartRequest(requestId)
        message.value = "휴대폰에서 운동을 계속합니다."
    }

    fun togglePause() = viewModelScope.launch {
        if (workoutFinishInProgress) return@launch
        val active = state.value.active ?: return@launch
        if (active.finishedAt != null) return@launch
        val now = System.currentTimeMillis()
        val updated = if (active.paused) {
            active.copy(
                paused = false,
                pausedAt = null,
                accumulatedPausedMillis = active.accumulatedPausedMillis + (now - (active.pausedAt ?: now)),
            )
        } else {
            active.copy(paused = true, pausedAt = now)
        }
        app.store.saveActiveSession(updated)
        if (state.value.metrics.tracking) {
            ContextCompat.startForegroundService(
                app,
                WearExerciseService.command(
                    app,
                    if (updated.paused) WearExerciseService.ACTION_PAUSE else WearExerciseService.ACTION_RESUME,
                ),
            )
        }
    }

    fun finishWorkout() = viewModelScope.launch {
        if (workoutFinishInProgress) return@launch
        var active = state.value.active ?: return@launch
        workoutFinishInProgress = true
        try {
            val endedAt = active.finishedAt ?: System.currentTimeMillis()
            active = active.copy(finishedAt = endedAt, paused = true, pausedAt = active.pausedAt ?: endedAt)
            app.store.saveActiveSession(active)
            message.value = "마지막 센서 기록을 저장하고 있습니다…"
            if (WearMetricsRepository.metrics.value.tracking) {
                ContextCompat.startForegroundService(app, WearExerciseService.command(app, WearExerciseService.ACTION_STOP))
                withTimeout(60_000L) { WearMetricsRepository.metrics.first { !it.tracking } }
            }
            val telemetry = app.sensorRecorder.snapshot(active.sessionId)
            val metrics = withContext(Dispatchers.Default) { WearMetricsRepository.metrics.value.withTelemetryFallback(telemetry) }
            val trackedDurationMillis = metrics.activeDurationMillis.takeIf { it > 0L }
                ?: active.elapsedMillis(endedAt)
            val elapsedMinutes = trackedDurationMillis / 60_000.0
            var cardioRecorded = false
            val exercises = active.exercises.map { exercise ->
                if (exercise.recordMode == WearRecordMode.CARDIO && !cardioRecorded) {
                    cardioRecorded = true
                    exercise.copy(durationMin = elapsedMinutes, distanceKm = metrics.distanceKm)
                } else exercise
            }
            val workout = WearCompletedWorkout(
                sessionId = active.sessionId,
                routineId = active.routine.routineId,
                planSlotId = active.routine.planSlotId,
                title = active.routine.title,
                startedAt = active.startedAt,
                endedAt = endedAt,
                exercises = exercises,
                averageHeartRateBpm = metrics.averageHeartRateBpm,
                distanceKm = metrics.distanceKm,
                caloriesKcal = metrics.caloriesKcal,
                activeDurationMillis = trackedDurationMillis,
                routePolyline = WearRouteCodec.encode(metrics.route).takeIf { it.isNotBlank() },
                telemetry = telemetry,
            )
            runCatching { withContext(Dispatchers.IO) { app.dataGateway.sendCompletedWorkout(workout) } }
                .onSuccess {
                    app.store.clearActiveSession()
                    runCatching { app.sensorRecorder.removeQueued(active.sessionId) }
                    message.value = "휴대폰 전송 대기열에 저장했습니다."
                }
                .onFailure { message.value = it.message ?: "운동 저장에 실패했습니다." }
        } catch (error: Exception) {
            message.value = "기록을 보관 중입니다. 완료를 다시 눌러 주세요. ${error.message.orEmpty()}"
        } finally {
            workoutFinishInProgress = false
        }
    }

    fun consumeMessage() { message.value = null }

    private suspend fun consumeAndClearStartRequest(requestId: String) {
        val consumeFailure = runCatching { app.dataGateway.consumeStartRequest(requestId) }.exceptionOrNull()
        app.store.clearPendingStartRequest(requestId)
        if (consumeFailure != null) {
            message.value = "운동 요청 동기화를 마치지 못했습니다."
        }
    }

}

internal fun startRequestExpiryDelayMillis(
    request: WearStartWorkoutRequest,
    now: Long = System.currentTimeMillis(),
): Long = (request.expiresAt - now).coerceAtLeast(0L)

internal fun isWorkoutStartCurrent(
    candidate: PendingWorkoutStart,
    active: WearActiveSession?,
    pendingRequest: WearStartWorkoutRequest?,
    now: Long = System.currentTimeMillis(),
): Boolean {
    if (!candidate.routine.usesGpsRunning || active != null) return false
    val liveRequest = pendingRequest?.takeIf { it.isValid(now) && it.routine.usesGpsRunning }
    if (candidate.requestId == null) return candidate.sessionId == null && liveRequest == null
    if (candidate.sessionId.isNullOrBlank()) return false
    return liveRequest?.requestId == candidate.requestId && liveRequest.sessionId == candidate.sessionId
}
