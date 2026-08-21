package com.hanshin.healthtask.wear

import android.app.Application
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hanshin.healthtask.shared.WearActiveSession
import com.hanshin.healthtask.shared.WearCompletedWorkout
import com.hanshin.healthtask.shared.WearRecordMode
import com.hanshin.healthtask.shared.WearRoutinePayload
import com.hanshin.healthtask.shared.WearRoutineSet
import com.hanshin.healthtask.shared.elapsedMillis
import com.hanshin.healthtask.wear.health.WearExerciseService
import com.hanshin.healthtask.wear.health.WearMetrics
import com.hanshin.healthtask.wear.health.WearMetricsRepository
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WearUiState(
    val routine: WearRoutinePayload? = null,
    val active: WearActiveSession? = null,
    val metrics: WearMetrics = WearMetrics(),
    val message: String? = null,
) {
    val currentExercise get() = active?.exercises?.getOrNull(active.currentExerciseIndex)
}

class WearMainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WatchApplication
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<WearUiState> = combine(
        app.store.routine,
        app.store.activeSession,
        WearMetricsRepository.metrics,
        message,
    ) { routine, active, metrics, currentMessage ->
        WearUiState(routine, active, metrics, currentMessage)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WearUiState())

    init {
        viewModelScope.launch {
            runCatching { app.dataGateway.readLatestRoutine() }
                .getOrNull()?.let { app.store.saveRoutine(it) }
        }
    }

    fun startWorkout(trackSensors: Boolean) = viewModelScope.launch {
        val routine = state.value.routine ?: return@launch
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
        app.store.saveActiveSession(active)
        if (trackSensors) {
            val cardioOnly = active.exercises.isNotEmpty() && active.exercises.all { it.recordMode == WearRecordMode.CARDIO }
            ContextCompat.startForegroundService(
                app,
                WearExerciseService.command(app, WearExerciseService.ACTION_START, cardioOnly),
            )
        } else {
            message.value = "센서 권한 없이 운동 기록을 시작했습니다."
        }
    }

    fun toggleSet(order: Int) = updateActive { active ->
        active.updateCurrentExercise { exercise ->
            exercise.copy(sets = exercise.sets.map { set ->
                if (set.order == order) set.copy(completed = !set.completed) else set
            })
        }
    }

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
        val active = state.value.active ?: return@launch
        val endedAt = System.currentTimeMillis()
        val elapsedMinutes = active.elapsedMillis(endedAt) / 60_000.0
        val metrics = state.value.metrics
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
            title = active.routine.title,
            startedAt = active.startedAt,
            endedAt = endedAt,
            exercises = exercises,
            averageHeartRateBpm = metrics.averageHeartRateBpm,
            distanceKm = metrics.distanceKm,
            caloriesKcal = metrics.caloriesKcal,
        )
        runCatching { app.dataGateway.sendCompletedWorkout(workout) }
            .onSuccess {
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
