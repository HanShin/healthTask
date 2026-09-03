package com.hanshin.healthtask.wear

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanshin.healthtask.shared.DEFAULT_REST_TIMER_SECONDS
import com.hanshin.healthtask.shared.TABATA_TOTAL_SECONDS
import com.hanshin.healthtask.shared.TabataPhase
import com.hanshin.healthtask.shared.TabataTimer
import com.hanshin.healthtask.shared.TabataTimerState
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearRoutineSet
import com.hanshin.healthtask.shared.WearRouteCodec
import com.hanshin.healthtask.shared.WearStartWorkoutRequest
import com.hanshin.healthtask.shared.elapsedMillis
import com.hanshin.healthtask.shared.isTabata
import com.hanshin.healthtask.shared.remainingRestSeconds
import com.hanshin.healthtask.shared.usesGpsRunning
import com.hanshin.healthtask.wear.health.WearExerciseService
import com.hanshin.healthtask.wear.health.WearMetrics
import com.hanshin.healthtask.wear.health.WearMetricsRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WearUiState(
    val routine: WearRoutinePayload? = null,
    val pendingStartRequest: WearStartWorkoutRequest? = null,
    val active: WearActiveSession? = null,
    val metrics: WearMetrics = WearMetrics(),
    val restRemainingSeconds: Int = 0,
    val tabataTimer: TabataTimerState = TabataTimerState(),
    val message: String? = null,
) {
    val currentExercise get() = active?.exercises?.getOrNull(active.currentExerciseIndex)
}

internal data class PendingWorkoutStart(
    val routine: WearRoutinePayload,
    val sessionId: String? = null,
    val requestId: String? = null,
)

private data class WearTransientUiState(
    val restRemainingSeconds: Int,
    val tabataTimer: TabataTimerState,
    val message: String?,
)

class WearMainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchApplication
    private val restRemainingSeconds = MutableStateFlow(0)
    private val tabataTimer = MutableStateFlow(TabataTimerState())
    private val message = MutableStateFlow<String?>(null)
    private val _pendingPermissionStart = MutableStateFlow<PendingWorkoutStart?>(null)
    internal val pendingPermissionStart = _pendingPermissionStart.asStateFlow()
    private var tabataTimerJob: Job? = null
    private var tabataPausedByWorkout = false
    private var workoutStartInProgress = false

    private val transientUiState = combine(restRemainingSeconds, tabataTimer, message) { rest, tabata, currentMessage ->
        WearTransientUiState(rest, tabata, currentMessage)
    }

    val state: StateFlow<WearUiState> = combine(
        app.store.routine,
        app.store.pendingStartRequest,
        app.store.activeSession,
        WearMetricsRepository.metrics,
        transientUiState,
    ) { routine, pendingStartRequest, active, metrics, transient ->
        WearUiState(
            routine = routine,
            pendingStartRequest = pendingStartRequest,
            active = active,
            metrics = metrics,
            restRemainingSeconds = transient.restRemainingSeconds,
            tabataTimer = transient.tabataTimer,
            message = transient.message,
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
        viewModelScope.launch {
            app.store.activeSession.collectLatest { active ->
                val restEndsAt = active?.restEndsAt
                if (active == null || restEndsAt == null) {
                    restRemainingSeconds.value = 0
                    return@collectLatest
                }
                while (true) {
                    val remaining = remainingRestSeconds(restEndsAt)
                    restRemainingSeconds.value = remaining
                    if (remaining <= 0) {
                        signalRestComplete()
                        message.value = "휴식 끝! 다음 세트를 시작하세요."
                        app.store.saveActiveSession(active.copy(restEndsAt = null))
                        return@collectLatest
                    }
                    delay(250L)
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
                stopTabataTimer()
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

    fun toggleSet(order: Int) = viewModelScope.launch {
        val active = state.value.active ?: return@launch
        val set = active.exercises.getOrNull(active.currentExerciseIndex)
            ?.sets?.firstOrNull { it.order == order } ?: return@launch
        val completing = !set.completed
        val updated = active.updateCurrentExercise { exercise ->
            exercise.copy(sets = exercise.sets.map { candidate ->
                if (candidate.order == order) candidate.copy(completed = completing) else candidate
            })
        }.let { session ->
            if (!completing) session
            else {
                val configuredSeconds = active.routine.restTimerSeconds
                    .takeIf { it > 0 } ?: DEFAULT_REST_TIMER_SECONDS
                session.copy(restEndsAt = System.currentTimeMillis() + configuredSeconds * 1_000L)
            }
        }
        app.store.saveActiveSession(updated)
    }

    fun addRestTime(seconds: Int = 30) = updateActive { active ->
        val currentEnd = active.restEndsAt ?: return@updateActive active
        active.copy(restEndsAt = maxOf(currentEnd, System.currentTimeMillis()) + seconds * 1_000L)
    }

    fun skipRestTimer() = updateActive { it.copy(restEndsAt = null) }

    fun adjustReps(delta: Int) = updateFocusedSet { set ->
        set.copy(reps = ((set.reps ?: 0) + delta).coerceAtLeast(0))
    }

    fun adjustWeight(delta: Double) = updateFocusedSet { set ->
        set.copy(weightKg = (((set.weightKg ?: 0.0) + delta).coerceAtLeast(0.0) * 2.0).toInt() / 2.0)
    }

    fun moveExercise(delta: Int) = updateActive { active ->
        active.copy(currentExerciseIndex = (active.currentExerciseIndex + delta)
            .coerceIn(0, (active.exercises.size - 1).coerceAtLeast(0)))
    }

    fun togglePause() = viewModelScope.launch {
        val active = state.value.active ?: return@launch
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
        if (updated.paused && tabataTimer.value.isRunning) {
            tabataPausedByWorkout = true
            tabataTimer.value = TabataTimer.pause(tabataTimer.value)
        } else if (!updated.paused && tabataPausedByWorkout) {
            tabataPausedByWorkout = false
            tabataTimer.value = TabataTimer.resume(tabataTimer.value)
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

    fun startTabata(exercise: com.hanshin.healthtask.shared.WearRoutineExercise) {
        if (!exercise.isTabata) return
        if (state.value.active?.paused == true) {
            message.value = "운동을 재개한 뒤 타바타를 시작하세요."
            return
        }
        tabataTimerJob?.cancel()
        val ready = TabataTimer.ready(
            targetId = exercise.id,
            workSeconds = exercise.intervalWorkSeconds ?: com.hanshin.healthtask.shared.TABATA_WORK_SECONDS,
            restSeconds = exercise.intervalRestSeconds ?: com.hanshin.healthtask.shared.TABATA_REST_SECONDS,
            rounds = exercise.intervalRounds ?: com.hanshin.healthtask.shared.TABATA_ROUNDS,
        )
        tabataTimer.value = TabataTimer.start(ready)
        updateExercise(exercise.id) { it.copy(durationMin = null, distanceKm = null) }
        signalTabataPhase(TabataPhase.WORK)
        tabataTimerJob = viewModelScope.launch {
            while (true) {
                delay(1_000L)
                val before = tabataTimer.value
                val after = TabataTimer.tick(before)
                tabataTimer.value = after
                if (after.phase != before.phase) signalTabataPhase(after.phase)
                if (after.isCompleted) {
                    updateExerciseNow(after.targetId) {
                        it.copy(durationMin = TABATA_TOTAL_SECONDS / 60.0, distanceKm = null)
                    }
                    message.value = "타바타 8라운드 완료!"
                    tabataTimerJob = null
                    return@launch
                }
            }
        }
    }

    fun pauseTabata() {
        tabataPausedByWorkout = false
        tabataTimer.value = TabataTimer.pause(tabataTimer.value)
    }

    fun resumeTabata() {
        if (state.value.active?.paused == true) {
            message.value = "전체 운동을 먼저 재개하세요."
            return
        }
        tabataTimer.value = TabataTimer.resume(tabataTimer.value)
    }

    fun resetTabata(exercise: com.hanshin.healthtask.shared.WearRoutineExercise) {
        stopTabataTimer(exercise.id)
        updateExercise(exercise.id) { it.copy(durationMin = null, distanceKm = null) }
    }

    fun finishWorkout() = viewModelScope.launch {
        val active = state.value.active ?: return@launch
        val endedAt = System.currentTimeMillis()
        val metrics = state.value.metrics
        val trackedDurationMillis = metrics.activeDurationMillis.takeIf { metrics.tracking && it > 0L }
            ?: active.elapsedMillis(endedAt)
        val elapsedMinutes = trackedDurationMillis / 60_000.0
        var cardioRecorded = false
        val exercises = active.exercises.map { exercise ->
            if (exercise.isTabata) {
                exercise
            } else if (exercise.recordMode == WearRecordMode.CARDIO && !cardioRecorded) {
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
        )
        runCatching { app.dataGateway.sendCompletedWorkout(workout) }
            .onSuccess {
                stopTabataTimer()
                app.store.clearActiveSession()
                if (metrics.tracking) {
                    ContextCompat.startForegroundService(
                        app,
                        WearExerciseService.command(app, WearExerciseService.ACTION_STOP),
                    )
                }
                message.value = "휴대폰 전송 대기열에 저장했습니다."
            }
            .onFailure { message.value = it.message ?: "운동 저장에 실패했습니다." }
    }

    fun consumeMessage() { message.value = null }

    private suspend fun consumeAndClearStartRequest(requestId: String) {
        val consumeFailure = runCatching { app.dataGateway.consumeStartRequest(requestId) }.exceptionOrNull()
        app.store.clearPendingStartRequest(requestId)
        if (consumeFailure != null) {
            message.value = "운동 요청 동기화를 마치지 못했습니다."
        }
    }

    private fun signalRestComplete() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 250L, 150L, 350L), -1))
    }

    private fun signalTabataPhase(phase: TabataPhase) {
        val pattern = when (phase) {
            TabataPhase.WORK -> longArrayOf(0L, 150L, 70L, 150L)
            TabataPhase.REST -> longArrayOf(0L, 280L)
            TabataPhase.COMPLETED -> longArrayOf(0L, 220L, 100L, 220L, 100L, 420L)
            else -> return
        }
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            app.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun stopTabataTimer(targetId: String? = null) {
        tabataTimerJob?.cancel()
        tabataTimerJob = null
        tabataPausedByWorkout = false
        tabataTimer.value = targetId?.let { TabataTimer.ready(it) } ?: TabataTimerState()
    }

    private fun updateExercise(
        exerciseId: String,
        transform: (com.hanshin.healthtask.shared.WearRoutineExercise) -> com.hanshin.healthtask.shared.WearRoutineExercise,
    ) = viewModelScope.launch { updateExerciseNow(exerciseId, transform) }

    private suspend fun updateExerciseNow(
        exerciseId: String?,
        transform: (com.hanshin.healthtask.shared.WearRoutineExercise) -> com.hanshin.healthtask.shared.WearRoutineExercise,
    ) {
        val active = state.value.active ?: return
        app.store.saveActiveSession(active.copy(exercises = active.exercises.map { exercise ->
            if (exercise.id == exerciseId) transform(exercise) else exercise
        }))
    }

    private fun updateFocusedSet(transform: (WearRoutineSet) -> WearRoutineSet) = updateActive { active ->
        active.updateCurrentExercise { exercise ->
            val focus = exercise.sets.firstOrNull { !it.completed } ?: exercise.sets.lastOrNull()
                ?: return@updateCurrentExercise exercise
            exercise.copy(sets = exercise.sets.map { if (it.order == focus.order) transform(it) else it })
        }
    }

    private fun updateActive(transform: (WearActiveSession) -> WearActiveSession) {
        viewModelScope.launch {
            val active = state.value.active ?: return@launch
            app.store.saveActiveSession(transform(active))
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
    if (active != null) return false
    val liveRequest = pendingRequest?.takeIf { it.isValid(now) }
    if (candidate.requestId == null) return candidate.sessionId == null && liveRequest == null
    if (candidate.sessionId.isNullOrBlank()) return false
    return liveRequest?.requestId == candidate.requestId && liveRequest.sessionId == candidate.sessionId
}

private fun WearActiveSession.updateCurrentExercise(
    transform: (com.hanshin.healthtask.shared.WearRoutineExercise) -> com.hanshin.healthtask.shared.WearRoutineExercise,
): WearActiveSession = copy(exercises = exercises.mapIndexed { index, exercise ->
    if (index == currentExerciseIndex) transform(exercise) else exercise
})
