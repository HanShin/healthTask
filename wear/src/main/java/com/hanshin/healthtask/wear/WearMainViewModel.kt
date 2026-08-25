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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WearUiState(
    val routine: WearRoutinePayload? = null,
    val active: WearActiveSession? = null,
    val metrics: WearMetrics = WearMetrics(),
    val restRemainingSeconds: Int = 0,
    val tabataTimer: TabataTimerState = TabataTimerState(),
    val message: String? = null,
) {
    val currentExercise get() = active?.exercises?.getOrNull(active.currentExerciseIndex)
}

class WearMainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchApplication
    private val restRemainingSeconds = MutableStateFlow(0)
    private val tabataTimer = MutableStateFlow(TabataTimerState())
    private val message = MutableStateFlow<String?>(null)
    private var tabataTimerJob: Job? = null
    private var tabataPausedByWorkout = false

    val state: StateFlow<WearUiState> = combine(
        app.store.routine,
        app.store.activeSession,
        WearMetricsRepository.metrics,
        combine(restRemainingSeconds, tabataTimer) { rest, tabata -> rest to tabata },
        message,
    ) { routine, active, metrics, timers, currentMessage ->
        WearUiState(routine, active, metrics, timers.first, timers.second, currentMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearUiState())

    init {
        viewModelScope.launch {
            runCatching { app.dataGateway.readLatestRoutine() }
                .getOrNull()?.let { app.store.saveRoutine(it) }
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

    fun startWorkout(routine: WearRoutinePayload, trackSensors: Boolean) = viewModelScope.launch {
        val usesGpsRunning = routine.usesGpsRunning
        if (usesGpsRunning && !trackSensors) {
            message.value = "러닝 기록에는 위치·활동·심박 권한이 필요합니다."
            return@launch
        }
        val active = WearActiveSession(
            sessionId = "wear-session-${UUID.randomUUID()}",
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
            ContextCompat.startForegroundService(
                app,
                WearExerciseService.command(app, WearExerciseService.ACTION_START, usesGpsRunning),
            )
        } else {
            message.value = "센서 권한 없이 운동 기록을 시작했습니다."
        }
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

private fun WearActiveSession.updateCurrentExercise(
    transform: (com.hanshin.healthtask.shared.WearRoutineExercise) -> com.hanshin.healthtask.shared.WearRoutineExercise,
): WearActiveSession = copy(exercises = exercises.mapIndexed { index, exercise ->
    if (index == currentExerciseIndex) transform(exercise) else exercise
})
