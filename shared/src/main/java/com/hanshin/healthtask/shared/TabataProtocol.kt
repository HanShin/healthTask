package com.hanshin.healthtask.shared

const val TABATA_EXERCISE_ID = "tabata-finisher"
const val TABATA_WORK_SECONDS = 20
const val TABATA_REST_SECONDS = 10
const val TABATA_ROUNDS = 8
const val TABATA_TOTAL_SECONDS = (TABATA_WORK_SECONDS + TABATA_REST_SECONDS) * TABATA_ROUNDS

enum class TabataPhase { IDLE, WORK, REST, PAUSED, COMPLETED }

data class TabataTimerState(
    val targetId: String? = null,
    val phase: TabataPhase = TabataPhase.IDLE,
    val round: Int = 1,
    val remainingSeconds: Int = TABATA_WORK_SECONDS,
    val workSeconds: Int = TABATA_WORK_SECONDS,
    val restSeconds: Int = TABATA_REST_SECONDS,
    val totalRounds: Int = TABATA_ROUNDS,
    val pausedFrom: TabataPhase? = null,
) {
    val isRunning: Boolean get() = phase == TabataPhase.WORK || phase == TabataPhase.REST
    val isCompleted: Boolean get() = phase == TabataPhase.COMPLETED
}

object TabataTimer {
    fun ready(
        targetId: String,
        workSeconds: Int = TABATA_WORK_SECONDS,
        restSeconds: Int = TABATA_REST_SECONDS,
        rounds: Int = TABATA_ROUNDS,
    ): TabataTimerState = TabataTimerState(
        targetId = targetId,
        workSeconds = workSeconds.coerceAtLeast(1),
        restSeconds = restSeconds.coerceAtLeast(1),
        totalRounds = rounds.coerceAtLeast(1),
        remainingSeconds = workSeconds.coerceAtLeast(1),
    )

    fun start(state: TabataTimerState): TabataTimerState = state.copy(
        phase = TabataPhase.WORK,
        round = 1,
        remainingSeconds = state.workSeconds,
        pausedFrom = null,
    )

    fun tick(state: TabataTimerState): TabataTimerState {
        if (!state.isRunning) return state
        if (state.remainingSeconds > 1) return state.copy(remainingSeconds = state.remainingSeconds - 1)
        return when (state.phase) {
            TabataPhase.WORK -> state.copy(
                phase = TabataPhase.REST,
                remainingSeconds = state.restSeconds,
            )
            TabataPhase.REST -> if (state.round >= state.totalRounds) {
                state.copy(phase = TabataPhase.COMPLETED, remainingSeconds = 0)
            } else {
                state.copy(
                    phase = TabataPhase.WORK,
                    round = state.round + 1,
                    remainingSeconds = state.workSeconds,
                )
            }
            else -> state
        }
    }

    fun pause(state: TabataTimerState): TabataTimerState = if (state.isRunning) {
        state.copy(phase = TabataPhase.PAUSED, pausedFrom = state.phase)
    } else state

    fun resume(state: TabataTimerState): TabataTimerState = if (state.phase == TabataPhase.PAUSED) {
        state.copy(phase = state.pausedFrom ?: TabataPhase.WORK, pausedFrom = null)
    } else state

    fun reset(state: TabataTimerState): TabataTimerState = state.copy(
        phase = TabataPhase.IDLE,
        round = 1,
        remainingSeconds = state.workSeconds,
        pausedFrom = null,
    )
}

val WearRoutineExercise.isTabata: Boolean
    get() = exerciseId == TABATA_EXERCISE_ID
