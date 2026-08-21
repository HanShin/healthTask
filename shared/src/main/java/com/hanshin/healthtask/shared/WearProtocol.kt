package com.hanshin.healthtask.shared

object WearPaths {
    const val TODAY_ROUTINE = "/today-routine"
    const val COMPLETED_WORKOUT_PREFIX = "/completed-workout/"
    const val KEY_JSON = "json"
    const val KEY_UPDATED_AT = "updatedAt"
}

enum class WearRecordMode { SETS, CARDIO }

data class WearRoutinePayload(
    val schemaVersion: Int = 1,
    val routineId: String,
    val title: String,
    val exercises: List<WearRoutineExercise>,
    val updatedAt: Long,
)

data class WearRoutineExercise(
    val id: String,
    val exerciseId: String,
    val name: String,
    val order: Int,
    val recordMode: WearRecordMode,
    val category: String,
    val sets: List<WearRoutineSet> = emptyList(),
    val targetDurationMin: Double? = null,
    val targetDistanceKm: Double? = null,
    val durationMin: Double? = null,
    val distanceKm: Double? = null,
)

data class WearRoutineSet(
    val order: Int,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val completed: Boolean = false,
)

data class WearActiveSession(
    val sessionId: String,
    val routine: WearRoutinePayload,
    val startedAt: Long,
    val currentExerciseIndex: Int = 0,
    val exercises: List<WearRoutineExercise> = routine.exercises,
    val paused: Boolean = false,
    val pausedAt: Long? = null,
    val accumulatedPausedMillis: Long = 0L,
)

fun WearActiveSession.elapsedMillis(now: Long = System.currentTimeMillis()): Long =
    ((pausedAt ?: now) - startedAt - accumulatedPausedMillis).coerceAtLeast(0L)

data class WearCompletedWorkout(
    val schemaVersion: Int = 1,
    val sessionId: String,
    val routineId: String,
    val title: String,
    val startedAt: Long,
    val endedAt: Long,
    val exercises: List<WearRoutineExercise>,
    val averageHeartRateBpm: Double? = null,
    val distanceKm: Double? = null,
    val caloriesKcal: Double? = null,
)
